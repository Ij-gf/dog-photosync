package com.autoshot.app.ui

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 首次启动的一次性知情同意页。
 *
 * 为什么必须有：本应用会在后台定时调用摄像头。未取得设备使用者同意的采集，
 * 在多数司法辖区（含《个人信息保护法》第 13/14/29 条）不具合法性基础。
 * 采集服务在 CaptureService 里有第二道校验，没同意就拒绝启动。
 */
@Composable
fun ConsentScreen(
    onAgree: () -> Unit,
    onDecline: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 40.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = "使用前须知",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(18.dp))
            Text(
                text = "本应用会在后台定时调用本机前后摄像头拍照，并把照片同步到你自己配置的云端空间。",
                fontSize = 15.sp,
                lineHeight = 24.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "· 仅在你本人所有、自己使用的设备上使用\n" +
                    "· 拍摄范围若涉及他人，需事先取得对方同意\n" +
                    "· 照片存储在你自己的账号下，开发者无法访问",
                fontSize = 14.sp,
                lineHeight = 24.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
            )
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = onAgree,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("我已知悉并同意")
            }
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = onDecline,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("不同意，退出")
            }
        }
    }
}
