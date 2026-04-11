package com.justb81.watchbuddy.phone.server

import com.justb81.watchbuddy.core.model.DeviceCapability
import com.justb81.watchbuddy.phone.llm.LlmOrchestrator
import com.justb81.watchbuddy.phone.llm.RecapGenerator
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local HTTP server running on the phone (port 8765).
 * The TV app discovers this via NSD (mDNS) and calls its endpoints.
 *
 * Endpoints:
 *   GET  /capability           → DeviceCapability (device score, RAM, LLM backend)
 *   GET  /shows                → List of watched shows for this user (from Trakt cache)
 *   POST /recap/{traktShowId}  → Generate + return HTML recap for a show
 */
@Singleton
class CompanionHttpServer @Inject constructor(
    private val llmOrchestrator: LlmOrchestrator,
    private val recapGenerator: RecapGenerator,
    private val capabilityProvider: DeviceCapabilityProvider
) {
    companion object {
        const val PORT = 8765
    }

    private var server: ApplicationEngine? = null

    fun start() {
        server = embeddedServer(Netty, port = PORT) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            routing {
                get("/capability") {
                    call.respond(capabilityProvider.getCapability())
                }

                get("/shows") {
                    // TODO: return cached Trakt watched shows for this user
                    call.respond(emptyList<String>())
                }

                post("/recap/{traktShowId}") {
                    val showId = call.parameters["traktShowId"]?.toIntOrNull()
                        ?: return@post call.respond(io.ktor.http.HttpStatusCode.BadRequest)
                    // TODO: fetch show + watched episodes, call recapGenerator.generateRecap()
                    call.respond(mapOf("html" to "<div>Recap für Show $showId wird generiert…</div>"))
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 1_000, timeoutMillis = 5_000)
        server = null
    }
}
