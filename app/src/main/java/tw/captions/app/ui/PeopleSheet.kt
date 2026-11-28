package tw.captions.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tw.captions.app.UiState

/** 認得的人: people whose voices the app recognises; rename, merge two entries, or forget. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleSheet(
    ui: UiState,
    onDismiss: () -> Unit,
    onForget: (Int) -> Unit,
    onRename: (Int, String) -> Unit = { _, _ -> },
    onMerge: (Int, Int) -> Unit = { _, _ -> },
) {
    var renaming by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var merging by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var forgetting by remember { mutableStateOf<Pair<Int, String>?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = LocalPalette.current.surface) {
        SheetStatusBar()
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("認得的人", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                "在字幕上長按一句話，告訴 App 是誰說的，它就會記住這個聲音，之後自動顯示名字。" +
                    "有認得的人之後，不認得的聲音（電視、隔壁桌）會依「背景過濾」淡化或隱藏。聲音特徵只存在這支手機上。",
                color = LocalPalette.current.sub, fontSize = 15.sp,
            )
            if (ui.people.isEmpty()) Text("還沒有認得的人。", color = LocalPalette.current.sub, fontSize = 16.sp)
            ui.people.forEach { person ->
                val (_, name) = person
                Column {
                    Text(name, color = Ink, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { renaming = person }) { Text("改名", color = Ink) }
                        if (ui.people.size > 1) TextButton(onClick = { merging = person }) { Text("合併到…", color = Ink) }
                        TextButton(onClick = { forgetting = person }) { Text("忘記", color = LocalPalette.current.error) }
                    }
                }
            }
            Text(" ", fontSize = 8.sp)
        }
    }
    renaming?.let { (id, old) ->
        var name by remember(id) { mutableStateOf(old) }
        val clash = ui.people.any { it.first != id && it.second == name.trim() }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("改名") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("名字") })
                    if (clash) Text("已經有「${name.trim()}」，儲存後會合併成同一個人。", fontSize = 14.sp)
                }
            },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onRename(id, name); renaming = null }) { Text(if (clash) "合併" else "儲存") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("取消") } },
        )
    }
    merging?.let { (id, name) ->
        AlertDialog(
            onDismissRequest = { merging = null },
            title = { Text("「$name」其實是誰？") },
            text = {
                Column {
                    Text("兩個名字是同一個人時，合併後只留下你選的名字，聲音會一起記住。", fontSize = 14.sp)
                    ui.people.filter { it.first != id }.forEach { (otherId, otherName) ->
                        TextButton(onClick = { onMerge(id, otherId); merging = null }) { Text(otherName, fontSize = 18.sp) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { merging = null }) { Text("取消") } },
        )
    }
    forgetting?.let { (id, name) ->
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text("忘記「$name」？") },
            text = { Text("之後不會再自動顯示這個名字。要再認得，需要重新長按字幕指定。") },
            confirmButton = { TextButton(onClick = { onForget(id); forgetting = null }) { Text("忘記", color = LocalPalette.current.error) } },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text("取消") } },
        )
    }
}
