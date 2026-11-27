package tw.captions.app.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import tw.captions.app.UiState
import tw.captions.app.engine.AzureSpeechEngine

private val Sub = Color(0xFFB0B0B0)
private val Accent = Color(0xFF66BB6A)

/** Where users enter their own cloud API keys. Keys are stored encrypted on the phone only. */
@Composable
fun CloudSettings(
    ui: UiState,
    onDismiss: () -> Unit,
    onSaveAzure: (key: String, region: String) -> Unit,
    onTestAzure: () -> Unit,
    onRemoveAzure: () -> Unit,
    onSaveServer: (url: String, token: String) -> Unit,
    onTestServer: () -> Unit,
    onRemoveServer: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF121212))
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("雲端服務設定", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("完成", color = Color.White, fontSize = 18.sp) }
            }
            Text(
                "使用你自己的雲端帳號。金鑰經過加密，只存在這支手機上，只會傳給它所屬的服務，不會上傳到其他地方。",
                color = Sub, fontSize = 15.sp,
            )
            AzureCard(ui, onSaveAzure, onTestAzure, onRemoveAzure)
            ServerCard(ui, onSaveServer, onTestServer, onRemoveServer)
            Text(
                "Google 雲端辨識在台灣腔測試中慢（約 3.7 秒才出字）或不準，暫時不提供。",
                color = Sub, fontSize = 14.sp,
            )
        }
    }
}

@Composable
private fun AzureCard(
    ui: UiState,
    onSave: (String, String) -> Unit,
    onTest: () -> Unit,
    onRemove: () -> Unit,
) {
    var key by remember { mutableStateOf("") }
    var region by remember(ui.azureRegion) { mutableStateOf(ui.azureRegion) }
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF3A3A3A), RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Azure 語音服務", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("台灣腔最準（錯字率約 5%），會標示說話者。按使用時間計費，約 US\$1.3／小時；免費方案每月 5 小時。", color = Sub, fontSize = 15.sp)
        Text(
            if (ui.azureKeyHint != null) "目前金鑰：${ui.azureKeyHint}（${regionName(ui.azureRegion)}）" else "尚未設定金鑰",
            color = if (ui.azureKeyHint != null) Accent else Color(0xFFFFB74D), fontSize = 16.sp,
        )
        OutlinedTextField(
            value = key,
            onValueChange = { key = it.trim() },
            label = { Text(if (ui.azureKeyHint != null) "換一組金鑰（KEY 1）" else "金鑰（KEY 1）") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Text("區域（要和建立資源時選的一樣）", color = Sub, fontSize = 14.sp)
        AzureSpeechEngine.REGIONS.forEach { (id, name) ->
            Row(
                Modifier.fillMaxWidth().clickable { region = id },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = region == id, onClick = { region = id })
                Text(name, color = Color.White, fontSize = 16.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(key, region); key = "" }, enabled = key.isNotEmpty() || (ui.azureKeyHint != null && region != ui.azureRegion)) {
                Text("儲存")
            }
            OutlinedButton(onClick = onTest, enabled = ui.azureKeyHint != null && !ui.cloudTesting) {
                Text(if (ui.cloudTesting) "測試中…" else "測試連線", color = Color.White)
            }
            if (ui.azureKeyHint != null) {
                TextButton(onClick = onRemove) { Text("刪除金鑰", color = Color(0xFFFF8A80)) }
            }
        }
        ui.cloudTestMessage?.let { Text(it, color = if (it.startsWith("可以使用")) Accent else Color(0xFFFF8A80), fontSize = 15.sp) }
        Text("怎麼取得金鑰", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        SelectionContainer {
            Text(
                "1. 到 portal.azure.com 註冊（有免費試用）。\n" +
                    "2. 建立「Speech 服務」資源：區域選 East Asia，定價層選 Free F0。\n" +
                    "3. 打開資源 →「金鑰與端點」，複製 KEY 1 貼到上面。",
                color = Sub, fontSize = 15.sp, lineHeight = 22.sp,
            )
        }
    }
}

private fun regionName(id: String) = AzureSpeechEngine.REGIONS.firstOrNull { it.first == id }?.second ?: id

@Composable
private fun ServerCard(
    ui: UiState,
    onSave: (String, String) -> Unit,
    onTest: () -> Unit,
    onRemove: () -> Unit,
) {
    var url by remember(ui.serverUrl) { mutableStateOf(ui.serverUrl) }
    var token by remember { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF3A3A3A), RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("自架伺服器（你的電腦）", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("在有顯示卡的電腦上執行專案裡的 server/server.py，手機連過去辨識。課程、演講最準，不用付雲端費用。", color = Sub, fontSize = 15.sp)
        OutlinedTextField(
            value = url,
            onValueChange = { url = it.trim() },
            label = { Text("伺服器網址，例如 ws://192.168.1.20:8765") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it.trim() },
            label = { Text(if (ui.serverTokenHint != null) "換一組權杖（目前 ${ui.serverTokenHint}）" else "權杖（伺服器的 CAPTIONS_TOKEN）") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(url, token); token = "" }, enabled = url.startsWith("ws") && (token.isNotEmpty() || ui.serverTokenHint != null)) { Text("儲存") }
            OutlinedButton(onClick = onTest, enabled = ui.serverTokenHint != null && ui.serverUrl.isNotBlank()) { Text("測試連線", color = Color.White) }
            if (ui.serverTokenHint != null) TextButton(onClick = onRemove) { Text("刪除", color = Color(0xFFFF8A80)) }
        }
        ui.serverTestMessage?.let { Text(it, color = if (it.startsWith("可以使用")) Accent else Color(0xFFFF8A80), fontSize = 15.sp) }
    }
}
