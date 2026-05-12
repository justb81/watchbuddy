package com.justb81.watchbuddy.phone.server.routes

import com.justb81.watchbuddy.phone.server.DeviceCapabilityProvider
import com.justb81.watchbuddy.service.CompanionStateManager
import io.ktor.server.response.*
import io.ktor.server.routing.*

data class CapabilityRouteDeps(
    val capabilityProvider: DeviceCapabilityProvider,
    val stateManager: CompanionStateManager,
)

fun Route.capabilityRoutes(deps: CapabilityRouteDeps) {
    get("/capability") {
        deps.stateManager.onCapabilityChecked()
        call.respond(deps.capabilityProvider.getCapability())
    }
}
