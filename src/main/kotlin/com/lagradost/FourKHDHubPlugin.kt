package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element

class FourKHDHubPlugin : MainAPI() {
    override var mainUrl = "https://4khdhub.one"
    override var name = "4KHDHub"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/category/movies/" to "Latest Movies",
        "$mainUrl/category/series/" to "Latest Series",
        "$mainUrl/category/hindi-movies/" to "Hindi Movies",
        "$mainUrl/category/english-movies/" to "English Movies",
        "$mainUrl/category/2160p-HDR/" to "4K HDR",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val doc = app.get(url).document
        val items = doc.select("article.post").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=${query.replace(" ", "+")}").document
        return doc.select("article.post").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: return null
        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
        val description = doc.selectFirst(".entry-content p")?.text()?.trim()
        val isSeries = url.contains("-series-")

        val links = doc.select("a[href*='hubcloud'], a[href*='hubdrive'], a[href*='gdflix']")
            .map { it.attr("href") }

        return if (isSeries) {
            val episodes = links.mapIndexed { i, link ->
                newEpisode(link) {
                    name = "Episode ${i + 1}"
                    season = 1
                    episode = i + 1
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, links.firstOrNull() ?: url) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        loadExtractor(data, mainUrl, subtitleCallback, callback)
        return true
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a") ?: return null
        val href = a.attr("href").takeIf { it.isNotBlank() } ?: return null
        val title = selectFirst("h2, h3, .entry-title")?.text()?.trim()
            ?: a.attr("title").ifBlank { return null }
        val poster = selectFirst("img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }
        return if (href.contains("-series-")) {
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
