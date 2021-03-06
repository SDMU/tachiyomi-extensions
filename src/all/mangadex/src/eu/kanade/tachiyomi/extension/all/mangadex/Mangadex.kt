package eu.kanade.tachiyomi.extension.all.mangadex

import com.github.salomonbrys.kotson.*
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.*
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import java.net.URLEncoder
import java.util.*
import java.util.concurrent.TimeUnit

open class Mangadex(override val lang: String, private val internalLang: String, val langCode: Int) : ParsedHttpSource() {

    override val name = "MangaDex"

    override val baseUrl = "https://mangadex.org"

    override val supportsLatest = true

    override val client = clientBuilder(ALL)

    private fun clientBuilder(r18Toggle: Int): OkHttpClient = network.cloudflareClient.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                val newReq = chain
                        .request()
                        .newBuilder()
                        .addHeader("Cookie", cookiesHeader(r18Toggle, langCode))
                        .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 6.3; WOW64)")
                        .build()
                chain.proceed(newReq)
            }.build()!!

    private fun cookiesHeader(r18Toggle: Int, langCode: Int): String {
        val cookies = mutableMapOf<String, String>()
        cookies["mangadex_h_toggle"] = r18Toggle.toString()
        cookies["mangadex_filter_langs"] = langCode.toString()
        return buildCookies(cookies)
    }

    private fun buildCookies(cookies: Map<String, String>) = cookies.entries.joinToString(separator = "; ", postfix = ";") {
        "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
    }

    override fun popularMangaSelector() = "div.col-sm-6"

    override fun latestUpdatesSelector() = ".table-responsive tbody tr a.manga_title[href*=manga]"

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/titles/0/$page", headers)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/0/$page", headers)
    }

    override fun popularMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        element.select("a.manga_title").first().let {
            val url = removeMangaNameFromUrl(it.attr("href"))
            manga.setUrlWithoutDomain(url)
            manga.thumbnail_url = baseUrl + "/images" + manga.url.substringBeforeLast("/") + ".jpg"
            manga.title = it.text().trim()
            manga.author = it?.text()?.trim()
        }
        return manga
    }

    private fun removeMangaNameFromUrl(url: String): String = url.substringBeforeLast("/") + "/"

    override fun latestUpdatesFromElement(element: Element): SManga {
        val manga = SManga.create()
        element.let {
            manga.setUrlWithoutDomain(removeMangaNameFromUrl(it.attr("href")))
            manga.title = it.text().trim()
        }
        return manga
    }

    override fun popularMangaNextPageSelector() = ".pagination li:not(.disabled) span[title*=last page]:not(disabled)"

    override fun latestUpdatesNextPageSelector() = ".pagination li:not(.disabled) span[title*=last page]:not(disabled)"

    override fun searchMangaNextPageSelector() = ".pagination li:not(.disabled) span[title*=last page]:not(disabled)"

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return getSearchClient(filters).newCall(searchMangaRequest(page, query, filters))
                .asObservableSuccess()
                .map { response ->
                    searchMangaParse(response)
                }
    }

    /* get search client based off r18 filter.  This will always return default client builder now until r18 solution is found or login is add
     */
    private fun getSearchClient(filters: FilterList): OkHttpClient {
        filters.forEach { filter ->
            when (filter) {
                is R18 -> {
                    return when (filter.state) {
                        1 -> clientBuilder(ONLY_R18)
                        2 -> clientBuilder(NO_R18)
                        else -> clientBuilder(ALL)
                    }
                }
            }
        }
        return clientBuilder(ALL)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val byGenre = filters.find { it is GenreList }
        val genres = mutableListOf<String>()
        if (byGenre != null) {
            byGenre as GenreList
            byGenre.state.forEach { genre ->
                when (genre.state) {
                    true -> genres.add(genre.id)
                }
            }
        }
        //do browse by letter if set
        val byLetter = filters.find { it is ByLetter }

        if (byLetter != null && (byLetter as ByLetter).state.first().state != 0) {
            val s = byLetter.state.first().values[byLetter.state.first().state]
            val pageStr = if (page != 1) (((page - 1) * 100)).toString() else "0"
            val url = HttpUrl.parse("$baseUrl/titles/")!!.newBuilder().addPathSegment(s).addPathSegment(pageStr)
            return GET(url.toString(), headers)

        } else {
            //do traditional search
            val url = HttpUrl.parse("$baseUrl/?page=search")!!.newBuilder().addQueryParameter("title", query)
            filters.forEach { filter ->
                when (filter) {
                    is TextField -> url.addQueryParameter(filter.key, filter.state)
                }
            }
            if (genres.isNotEmpty()) url.addQueryParameter("genres", genres.joinToString(","))

            return GET(url.toString(), headers)
        }
    }

    override fun searchMangaSelector() = ".table.table-striped.table-hover.table-condensed tbody tr"

    override fun searchMangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        element.select("a[href*=manga]").first().let {
            val url = removeMangaNameFromUrl(it.attr("href"))
            manga.setUrlWithoutDomain(url)
            manga.thumbnail_url = baseUrl + "/images" + manga.url.substringBeforeLast("/") + ".jpg"
            manga.title = it.text().trim()
            manga.author = it?.text()?.trim()
        }
        return manga
    }

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        return client.newCall(apiRequest(manga))
                .asObservableSuccess()
                .map { response ->
                    mangaDetailsParse(response).apply { initialized = true }
                }
    }

    private fun apiRequest(manga: SManga): Request {
        return GET(baseUrl + URL + getMangaId(manga.url), headers)
    }

    private fun getMangaId(url: String): String {

        val lastSection = url.trimEnd('/').substringAfterLast("/")
        return if (lastSection.toIntOrNull() != null) {
            lastSection
        } else {
            //this occurs if person has manga from before that had the id/name/
            url.trimEnd('/').substringBeforeLast("/").substringAfterLast("/")
        }
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val manga = SManga.create()
        var jsonData = response.body()!!.string()
        val json = JsonParser().parse(jsonData).asJsonObject
        val mangaJson = json.getAsJsonObject("manga")
        manga.thumbnail_url = baseUrl + mangaJson.get("cover_url").string
        manga.description = cleanString(mangaJson.get("description").string)
        manga.author = mangaJson.get("author").string
        manga.artist = mangaJson.get("artist").string
        manga.status = parseStatus(mangaJson.get("status").int)
        var genres = mutableListOf<String>()

        mangaJson.get("genres").asJsonArray.forEach { id ->
            getGenreList().find { it -> it.id == id.string }?.let { genre ->
                genres.add(genre.name)
            }
        }
        manga.genre = genres.joinToString(", ")
        return manga
    }

    //remove bbcode as well as parses any html characters in description or chapter name to actual characters for example &hearts will show a heart
    private fun cleanString(description: String): String {
        return Jsoup.parseBodyFragment(description.replace("[list]", "").replace("[/list]", "").replace("[*]", "").replace("""\[(\w+)[^\]]*](.*?)\[/\1]""".toRegex(), "$2")).text()
    }


    override fun mangaDetailsParse(document: Document) = throw Exception("Not Used")

    override fun chapterListSelector() = ""

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        return client.newCall(apiRequest(manga))
                .asObservableSuccess()
                .map { response ->
                    chapterListParse(response)
                }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val now = Date().time
        var jsonData = response.body()!!.string()
        val json = JsonParser().parse(jsonData).asJsonObject
        val chapterJson = json.getAsJsonObject("chapter")
        val chapters = mutableListOf<SChapter>()

        //skip chapters that dont match the desired language, or are future releases
        chapterJson?.forEach { key, jsonElement ->
            val chapterElement = jsonElement.asJsonObject
            if (chapterElement.get("lang_code").string == internalLang && (chapterElement.get("timestamp").asLong * 1000) <= now) {
                chapterElement.toString()
                chapters.add(chapterFromJson(key, chapterElement))
            }
        }
        return chapters
    }

    private fun chapterFromJson(chapterId: String, chapterJson: JsonObject): SChapter {
        val chapter = SChapter.create()
        chapter.url = BASE_CHAPTER + chapterId
        var chapterName = mutableListOf<String>()
        //build chapter name
        if (chapterJson.get("volume").string.isNotBlank()) {
            chapterName.add("Vol." + chapterJson.get("volume").string)
        }
        if (chapterJson.get("chapter").string.isNotBlank()) {
            chapterName.add("Ch." + chapterJson.get("chapter").string)
        }
        if (chapterJson.get("title").string.isNotBlank()) {
            chapterName.add("-")
            chapterName.add(chapterJson.get("title").string)
        }

        chapter.name = cleanString(chapterName.joinToString(" "))
        //convert from unix time
        chapter.date_upload = chapterJson.get("timestamp").long * 1000
        var scanlatorName = mutableListOf<String>()
        if (!chapterJson.get("group_name").nullString.isNullOrBlank()) {
            scanlatorName.add(chapterJson.get("group_name").string)
        }
        if (!chapterJson.get("group_name_2").nullString.isNullOrBlank()) {
            scanlatorName.add(chapterJson.get("group_name_2").string)
        }
        if (!chapterJson.get("group_name_3").nullString.isNullOrBlank()) {
            scanlatorName.add(chapterJson.get("group_name_3").string)
        }
        chapter.scanlator = scanlatorName.joinToString(" & ")


        return chapter
    }

    override fun chapterFromElement(element: Element) = throw Exception("Not used")

    override fun pageListParse(document: Document): List<Page> {
        val pages = mutableListOf<Page>()
        val url = document.baseUri()

        val dataUrl = document.select("script").last().html().substringAfter("dataurl = '").substringBefore("';")
        val imageUrl = document.select("script").last().html().substringAfter("page_array = [").substringBefore("];")
        val listImageUrls = imageUrl.replace("'", "").split(",")
        val server = document.select("script").last().html().substringAfter("server = '").substringBefore("';")

        listImageUrls.filter { it.isNotBlank() }.forEach {
            val url = "$server$dataUrl/$it"
            pages.add(Page(pages.size, "", getImageUrl(url)))
        }

        return pages
    }

    override fun imageUrlParse(document: Document): String = ""

    private fun parseStatus(status: Int) = when (status) {
        1 -> SManga.ONGOING
        2 -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    private fun getImageUrl(attr: String): String {
        //some images are hosted elsewhere
        if (attr.startsWith("http")) {
            return attr
        }
        return baseUrl + attr
    }

    private class TextField(name: String, val key: String) : Filter.Text(name)
    private class Genre(val id: String, name: String) : Filter.CheckBox(name)
    private class GenreList(genres: List<Genre>) : Filter.Group<Genre>("Genres", genres)
    private class R18 : Filter.Select<String>("R18+", arrayOf("Show all", "Show only", "Show none"))
    private class ByLetter(letters: List<Letters>) : Filter.Group<Letters>("Browse by Letter only", letters)
    private class Letters : Filter.Select<String>("Letter", arrayOf("", "~", "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z"))

    override fun getFilterList() = FilterList(
            TextField("Author", "author"),
            TextField("Artist", "artist"),
            R18(),
            GenreList(getGenreList()),
            ByLetter(listOf(Letters()))
    )


    private fun getGenreList() = listOf(
            Genre("1", "4-koma"),
            Genre("2", "Action"),
            Genre("3", "Adventure"),
            Genre("4", "Award Winning"),
            Genre("5", "Comedy"),
            Genre("6", "Cooking"),
            Genre("7", "Doujinshi"),
            Genre("8", "Drama"),
            Genre("9", "Ecchi"),
            Genre("10", "Fantasy"),
            Genre("11", "Gender Bender"),
            Genre("12", "Harem"),
            Genre("13", "Historical"),
            Genre("14", "Horror"),
            Genre("15", "Josei"),
            Genre("16", "Martial Arts"),
            Genre("17", "Mecha"),
            Genre("18", "Medical"),
            Genre("19", "Music"),
            Genre("20", "Mystery"),
            Genre("21", "Oneshot"),
            Genre("22", "Psychological"),
            Genre("23", "Romance"),
            Genre("24", "School Life"),
            Genre("25", "Sci-Fi"),
            Genre("26", "Seinen"),
            Genre("27", "Shoujo"),
            Genre("28", "Shoujo Ai"),
            Genre("29", "Shounen"),
            Genre("30", "Shounen Ai"),
            Genre("31", "Slice of Life"),
            Genre("32", "Smut"),
            Genre("33", "Sports"),
            Genre("34", "Supernatural"),
            Genre("35", "Tragedy"),
            Genre("36", "Webtoon"),
            Genre("37", "Yaoi"),
            Genre("38", "Yuri"),
            Genre("39", "[no chapters]"),
            Genre("40", "Game")
    )

    companion object {
        //this number matches to the cookie
        private const val NO_R18 = 0
        private const val ALL = 1
        private const val ONLY_R18 = 2
        private const val URL = "/api/3640f3fb/"
        private const val BASE_CHAPTER = "/chapter/"

    }
}