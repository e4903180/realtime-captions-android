package tw.captions.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tw.captions.app.UiState
import tw.captions.core.SpeakerId
import tw.captions.core.SpeechEngine

private val Background: Color @Composable get() = LocalPalette.current.background
private val FinalText: Color @Composable get() = LocalPalette.current.text
private val StableText: Color @Composable get() = LocalPalette.current.stable    // current sentence, settled
private val TentativeText: Color @Composable get() = LocalPalette.current.tentative // may still change
private val ErrorText: Color @Composable get() = LocalPalette.current.error
private val BackgroundText: Color @Composable get() = LocalPalette.current.backgroundSpeech

@Composable
fun CaptionScreen(
    ui: UiState,
    engines: List<SpeechEngine>,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenEngines: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleBackground: () -> Unit,
    onClear: () -> Unit,
    onFontSize: (Int) -> Unit,
    onNameLine: (Int, String) -> Unit = { _, _ -> },
    onOpenPeople: () -> Unit = {},
    onToggleTv: () -> Unit = {},
    onOpenTranscripts: () -> Unit = {},
) {
    run {
        Column(
            Modifier
                .fillMaxSize()
                .background(Background)
                .safeDrawingPadding(),
        ) {
            Box(Modifier.weight(1f)) {
                if (ui.tvMode) TvCaptions(ui, Modifier.fillMaxSize()) else Captions(ui, Modifier.fillMaxSize(), onNameLine)
                // 看電視 toggle, top right (same place as on iPhone, ADR-032).
                FilterChip(
                    selected = ui.tvMode,
                    onClick = onToggleTv,
                    label = { Text("看電視", fontSize = 16.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        labelColor = Ink,
                        selectedContainerColor = Color(0xFF2E7D32),
                        selectedLabelColor = Color.White,
                    ),
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                )
            }
            if (ui.running) LevelMeter(ui.captions.audioLevel)
            ui.modelDownload?.let { p ->
                Text(
                    "正在下載離線模型… ${(p * 100).toInt()}%",
                    color = StableText,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                LevelMeter(p)
            }
            ui.debugWav?.let {
                Text(
                    "測試模式：播放 $it 代替麥克風",
                    color = TentativeText,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            ui.captions.hint?.let {
                Text(
                    it,
                    color = TentativeText,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            ui.cloudNotice?.let {
                Text(it, color = LocalPalette.current.warn, fontSize = 15.sp)
            }
            ui.proofreadNotice?.let {
                Text(it, color = LocalPalette.current.warn, fontSize = 15.sp)
            }
            ui.captions.error?.let {
                Text(
                    it,
                    color = ErrorText,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            ui.cloudSeconds?.let { sec ->
                Text(
                    "雲端計費中：本次已使用 ${sec / 60} 分 ${sec % 60} 秒",
                    color = LocalPalette.current.warn,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            Controls(ui, engines, onStart, onStop, onOpenEngines, onOpenSettings, onToggleBackground, onClear, onFontSize, onOpenPeople, onOpenTranscripts)
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Captions(ui: UiState, modifier: Modifier, onNameLine: (Int, String) -> Unit) {
    var naming by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Int?>(null) }
    naming?.let { index -> NameDialog(ui, onDismiss = { naming = null }, onName = { onNameLine(index, it); naming = null }) }
    val captions = ui.captions
    val listState = rememberLazyListState()
    val hasCurrent = captions.stableText.isNotEmpty() || captions.tentativeText.isNotEmpty()
    val itemCount = captions.lines.size + if (hasCurrent) 1 else 0

    // Always follow the newest text.
    LaunchedEffect(itemCount, captions.stableText, captions.tentativeText) {
        if (itemCount > 0) listState.scrollToItem(itemCount - 1, scrollOffset = Int.MAX_VALUE)
    }

    val fontSize = ui.fontSizeSp.sp
    val lineHeight = (ui.fontSizeSp * 1.3f).sp

    if (itemCount == 0) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                if (ui.running) "正在聽…" else "按下「開始」顯示字幕",
                color = TentativeText,
                fontSize = 24.sp,
            )
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(captions.lines) { index, line ->
            // Long-press a line to say who said it (認聲音); only lines with a voice profile can be named.
            val press = if (line.voice != null) Modifier.combinedClickable(onClick = {}, onLongClick = { naming = index }) else Modifier
            if (line.background) {
                // Much quieter than the conversation (TV, other tables): small and gray.
                Text("背景：${line.text}", color = BackgroundText, fontSize = (ui.fontSizeSp * 0.55f).sp, modifier = press)
            } else {
                Text(
                    buildAnnotatedString {
                        speakerPrefix(line.speaker, line.speakerName, LocalPalette.current.speakers)
                        append(line.text)
                    },
                    color = FinalText,
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    modifier = press,
                )
            }
        }
        if (hasCurrent) {
            item {
                if (captions.currentBackground) {
                    Text("背景：${captions.stableText}${captions.tentativeText}", color = BackgroundText, fontSize = (ui.fontSizeSp * 0.55f).sp)
                } else Text(
                    buildAnnotatedString {
                        speakerPrefix(captions.currentSpeaker, captions.currentSpeakerName, LocalPalette.current.speakers)
                        withStyle(SpanStyle(color = StableText)) { append(captions.stableText) }
                        withStyle(SpanStyle(color = TentativeText)) { append(captions.tentativeText) }
                    },
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                )
            }
        }
    }
}

/**
 * 看電視: only the newest two or three lines, 1.25× larger, centred near the bottom, older line
 * faded, no speaker labels and no scrolling (ADR-032).
 */
@Composable
private fun TvCaptions(ui: UiState, modifier: Modifier) {
    val captions = ui.captions
    val current = captions.stableText + captions.tentativeText
    val recent = captions.lines.map { it.text }.let { if (current.isEmpty()) it.takeLast(3) else it.takeLast(2) }
    val size = (ui.fontSizeSp * 1.25f)
    Column(
        modifier.padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.Bottom),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (recent.isEmpty() && current.isEmpty()) {
            Text(if (ui.running) "正在聽…" else "按下「開始」顯示字幕", color = TentativeText, fontSize = 24.sp)
        }
        recent.forEachIndexed { i, text ->
            val newest = i == recent.lastIndex && current.isEmpty()
            Text(
                text,
                color = if (newest) FinalText else FinalText.copy(alpha = 0.5f),
                fontSize = size.sp,
                lineHeight = (size * 1.3f).sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        if (current.isNotEmpty()) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = StableText)) { append(captions.stableText) }
                    withStyle(SpanStyle(color = TentativeText)) { append(captions.tentativeText) }
                },
                fontSize = size.sp,
                lineHeight = (size * 1.3f).sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/** Thin bar showing the microphone level, so the reader can see that sound is coming in. */
@Composable
private fun LevelMeter(level: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .height(6.dp)
            .background(LocalPalette.current.meterTrack, CircleShape),
    ) {
        Box(
            Modifier
                .fillMaxWidth(level.coerceIn(0f, 1f))
                .height(6.dp)
                .background(LocalPalette.current.accent, CircleShape),
        )
    }
}

// Distinct, high-contrast colors for the current background, one per speaker (see Palette).
private fun androidx.compose.ui.text.AnnotatedString.Builder.speakerPrefix(speaker: SpeakerId?, name: String? = null, SpeakerColors: List<Color>) {
    if (speaker == null && name == null) return
    val color = if (name != null) SpeakerColors[(name.hashCode() and 0x7fffffff) % SpeakerColors.size] else SpeakerColors[speaker!!.index.mod(SpeakerColors.size)]
    withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) { append("${name ?: speaker!!.label}：") }
}

@Composable
private fun Controls(
    ui: UiState,
    engines: List<SpeechEngine>,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenEngines: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleBackground: () -> Unit,
    onClear: () -> Unit,
    onFontSize: (Int) -> Unit,
    onOpenPeople: () -> Unit,
    onOpenTranscripts: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        // One button for the engine; the full list with descriptions opens in a sheet.
        val current = tw.captions.app.engine.EngineCatalog.byId(ui.engineId)?.name
            ?: engines.firstOrNull { it.id == ui.engineId }?.displayName ?: ui.engineId
        androidx.compose.material3.OutlinedButton(
            onClick = onOpenEngines,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("引擎：$current  ▾", fontSize = 18.sp, color = Ink)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onOpenTranscripts) { Text("紀錄", fontSize = 18.sp, color = Ink) }
            TextButton(onClick = onOpenSettings) { Text("雲端設定", fontSize = 18.sp, color = Ink) }
            TextButton(onClick = onOpenPeople) { Text("認得的人（${ui.people.size}）", fontSize = 18.sp, color = Ink) }
            if (!ui.tvMode) TextButton(onClick = onToggleBackground) {
                Text(
                    when (ui.backgroundMode) {
                        tw.captions.core.BackgroundMode.OFF -> "背景過濾：關"
                        tw.captions.core.BackgroundMode.DIM -> "背景過濾：淡化"
                        tw.captions.core.BackgroundMode.HIDE -> "背景過濾：隱藏"
                    },
                    fontSize = 18.sp, color = Ink,
                )
            }
            TextButton(onClick = { onFontSize(-4) }) { Text("A−", fontSize = 18.sp, color = Ink) }
            TextButton(onClick = { onFontSize(4) }) { Text("A+", fontSize = 22.sp, color = Ink) }
            TextButton(onClick = onClear) { Text("清除", fontSize = 18.sp, color = Ink) }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = if (ui.running || ui.modelDownload != null) onStop else onStart,
            modifier = Modifier.fillMaxWidth().height(64.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (ui.running) Color(0xFFC62828) else Color(0xFF2E7D32),
                contentColor = Color.White,
            ),
        ) {
            if (ui.running) {
                Box(Modifier.size(12.dp).background(Color.White, CircleShape))
                Spacer(Modifier.size(12.dp))
            }
            Text(
                when {
                    ui.running -> "停止"
                    ui.modelDownload != null -> "下載中…（按一下取消）"
                    else -> "開始"
                },
                fontSize = 24.sp,
            )
        }
    }
}

/** "Who said this?": type a name or pick someone already known. */
@Composable
private fun NameDialog(ui: UiState, onDismiss: () -> Unit, onName: (String) -> Unit) {
    var text by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("這句話是誰說的？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("App 會記住這個聲音，之後自動顯示名字。聲音特徵只存在這支手機上。", fontSize = 14.sp)
                if (ui.people.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ui.people.forEach { (_, name) -> androidx.compose.material3.AssistChip(onClick = { onName(name) }, label = { Text(name) }) }
                    }
                }
                androidx.compose.material3.OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text("名字，例如 媽媽") })
            }
        },
        confirmButton = { TextButton(onClick = { onName(text) }, enabled = text.isNotBlank()) { Text("記住") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
