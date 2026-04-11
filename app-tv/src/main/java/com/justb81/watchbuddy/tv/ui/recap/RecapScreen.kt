package com.justb81.watchbuddy.tv.ui.recap

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.CircularProgressIndicator
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.*
import com.justb81.watchbuddy.R

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RecapScreen(
    traktShowId: Int,
    showTitle: String,
    fallbackSynopsis: String,
    onClose: () -> Unit,
    onWatchNow: () -> Unit,
    viewModel: RecapViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(traktShowId) {
        viewModel.requestRecap(traktShowId, fallbackSynopsis)
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.reset() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xEE0A0A0A)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier            = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 48.dp, vertical = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text     = stringResource(R.string.tv_recap_title),
                        fontSize = 14.sp,
                        color    = Color(0xFFE53935)
                    )
                    Text(
                        text       = showTitle,
                        fontSize   = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color      = Color.White
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onClose) {
                        Text(stringResource(R.string.tv_recap_close))
                    }
                    Button(
                        onClick = onWatchNow,
                        colors  = ButtonDefaults.colors(containerColor = Color(0xFFE53935))
                    ) {
                        Text(stringResource(R.string.tv_recap_watch_now))
                    }
                }
            }

            // Content area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 48.dp, vertical = 0.dp),
                contentAlignment = Alignment.Center
            ) {
                when (val s = state) {
                    is RecapUiState.Idle -> {
                        CircularProgressIndicator(color = Color(0xFFE53935))
                    }

                    is RecapUiState.Generating -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Color(0xFFE53935))
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text     = stringResource(R.string.tv_recap_generating_on, s.deviceName),
                                fontSize = 16.sp,
                                color    = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }

                    is RecapUiState.Ready -> {
                        RecapWebView(html = s.html)
                    }

                    is RecapUiState.Fallback -> {
                        Column(
                            modifier            = Modifier
                                .fillMaxWidth(0.7f)
                                .background(Color(0xFF1C1C1E), RoundedCornerShape(16.dp))
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text     = stringResource(R.string.tv_recap_fallback),
                                fontSize = 12.sp,
                                color    = Color.White.copy(alpha = 0.4f)
                            )
                            Text(
                                text      = s.synopsis,
                                fontSize  = 18.sp,
                                color     = Color.White,
                                textAlign = TextAlign.Center,
                                lineHeight = 28.sp
                            )
                        }
                    }

                    is RecapUiState.Error -> {
                        Text(
                            text     = stringResource(R.string.tv_recap_error),
                            fontSize = 16.sp,
                            color    = Color(0xFFE53935)
                        )
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun RecapWebView(html: String) {
    // Wrap HTML in a full page with dark background and slide animation
    val fullHtml = """
        <!DOCTYPE html>
        <html>
        <head>
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <style>
          * { margin: 0; padding: 0; box-sizing: border-box; }
          body {
            background: #0A0A0A;
            color: #EEEEEE;
            font-family: -apple-system, 'Helvetica Neue', sans-serif;
            display: flex;
            align-items: center;
            justify-content: center;
            min-height: 100vh;
            padding: 32px;
          }
          .slides { width: 100%; max-width: 1400px; }
          .slide {
            opacity: 0;
            animation: fadeIn 0.8s forwards;
            margin-bottom: 48px;
            display: flex;
            gap: 32px;
            align-items: center;
          }
          .slide:nth-child(1) { animation-delay: 0.2s; }
          .slide:nth-child(2) { animation-delay: 1.4s; }
          .slide:nth-child(3) { animation-delay: 2.6s; }
          .slide:nth-child(4) { animation-delay: 3.8s; }
          .slide:nth-child(5) { animation-delay: 5.0s; }
          .slide:nth-child(6) { animation-delay: 6.2s; }
          .slide img {
            width: 320px;
            height: 180px;
            object-fit: cover;
            border-radius: 12px;
            flex-shrink: 0;
            background: #2A2A2C;
          }
          .slide p {
            font-size: 20px;
            line-height: 1.6;
            color: #EEEEEE;
          }
          @keyframes fadeIn {
            from { opacity: 0; transform: translateY(16px); }
            to   { opacity: 1; transform: translateY(0); }
          }
        </style>
        </head>
        <body>
          <div class="slides">
            $html
          </div>
        </body>
        </html>
    """.trimIndent()

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                webViewClient = WebViewClient()
                settings.javaScriptEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                loadDataWithBaseURL(null, fullHtml, "text/html", "UTF-8", null)
            }
        },
        update = { webView ->
            webView.loadDataWithBaseURL(null, fullHtml, "text/html", "UTF-8", null)
        },
        modifier = Modifier.fillMaxSize()
    )
}
