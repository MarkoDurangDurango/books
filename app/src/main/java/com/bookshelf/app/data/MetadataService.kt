package com.bookshelf.app.data

import com.bookshelf.app.BuildConfig
import com.bookshelf.app.domain.BookDraft
import com.bookshelf.app.domain.PublicationType
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

class MetadataService {
    suspend fun lookup(isbn13: String): BookDraft? {
        val googleExact = runCatching { googleBooks(isbn13, exactIsbnQuery = true) }.getOrNull()
        val rusNeb = runCatching { rusNeb(isbn13) }.getOrNull()
        val openExact = runCatching { openLibraryBooksApi(isbn13) }.getOrNull()

        val needFallback = listOfNotNull(googleExact, rusNeb, openExact).none { !it.isIncomplete() }

        val googleBroad = if (googleExact == null || googleExact.isIncomplete()) {
            runCatching { googleBooks(isbn13, exactIsbnQuery = false) }.getOrNull()
        } else {
            null
        }
        val openSearch = if (needFallback) {
            runCatching { openLibrarySearch(isbn13) }.getOrNull()
        } else {
            null
        }

        val candidates = listOfNotNull(googleExact, rusNeb, openExact, googleBroad, openSearch)
        if (candidates.isEmpty()) return null

        fun pick(selector: (RemoteBook) -> String?): String? =
            candidates.asSequence().mapNotNull(selector).firstOrNull { it.isNotBlank() }

        val categories = candidates.asSequence()
            .mapNotNull { it.categories?.takeIf(String::isNotBlank) }
            .distinct()
            .joinToString(", ")

        val sources = candidates.map { it.source }.distinct().joinToString("+")

        return BookDraft(
            isbn13 = isbn13,
            isbn10 = pick { it.isbn10 },
            title = pick { it.title }.orEmpty(),
            subtitle = pick { it.subtitle }.orEmpty(),
            authors = pick { it.authors }.orEmpty(),
            publisher = pick { it.publisher }.orEmpty(),
            publishedYear = pick { it.publishedYear }.orEmpty(),
            pages = pick { it.pages }.orEmpty(),
            publicationType = classify(categories),
            categories = categories,
            description = pick { it.description }.orEmpty(),
            coverRemoteUrl = pick { it.coverUrl } ?: openLibraryCoverUrl(isbn13),
            metadataSource = sources.ifBlank { "not_found" }
        )
    }

    private fun googleBooks(isbn: String, exactIsbnQuery: Boolean): RemoteBook? {
        val rawQuery = if (exactIsbnQuery) "isbn:$isbn" else isbn
        val query = URLEncoder.encode(rawQuery, StandardCharsets.UTF_8.toString())
        val apiKey = BuildConfig.GOOGLE_BOOKS_API_KEY.trim()
        val keyPart = if (apiKey.isNotBlank()) {
            "&key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8.toString())
        } else {
            ""
        }
        val url = "https://www.googleapis.com/books/v1/volumes?q=$query&maxResults=10&printType=books$keyPart"
        val root = getJson(url)
        val items = root.optJSONArray("items") ?: return null

        for (i in 0 until items.length()) {
            val volume = items.optJSONObject(i)?.optJSONObject("volumeInfo") ?: continue
            val identifiers = volume.optJSONArray("industryIdentifiers")
            if (!containsIsbn(identifiers, isbn)) continue
            return parseGoogleVolume(volume)
        }
        return null
    }

    private fun parseGoogleVolume(volume: JSONObject): RemoteBook {
        val ids = volume.optJSONArray("industryIdentifiers")
        var isbn10: String? = null
        if (ids != null) {
            for (i in 0 until ids.length()) {
                val item = ids.optJSONObject(i) ?: continue
                if (item.optString("type") == "ISBN_10") {
                    isbn10 = item.optString("identifier").takeIf { it.isNotBlank() }
                    break
                }
            }
        }

        val imageLinks = volume.optJSONObject("imageLinks")
        val cover = listOf("extraLarge", "large", "medium", "small", "thumbnail", "smallThumbnail")
            .asSequence()
            .mapNotNull { keyName -> imageLinks?.optString(keyName)?.takeIf { it.isNotBlank() } }
            .firstOrNull()
            ?.replace("http://", "https://")
            ?.replace("&edge=curl", "")
            ?.replace("zoom=1", "zoom=2")

        return RemoteBook(
            source = "google_books",
            isbn10 = isbn10,
            title = volume.optString("title").takeIf { it.isNotBlank() },
            subtitle = volume.optString("subtitle").takeIf { it.isNotBlank() },
            authors = join(volume.optJSONArray("authors")),
            publisher = volume.optString("publisher").takeIf { it.isNotBlank() },
            publishedYear = extractYear(volume.optString("publishedDate")),
            pages = volume.optInt("pageCount", 0).takeIf { it > 0 }?.toString(),
            categories = join(volume.optJSONArray("categories")),
            description = volume.optString("description").takeIf { it.isNotBlank() },
            coverUrl = cover
        )
    }

    private fun containsIsbn(identifiers: JSONArray?, target: String): Boolean {
        if (identifiers == null) return false
        val wanted = normalizeIsbn(target)
        for (i in 0 until identifiers.length()) {
            val item = identifiers.optJSONObject(i) ?: continue
            val value = normalizeIsbn(item.optString("identifier"))
            if (value == wanted) return true
        }
        return false
    }

    private fun rusNeb(isbn: String): RemoteBook? {
        val normalized = normalizeIsbn(isbn)
        fun catalogIds(html: String): List<String> =
            Regex("""href=[\"'](?:https?://rusneb\.ru)?/catalog/([^/\"'?#]+)/?""", RegexOption.IGNORE_CASE)
                .findAll(html)
                .map { it.groupValues[1] }
                .distinct()
                .take(5)
                .toList()

        val encoded = URLEncoder.encode(normalized, StandardCharsets.UTF_8.toString())
        val exactHtml = getText(
            "https://rusneb.ru/search/?isbn=$encoded&search=Y",
            accept = "text/html,application/xhtml+xml"
        )
        var ids = catalogIds(exactHtml)
        if (ids.isEmpty()) {
            val broadHtml = getText(
                "https://rusneb.ru/search/?q=$encoded",
                accept = "text/html,application/xhtml+xml"
            )
            ids = catalogIds(broadHtml)
        }

        for (id in ids) {
            val marcUrl = "https://rusneb.ru/local/components/exalead/search.page.detail/ajax/marcExport.php?book_id=" +
                URLEncoder.encode(id, StandardCharsets.UTF_8.toString())
            val xml = runCatching { getText(marcUrl, accept = "application/xml,text/xml,*/*") }.getOrNull()
                ?: continue
            val book = runCatching { parseRusNebMarc(xml, normalized) }.getOrNull() ?: continue
            return book
        }
        return null
    }

    private fun parseRusNebMarc(xml: String, expectedIsbn: String): RemoteBook? {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            runCatching { isXIncludeAware = false }
            runCatching { isExpandEntityReferences = false }
        }
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val fields = document.getElementsByTagNameNS("*", "datafield")

        fun subfields(tag: String, code: String): List<String> {
            val result = mutableListOf<String>()
            for (i in 0 until fields.length) {
                val field = fields.item(i) as? Element ?: continue
                if (field.getAttribute("tag") != tag) continue
                val children = field.getElementsByTagNameNS("*", "subfield")
                for (j in 0 until children.length) {
                    val child = children.item(j) as? Element ?: continue
                    if (child.getAttribute("code") == code) {
                        child.textContent?.trim()?.takeIf { it.isNotBlank() }?.let(result::add)
                    }
                }
            }
            return result
        }

        fun firstSub(tag: String, vararg codes: String): String? =
            codes.asSequence().flatMap { subfields(tag, it).asSequence() }.firstOrNull()

        val isbnValues = (subfields("020", "a") + subfields("773", "z") + subfields("776", "z"))
            .flatMap { raw -> Regex("""(?:97[89][\s-]*)?[0-9Xx][0-9Xx\s-]{8,20}[0-9Xx]""").findAll(raw).map { it.value }.toList() }
            .map(::normalizeIsbn)
            .filter { it.length == 10 || it.length == 13 }
            .distinct()
        if (isbnValues.none { it == expectedIsbn }) return null

        val rawTitle = firstSub("245", "a")?.cleanMarcText()
        val subtitle = firstSub("245", "b")?.cleanMarcText()
        val parentTitle = firstSub("773", "t")?.cleanMarcText()
        val title = when {
            !rawTitle.isNullOrBlank() && !parentTitle.isNullOrBlank() && rawTitle.length <= 24 &&
                !parentTitle.contains(rawTitle, ignoreCase = true) -> "$parentTitle. $rawTitle"
            !rawTitle.isNullOrBlank() -> rawTitle
            else -> parentTitle
        }

        val primaryAuthors = (subfields("100", "a") + subfields("110", "a"))
            .map { it.cleanMarcText() }
            .filter { it.isNotBlank() }
        val otherAuthors = subfields("700", "a")
            .map { it.cleanMarcText() }
            .filter { it.isNotBlank() }
        val parentAuthors = subfields("773", "a")
            .map { it.cleanMarcText() }
            .filter { it.isNotBlank() }
        val authors = (primaryAuthors + otherAuthors + if (primaryAuthors.isEmpty()) parentAuthors else emptyList())
            .distinct()
            .take(6)
            .joinToString(", ")
            .takeIf { it.isNotBlank() }

        val publisher = (firstSub("264", "b") ?: firstSub("260", "b") ?: firstSub("773", "d"))
            ?.cleanMarcText()
        val dateRaw = firstSub("264", "c") ?: firstSub("260", "c") ?: firstSub("773", "d").orEmpty()
        val pages = firstSub("300", "a")?.cleanMarcText()
        val categories = (subfields("650", "a") + subfields("653", "a") + subfields("655", "a"))
            .map { it.cleanMarcText() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(12)
            .joinToString(", ")
            .takeIf { it.isNotBlank() }
        val description = firstSub("520", "a")?.cleanMarcText()
        val isbn10 = isbnValues.firstOrNull { it.length == 10 }

        if (title.isNullOrBlank() && authors.isNullOrBlank() && publisher.isNullOrBlank()) return null

        return RemoteBook(
            source = "rusneb",
            isbn10 = isbn10,
            title = title,
            subtitle = subtitle,
            authors = authors,
            publisher = publisher,
            publishedYear = extractYear(dateRaw),
            pages = pages,
            categories = categories,
            description = description,
            coverUrl = null
        )
    }

    private fun String.cleanMarcText(): String =
        trim().trimEnd(' ', '/', ':', ';', ',', '.', '-')

    private fun openLibraryBooksApi(isbn: String): RemoteBook? {
        val bibKey = "ISBN:$isbn"
        val url = "https://openlibrary.org/api/books?bibkeys=" +
            URLEncoder.encode(bibKey, StandardCharsets.UTF_8.toString()) +
            "&jscmd=data&format=json"
        val root = getJson(url)
        val book = root.optJSONObject(bibKey) ?: return null

        val identifiers = book.optJSONObject("identifiers")
        val isbn10 = first(identifiers?.optJSONArray("isbn_10"))
        val authors = joinObjectNames(book.optJSONArray("authors"))
        val publishers = joinObjectNames(book.optJSONArray("publishers"))
        val subjects = joinObjectNames(book.optJSONArray("subjects"))
        val cover = book.optJSONObject("cover")
        val coverUrl = firstNonBlank(
            cover?.optString("large")?.takeIf { it.isNotBlank() },
            firstNonBlank(
                cover?.optString("medium")?.takeIf { it.isNotBlank() },
                cover?.optString("small")?.takeIf { it.isNotBlank() }
            )
        )?.replace("http://", "https://")

        return RemoteBook(
            source = "open_library",
            isbn10 = isbn10,
            title = book.optString("title").takeIf { it.isNotBlank() },
            subtitle = book.optString("subtitle").takeIf { it.isNotBlank() },
            authors = authors,
            publisher = publishers,
            publishedYear = extractYear(book.optString("publish_date")),
            pages = book.optInt("number_of_pages", 0).takeIf { it > 0 }?.toString(),
            categories = subjects,
            description = description(book.opt("description")),
            coverUrl = coverUrl
        )
    }

    private fun openLibrarySearch(isbn: String): RemoteBook? {
        val query = URLEncoder.encode("isbn:$isbn", StandardCharsets.UTF_8.toString())
        val fields = listOf(
            "title", "subtitle", "author_name", "first_publish_year", "publish_year",
            "publisher", "number_of_pages_median", "subject", "cover_i", "isbn"
        ).joinToString(",")
        val url = "https://openlibrary.org/search.json?q=$query&limit=10&fields=" +
            URLEncoder.encode(fields, StandardCharsets.UTF_8.toString())
        val root = getJson(url)
        val docs = root.optJSONArray("docs") ?: return null

        for (i in 0 until docs.length()) {
            val doc = docs.optJSONObject(i) ?: continue
            val isbnArray = doc.optJSONArray("isbn")
            if (!containsStringIsbn(isbnArray, isbn)) continue

            var isbn10: String? = null
            if (isbnArray != null) {
                for (j in 0 until isbnArray.length()) {
                    val value = normalizeIsbn(isbnArray.optString(j))
                    if (value.length == 10) {
                        isbn10 = value
                        break
                    }
                }
            }

            val coverId = doc.optLong("cover_i", 0)
            val coverUrl = if (coverId > 0) {
                "https://covers.openlibrary.org/b/id/$coverId-L.jpg"
            } else {
                null
            }

            val publishYear = when {
                doc.optInt("first_publish_year", 0) > 0 -> doc.optInt("first_publish_year").toString()
                else -> first(doc.optJSONArray("publish_year"))
            }

            return RemoteBook(
                source = "open_library_search",
                isbn10 = isbn10,
                title = doc.optString("title").takeIf { it.isNotBlank() },
                subtitle = doc.optString("subtitle").takeIf { it.isNotBlank() },
                authors = join(doc.optJSONArray("author_name")),
                publisher = first(doc.optJSONArray("publisher")),
                publishedYear = publishYear,
                pages = doc.optInt("number_of_pages_median", 0).takeIf { it > 0 }?.toString(),
                categories = join(doc.optJSONArray("subject")),
                coverUrl = coverUrl
            )
        }
        return null
    }

    private fun containsStringIsbn(values: JSONArray?, target: String): Boolean {
        if (values == null) return false
        val wanted = normalizeIsbn(target)
        for (i in 0 until values.length()) {
            if (normalizeIsbn(values.optString(i)) == wanted) return true
        }
        return false
    }

    private fun openLibraryCoverUrl(isbn: String): String =
        "https://covers.openlibrary.org/b/isbn/${normalizeIsbn(isbn)}-L.jpg?default=false"

    private fun getJson(url: String): JSONObject = JSONObject(getText(url, accept = "application/json"))

    private fun getText(url: String, accept: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", accept)
        connection.setRequestProperty("Accept-Language", "ru,en;q=0.8")
        connection.setRequestProperty(
            "User-Agent",
            if (url.contains("rusneb.ru")) {
                "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0 Mobile Safari/537.36"
            } else {
                "BookShelf/1.0.4 (Android; personal library app)"
            }
        )
        if (url.contains("rusneb.ru")) {
            connection.setRequestProperty("Referer", "https://rusneb.ru/")
        }

        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.let {
                BufferedInputStream(it).bufferedReader().use { reader -> reader.readText() }
            }.orEmpty()
            if (code !in 200..299) {
                error("HTTP $code${text.takeIf { it.isNotBlank() }?.let { ": ${it.take(180)}" }.orEmpty()}")
            }
            text
        } finally {
            connection.disconnect()
        }
    }

    private fun normalizeIsbn(value: String): String =
        value.uppercase().filter { it.isDigit() || it == 'X' }

    private fun join(array: JSONArray?): String? {
        if (array == null || array.length() == 0) return null
        val values = buildList {
            for (i in 0 until array.length()) {
                array.optString(i).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
        return values.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    private fun joinObjectNames(array: JSONArray?): String? {
        if (array == null || array.length() == 0) return null
        val values = buildList {
            for (i in 0 until array.length()) {
                val value = when (val item = array.opt(i)) {
                    is JSONObject -> item.optString("name").takeIf { it.isNotBlank() }
                    is String -> item.takeIf { it.isNotBlank() }
                    else -> null
                }
                value?.let(::add)
            }
        }
        return values.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    private fun first(array: JSONArray?): String? =
        if (array != null && array.length() > 0) array.optString(0).takeIf { it.isNotBlank() } else null

    private fun description(value: Any?): String? = when (value) {
        is String -> value.takeIf { it.isNotBlank() }
        is JSONObject -> value.optString("value").takeIf { it.isNotBlank() }
        else -> null
    }

    private fun extractYear(raw: String): String? =
        Regex("""\b(1[5-9]\d{2}|20\d{2}|21\d{2})\b""").find(raw)?.value

    private fun firstNonBlank(a: String?, b: String?): String? =
        a?.takeIf { it.isNotBlank() } ?: b?.takeIf { it.isNotBlank() }

    private fun classify(categories: String): PublicationType {
        val c = categories.lowercase()
        return when {
            "manga" in c || "манга" in c -> PublicationType.MANGA
            "graphic novel" in c || "графический роман" in c -> PublicationType.GRAPHIC_NOVEL
            "comic" in c || "комикс" in c -> PublicationType.COMIC
            "artbook" in c || "артбук" in c -> PublicationType.ARTBOOK
            "textbook" in c || "учебник" in c || "education" in c -> PublicationType.TEXTBOOK
            "juvenile" in c || "children" in c || "детск" in c -> PublicationType.CHILDRENS_BOOK
            "magazine" in c || "periodical" in c || "журнал" in c -> PublicationType.MAGAZINE
            else -> PublicationType.BOOK
        }
    }

    private data class RemoteBook(
        val source: String,
        val isbn10: String? = null,
        val title: String? = null,
        val subtitle: String? = null,
        val authors: String? = null,
        val publisher: String? = null,
        val publishedYear: String? = null,
        val pages: String? = null,
        val categories: String? = null,
        val description: String? = null,
        val coverUrl: String? = null
    ) {
        fun isIncomplete(): Boolean =
            title.isNullOrBlank() || authors.isNullOrBlank() || coverUrl.isNullOrBlank()
    }
}
