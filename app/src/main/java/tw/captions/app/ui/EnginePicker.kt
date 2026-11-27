package tw.captions.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tw.captions.app.UiState
import tw.captions.app.engine.EngineCatalog
import tw.captions.app.engine.EngineInfo

private val SheetBg = Color(0xFF1C1C1C)
private val Sub = Color(0xFFB0B0B0)
private val Accent = Color(0xFF66BB6A)

/** Engine list: name, one-line description, badges, and download / remove for offline models. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnginePicker(
    ui: UiState,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onDownload: (String) -> Unit,
    onCancelDownload: () -> Unit,
    onUninstall: (String) -> Unit,
) {
    var confirmRemove by remember { mutableStateOf<EngineInfo?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SheetBg) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("選擇辨識引擎", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            EngineCatalog.ALL.forEach { info ->
                EngineRow(
                    info = info,
                    selected = ui.engineId == info.id,
                    installed = !info.offline || info.packs.isEmpty() || info.id in ui.installedEngines,
                    downloading = ui.downloadingEngine == info.id,
                    progress = ui.modelDownload,
                    freeBytes = ui.freeableBytes[info.id],
                    onSelect = { onSelect(info.id) },
                    onDownload = { onDownload(info.id) },
                    onCancel = onCancelDownload,
                    onRemove = { confirmRemove = info },
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    confirmRemove?.let { info ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("移除「${info.name}」的模型？") },
            text = {
                Text("會釋放約 ${mb(ui.freeableBytes[info.id] ?: 0)}。之後要用時可以再下載。")
            },
            confirmButton = {
                TextButton(onClick = { onUninstall(info.id); confirmRemove = null }) { Text("移除") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("取消") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EngineRow(
    info: EngineInfo,
    selected: Boolean,
    installed: Boolean,
    downloading: Boolean,
    progress: Float?,
    freeBytes: Long?,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, if (selected) Accent else Color(0xFF3A3A3A), RoundedCornerShape(12.dp))
            .clickable(onClick = onSelect)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = selected,
                onClick = onSelect,
                colors = RadioButtonDefaults.colors(selectedColor = Accent, unselectedColor = Sub),
            )
            Text(info.name, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        }
        Text(info.subtitle, color = Sub, fontSize = 15.sp, lineHeight = 21.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (info.recommended) Badge("推薦", Accent)
            Badge(if (info.offline) "不用網路" else "需要網路", Sub)
            if (info.speakerTags) Badge("說話者標籤", Sub)
            if (info.paid) Badge("付費", Color(0xFFFFB74D))
            if (info.packs.isNotEmpty()) Badge("下載約 ${mb(info.packs.sumOf { it.downloadBytes })}", Sub)
        }
        if (info.packs.isNotEmpty()) {
            when {
                downloading -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(progress = { progress ?: 0f }, modifier = Modifier.fillMaxWidth(), color = Accent)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("下載中 ${((progress ?: 0f) * 100).toInt()}%", color = Sub, modifier = Modifier.weight(1f))
                        TextButton(onClick = onCancel) { Text("取消", color = Color.White) }
                    }
                }
                installed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("已安裝", color = Accent, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = onRemove) { Text("移除（約 ${mb(freeBytes ?: 0)}）", color = Color.White) }
                }
                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("未安裝", color = Sub, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = onDownload) { Text("下載", color = Color.White) }
                }
            }
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text,
        color = color,
        fontSize = 13.sp,
        modifier = Modifier
            .border(1.dp, color, RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

private fun mb(bytes: Long): String = "${(bytes + 500_000) / 1_000_000} MB"
