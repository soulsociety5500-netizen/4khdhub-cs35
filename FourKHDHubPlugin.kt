package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class FourKHDHubPlugin : MainAPI() {
    override var mainUrl = "https://4khdhub.one"
    override var name = "4KHDHub"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    // ─── Main Page Categories ───────────────────────────────────────────────

    override val mainPage = mainPageOf(
        "$mainUrl/category/movies/"   to "Latest Movies",
        "$mainUrl/category/series/"   to "Latest Series",
        "$mainUrl/category/hindi-movies/" to "Hindi Movies",
        "$mainUrl/category/english-movies/" to "English Movies",
        "$mainUrl/category/hindi-series/" to "Hindi Series",
        "$mainUrl/category/english-series/" to "English Series",
        "$mainUrl/category/2160p-HDR/" to "4K HDR",
        "$mainUrl/category/netflix/" to "Netflix",
        "$mainUrl/category/amazon_prime_video/" to "Amazon Prime",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val doc = app.get(url).document
        val items = doc.select("a.post-link, .post-card a, article a[href*='$mainUrl']")
            .distinctBy { it.attr("href") }
            .mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    // ─── Search ─────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=${query.replace(" ", "+")}").document
        return doc.select("article, .post-card, a[href*='-movie-'], a[href*='-series-']")
            .mapNotNull { el ->
                val a = if (el.tagName() == "a") el else el.selectFirst("a") ?: return@mapNotNull null
                a.toSearchResult()
            }
            .distinctBy { it.url }
    }

    // ─── Load (detail page) ─────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: doc.selectFirst(".poster img, .entry-media img")?.attr("src")
        val description = doc.selectFirst(".entry-content p, .synopsis, .description")?.text()?.trim()
        val isSeries = url.contains("-series-") || doc.select(".season-section, .episode-list").isNotEmpty()

        // Extract the inline video player embed (videasy.net)
        val embedUrl = doc.html()
            .let { Regex("""https://player\.videasy\.net/(movie|tv)/(\d+)""").find(it) }
            ?.value

        // Collect all download links from HubCloud / HubDrive
        val downloadLinks = doc.select("a[href*='hubcloud'], a[href*='hubdrive']")
            .map { it.attr("href") to (it.text().ifBlank { it.parent()?.text() ?: "Download" }) }

        return if (isSeries) {
            // Build season/episode structure from download section headings
            val episodes = mutableListOf<Episode>()
            doc.select(".season-section, [class*='season'], h3, h4").forEach { heading ->
                val headingText = heading.text()
                val seasonMatch = Regex("""S(\d+)""", RegexOption.IGNORE_CASE).find(headingText)
                val season = seasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1

                // Individual episode links under this heading
                heading.nextElementSiblings().takeWhile {
                    !it.tagName().matches(Regex("h[2-4]"))
                }.forEach siblings@{ sibling ->
                    val epMatch = Regex("""E(\d+)""", RegexOption.IGNORE_CASE)
                        .find(sibling.text()) ?: return@siblings
                    val epNum = epMatch.groupValues[1].toIntOrNull() ?: return@siblings
                    val epTitle = sibling.selectFirst(".ep-title, .filename")?.text()?.trim()
                    val epLinks = sibling.select("a[href*='hubcloud'], a[href*='hubdrive']")
                        .map { it.attr("href") }

                    if (epLinks.isNotEmpty()) {
                        episodes.add(
                            newEpisode(epLinks.first()) {
                                this.season = season
                                this.episode = epNum
                                this.name = epTitle ?: "Episode $epNum"
                            }
                        )
                    }
                }
            }

            // Fallback: if no structured episodes found, add pack downloads as episode 0
            if (episodes.isEmpty()) {
                downloadLinks.forEachIndexed { i, (link, label) ->
                    episodes.add(
                        newEpisode(link) {
                            this.season = 1
                            this.episode = i + 1
                            this.name = label
                        }
                    )
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            // Movie — prefer the embed, then fall back to first download link
            val dataUrl = embedUrl ?: downloadLinks.firstOrNull()?.first ?: url
            newMovieLoadResponse(title, url, TvType.Movie, dataUrl) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    // ─── Link Extraction ────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Handle videasy.net embed
        if (data.contains("videasy.net")) {
            loadExtractor(data, mainUrl, subtitleCallback, callback)
            return true
        }

        // Handle HubCloud / HubDrive redirect pages
        if (data.contains("hubcloud") || data.contains("hubdrive")) {
            try {
                val doc = app.get(data).document
                // Look for direct download/stream link on the landing page
                val directLink = doc.selectFirst("a.download-btn, a[href*='.mkv'], a[href*='.mp4']")
                    ?.attr("href")
                if (directLink != null) {
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = name,
                            url = directLink,
                            referer = data,
                            quality = getQualityFromName(directLink),
                            isM3u8 = directLink.contains(".m3u8")
                        )
                    )
                    return true
                }
                // Try any iframe or embed
                val iframeSrc = doc.selectFirst("iframe")?.attr("src")
                if (iframeSrc != null) {
                    loadExtractor(iframeSrc, data, subtitleCallback, callback)
                    return true
                }
            } catch (e: Exception) {
                // ignore and fall through
            }
        }

        // Fallback: try to treat data as a direct URL
        loadExtractor(data, mainUrl, subtitleCallback, callback)
        return true
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private fun Element.toSearchResult(): SearchResponse? {
        val href = attr("abs:href").takeIf { it.startsWith(mainUrl) } ?: return null
        if (!href.contains("-movie-") && !href.contains("-series-")) return null

        val title = attr("title").ifBlank {
            selectFirst("img")?.attr("alt")
                ?: selectFirst(".post-title, h2, h3")?.text()
                ?: text()
        }.trim().ifBlank { return null }

        val poster = selectFirst("img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }

        val isSeries = href.contains("-series-")
        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }
}
