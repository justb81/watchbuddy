package com.justb81.watchbuddy.phone.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.justb81.watchbuddy.phone.ui.navigation.PhoneNavGraph
import com.justb81.watchbuddy.phone.ui.theme.WatchBuddyTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WatchBuddyTheme {
                PhoneNavGraph()
            }
        }
    }
}
