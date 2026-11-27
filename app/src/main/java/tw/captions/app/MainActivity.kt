package tw.captions.app

import android.Manifest
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

class MainActivity : ComponentActivity() {

    private val viewModel: CaptionViewModel by viewModels()

    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            // The notification permission is optional; only the microphone is required.
            if (result[Manifest.permission.RECORD_AUDIO] == true) viewModel.start()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

            CaptionScreen(
                ui = ui,
                engines = viewModel.engines,
                onStart = ::startWithPermission,
                onStop = viewModel::stop,
                onSelectEngine = viewModel::selectEngine,
                onClear = viewModel::clear,
                onFontSize = viewModel::changeFontSize,
            )
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
