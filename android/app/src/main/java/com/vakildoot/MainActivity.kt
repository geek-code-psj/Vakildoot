package com.vakildoot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.vakildoot.ui.VakilDootViewModel
import com.vakildoot.ui.screens.VakilDootRoot
import com.vakildoot.ui.theme.VakilColors
import com.vakildoot.ui.theme.VakilDootTheme
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: VakilDootViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Handle PDF shared/opened via intent
        handleIncomingIntent()

        setContent {
            VakilDootTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color    = VakilColors.Ink,
                ) {
                    val uiState by viewModel.uiState.collectAsState()

                    if (uiState != null) {
                        VakilDootRoot(
                            uiState            = uiState,
                            onDocumentSelected = { viewModel.onDocumentSelected(it) },
                            onUploadPdf        = { viewModel.indexDocument(it) },
                            onSendMessage      = { viewModel.sendMessage(it) },
                            onDeleteDocument   = { viewModel.deleteDocument(it) },
                            onShowUpload       = { viewModel.showUploadSheet(it) },
                            onResetIndexing    = { viewModel.resetIndexingState() },
                        )
                    }
                }
            }
        }
    }

    private fun handleIncomingIntent() {
        val uri = when (intent?.action) {
            android.content.Intent.ACTION_VIEW -> intent.data
            android.content.Intent.ACTION_SEND ->
                intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM)
            else -> null
        }
        uri?.let {
            Timber.d("Incoming PDF intent: $it")
            viewModel.indexDocument(it)
        }
    }
}

// ── Application class ─────────────────────────────────────────────────────────

@dagger.hilt.android.HiltAndroidApp
class VakilDootApp : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.ENABLE_LOGGING) {
            Timber.plant(Timber.DebugTree())
        }
    }
}
