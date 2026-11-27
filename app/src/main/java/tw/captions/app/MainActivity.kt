package tw.captions.app

import android.Manifest
import android.content.pm.PackageManager
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

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) viewModel.start()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val ui by viewModel.ui.collectAsStateWithLifecycle()

            // Keep the screen on while captions are running.
            LaunchedEffect(ui.running) {
                if (ui.running) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}
