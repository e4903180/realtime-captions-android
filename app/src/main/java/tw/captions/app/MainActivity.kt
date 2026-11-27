package tw.captions.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tw.captions.app.ui.CaptionScreen
import tw.captions.app.ui.EnginePicker
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.io.File

class MainActivity : ComponentActivity() {

    private val viewModel: CaptionViewModel by viewModels()

    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            // The notification permission is optional; only the microphone is required.
            if (result[Manifest.permission.RECORD_AUDIO] == true) viewModel.start()
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

            var showEngines by remember { mutableStateOf(false) }
            CaptionScreen(
                ui = ui,
                engines = viewModel.engines,
                onStart = ::startWithPermission,
                onStop = viewModel::stop,
                onOpenEngines = { showEngines = true },
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
                )
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
