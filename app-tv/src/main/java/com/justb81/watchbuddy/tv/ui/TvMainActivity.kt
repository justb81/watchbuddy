package com.justb81.watchbuddy.tv.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.justb81.watchbuddy.tv.ui.navigation.TvNavGraph
import com.justb81.watchbuddy.tv.ui.theme.WatchBuddyTvTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class TvMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WatchBuddyTvTheme {
                TvNavGraph()
            }
        }
    }
}
