package com.justb81.watchbuddy.phone.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.justb81.watchbuddy.phone.ui.home.HomeScreen
import com.justb81.watchbuddy.phone.ui.onboarding.OnboardingScreen
import com.justb81.watchbuddy.phone.ui.settings.SettingsScreen
import com.justb81.watchbuddy.phone.ui.showdetail.ShowDetailScreen

sealed class PhoneRoute(val route: String) {
    object Onboarding  : PhoneRoute("onboarding")
    object Home        : PhoneRoute("home")
    object Settings    : PhoneRoute("settings")
    object Connect     : PhoneRoute("connect")
    object ShowDetail  : PhoneRoute("show_detail/{traktShowId}") {
        fun route(traktShowId: Int) = "show_detail/$traktShowId"
    }
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
                },
                onOpenSettings = { navController.navigate(PhoneRoute.Settings.route) }
            )
        }

        composable(PhoneRoute.Home.route) {
            HomeScreen(
                onSettingsClick = { navController.navigate(PhoneRoute.Settings.route) },
                onConnectClick  = { navController.navigate(PhoneRoute.Connect.route) },
                onShowClick     = { traktShowId ->
                    navController.navigate(PhoneRoute.ShowDetail.route(traktShowId))
                }
            )
        }

        composable(PhoneRoute.Settings.route) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onDisconnected = {
                    navController.navigate(PhoneRoute.Onboarding.route) {
                        popUpTo(navController.graph.id) { inclusive = true }
                    }
                },
                onConnectClick = { navController.navigate(PhoneRoute.Connect.route) }
            )
        }

        composable(PhoneRoute.Connect.route) {
            OnboardingScreen(
                onSuccess = { navController.popBackStack() },
                onSkip = { navController.popBackStack() },
                onOpenSettings = { navController.navigate(PhoneRoute.Settings.route) },
                isReconnect = true
            )
        }

        composable(
            route = PhoneRoute.ShowDetail.route,
            arguments = listOf(navArgument("traktShowId") { type = NavType.IntType })
        ) {
            ShowDetailScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
