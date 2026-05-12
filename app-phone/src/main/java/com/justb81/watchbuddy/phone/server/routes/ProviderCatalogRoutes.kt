package com.justb81.watchbuddy.phone.server.routes

import com.justb81.watchbuddy.phone.data.ProviderCatalogRepository
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

private const val ETAG_HEX_CHARS = 16

data class ProviderCatalogRouteDeps(
    val providerCatalogRepository: ProviderCatalogRepository,
)

fun Route.providerCatalogRoutes(deps: ProviderCatalogRouteDeps) {
    get("/provider-catalog") {
        val json = deps.providerCatalogRepository.currentJson()
        if (json == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("catalog not yet fetched from backend"))
            return@get
        }
        val etag = "\"${sha256Hex(json.toByteArray()).take(ETAG_HEX_CHARS)}\""
        val ifNoneMatch = call.request.headers[HttpHeaders.IfNoneMatch]
        if (ifNoneMatch == etag) {
            call.respond(HttpStatusCode.NotModified)
            return@get
        }
        call.response.header(HttpHeaders.ETag, etag)
        call.response.header(HttpHeaders.CacheControl, "public, max-age=86400")
        call.respondText(json, ContentType.Application.Json)
    }
}
