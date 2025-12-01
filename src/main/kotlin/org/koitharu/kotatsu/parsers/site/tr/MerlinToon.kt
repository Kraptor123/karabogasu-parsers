package org.koitharu.kotatsu.parsers.site.tr

import okhttp3.Headers
import org.json.JSONArray
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.attrAsAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrl
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.oneOrThrowIfMany
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.parseJsonArray
import org.koitharu.kotatsu.parsers.util.parseSafe
import org.koitharu.kotatsu.parsers.util.requireSrc
import org.koitharu.kotatsu.parsers.util.selectFirstOrThrow
import org.koitharu.kotatsu.parsers.util.selectLast
import org.koitharu.kotatsu.parsers.util.src
import org.koitharu.kotatsu.parsers.util.toAbsoluteUrl
import org.koitharu.kotatsu.parsers.util.toRelativeUrl
import java.text.SimpleDateFormat
import java.util.EnumSet
import java.util.Locale

@MangaSourceParser("MERLINTOON", "MerlinToon", "tr")
internal class MerlinToon(context: MangaLoaderContext) : PagedMangaParser(context, MangaParserSource.MERLINTOON, 45) {

    override val configKeyDomain = ConfigKey.Domain("merlintoon.com")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(SortOrder.UPDATED)

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
        )

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = genreTags.toSet(),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        when {
            !filter.query.isNullOrEmpty() -> {
                if (page > 1) return emptyList()

                val response = webClient.httpGet(
                    url = "https://$domain/wp-json/initlise/v1/search?term=${filter.query}",
                    extraHeaders = Headers.headersOf(
                        "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:144.0) Gecko/20100101 Firefox/144.0",
                        "Referer", "https://$domain/"
                    )
                )

                val jsonArray = response.parseJsonArray()

                return parseSearchJsonArray(jsonArray)
            }

            else -> {

                if (filter.tags.isNotEmpty()) {
                    filter.tags.oneOrThrowIfMany()?.let {
                        if (page > 1) {
                            return parseMangaListQueryOrTags(
                                webClient.httpGet(
                                    "https://$domain/gelismis-seri-filtreleme/page/$page/?genre[]=${it.key}&type=&status=&sort=updated",
                                    extraHeaders = Headers.headersOf(
                                        "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:144.0) Gecko/20100101 Firefox/144.0",
                                        "Referer", "https://$domain/"
                                    )
                                ).parseHtml(),
                            )
                        }
                        return parseMangaListQueryOrTags(
                            webClient.httpGet(
                                "https://$domain/gelismis-seri-filtreleme/?genre[]=${it.key}&type=&status=&sort=updated",
                                extraHeaders = Headers.headersOf(
                                    "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:144.0) Gecko/20100101 Firefox/144.0",
                                    "Referer", "https://$domain/"
                                )
                            ).parseHtml(),
                        )
                    }
                } else {
                    val url = buildString {
                        append("https://")
                        append(domain)
                        append("/seri/")
                        if (page > 1) {
                            append("page/")
                            append(page)
                            append('/')
                        }
                    }
                    return parseMangaList(webClient.httpGet(url).parseHtml())
                }

            }
        }

        return emptyList()
    }

    private fun parseSearchJsonArray(arr: JSONArray): List<Manga> {
        val list = ArrayList<Manga>(arr.length())

        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)

            val href = obj.optString("url")
            val thumb = obj.optString("thumb", null).takeIf { !it.isNullOrBlank() }

            val cleanTitle = Jsoup.parse(obj.optString("title", "")).text()

            list.add(
                Manga(
                    id = generateUid(href),
                    url = href,
                    publicUrl = href,
                    title = cleanTitle,
                    coverUrl = thumb,
                    altTitles = emptySet(),
                    rating = RATING_UNKNOWN,
                    tags = emptySet(),
                    description = null,
                    state = null,
                    authors = emptySet(),
                    contentRating = sourceContentRating,
                    source = source,
                )
            )
        }

        return list
    }

    private fun parseMangaList(doc: Document): List<Manga> {
        return doc.select("div.manga-block > div ~ div > div").map { div ->
            val a = div.selectFirstOrThrow("div div.uk-overflow-hidden a")
            val href = a.attrAsRelativeUrl("href")
            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = a.attrAsAbsoluteUrl("href"),
                title = div.selectLast("div.uk-overflow-hidden a")?.text().orEmpty(),
                coverUrl = div.selectFirst("img")?.src(),
                altTitles = emptySet(),
                rating = RATING_UNKNOWN,
                tags = emptySet(),
                description = null,
                state = null,
                authors = emptySet(),
                contentRating = sourceContentRating,
                source = source,
            )
        }
    }

    private fun parseMangaListQueryOrTags(doc: Document): List<Manga> {
        return doc.select("div.manga-block > div ~ div > div").map { div ->
            val a = div.selectFirstOrThrow("div div.uk-overflow-hidden a")
            val href = a.attrAsRelativeUrl("href")
            Manga(
                id = generateUid(href),
                url = href,
                publicUrl = a.attrAsAbsoluteUrl("href"),
                title = div.selectLast("div.uk-overflow-hidden a")?.text().orEmpty(),
                coverUrl = div.selectFirst("img")?.src(),
                altTitles = emptySet(),
                rating = RATING_UNKNOWN,
                tags = emptySet(),
                description = null,
                state = null,
                authors = emptySet(),
                contentRating = sourceContentRating,
                source = source,
            )
        }
    }
    private val genreTags = listOf(
        MangaTag(key = "Aksiyon", title = "aksiyon", source = source),
        MangaTag(key = "Fantastik", title = "fantastik", source = source),
        MangaTag(key = "Dram", title = "dram", source = source),
        MangaTag(key = "Macera", title = "macera", source = source)
    )

    val tagsSet: Set<MangaTag> = genreTags.toSet()

    override suspend fun getDetails(manga: Manga): Manga {
        val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ROOT)
        val tagMap: Map<String, MangaTag> = genreTags.associateBy { it.title }

        // İlk sayfayı yükle
        val firstDoc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()

        // Tags ve description'ı ilk sayfadan al
        val tags = firstDoc.select("div#genre-tags a").mapNotNullToSet { tagMap[it.text()] }
        val description = firstDoc.selectFirst("div#manga-description p")?.html()
        val coverUrl = firstDoc.selectFirst("a.story-cover img")?.src() ?: manga.coverUrl

        // Tüm bölümleri toplamak için liste
        val allChapters = mutableListOf<MangaChapter>()
        var chapterIndex = 0
        var page = 1

        // İlk sayfanın bölümlerini ekle
        firstDoc.select("div.chapter-item").forEach { tr ->
            val a = tr.selectFirstOrThrow("a")
            val href = a.attrAsRelativeUrl("href")
            allChapters.add(
                MangaChapter(
                    id = generateUid(href),
                    title = tr.selectFirst("h3")?.text(),
                    number = chapterIndex + 1f,
                    volume = 0,
                    url = href,
                    scanlator = null,
                    uploadDate = dateFormat.parseSafe(tr.selectFirstOrThrow("time").attr("datetime")),
                    branch = null,
                    source = source,
                )
            )
            chapterIndex++
        }

        // Diğer sayfaları yükle
        while (true) {
            page++
            val url = "${manga.url.toAbsoluteUrl(domain)}/bolum/page/$page/"

            try {
                val doc = webClient.httpGet(url).parseHtml()
                val chapters = doc.select("div.chapter-item")

                if (chapters.isEmpty()) break

                chapters.forEach { tr ->
                    val a = tr.selectFirstOrThrow("a")
                    val href = a.attrAsRelativeUrl("href")
                    allChapters.add(
                        MangaChapter(
                            id = generateUid(href),
                            title = tr.selectFirst("h3")?.text(),
                            number = chapterIndex + 1f,
                            volume = 0,
                            url = href,
                            scanlator = null,
                            uploadDate = dateFormat.parseSafe(tr.selectFirstOrThrow("time").attr("datetime")),
                            branch = null,
                            source = source,
                        )
                    )
                    chapterIndex++
                }
            } catch (e: Exception) {
                break
            }
        }

        return manga.copy(
            description = description,
            coverUrl = coverUrl,
            tags = tags,
            chapters = allChapters.reversed(), // Ters çevir ki en yeni bölümler üstte olsun
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()
        return doc.select("div#chapter-content img").map { img ->
            val url = img.requireSrc().toRelativeUrl(domain)
            MangaPage(
                id = generateUid(url),
                url = url,
                preview = null,
                source = source,
            )
        }
    }
}
