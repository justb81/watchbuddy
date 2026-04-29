package com.justb81.watchbuddy.tv.ui.navigation

sealed class TvRoute(val route: String) {
    object Home : TvRoute("tv_home")
    object ShowDetail : TvRoute("tv_show_detail")
    object Recap : TvRoute("tv_recap")
    object Settings : TvRoute("tv_settings")
    object Diagnostics : TvRoute("tv_diagnostics")
}
