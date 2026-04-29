package com.justb81.watchbuddy.core.justwatch

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Retrofit interface for JustWatch's unofficial GraphQL API.
 *
 * Base URL: https://apis.justwatch.com/
 * All requests POST to the single /graphql endpoint.
 * TV-direct — the phone is never involved.
 *
 * Returns the raw [Response] so callers can read the error body on non-2xx
 * responses (JustWatch's GraphQL endpoint frequently returns HTTP 422 with a
 * descriptive payload when a request looks unidentified or malformed).
 */
interface JustWatchApiService {

    @POST("graphql")
    suspend fun query(@Body request: JustWatchGraphQlRequest): Response<JustWatchGraphQlResponse>

    companion object {

        val SEARCH_QUERY = """
            query SearchTitles(
              ${'$'}searchQuery: String!
              ${'$'}country: Country!
              ${'$'}language: Language!
              ${'$'}first: Int!
            ) {
              popularTitles(
                country: ${'$'}country
                first: ${'$'}first
                filter: { searchQuery: ${'$'}searchQuery, objectTypes: [SHOW] }
              ) {
                edges {
                  node {
                    id
                    offers(country: ${'$'}country, platform: WEB) {
                      standardWebURL
                      monetizationType
                      package { technicalName }
                    }
                    content(country: ${'$'}country, language: ${'$'}language) {
                      externalIds { tmdbId }
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val SEASONS_QUERY = """
            query GetShowSeasons(${'$'}nodeId: ID!, ${'$'}country: Country!, ${'$'}language: Language!) {
              node(id: ${'$'}nodeId) {
                ... on Show {
                  seasons { id }
                }
              }
            }
        """.trimIndent()

        val EPISODES_QUERY = """
            query GetSeasonEpisodes(${'$'}nodeId: ID!, ${'$'}country: Country!, ${'$'}language: Language!) {
              node(id: ${'$'}nodeId) {
                ... on Season {
                  episodes {
                    episodeNumber
                    seasonNumber
                    offers(country: ${'$'}country, platform: WEB) {
                      standardWebURL
                      monetizationType
                      package { technicalName }
                    }
                  }
                }
              }
            }
        """.trimIndent()
    }
}

// ── Request ───────────────────────────────────────────────────────────────────

@Serializable
data class JustWatchGraphQlRequest(
    val query: String,
    val variables: JsonObject,
)

// ── Response ──────────────────────────────────────────────────────────────────

@Serializable
data class JustWatchGraphQlResponse(
    val data: JustWatchData? = null,
    val errors: JsonArray? = null,
)

@Serializable
data class JustWatchData(
    val popularTitles: JustWatchTitleConnection? = null,
    val node: JustWatchNode? = null,
)

@Serializable
data class JustWatchTitleConnection(
    val edges: List<JustWatchTitleEdge> = emptyList(),
)

@Serializable
data class JustWatchTitleEdge(
    val node: JustWatchTitle,
)

@Serializable
data class JustWatchTitle(
    val id: String,
    val offers: List<JustWatchOffer> = emptyList(),
    val content: JustWatchContent? = null,
    val seasons: List<JustWatchSeasonRef>? = null,
)

@Serializable
data class JustWatchNode(
    val seasons: List<JustWatchSeasonRef>? = null,
    val episodes: List<JustWatchEpisode>? = null,
)

@Serializable
data class JustWatchSeasonRef(
    val id: String,
)

@Serializable
data class JustWatchEpisode(
    val episodeNumber: Int,
    val seasonNumber: Int,
    val offers: List<JustWatchOffer> = emptyList(),
)

@Serializable
data class JustWatchOffer(
    val standardWebURL: String? = null,
    val monetizationType: String? = null,
    val `package`: JustWatchPackage? = null,
)

@Serializable
data class JustWatchPackage(
    val technicalName: String,
)

@Serializable
data class JustWatchContent(
    val externalIds: JustWatchExternalIds? = null,
)

@Serializable
data class JustWatchExternalIds(
    val tmdbId: String? = null,
)
