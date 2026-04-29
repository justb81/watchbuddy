package com.justb81.watchbuddy.phone.ui.navigation

sealed class PhoneRoute(val route: String) {
    object Onboarding  : PhoneRoute("onboarding")
    object Home        : PhoneRoute("home")
    object Settings    : PhoneRoute("settings")
    object Connect     : PhoneRoute("connect")
    object Diagnostics : PhoneRoute("diagnostics")
    object ShowDetail  : PhoneRoute("show_detail/{traktShowId}") {
        fun route(traktShowId: Int) = "show_detail/$traktShowId"
    }
    object LlmEventDetail : PhoneRoute("llm_event/{eventId}") {
        fun route(eventId: Long) = "llm_event/$eventId"
    }
}
