@file:Suppress("MatchingDeclarationName")

package com.justb81.watchbuddy.phone.server.routes

import com.justb81.watchbuddy.phone.settings.AvatarImageStore
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

data class AvatarRouteDeps(
    val avatarImageStore: AvatarImageStore,
)

fun Route.avatarRoutes(deps: AvatarRouteDeps) {
    get("/avatar") {
        val file = deps.avatarImageStore.file()
        if (!deps.avatarImageStore.exists()) {
            return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("No custom avatar"))
        }
        val etag = "\"${sha256Hex(file.readBytes())}\""
        if (call.request.headers[HttpHeaders.IfNoneMatch] == etag) {
            return@get call.respond(HttpStatusCode.NotModified)
        }
        call.response.header(HttpHeaders.CacheControl, "private, max-age=60")
        call.response.header(HttpHeaders.ETag, etag)
        call.respondFile(file)
    }
}
