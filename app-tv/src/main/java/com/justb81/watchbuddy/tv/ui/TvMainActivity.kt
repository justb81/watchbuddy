package com.justb81.watchbuddy.tv.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.justb81.watchbuddy.R
import com.justb81.watchbuddy.core.logging.CrashReporter
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import com.justb81.watchbuddy.core.logging.DiagnosticShare
import com.justb81.watchbuddy.tv.ui.navigation.TvNavGraph
import com.justb81.watchbuddy.tv.ui.theme.WatchBuddyTvTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class TvMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        DiagnosticLog.event(TAG, "onCreate entered")
        super.onCreate(savedInstanceState)

        setContent {
            WatchBuddyTvTheme {
                // Catch exceptions thrown during initial composition of the real
                // nav graph and swap in a fallback that lets the user share the
                // crash report. Without this, a throw inside TvNavGraph() tears
                // down the window before anything is drawn — and the only symptom
                // the user sees is "tap icon, nothing happens" (issue #238).
                var initError by remember { mutableStateOf<Throwable?>(null) }
                val err = initError
                if (err == null) {
                    runCatching { TvNavGraph() }.onFailure { t ->
                        DiagnosticLog.error(TAG, "TvNavGraph composition failed", t)
                        runCatching {
                            CrashReporter.writeManualSnapshot(
                                this@TvMainActivity,
                                reason = "TvNavGraph composition failed: ${t.javaClass.simpleName}: ${t.message}"
                            )
                        }
                        initError = t
                    }
                } else {
                    StartupFailedScreen(error = err)
                }
            }
        }
        DiagnosticLog.event(TAG, "onCreate completed")
    }

    private companion object {
        const val TAG = "TvMainActivity"
    }
}

@Composable
private fun StartupFailedScreen(error: Throwable) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(48.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = stringResource(R.string.tv_startup_failed_title),
            color = Color.White,
            style = MaterialTheme.typography.headlineMedium
        )
        Text(
            text = "${error.javaClass.name}: ${error.message ?: ""}",
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = error.stackTraceToString(),
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodySmall
        )
        Button(onClick = { DiagnosticShare.launchShare(context) }) {
            Text(stringResource(R.string.tv_startup_failed_share))
        }
    }
}
