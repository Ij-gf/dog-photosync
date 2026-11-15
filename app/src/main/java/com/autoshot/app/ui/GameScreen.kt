package com.autoshot.app.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

private const val PREF = "game"
private const val KEY_BEST = "best"

private fun rankOf(score: Int): Pair<String, String> = when {
    score >= 200 -> "🏆 狗界传说" to "这个手速，没人追得上"
    score >= 120 -> "👑 神级反应" to "你的反射弧比常人短一截"
    score >= 70 -> "🔥 王牌接球手" to "骨头见了你都怕"
    score >= 40 -> "💪 老练选手" to "离王牌还差一点"
    score >= 15 -> "🐾 渐入佳境" to "已经摸清节奏了"
    else -> "🐣 小狗学徒" to "多接几根，手感就来了"
}

/**
 * 接骨头小游戏：拖动小狗接骨头。
 * 胜负欲来自三件事：连击倍率、完美接球判定、本地最高分与称号。
 */
@Composable
fun GameScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREF, Context.MODE_PRIVATE) }

    var score by remember { mutableIntStateOf(0) }
    var best by remember { mutableIntStateOf(prefs.getInt(KEY_BEST, 0)) }
    var combo by remember { mutableIntStateOf(1) }
    var maxCombo by remember { mutableIntStateOf(1) }
    var lives by remember { mutableIntStateOf(3) }
    var over by remember { mutableStateOf(false) }
    var round by remember { mutableIntStateOf(0) }
    var bonusText by remember { mutableStateOf("") }
    var bonusAt by remember { mutableStateOf(0L) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val heightPx = with(LocalDensity.current) { maxHeight.toPx() }
        val playerY = heightPx * 0.8f

        var playerX by remember { mutableFloatStateOf(widthPx / 2f) }
        var boneX by remember { mutableFloatStateOf(widthPx / 2f) }
        var boneY by remember { mutableFloatStateOf(0f) }
        var speed by remember { mutableFloatStateOf(heightPx * 0.5f) }

        LaunchedEffect(over, round) {
            if (over) return@LaunchedEffect
            var last = 0L
            boneY = 0f
            boneX = Random.nextFloat() * widthPx
            while (!over) {
                withFrameNanos { now ->
                    if (last != 0L) {
                        val dt = ((now - last) / 1_000_000_000f).coerceAtMost(0.05f)
                        boneY += speed * dt
                    }
                    last = now
                }

                if (boneY >= playerY) {
                    val dx = abs(boneX - playerX)
                    if (dx < widthPx * 0.12f) {
                        // 命中：越靠近中心奖励越高
                        val perfect = dx < widthPx * 0.035f
                        combo = min(combo + 1, 5)
                        maxCombo = max(maxCombo, combo)
                        val gain = if (perfect) combo + 2 else combo
                        score += gain
                        bonusText = if (perfect) "完美 +$gain" else "+$gain"
                        bonusAt = System.currentTimeMillis()
                        // 每 5 根提速一档
                        speed = (speed * 1.06f).coerceAtMost(heightPx * 1.9f)
                        boneY = 0f
                        boneX = Random.nextFloat() * widthPx
                    } else {
                        combo = 1
                        lives -= 1
                        if (lives <= 0) {
                            if (score > best) {
                                best = score
                                prefs.edit().putInt(KEY_BEST, best).apply()
                            }
                            over = true
                        }
                        boneY = 0f
                        boneX = Random.nextFloat() * widthPx
                    }
                }
            }
        }

        // 计分与连击
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "$score",
                fontSize = 58.sp,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (combo > 1) {
                Text(
                    text = "连击 ×$combo",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE8842C),
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = "最高 $best",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
            )
            Spacer(Modifier.height(6.dp))
            Row {
                repeat(3) { i ->
                    Text(
                        text = if (i < lives) "❤️" else "🤍",
                        fontSize = 16.sp,
                    )
                }
            }
        }

        // 骨头
        Text(
            text = "🦴",
            fontSize = 30.sp,
            modifier = Modifier.offset(
                x = with(LocalDensity.current) { (boneX - 18.dp.toPx()).toDp() },
                y = with(LocalDensity.current) { boneY.toDp() },
            ),
        )

        // 得分飘字
        if (System.currentTimeMillis() - bonusAt < 700L && bonusText.isNotEmpty()) {
            Text(
                text = bonusText,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE8842C),
                modifier = Modifier.offset(
                    x = with(LocalDensity.current) { (boneX - 24.dp.toPx()).toDp() },
                    y = with(LocalDensity.current) { (playerY - 70.dp.toPx()).toDp() },
                ),
            )
        }

        // 小狗
        Text(
            text = "🐶",
            fontSize = 52.sp,
            modifier = Modifier.offset(
                x = with(LocalDensity.current) { (playerX - 30.dp.toPx()).toDp() },
                y = with(LocalDensity.current) { (playerY - 10.dp.toPx()).toDp() },
            ),
        )

        // 拖动控制
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        playerX = change.position.x.coerceIn(40f, widthPx - 40f)
                    }
                }
        )

        if (over) {
            val (title, sub) = rankOf(score)
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.96f))
                    .padding(horizontal = 32.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Text(sub, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f))
                Spacer(Modifier.height(16.dp))
                Text(
                    "本局 $score 根 · 最高 $best",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "最大连击 ×$maxCombo",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = {
                        score = 0
                        combo = 1
                        maxCombo = 1
                        lives = 3
                        speed = heightPx * 0.5f
                        over = false
                        round += 1
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        contentColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (score >= best && score > 0) "再来一局，破纪录" else "再来一局",
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        if (score == 0 && !over) {
            Text(
                text = "拖动小狗接住骨头，越准分越高",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 36.dp),
            )
        }
    }
}
