package com.autoshot.app.data

import android.util.Log
import com.autoshot.app.BuildConfig
import com.autoshot.app.data.db.MediaEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * 极简 Supabase 客户端（PostgREST + GoTrue），只用 OkHttp + org.json。
 *
 * 全自动策略（前端没有任何登录界面）：
 * 1. local.properties 里配了 SUPABASE_EMAIL / SUPABASE_PASSWORD → 启动后自动登录，行带 user_id，RLS 按账号隔离；
 * 2. 没配账号 → 用 anon key 直传，user_id 为 NULL，对应 SQL 里的 anon 插入策略；
 * 3. token 过期（401）→ 清 token 自动重登一次并重传。
 *
 * 约定后端表：public.images(
 *   id uuid pk, user_id uuid, source text, mime text, w int, h int,
 *   size_bytes bigint, sha1 text, base64 text, created_at timestamptz)
 */
class SupabaseApi(
    private val baseUrl: String,
    private val anonKey: String,
    private val settings: SettingsStore,
) {

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && anonKey.isNotBlank()

    private val restUrl: String get() = baseUrl.trimEnd('/') + "/rest/v1/images"
    private val tokenUrl: String get() = baseUrl.trimEnd('/') + "/auth/v1/token?grant_type=password"

    private data class HttpResult(val code: Int, val body: String) {
        val ok: Boolean get() = code in 200..299
    }

    // ---------------- 会话 ----------------

    /** 返回可用的 access token；没有账号配置时返回 null（走 anon） */
    suspend fun ensureSession(forceRefresh: Boolean = false): String? = withContext(Dispatchers.IO) {
        if (forceRefresh) settings.clearAuth()
        val cached = settings.current().supabaseToken
        if (!cached.isNullOrBlank()) return@withContext cached

        val email = BuildConfig.SUPABASE_EMAIL
        val password = BuildConfig.SUPABASE_PASSWORD
        if (email.isBlank() || password.isBlank()) return@withContext null

        signIn(email, password).getOrNull()
    }

    private suspend fun signIn(email: String, password: String): Result<String> =
        withContext(Dispatchers.IO) {
            if (!isConfigured) return@withContext Result.failure(IllegalStateException("未配置 Supabase"))
            try {
                val body = JSONObject()
                    .put("email", email)
                    .put("password", password)
                    .toString()
                    .toRequestBody(jsonType)
                val request = Request.Builder()
                    .url(tokenUrl)
                    .addHeader("apikey", anonKey)
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build()
                client.newCall(request).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        Log.e(TAG, "自动登录失败(${resp.code}): ${text.take(200)}")
                        return@withContext Result.failure(IllegalStateException("登录失败(${resp.code})"))
                    }
                    val json = JSONObject(text)
                    val token = json.optString("access_token")
                    if (token.isBlank()) return@withContext Result.failure(IllegalStateException("无 access_token"))
                    val userId = json.optJSONObject("user")?.optString("id")?.takeIf { it.isNotBlank() }
                    settings.setAuth(email, token, userId)
                    Log.i(TAG, "已自动登录，user_id=${userId ?: "null"}")
                    Result.success(token)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "自动登录异常", t)
                Result.failure(t)
            }
        }

    private fun headers(token: String?, omitAuth: Boolean = false): Map<String, String> = buildMap {
        put("apikey", anonKey)
        // 新版 sb_publishable_ 密钥不一定被当作 Bearer 接受；401 时会退化为只带 apikey
        if (!omitAuth) put("Authorization", "Bearer ${token ?: anonKey}")
        put("Content-Type", "application/json")
        // 注意：绝不能带 resolution=merge-duplicates。
        // 它会让 PostgREST 生成 ON CONFLICT DO UPDATE，而 PostgreSQL 要求该语句具备 UPDATE 权限；
        // anon 角色只有 INSERT 权限，于是每次上传都会 403 permission denied。
        // 幂等改由「重复 id -> 409 -> 视为已上传」保证。
        put("Prefer", "return=minimal")
    }

    // ---------------- 上传 ----------------

    /**
     * 批量上传。成功返回已提交条数（409 主键冲突视为幂等成功）。
     */
    suspend fun uploadBatch(items: List<MediaEntity>): Result<Int> = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext Result.success(0)
        if (!isConfigured) return@withContext Result.failure(IllegalStateException("未配置 Supabase"))

        var token = ensureSession()
        val dropped = mutableSetOf<String>()
        var omitAuth = false
        var result: HttpResult

        // 最多三轮：遇到 PGRST204（表里缺某列）就摘掉该列重发，
        // 兼容早期建表、没跑过补列脚本的库。
        repeat(3) {
            val payload = buildPayload(items, dropped)
            if (payload.length() == 0) return@withContext Result.success(0)

            result = post(payload.toString(), token, omitAuth)

            // token 过期 → 重登一次再试
            if (result.code == 401 && BuildConfig.SUPABASE_EMAIL.isNotBlank()) {
                token = ensureSession(forceRefresh = true)
                if (token != null) result = post(payload.toString(), token, omitAuth)
            }

            // 没配账号却 401/403：多半是新版 publishable key 不被接受为 Bearer，退化为只带 apikey 重试
            if ((result.code == 401 || result.code == 403) && !omitAuth && BuildConfig.SUPABASE_EMAIL.isBlank()) {
                Log.w(TAG, "鉴权失败(${result.code})，改为只带 apikey 重试")
                omitAuth = true
                return@repeat
            }

            if (result.ok || result.code == 409) {
                return@withContext finishUpload(payload.length(), null)
            }

            val missing = missingColumn(result.body)
            if (result.code == 400 && missing != null && OPTIONAL_COLUMNS.contains(missing) && dropped.add(missing)) {
                Log.w(TAG, "服务端缺列 $missing，摘掉该列重试")
                return@repeat
            }

            val msg = "上传失败(${result.code})：${result.body.take(200)}"
            settings.setLastError(msg)
            Log.e(TAG, msg)
            return@withContext Result.failure(IllegalStateException(msg))
        }

        val msg = "上传失败：重试三轮仍未成功"
        settings.setLastError(msg)
        Result.failure(IllegalStateException(msg))
    }

    private suspend fun finishUpload(count: Int, error: String?): Result<Int> {
        settings.setLastError(error)
        settings.setLastSyncAt(System.currentTimeMillis())
        return Result.success(count)
    }

    /** 从 PostgREST 的错误体里取出缺失列名，例如 Could not find the 'sha1' column */
    private fun missingColumn(body: String): String? =
        Regex("Could not find the '([a-zA-Z0-9_]+)' column").find(body)?.groupValues?.get(1)

    private suspend fun buildPayload(items: List<MediaEntity>, dropped: Set<String>): JSONArray {
        val userId = settings.current().supabaseUserId
        val array = JSONArray()
        for (item in items) {
            val b64 = item.base64
            if (b64.isNullOrBlank()) continue
            val obj = JSONObject()
                .put("id", item.id)
                .put("user_id", userId ?: JSONObject.NULL)
                .put("source", item.source)
                .put("mime", item.mime)
                .put("w", item.w)
                .put("h", item.h)
                .put("base64", b64)
                .put("created_at", Instant.ofEpochMilli(item.capturedAt).toString())
            if ("size_bytes" !in dropped) obj.put("size_bytes", item.sizeBytes)
            if ("sha1" !in dropped) obj.put("sha1", item.sha1 ?: JSONObject.NULL)
            array.put(obj)
        }
        return array
    }

    private fun post(json: String, token: String?, omitAuth: Boolean = false): HttpResult = try {
        val builder = Request.Builder().url(restUrl)
        headers(token, omitAuth).forEach { (k, v) -> builder.addHeader(k, v) }
        val request = builder.post(json.toRequestBody(jsonType)).build()
        client.newCall(request).execute().use { resp ->
            HttpResult(resp.code, resp.body?.string().orEmpty())
        }
    } catch (t: Throwable) {
        Log.e(TAG, "上传异常", t)
        HttpResult(-1, t.message.orEmpty())
    }

    companion object {
        private const val TAG = "SupabaseApi"

        /** 表里可能不存在、可安全丢弃的列 */
        private val OPTIONAL_COLUMNS = setOf("sha1", "size_bytes")
    }
}
