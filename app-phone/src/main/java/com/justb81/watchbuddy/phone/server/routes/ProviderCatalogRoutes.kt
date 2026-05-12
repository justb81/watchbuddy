package com.justb81.watchbuddy.phone.server.routes

import com.justb81.watchbuddy.phone.data.ProviderCatalogRepository
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

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
