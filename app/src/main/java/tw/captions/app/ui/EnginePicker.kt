package tw.captions.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
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

private val SheetBg: Color @Composable get() = LocalPalette.current.surface
private val Sub: Color @Composable get() = LocalPalette.current.sub
private val Accent: Color @Composable get() = LocalPalette.current.accent

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
    onOpenSettings: () -> Unit,
    onAppearance: (Appearance) -> Unit = {},
    onSpeakersOn: (Boolean) -> Unit = {},
    onSpeakerCount: (Int) -> Unit = {},
    onVocabulary: (String) -> Unit = {},
    onMic: (Int) -> Unit = {},
    onSystemNoiseSuppression: (Boolean) -> Unit = {},
) {
    var confirmRemove by remember { mutableStateOf<EngineInfo?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SheetBg) {
        SheetStatusBar()
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("選擇辨識引擎", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            // Two kinds: models that run on the phone, and cloud services with the user's own key.
            var tab by remember { mutableStateOf(if (EngineCatalog.byId(ui.engineId)?.offline == false) 1 else 0) }
            androidx.compose.material3.TabRow(
                selectedTabIndex = tab,
                containerColor = SheetBg,
                contentColor = Ink,
            ) {
                listOf("離線（在手機上）", "雲端").forEachIndexed { i, label ->
                    androidx.compose.material3.Tab(
                        selected = tab == i,
                        onClick = { tab = i },
                        text = { Text(label, fontSize = 17.sp) },
                    )
                }
            }
            Text(
                if (tab == 0) "模型下載到手機後就不用網路，也不花錢。只下載你要用的模型，不用時可以移除。"
                else "使用雲端公司的辨識服務，需要網路和你自己的帳號金鑰，按使用時間計費。",
                color = Sub, fontSize = 15.sp,
            )
            EngineCatalog.ALL.filter { it.offline == (tab == 0) }.forEach { info ->
                EngineRow(
                    info = info,
                    selected = ui.engineId == info.id,
                    installed = !info.offline || info.packs.isEmpty() || info.id in ui.installedEngines,
                    downloading = ui.downloadingEngine == info.id,
                    progress = ui.modelDownload,
                    freeBytes = ui.freeableBytes[info.id],
                    downloadBytes = ui.downloadBytes[info.id],
                    onSelect = { onSelect(info.id) },
                    onDownload = { onDownload(info.id) },
                    onCancel = onCancelDownload,
                    onRemove = { confirmRemove = info },
                    keyHint = when (info.id) {
                        tw.captions.app.engine.AzureSpeechEngine.ID -> ui.azureKeyHint
                        else -> null
                    },
                    onOpenSettings = onOpenSettings,
                    billing = if (info.paid) billingLine(ui) else null,
                )
            }
            // 自訂詞彙: the user's own names and terms.
            Text("自訂詞彙", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            var words by remember(ui.vocabulary) { mutableStateOf(ui.vocabulary.joinToString("\n")) }
            androidx.compose.material3.OutlinedTextField(
                value = words,
                onValueChange = { words = it },
                label = { Text("一行一個詞，例如家人名字、地名、專有名詞") },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onVocabulary(words) }, enabled = words != ui.vocabulary.joinToString("\n")) { Text("儲存", color = Ink) }
                Text("目前 ${ui.vocabulary.size} 個詞", color = Sub, fontSize = 14.sp)
            }
            Text("Azure 雲端會優先辨識成這些詞，下次按「開始」生效。離線模型還不支援，之後補上。", color = Sub, fontSize = 14.sp)
            // 說話者 (ADR-027, ADR-033)
            Text("說話者", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("說話者辨識", color = Ink, fontSize = 16.sp, modifier = Modifier.weight(1f))
                androidx.compose.material3.Switch(checked = ui.speakersOn, onCheckedChange = onSpeakersOn)
            }
            if (ui.speakersOn) {
                Text("說話人數", color = Sub, fontSize = 15.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (listOf(0) + (2..6)).forEach { n ->
                        val label = if (n == 0) "自動" else "$n 人"
                        if (n == ui.speakerCount) {
                            androidx.compose.material3.Button(onClick = { onSpeakerCount(n) }) { Text(label) }
                        } else {
                            OutlinedButton(onClick = { onSpeakerCount(n) }) { Text(label, color = Ink) }
                        }
                    }
                }
            }
            Text(
                if (ui.speakersOn) "分出不同的人，標 A、B、C。知道有幾個人在講話時選人數，離線分人會比較準。"
                else "不分人、不顯示標籤，只照停頓換行；離線時比較省電。",
                color = Sub, fontSize = 14.sp,
            )
            // 麥克風: the phone's own, or a wired / USB one placed near the speaker.
            Text("麥克風", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            ui.micChoices.forEach { (type, label) ->
                Row(Modifier.fillMaxWidth().clickable { onMic(type) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = ui.micType == type, onClick = { onMic(type) })
                    Text(label, color = Ink, fontSize = 16.sp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("手機內建降噪", color = Ink, fontSize = 16.sp, modifier = Modifier.weight(1f))
                androidx.compose.material3.Switch(checked = ui.systemNoiseSuppression, onCheckedChange = onSystemNoiseSuppression)
            }
            Text(
                "吵的地方，把有線或 USB 麥克風放在說話的人附近最有效；插上後會出現在這裡。藍牙麥克風還不支援。" +
                    "如果小聲的話常常沒出字，可以試著關掉手機內建降噪。",
                color = Sub, fontSize = 14.sp,
            )
            // 外觀 (ADR-031): follow the system, or always dark / light.
            Text("外觀", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Appearance.entries.forEach { a ->
                    if (a == ui.appearance) {
                        androidx.compose.material3.Button(onClick = { onAppearance(a) }) { Text(a.label) }
                    } else {
                        OutlinedButton(onClick = { onAppearance(a) }) { Text(a.label, color = Ink) }
                    }
                }
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
    downloadBytes: Long?,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
    keyHint: String?,
    onOpenSettings: () -> Unit,
    billing: String? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, if (selected) Accent else LocalPalette.current.border, RoundedCornerShape(12.dp))
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
            Text(info.name, color = Ink, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        }
        Text(info.subtitle, color = Sub, fontSize = 15.sp, lineHeight = 21.sp)
        billing?.let { Text(it, color = Sub, fontSize = 14.sp, lineHeight = 20.sp) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (info.recommended) Badge("推薦", Accent)
            Badge(if (info.offline) "不用網路" else "需要網路", Sub)
            if (info.speakerTags) Badge("說話者標籤", Sub)
            if (info.paid) Badge("付費", LocalPalette.current.warn)
            if (info.packs.isNotEmpty() && !installed) Badge("下載約 ${mb(downloadBytes ?: info.packs.sumOf { it.downloadBytes })}", Sub)
        }
        if (info.needsKey) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (keyHint != null) "金鑰已設定（$keyHint）" else "需要設定金鑰",
                    color = if (keyHint != null) Accent else LocalPalette.current.warn,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = onOpenSettings) { Text(if (keyHint != null) "設定" else "設定金鑰", color = Ink) }
            }
        }
        if (info.packs.isNotEmpty()) {
            when {
                downloading -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(progress = { progress ?: 0f }, modifier = Modifier.fillMaxWidth(), color = Accent)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("下載中 ${((progress ?: 0f) * 100).toInt()}%", color = Sub, modifier = Modifier.weight(1f))
                        TextButton(onClick = onCancel) { Text("取消", color = Ink) }
                    }
                }
                installed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("已安裝", color = Accent, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = onRemove) { Text("移除（約 ${mb(freeBytes ?: 0)}）", color = Ink) }
                }
                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("未安裝", color = Sub, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = onDownload) { Text("下載", color = Ink) }
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

/** Billing standard and this month's estimate, shown under a paid engine (ADR-028). */
private fun billingLine(ui: tw.captions.app.UiState): String {
    val p = ui.usagePrices
    val month = ui.usage.azureCost(tw.captions.core.CloudUsage.Range.MONTH, ui.today, p)
    val free = if (p.azureFreeTier) "，免費方案每月前 5 小時不計費" else ""
    val limit = if (p.monthlyLimit > 0) "（上限 US\$${"%.2f".format(p.monthlyLimit)}）" else ""
    return "計費：約 US\$${"%.2f".format(p.azurePerHour)}／小時$free。本月預估 US\$${"%.2f".format(month)}$limit"
}
