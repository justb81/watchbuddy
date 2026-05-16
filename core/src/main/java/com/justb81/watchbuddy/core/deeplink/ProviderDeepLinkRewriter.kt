package com.justb81.watchbuddy.core.deeplink

/**
 * Per-provider rewriter that converts the JustWatch standardWebURL into the URI
 * variant the streaming app's Android intent filter actually handles.
 *
 * Returns one or more candidate URIs in priority order. The caller (TV
 * launchProvider) tries each in turn until resolveActivity() succeeds.
 *
 * Returning the original URL as the only candidate means "no rewrite known".
 */
object ProviderDeepLinkRewriter {

    private const val NETFLIX_PROVIDER_ID = 8

    // Netflix title id pattern in JustWatch URLs:
    //   https://www.netflix.com/watch/<id>            (rare, JustWatch sometimes returns this)
    //   https://www.netflix.com/title/<id>            (most common)
    //   https://www.netflix.com/<lang>/title/<id>     (locale-prefixed)
    private val NETFLIX_TITLE_REGEX = Regex(
        "https?://(?:www\\.)?netflix\\.com/(?:[a-z-]+/)?(?:title|watch)/(\\d+)",
        RegexOption.IGNORE_CASE,
    )

    /**
     * @return Ordered list of candidate URIs to try, most app-friendly first.
     *         Always non-empty: at minimum returns [standardWebUrl] verbatim.
     */
    fun rewrite(providerId: Int, standardWebUrl: String): List<String> = when (providerId) {
        NETFLIX_PROVIDER_ID -> rewriteNetflix(standardWebUrl)
        else -> listOf(standardWebUrl)
    }

    private fun rewriteNetflix(url: String): List<String> {
        val match = NETFLIX_TITLE_REGEX.find(url) ?: return listOf(url)
        val titleId = match.groupValues[1]
        // The Netflix TV app (com.netflix.ninja) registers intent filters for both
        // the nflx:// scheme and https://www.netflix.com/title/<id>. The /watch/<id>
        // variant is NOT registered. Order: native scheme > canonical title URL > raw URL.
        return listOf(
            "nflx://www.netflix.com/title/$titleId",
            "https://www.netflix.com/title/$titleId",
            url,
        ).distinct()
    }
}
