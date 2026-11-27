package tw.captions.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tw.captions.app.UiState

/** 認得的人: people whose voices the app recognises. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleSheet(ui: UiState, onDismiss: () -> Unit, onForget: (Int) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFF1C1C1C)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("認得的人", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                "在字幕上長按一句話，告訴 App 是誰說的，它就會記住這個聲音，之後自動顯示名字。" +
                    "有認得的人之後，不認得的聲音（電視、隔壁桌）會依「背景過濾」淡化或隱藏。聲音特徵只存在這支手機上。",
                color = Color(0xFFB0B0B0), fontSize = 15.sp,
            )
            if (ui.people.isEmpty()) Text("還沒有認得的人。", color = Color(0xFFB0B0B0), fontSize = 16.sp)
            ui.people.forEach { (id, name) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, color = Color.White, fontSize = 19.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onForget(id) }) { Text("忘記", color = Color(0xFFFF8A80)) }
                }
            }
            Text(" ", fontSize = 8.sp)
        }
    }
}
