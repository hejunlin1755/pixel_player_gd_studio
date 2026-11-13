package com.theveloper.pixelplay.presentation.identify

import android.Manifest
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ScreenShare
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theveloper.pixelplay.data.identify.IdentifySource
import com.theveloper.pixelplay.presentation.components.SmartImage
import kotlinx.coroutines.delay

@Composable
fun SongIdentifyDialog(
    onDismiss: () -> Unit,
    onSearch: (String) -> Unit,
    viewModel: SongIdentifyViewModel = hiltViewModel()
) {
    val phase by viewModel.phase.collectAsStateWithLifecycle()
    val level by viewModel.level.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pending by remember { mutableStateOf<IdentifySource?>(null) }
    var heard by remember { mutableFloatStateOf(0f) }
    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val consent = result.data
        if (result.resultCode == android.app.Activity.RESULT_OK && consent != null) {
            viewModel.listenPlayback(result.resultCode, consent)
        } else {
            viewModel.fail("没有允许录制屏幕声音")
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val source = pending
        pending = null
        if (!granted || source == null) {
            viewModel.fail("需要麦克风权限才能听歌识曲")
            return@rememberLauncherForActivityResult
        }
        when (source) {
            IdentifySource.Microphone -> viewModel.listenMicrophone()
            IdentifySource.Playback -> {
                val manager = context.getSystemService(MediaProjectionManager::class.java)
                projectionLauncher.launch(manager.createScreenCaptureIntent())
            }
        }
    }
    DisposableEffect(viewModel) {
        onDispose { viewModel.stop() }
    }
    LaunchedEffect(phase) {
        if (phase is IdentifyPhase.Listening) {
            heard = 0f
            val steps = 120
            repeat(steps) {
                delay(100)
                if (viewModel.phase.value !is IdentifyPhase.Listening) return@LaunchedEffect
                heard = (it + 1) / steps.toFloat()
            }
        } else if (phase !is IdentifyPhase.Matching) {
            heard = 0f
        }
    }

    fun begin(source: IdentifySource) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            pending = source
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        when (source) {
            IdentifySource.Microphone -> viewModel.listenMicrophone()
            IdentifySource.Playback -> {
                val manager = context.getSystemService(MediaProjectionManager::class.java)
                projectionLauncher.launch(manager.createScreenCaptureIntent())
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "听歌识曲",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "关闭")
                    }
                }
                Text(
                    text = "免费识别。麦克风听周围的歌，屏幕声音听手机里正在放的歌。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    IdentifyVisualizer(
                        busy = phase is IdentifyPhase.Listening || phase is IdentifyPhase.Matching || phase is IdentifyPhase.Found,
                        level = level,
                        progress = heard,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                AnimatedContent(targetState = phase, label = "identify_status") { current ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = when (current) {
                                IdentifyPhase.Ready -> "选一种方式，动画会跟着声音动"
                                is IdentifyPhase.Listening -> if (current.source == IdentifySource.Microphone) {
                                    "正在听周围的声音"
                                } else {
                                    "正在听屏幕里的声音"
                                }
                                IdentifyPhase.Matching -> "正在比对这首歌"
                                is IdentifyPhase.Found -> "认出来了"
                                is IdentifyPhase.Failed -> current.message
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            color = if (current is IdentifyPhase.Failed) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                        Spacer(Modifier.height(16.dp))
                        if (current is IdentifyPhase.Found) {
                            FoundCard(phase = current, onSearch = onSearch)
                        }
                        if (current !is IdentifyPhase.Listening && current !is IdentifyPhase.Matching && current !is IdentifyPhase.Found) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                FilledTonalButton(onClick = { begin(IdentifySource.Microphone) }) {
                                    Icon(Icons.Rounded.Mic, contentDescription = null)
                                    Text("麦克风", modifier = Modifier.padding(start = 8.dp))
                                }
                                Button(onClick = { begin(IdentifySource.Playback) }) {
                                    Icon(Icons.AutoMirrored.Rounded.ScreenShare, contentDescription = null)
                                    Text("屏幕声音", modifier = Modifier.padding(start = 8.dp))
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun FoundCard(phase: IdentifyPhase.Found, onSearch: (String) -> Unit) {
    val song = phase.song
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SmartImage(
            model = song.artworkUrl,
            contentDescription = song.title,
            modifier = Modifier.size(112.dp),
            shape = RoundedCornerShape(24.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text(song.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(song.artist, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        song.album?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = { onSearch("${song.title} ${song.artist}".trim()) }) {
            Icon(Icons.Rounded.GraphicEq, contentDescription = null)
            Text("去搜索这首歌", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun IdentifyVisualizer(
    busy: Boolean,
    level: Float,
    progress: Float,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "identify_motion")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(if (busy) 2400 else 6400, easing = LinearEasing)),
        label = "spin"
    )
    val ring = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1700, easing = LinearOutSlowInEasing)),
        label = "ring"
    )
    val ringB = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1700, delayMillis = 560, easing = LinearOutSlowInEasing)),
        label = "ring_b"
    )
    val ringC = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1700, delayMillis = 1120, easing = LinearOutSlowInEasing)),
        label = "ring_c"
    )
    val breathe by transition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(if (busy) 700 else 1400), repeatMode = RepeatMode.Reverse),
        label = "breathe"
    )
    val bars = List(9) { index ->
        transition.animateFloat(
            initialValue = 0.18f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(380 + index * 35, delayMillis = index * 60),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bar_$index"
        )
    }
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val barValues = bars.map { it.value }
    val ringProgress = ring.value
    val ringProgressB = ringB.value
    val ringProgressC = ringC.value
    Box(
        modifier = modifier.graphicsLayer {
            scaleX = breathe
            scaleY = breathe
        },
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.size(340.dp)) {
            val center = this.center
            fun pulse(amount: Float, color: Color) {
                drawCircle(
                    color = color.copy(alpha = (1f - amount) * if (busy) 0.9f else 0.45f),
                    radius = size.minDimension * (0.18f + 0.36f * amount) / 2f,
                    center = center,
                    style = Stroke(width = 5.dp.toPx())
                )
            }
            pulse(ringProgress, primary)
            pulse(ringProgressB, tertiary)
            pulse(ringProgressC, primary)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(primary.copy(alpha = 0.28f + level * 0.4f), Color.Transparent),
                    center = center,
                    radius = size.minDimension * 0.22f
                ),
                radius = size.minDimension * 0.2f,
                center = center
            )
            rotate(spin, center) {
                drawArc(
                    brush = Brush.sweepGradient(listOf(Color.Transparent, primary, tertiary, Color.Transparent)),
                    startAngle = 0f,
                    sweepAngle = 300f,
                    useCenter = false,
                    topLeft = Offset(center.x - size.minDimension * 0.3f, center.y - size.minDimension * 0.3f),
                    size = Size(size.minDimension * 0.6f, size.minDimension * 0.6f),
                    style = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round)
                )
            }
            if (progress > 0f) {
                drawArc(
                    color = tertiary,
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    topLeft = Offset(center.x - size.minDimension * 0.42f, center.y - size.minDimension * 0.42f),
                    size = Size(size.minDimension * 0.84f, size.minDimension * 0.84f),
                    style = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round)
                )
            }
            val count = barValues.size
            val gap = 7.dp.toPx()
            val barWidth = 9.dp.toPx()
            val total = count * barWidth + (count - 1) * gap
            var left = center.x - total / 2f
            barValues.forEach { value ->
                val amount = (value * 0.62f + level * 0.7f).coerceIn(0.16f, 1.25f)
                val height = size.minDimension * 0.24f * amount
                drawRoundRect(
                    color = if (busy) primary else primary.copy(alpha = 0.55f),
                    topLeft = Offset(left, center.y - height / 2f),
                    size = Size(barWidth, height),
                    cornerRadius = CornerRadius(barWidth, barWidth)
                )
                left += barWidth + gap
            }
        }
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.GraphicEq,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(36.dp)
            )
        }
    }
}
