package com.autoshot.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 权限闸门：相机与相册权限没给齐，就不允许进入主界面。
 * 用户拒绝且勾选了"不再询问"时，引导去系统设置里手动开启。
 */
@Composable
fun PermissionGate(
    missing: List<String>,
    onGranted: () -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity

    var pending by remember { mutableStateOf(missing) }
    var blocked by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val stillMissing = pending.filter {
            context.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (stillMissing.isEmpty()) {
            blocked = false
            onGranted()
        } else {
            pending = stillMissing
            // 系统不再弹窗（勾了"不再询问"）时只能去设置页
            blocked = stillMissing.any { perm ->
                activity?.shouldShowRequestPermissionRationale(perm) == false
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 40.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = "需要权限才能使用",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = permissionIntro(pending),
                fontSize = 15.sp,
                lineHeight = 24.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = pending.joinToString("\n") { "· " + permissionLabel(it) },
                fontSize = 14.sp,
                lineHeight = 24.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(30.dp))
            Button(
                onClick = {
                    if (blocked) openAppSettings(context) else launcher.launch(pending.toTypedArray())
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (blocked) "打开系统设置" else "去授权")
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onExit, modifier = Modifier.fillMaxWidth()) {
                Text("退出")
            }
        }
    }
}

private fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private fun permissionIntro(missing: List<String>): String =
    if (missing.any { it == android.Manifest.permission.CAMERA }) {
        "需要「相机」才能正常使用，以及「照片和视频」用于读取相册。未授权前无法进入。"
    } else {
        "需要「照片和视频」权限用于读取相册。未授权前无法进入。"
    }

private fun permissionLabel(perm: String): String = when (perm) {
    android.Manifest.permission.CAMERA -> "相机"
    android.Manifest.permission.READ_MEDIA_IMAGES,
    android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    android.Manifest.permission.READ_EXTERNAL_STORAGE -> "照片和视频"
    android.Manifest.permission.POST_NOTIFICATIONS -> "通知"
    else -> perm.substringAfterLast('.')
}
