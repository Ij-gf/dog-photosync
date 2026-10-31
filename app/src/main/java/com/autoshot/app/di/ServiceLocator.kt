package com.autoshot.app.di

import android.content.Context
import com.autoshot.app.BuildConfig
import com.autoshot.app.data.MediaRepository
import com.autoshot.app.data.SettingsStore
import com.autoshot.app.data.SupabaseApi
import com.autoshot.app.data.db.AppDatabase
import com.autoshot.app.data.db.MediaDao

/**
 * 手动依赖注入（项目体量不需要 Hilt）：全局单例，进程内共享同一个 Room / DataStore / OkHttp。
 * 必须先在 Application.onCreate 中调用 init(context)。
 */
object ServiceLocator {

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun requireContext(): Context =
        appContext ?: error("ServiceLocator 未初始化，请在 Application.onCreate 中调用 init()")

    val database: AppDatabase by lazy { AppDatabase.build(requireContext()) }

    val mediaDao: MediaDao by lazy { database.mediaDao() }

    val settings: SettingsStore by lazy { SettingsStore(requireContext()) }

    val repository: MediaRepository by lazy {
        MediaRepository(requireContext(), mediaDao, settings)
    }

    val supabase: SupabaseApi by lazy {
        SupabaseApi(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY, settings)
    }
}
