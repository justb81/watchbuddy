package com.justb81.watchbuddy.phone.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.justb81.watchbuddy.phone.ui.home.HomeScreen
import com.justb81.watchbuddy.phone.ui.onboarding.OnboardingScreen
import com.justb81.watchbuddy.phone.ui.settings.SettingsScreen

sealed class PhoneRoute(val route: String) {
    object Onboarding : PhoneRoute("onboarding")
    object Home       : PhoneRoute("home")
    object Settings   : PhoneRoute("settings")
}

@Composable
fun PhoneNavGraph(
    startDestination: String = PhoneRoute.Onboarding.route
) {
    val navController = rememberNavController()

    NavHost(
        navController    = navController,
        startDestination = startDestination
    ) {
        composable(PhoneRoute.Onboarding.route) {
            OnboardingScreen(
                onSuccess = {
                    navController.navigate(PhoneRoute.Home.route) {
                        popUpTo(PhoneRoute.Onboarding.route) { inclusive = true }
                    }
                },
                onSkip = {
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
                onBack = { navController.popBackStack() },
                onDisconnected = {
                    navController.navigate(PhoneRoute.Onboarding.route) {
                        popUpTo(navController.graph.id) { inclusive = true }
                    }
                }
            )
        }
    }
}
