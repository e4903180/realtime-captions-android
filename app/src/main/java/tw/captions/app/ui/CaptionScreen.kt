package tw.captions.app.ui

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

private val Background = Color.Black
private val FinalText = Color.White
private val StableText = Color(0xFFFFF59D)    // light yellow: current sentence, settled
private val TentativeText = Color(0xFF9E9E9E) // gray: may still change
private val ErrorText = Color(0xFFFF8A80)

@Composable
fun CaptionScreen(
    ui: UiState,
    engines: List<SpeechEngine>,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSelectEngine: (String) -> Unit,
    onClear: () -> Unit,
    onFontSize: (Int) -> Unit,
) {
    MaterialTheme(colorScheme = darkColorScheme(background = Background)) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Background)
                .safeDrawingPadding(),
        ) {
            Captions(ui, Modifier.weight(1f))
            if (ui.running) LevelMeter(ui.captions.audioLevel)
            ui.captions.hint?.let {
                Text(
                    it,
                    color = TentativeText,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            ui.captions.error?.let {
                Text(
                    it,
                    color = ErrorText,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            Controls(ui, engines, onStart, onStop, onSelectEngine, onClear, onFontSize)
        }
    }
}

@Composable
private fun Captions(ui: UiState, modifier: Modifier) {
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
        itemsIndexed(captions.lines) { _, line ->
            Text(
                buildAnnotatedString {
                    line.speaker?.let { speakerPrefix(it) }
                    append(line.text)
                },
                color = FinalText,
                fontSize = fontSize,
                lineHeight = lineHeight,
            )
        }
        if (hasCurrent) {
            item {
                Text(
                    buildAnnotatedString {
                        captions.currentSpeaker?.let { speakerPrefix(it) }
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

/** Thin bar showing the microphone level, so the reader can see that sound is coming in. */
@Composable
private fun LevelMeter(level: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .height(6.dp)
            .background(Color(0xFF303030), CircleShape),
    ) {
        Box(
            Modifier
                .fillMaxWidth(level.coerceIn(0f, 1f))
                .height(6.dp)
                .background(Color(0xFF66BB6A), CircleShape),
        )
    }
}

// Distinct, high-contrast colors on black, one per speaker.
private val SpeakerColors = listOf(
    Color(0xFF80D8FF), Color(0xFFFFAB91), Color(0xFFB9F6CA), Color(0xFFEA80FC), Color(0xFFFFE57F),
)

private fun androidx.compose.ui.text.AnnotatedString.Builder.speakerPrefix(speaker: SpeakerId) {
    withStyle(
        SpanStyle(color = SpeakerColors[speaker.index.mod(SpeakerColors.size)], fontWeight = FontWeight.Bold),
    ) { append("${speaker.label}：") }
}

@Composable
private fun Controls(
    ui: UiState,
    engines: List<SpeechEngine>,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSelectEngine: (String) -> Unit,
    onClear: () -> Unit,
    onFontSize: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            engines.forEach { engine ->
                FilterChip(
                    selected = ui.engineId == engine.id,
                    onClick = { onSelectEngine(engine.id) },
                    label = { Text(engine.displayName, fontSize = 18.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF424242),
                        selectedLabelColor = Color.White,
                        labelColor = Color.LightGray,
                    ),
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onFontSize(-4) }) { Text("A−", fontSize = 18.sp, color = Color.White) }
            TextButton(onClick = { onFontSize(4) }) { Text("A+", fontSize = 22.sp, color = Color.White) }
            TextButton(onClick = onClear) { Text("清除", fontSize = 18.sp, color = Color.White) }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = if (ui.running) onStop else onStart,
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
            Text(if (ui.running) "停止" else "開始", fontSize = 24.sp)
        }
    }
}
