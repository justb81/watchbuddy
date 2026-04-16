package com.justb81.watchbuddy.phone.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.justb81.watchbuddy.phone.auth.TokenRepository
import com.justb81.watchbuddy.phone.ui.navigation.PhoneNavGraph
import com.justb81.watchbuddy.phone.ui.navigation.PhoneRoute
import com.justb81.watchbuddy.phone.ui.theme.WatchBuddyTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var tokenRepository: TokenRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val startDestination = try {
            if (tokenRepository.isTokenValid()) {
                PhoneRoute.Home.route
            } else {
                PhoneRoute.Onboarding.route
            }
        } catch (_: Exception) {
            // Keystore unavailable — default to onboarding
            PhoneRoute.Onboarding.route
        }

        setContent {
            WatchBuddyTheme {
                PhoneNavGraph(startDestination = startDestination)
            }
        }
    }
}
