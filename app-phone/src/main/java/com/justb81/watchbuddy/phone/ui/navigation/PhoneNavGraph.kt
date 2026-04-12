package com.justb81.watchbuddy.phone.ui.navigation

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.justb81.watchbuddy.phone.auth.TokenRepository
import com.justb81.watchbuddy.phone.ui.home.HomeScreen
import com.justb81.watchbuddy.phone.ui.onboarding.OnboardingScreen
import com.justb81.watchbuddy.phone.ui.settings.SettingsScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

sealed class PhoneRoute(val route: String) {
    object Onboarding : PhoneRoute("onboarding")
    object Home       : PhoneRoute("home")
    object Settings   : PhoneRoute("settings")
}

@HiltViewModel
class AuthCheckViewModel @Inject constructor(
    private val tokenRepository: TokenRepository
) : ViewModel() {
    val startDestination: PhoneRoute = when {
        tokenRepository.isTokenValid() -> PhoneRoute.Home
        tokenRepository.hasRefreshToken() -> PhoneRoute.Home
        else -> PhoneRoute.Onboarding
    }
}

@Composable
fun PhoneNavGraph(
    authCheckViewModel: AuthCheckViewModel = hiltViewModel()
) {
    val navController = rememberNavController()
    val startDestination = authCheckViewModel.startDestination

    NavHost(
        navController    = navController,
        startDestination = startDestination.route
    ) {
        composable(PhoneRoute.Onboarding.route) {
            OnboardingScreen(
                onSuccess = {
                    navController.navigate(PhoneRoute.Home.route) {
                        popUpTo(PhoneRoute.Onboarding.route) { inclusive = true }
                    }
                }
            )
        }

        composable(PhoneRoute.Home.route) {
            HomeScreen(
                onSettingsClick = { navController.navigate(PhoneRoute.Settings.route) }
            )
        }

        composable(PhoneRoute.Settings.route) {
            SettingsScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
