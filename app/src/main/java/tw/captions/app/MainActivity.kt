package tw.captions.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tw.captions.app.ui.CaptionScreen
import tw.captions.app.ui.EnginePicker
import tw.captions.app.ui.CloudSettings
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.io.File

class MainActivity : ComponentActivity() {

    private val viewModel: CaptionViewModel by viewModels()

    /** The microphone permission was refused for good ("don't ask again"): only the system settings can turn it on. */
    private var micBlocked by mutableStateOf(false)

    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            // The notification permission is optional; only the microphone is required.
            if (result[Manifest.permission.RECORD_AUDIO] == true) {
                viewModel.start()
            } else if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                // Android shows no dialog any more; asking again would do nothing.
                micBlocked = true
            }
        }

    private fun openAppSettings() {
        startActivity(
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleDebugIntent(intent)
        setContent {
            val ui by viewModel.ui.collectAsStateWithLifecycle()

            // Keep the screen on while captions are running.
            LaunchedEffect(ui.running) {
                if (ui.running) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    ListeningService.start(this@MainActivity)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    ListeningService.stop(this@MainActivity)
                }
            }

            // Status / navigation bar icons follow the app's own dark or light setting, not only the system's.
            val dark = when (ui.appearance) {
                tw.captions.app.ui.Appearance.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
                tw.captions.app.ui.Appearance.DARK -> true
                tw.captions.app.ui.Appearance.LIGHT -> false
            }
            androidx.compose.runtime.DisposableEffect(dark) {
                val bars = if (dark) {
                    androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                } else {
                    androidx.activity.SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                onDispose {}
            }

            tw.captions.app.ui.AppTheme(ui.appearance) {
            var showEngines by remember { mutableStateOf(false) }
            var showSettings by remember { mutableStateOf(false) }
            var showPeople by remember { mutableStateOf(false) }
            var showTranscripts by remember { mutableStateOf(false) }
            val level = viewModel.audioLevel.collectAsStateWithLifecycle()
            CaptionScreen(
                ui = ui,
                audioLevel = { level.value },
                onResumeAfterIdle = viewModel::resumeAfterIdle,
                engines = viewModel.engines,
                onStart = ::startWithPermission,
                onStop = viewModel::stopByUser,
                onOpenEngines = { viewModel.refreshMics(); showEngines = true },
                onOpenSettings = { showSettings = true },
                onToggleBackground = viewModel::toggleBackground,
                onToggleTv = viewModel::toggleTvMode,
                onOpenTranscripts = { showTranscripts = true },
                onNameLine = viewModel::nameLine,
                onOpenPeople = { showPeople = true },
                onClear = viewModel::clear,
                onFontSize = viewModel::changeFontSize,
            )
            if (showEngines) {
                EnginePicker(
                    ui = ui,
                    onDismiss = { showEngines = false },
                    onSelect = viewModel::selectEngine,
                    onDownload = { viewModel.download(it) },
                    onCancelDownload = viewModel::cancelDownload,
                    onUninstall = viewModel::uninstall,
                    onOpenSettings = { showEngines = false; showSettings = true },
                    onAppearance = viewModel::setAppearance,
                    onSpeakersOn = viewModel::setSpeakersOn,
                    onSpeakerCount = viewModel::setSpeakerCount,
                    onVocabulary = viewModel::setVocabulary,
                    onMic = viewModel::setMic,
                    onSystemNoiseSuppression = viewModel::setSystemNoiseSuppression,
                )
            }
            if (showPeople) {
                tw.captions.app.ui.PeopleSheet(ui = ui, onDismiss = { showPeople = false }, onForget = viewModel::forgetPerson, onRename = viewModel::renamePerson, onMerge = viewModel::mergePeople)
            }
            if (showTranscripts) {
                tw.captions.app.ui.TranscriptsScreen(
                    ui = ui,
                    onDismiss = { showTranscripts = false },
                    onRename = viewModel::renameTranscript,
                    onDelete = viewModel::deleteTranscript,
                    onDeleteAll = viewModel::deleteAllTranscripts,
                    onSetEnabled = viewModel::setTranscriptsEnabled,
                    exportText = viewModel::transcriptText,
                )
            }
            if (micBlocked) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { micBlocked = false },
                    title = { androidx.compose.material3.Text("需要麥克風權限") },
                    text = {
                        androidx.compose.material3.Text("麥克風權限已被關閉，字幕需要聽到聲音才能運作。請到「設定 > 權限 > 麥克風」選擇「允許」。")
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = { micBlocked = false; openAppSettings() }) { androidx.compose.material3.Text("開啟設定") }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { micBlocked = false }) { androidx.compose.material3.Text("取消") }
                    },
                )
            }
            if (ui.usageLimitHit && ui.limitNeedsOfflineModels) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = viewModel::dismissLimitNotice,
                    title = { androidx.compose.material3.Text("已達雲端費用上限，字幕已停止") },
                    text = {
                        androidx.compose.material3.Text(
                            "本月 Azure 預估費用已達你設定的上限（US\$${"%.2f".format(ui.usagePrices.monthlyLimit)}）。手機上還沒有離線模型，" +
                                "要改用離線辨識需要先下載約 ${(ui.downloadBytes[tw.captions.app.engine.EngineCatalog.DEFAULT_ID] ?: 0L) / 1_000_000} MB（建議用 Wi-Fi）。",
                        )
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = viewModel::downloadOfflineAfterLimit) { androidx.compose.material3.Text("下載離線模型") }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = viewModel::continueCloudAnyway) { androidx.compose.material3.Text("繼續用雲端") }
                    },
                )
            } else if (ui.usageLimitHit) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = viewModel::dismissLimitNotice,
                    title = { androidx.compose.material3.Text("已達雲端費用上限") },
                    text = {
                        androidx.compose.material3.Text(
                            "本月 Azure 預估費用已達你設定的上限（US\$${"%.2f".format(ui.usagePrices.monthlyLimit)}），這次先改用手機離線辨識。想繼續用雲端也可以。",
                        )
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = viewModel::dismissLimitNotice) { androidx.compose.material3.Text("用離線就好") }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = viewModel::continueCloudAnyway) { androidx.compose.material3.Text("繼續用雲端") }
                    },
                )
            }
            if (showSettings) {
                CloudSettings(
                    ui = ui,
                    onDismiss = { showSettings = false },
                    onSaveAzure = viewModel::saveAzure,
                    onTestAzure = viewModel::testAzure,
                    onRemoveAzure = viewModel::removeAzure,
                    onSetPrices = viewModel::setUsagePrices,
                    onResetUsage = viewModel::resetUsage,
                    onProofreadEnabled = viewModel::setProofreadEnabled,
                    onProofreadModel = viewModel::setProofreadModel,
                    onSaveAnthropic = viewModel::saveAnthropicKey,
                    onTestAnthropic = viewModel::testAnthropicKey,
                    onRemoveAnthropic = viewModel::removeAnthropicKey,
                )
            }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDebugIntent(intent)
    }

    /**
     * Debug builds only, for testing on an emulator without a person speaking:
     *   adb push dialog.wav /sdcard/Android/data/tw.captions.app/files/
     *   adb shell am start -n tw.captions.app/.MainActivity --es engine sherpa-onnx      *       --es debug_wav dialog.wav --ez autostart true
     * debug_wav is a 16 kHz mono 16-bit file in the app's external files folder.
     */
    private fun handleDebugIntent(intent: Intent?) {
        if (!BuildConfig.DEBUG || intent == null) return
        intent.getStringExtra("azure_env")?.let { viewModel.importAzureEnv(File(getExternalFilesDir(null), it)) }
        intent.getStringExtra("engine")?.let { viewModel.selectEngine(it) }
        intent.getStringExtra("debug_wav")?.let { name ->
            viewModel.stop()
            viewModel.useDebugWav(if (name.isEmpty()) null else File(getExternalFilesDir(null), name))
        }
        if (intent.getBooleanExtra("autostart", false)) {
            viewModel.clear()
            startWithPermission()
        }
    }

    private fun startWithPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.start()
        } else {
            val wanted = buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            permissions.launch(wanted.toTypedArray())
        }
    }
}
