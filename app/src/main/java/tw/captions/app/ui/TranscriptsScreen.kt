package tw.captions.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import tw.captions.app.UiState
import tw.captions.app.history.TranscriptStore
import tw.captions.core.Transcript

private val Sub: Color @Composable get() = LocalPalette.current.sub
private val Danger: Color @Composable get() = LocalPalette.current.error
private val Hit = Color(0xFFFFEB3B)

/** 紀錄: saved transcripts, search, rename, delete and export (ADR-029). */
@Composable
fun TranscriptsScreen(
    ui: UiState,
    onDismiss: () -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onDeleteAll: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    exportText: (Transcript, Boolean) -> String,
) {
    var query by remember { mutableStateOf("") }
    var openId by remember { mutableStateOf<String?>(null) }
    var confirmAll by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(LocalPalette.current.surface).safeDrawingPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val open = ui.transcripts.firstOrNull { it.id == openId }
            if (open != null) {
                TranscriptDetail(open, query, onBack = { openId = null }, onRename = onRename, onDelete = { onDelete(it); openId = null }, exportText = exportText)
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("逐字稿紀錄", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("完成", color = Ink, fontSize = 18.sp) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("自動保存每次的逐字稿", color = Ink, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Switch(checked = ui.transcriptsEnabled, onCheckedChange = onSetEnabled)
            }
            Text("只存在這支手機上，不會上傳，也不存聲音。", color = Sub, fontSize = 14.sp)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("搜尋標題或內容") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            val shown = ui.transcripts.filter { it.matches(query.trim()) }
            if (shown.isEmpty()) {
                Text(if (ui.transcripts.isEmpty()) "還沒有紀錄。按「開始」後的字幕會自動存在這裡。" else "找不到符合的紀錄。", color = Sub, fontSize = 16.sp)
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shown, key = { it.id }) { t ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .border(1.dp, LocalPalette.current.border, RoundedCornerShape(12.dp))
                            .clickable { openId = t.id }
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(TranscriptStore.displayTitle(t), color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(TranscriptStore.subtitle(t), color = Sub, fontSize = 14.sp)
                        val preview = if (query.isNotBlank()) t.lines.firstOrNull { it.text.contains(query.trim(), ignoreCase = true) } ?: t.lines.firstOrNull() else t.lines.firstOrNull()
                        preview?.let { Text(highlight(it.text, query.trim()), color = Sub, fontSize = 15.sp, maxLines = 2) }
                    }
                }
            }
            if (ui.transcripts.isNotEmpty()) {
                TextButton(onClick = { confirmAll = true }) { Text("全部刪除", color = Danger) }
            }
        }
        if (confirmAll) {
            AlertDialog(
                onDismissRequest = { confirmAll = false },
                title = { Text("刪除所有逐字稿？") },
                text = { Text("共 ${ui.transcripts.size} 份，刪除後無法復原。") },
                confirmButton = { TextButton(onClick = { onDeleteAll(); confirmAll = false }) { Text("全部刪除", color = Danger) } },
                dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("取消") } },
            )
        }
    }
}

@Composable
private fun TranscriptDetail(
    t: Transcript,
    query: String,
    onBack: () -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    exportText: (Transcript, Boolean) -> String,
) {
    val context = LocalContext.current
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pendingSrt by remember { mutableStateOf(false) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(exportText(t, pendingSrt).toByteArray(Charsets.UTF_8)) }
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ 紀錄", color = Ink, fontSize = 18.sp) }
        }
        Text(TranscriptStore.displayTitle(t), color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(TranscriptStore.subtitle(t), color = Sub, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pendingSrt = false; save.launch(TranscriptStore.fileStem(t) + ".txt") }) { Text("下載 .txt", color = Ink) }
            OutlinedButton(onClick = { pendingSrt = true; save.launch(TranscriptStore.fileStem(t) + ".srt") }) { Text("下載 .srt", color = Ink) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { share(context, exportText(t, false), TranscriptStore.displayTitle(t)) }) { Text("分享", color = Ink) }
            TextButton(onClick = { copy(context, exportText(t, false)) }) { Text("複製全文", color = Ink) }
            TextButton(onClick = { renaming = true }) { Text("改標題", color = Ink) }
            TextButton(onClick = { confirmDelete = true }) { Text("刪除", color = Danger) }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(t.lines, key = { it.id }) { l ->
                Text(
                    buildAnnotatedString {
                        append(Transcript.srtTime(l.start).substring(0, 8) + "  ")
                        l.speaker?.let { withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = LocalPalette.current.speakers[0])) { append("$it：") } }
                        append(highlight(l.text, query))
                    },
                    color = Ink,
                    fontSize = 18.sp,
                    lineHeight = 26.sp,
                )
            }
        }
    }
    if (renaming) {
        var title by remember { mutableStateOf(t.title.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("改標題") },
            text = { OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true, label = { Text("例如 家庭聚餐") }) },
            confirmButton = { TextButton(onClick = { onRename(t.id, title); renaming = false }) { Text("儲存") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("取消") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("刪除這份逐字稿？") },
            text = { Text("刪除後無法復原。") },
            confirmButton = { TextButton(onClick = { onDelete(t.id); confirmDelete = false }) { Text("刪除", color = Danger) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

/** Marks every occurrence of [query] in yellow. */
private fun highlight(text: String, query: String): AnnotatedString = buildAnnotatedString {
    if (query.isBlank()) {
        append(text)
        return@buildAnnotatedString
    }
    var from = 0
    while (true) {
        val i = text.indexOf(query, from, ignoreCase = true)
        if (i < 0) break
        append(text.substring(from, i))
        withStyle(SpanStyle(background = Hit, color = Color.Black)) { append(text.substring(i, i + query.length)) }
        from = i + query.length
    }
    append(text.substring(from))
}

private fun share(context: Context, text: String, title: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, "分享逐字稿"))
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("逐字稿", text))
}
