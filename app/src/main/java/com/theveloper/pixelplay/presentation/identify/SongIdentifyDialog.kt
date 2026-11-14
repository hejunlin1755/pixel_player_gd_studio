package com.theveloper.pixelplay.presentation.identify

import android.Manifest
import android.app.Activity
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
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Mic
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theveloper.pixelplay.data.identify.IdentifyRecord
import com.theveloper.pixelplay.data.identify.IdentifySource
import com.theveloper.pixelplay.presentation.components.SmartImage
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SongIdentifyDialog(
    onDismiss: () -> Unit,
    onSearch: (String) -> Unit,
    viewModel: SongIdentifyViewModel = hiltViewModel()
) {
    val phase by viewModel.phase.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val level by viewModel.level.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showHistory by remember { mutableStateOf(false) }
    var heard by remember { mutableFloatStateOf(0f) }
    var pending by remember { mutableStateOf(IdentifySource.Microphone) }
    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            viewModel.listenPlayback(result.resultCode, data)
        } else {
            viewModel.fail("需要允许录制设备正在播放的声音")
        }
    }
    fun launchPlayback() {
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) viewModel.fail("需要麦克风权限才能听歌识曲")
        else if (pending == IdentifySource.Playback) launchPlayback()
        else viewModel.listenMicrophone()
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
        pending = source
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else if (source == IdentifySource.Playback) {
            launchPlayback()
        } else {
            viewModel.listenMicrophone()
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
                    if (showHistory) {
                        IconButton(onClick = { showHistory = false }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                        }
                    }
                    Text(
                        text = if (showHistory) "识别历史" else "听歌识曲",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    if (!showHistory) {
                        IconButton(onClick = { showHistory = true }) {
                            Icon(Icons.Rounded.History, contentDescription = "历史记录")
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "关闭")
                    }
                }
                if (showHistory) {
                    IdentifyHistoryList(
                        records = history,
                        onSearch = onSearch,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                } else {
                Text(
                    text = "可以听周围的声音，也可以听手机里正在放的声音。录好后发给免费的在线识别服务。",
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
                                IdentifyPhase.Ready -> "选一种方式开始听"
                                is IdentifyPhase.Listening -> when (current.source) {
                                    IdentifySource.Microphone -> "正在听周围的声音"
                                    IdentifySource.Playback -> "正在听设备里正在放的声音"
                                }
                                IdentifyPhase.Matching -> "正在发给在线识别"
                                is IdentifyPhase.Found -> "在线认出来了"
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
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(onClick = { begin(IdentifySource.Microphone) }) {
                                    Icon(Icons.Rounded.Mic, contentDescription = null)
                                    Text("麦克风", modifier = Modifier.padding(start = 8.dp))
                                }
                                FilledTonalButton(onClick = { begin(IdentifySource.Playback) }) {
                                    Text("设备播放")
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
}

@Composable
private fun IdentifyHistoryList(
    records: List<IdentifyRecord>,
    onSearch: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (records.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("还没有识别记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(modifier = modifier) {
        itemsIndexed(records, key = { index, item -> "${item.at}-$index" }) { _, item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSearch("${item.title} ${item.artist}".trim()) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SmartImage(
                    model = item.artworkUrl,
                    contentDescription = item.title,
                    modifier = Modifier.size(56.dp),
                    shape = RoundedCornerShape(14.dp)
                )
                Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = item.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = formatHistoryTime(item.at),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private val historyTime: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日 HH:mm")

private fun formatHistoryTime(at: Long): String {
    return Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(historyTime)
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
            Text("用这个歌名去在线搜索")
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
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Canvas(modifier = Modifier.size(280.dp)) {
                val center = this.center
                val radius = size.minDimension / 2f - 16.dp.toPx()
                val arcTop = Offset(center.x - radius, center.y - radius)
                val arcSize = Size(radius * 2f, radius * 2f)
                fun pulse(amount: Float, color: Color) {
                    drawCircle(
                        color = color.copy(alpha = (1f - amount) * if (busy) 0.9f else 0.45f),
                        radius = 48.dp.toPx() + (radius - 48.dp.toPx()) * amount,
                        center = center,
                        style = Stroke(width = 5.dp.toPx())
                    )
                }
                pulse(ringProgress, primary)
                pulse(ringProgressB, tertiary)
                pulse(ringProgressC, primary)
                drawCircle(
                    color = primary.copy(alpha = 0.28f),
                    radius = radius,
                    center = center,
                    style = Stroke(width = 6.dp.toPx())
                )
                rotate(degrees = spin, pivot = center) {
                    drawArc(
                        color = primary,
                        startAngle = -90f,
                        sweepAngle = 72f,
                        useCenter = false,
                        topLeft = arcTop,
                        size = arcSize,
                        style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
                if (progress > 0f) {
                    drawArc(
                        color = tertiary,
                        startAngle = -90f,
                        sweepAngle = 360f * progress,
                        useCenter = false,
                        topLeft = arcTop,
                        size = arcSize,
                        style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Mic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(36.dp)
                )
            }
        }
        Spacer(Modifier.height(28.dp))
        androidx.compose.foundation.Canvas(modifier = Modifier.size(width = 220.dp, height = 72.dp)) {
            val count = barValues.size
            val gap = 8.dp.toPx()
            val barWidth = 8.dp.toPx()
            val total = count * barWidth + (count - 1) * gap
            var left = (size.width - total) / 2f
            barValues.forEach { value ->
                val amount = (value * 0.62f + level * 0.7f).coerceIn(0.18f, 1f)
                val barHeight = size.height * amount
                drawRoundRect(
                    color = if (busy) primary else primary.copy(alpha = 0.45f),
                    topLeft = Offset(left, size.height - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(barWidth, barWidth)
                )
                left += barWidth + gap
            }
        }
    }
}
