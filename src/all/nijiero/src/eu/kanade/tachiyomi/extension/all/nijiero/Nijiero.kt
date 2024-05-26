package eu.kanade.tachiyomi.extension.all.nijiero

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.select.Evaluator
import java.text.SimpleDateFormat
import java.util.Locale

class Nijiero : HttpSource() {
    override val name = "Nijiero"
    override val lang = "all"
    override val supportsLatest = false

    override val baseUrl = "https://www.nijiero-ch.com"

    override val client = network.client.newBuilder().followRedirects(true).build()
    class TagFilter : Filter.Select<String>("Category", nijieroTags)

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        return document.select("#entry > ul > li a[href]").mapIndexed { i, linkElement ->
            val linkUrl = linkElement.attr("href").removeSuffix(".webp").removePrefix(baseUrl)
            Page(i, linkUrl, linkUrl)
        }
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()
    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException()

    override fun popularMangaRequest(page: Int): Request {
        val uniqueParam = System.currentTimeMillis()
        val url = "$baseUrl/ranking.html?refresh=$uniqueParam"

        return GET(url, headers)
    }
    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.selectFirst("#mainContent .allRunkingArea.tabContent.cf")!!.children().mapNotNull { it ->
            val link = it.select("a")
            if (!link.isEmpty()) {
                SManga.create().apply {
                    url = link.attr("href").removePrefix(baseUrl)
                    title = link.attr("title")
                    thumbnail_url = it.selectFirst("img")?.attr("src")
                    status = SManga.COMPLETED
                }
            } else {
                null
            }
        }

        return MangasPage(mangas, false)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val tagIdx: Int = (filters.last() as TagFilter).state

        val pageType = when {
            query.isBlank() -> "category"
            else -> "tag"
        }

        val keyword = when {
            query.isBlank() -> nijieroTags[tagIdx]
            else -> query
        }.replace(Regex("""\s+"""), "-").lowercase()

        val uniqueParam = System.currentTimeMillis()
        val url = "$baseUrl/$pageType/$keyword/page/$page?refresh=$uniqueParam"

        return GET(url, headers)
    }
    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        var mangas = document.selectFirst(".contentList")!!.children().mapNotNull { it ->
            val link = it.select("a")
            if (!link.isEmpty()) {
                SManga.create().apply {
                    url = link.attr("href").removePrefix(baseUrl)
                    title = link.attr("title")
                    thumbnail_url = it.selectFirst("img")?.attr("data-src")
                    status = SManga.COMPLETED
                }
            } else {
                null
            }
        }
        var isLastPage = document.selectFirst(Evaluator.Class("next page-numbers")) == null

        if (mangas.isEmpty()) {
            val currentUrl = response.request.url.toString()
            val newUrl = if (currentUrl.contains("category/")) {
                currentUrl.replace("category/", "tag/")
            } else if (currentUrl.contains("tag/")) {
                currentUrl.replace("tag/", "category/")
            } else {
                currentUrl
            }
            val newResponse = client.newCall(GET(newUrl, headers)).execute()
            return searchMangaParse(newResponse)
        }

        return MangasPage(mangas, !isLastPage)
    }


    override fun mangaDetailsRequest(manga: SManga) = GET(manga.url, headers)
    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst("div.arrow.mb0 h1.type01_hl")?.text() ?: document.title()
            thumbnail_url = document.select("meta[property=og:image]").attr("content")
            status = SManga.COMPLETED
            val genres = document.select("dl.cf:contains(カテゴリ) a").map { getCategoryOrTagFromLink(it.attr("href")) }
            genre = genres.joinToString(", ")
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
        }
    }
    private fun getCategoryOrTagFromLink(link: String): String {
        val segments = link.trimEnd('/').split("/")
        return segments.lastOrNull()?.replace("-", " ") ?: ""
    }

    override fun chapterListRequest(manga: SManga): Request = GET(manga.url, headers)
    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val dateStr = document.selectFirst("div.postInfo.cf div.postDate.cf time.entry-date.date.published.updated")?.attr("datetime") ?: ""
        val dateUpload = parseDate(dateStr)
	
        return listOf(
            SChapter.create().apply {
                name = "Chapter"
                setUrlWithoutDomain(response.request.url.encodedPath.removePrefix(baseUrl))
                date_upload = dateUpload
            },
        )
    }
	
    private fun parseDate(dateStr: String): Long {
        return runCatching { DATE_FORMATTER.parse(dateStr)?.time }
            .getOrNull() ?: 0L
    }
	
    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ENGLISH)
        }
    }

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("Category search directly is broken, type the name 1:1 in search bar."),
        TagFilter(),
    )
}
