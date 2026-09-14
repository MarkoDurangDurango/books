package com.bookshelf.app.data

import android.content.Context
import com.bookshelf.app.BuildConfig
import com.bookshelf.app.domain.BookDraft
import com.bookshelf.app.domain.BookLookupResult
import com.bookshelf.app.domain.MetadataDiagnostic
import com.bookshelf.app.domain.PublicationType
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import java.io.StringReader
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

class MetadataService(private val context: Context) {
    private val prefs = context.getSharedPreferences("bookshelf_settings", Context.MODE_PRIVATE)

    fun resolverUrl(): String = prefs.getString("resolver_url", null)
        ?.trim()
        ?.trimEnd('/')
        ?.takeIf { it.isNotBlank() }
        ?: BuildConfig.BOOKSHELF_RESOLVER_URL.trim().trimEnd('/')

    fun setResolverUrl(value: String) {
        prefs.edit().putString("resolver_url", value.trim().trimEnd('/')).apply()
    }

    fun testResolver(): String {
        val base = resolverUrl()
        require(base.isNotBlank()) { "Resolver URL не указан" }
        val root = JSONObject(requestResolver("$base/health", "GET", null))
        require(root.optBoolean("ok", false)) { "Resolver ответил некорректно" }
        return root.optString("version", "unknown")
    }

    suspend fun lookupDetailed(isbn13: String): BookLookupResult {
        val diagnostics = mutableListOf<MetadataDiagnostic>()
        val base = resolverUrl()
        if (base.isNotBlank()) {
            runCatching { resolverIsbn(base, isbn13) }
                .onSuccess { result ->
                    diagnostics += result.diagnostics
                    if (result.draft.title.isNotBlank()) {
                        return result.copy(diagnostics = diagnostics, resolverUsed = true)
                    }
                }
                .onFailure { error ->
                    diagnostics += MetadataDiagnostic("resolver", "ERROR", error.message.orEmpty().take(180))
                }
        }

        val local = lookupLocal(isbn13)
        if (local != null) {
            diagnostics += MetadataDiagnostic(local.metadataSource, "FOUND", "direct fallback")
            return BookLookupResult(local, diagnostics, resolverUsed = false)
        }
        if (base.isBlank()) diagnostics += MetadataDiagnostic("resolver", "NOT_CONFIGURED", "Укажите URL resolver в настройках")
        return BookLookupResult(BookDraft(isbn13 = isbn13, metadataSource = "not_found"), diagnostics, resolverUsed = false)
    }

    suspend fun lookupByCover(jpegBytes: ByteArray): BookLookupResult {
        val base = resolverUrl()
        if (base.isBlank()) {
            return BookLookupResult(
                BookDraft(metadataSource = "cover_resolver_not_configured"),
                listOf(MetadataDiagnostic("resolver", "NOT_CONFIGURED", "Укажите URL resolver в настройках"))
            )
        }
        return resolverCover(base, jpegBytes).copy(resolverUsed = true)
    }

    private suspend fun lookupLocal(isbn13: String): BookDraft? {
        val initial = coroutineScope {
            val nlr = async { runCatching { nationalLibraryRussia(isbn13) }.getOrNull() }
            val google = async { runCatching { googleBooks(isbn13, exactIsbnQuery = true) }.getOrNull() }
            val neb = async { runCatching { rusNeb(isbn13) }.getOrNull() }
            val open = async { runCatching { openLibraryBooksApi(isbn13) }.getOrNull() }
            listOf(nlr.await(), google.await(), neb.await(), open.await())
        }
        val nlr = initial[0]
        val googleExact = initial[1]
        val rusNeb = initial[2]
        val openExact = initial[3]

        val needFallback = listOfNotNull(nlr, googleExact, rusNeb, openExact).none { !it.isIncomplete() }

        val fallback = coroutineScope {
            val google = async {
                if (googleExact == null || googleExact.isIncomplete()) {
                    runCatching { googleBooks(isbn13, exactIsbnQuery = false) }.getOrNull()
                } else null
            }
            val open = async {
                if (needFallback) runCatching { openLibrarySearch(isbn13) }.getOrNull() else null
            }
            google.await() to open.await()
        }
        val googleBroad = fallback.first
        val openSearch = fallback.second

        val candidates = listOfNotNull(nlr, googleExact, rusNeb, openExact, googleBroad, openSearch)
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

    private fun resolverIsbn(base: String, isbn: String): BookLookupResult {
        val url = "$base/v1/books/isbn/${normalizeIsbn(isbn)}"
        return parseResolverResponse(requestResolver(url, "GET", null))
    }

    private fun resolverCover(base: String, jpegBytes: ByteArray): BookLookupResult {
        val url = "$base/v1/books/cover"
        return parseResolverResponse(requestResolver(url, "POST", jpegBytes))
    }

    private fun requestResolver(url: String, method: String, body: ByteArray?): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 12_000
        connection.readTimeout = 30_000
        connection.requestMethod = method
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "BookShelf-Android/1.1.0")
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "image/jpeg")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("Resolver HTTP $code: ${text.take(180)}")
            text
        } finally {
            connection.disconnect()
        }
    }

    private fun parseResolverResponse(text: String): BookLookupResult {
        val root = JSONObject(text)
        val diagnostics = buildList {
            val items = root.optJSONArray("diagnostics")
            if (items != null) {
                for (i in 0 until items.length()) {
                    val d = items.optJSONObject(i) ?: continue
                    add(
                        MetadataDiagnostic(
                            source = d.optString("source", "unknown"),
                            status = d.optString("status", "UNKNOWN"),
                            detail = d.optString("detail", "")
                        )
                    )
                }
            }
        }
        val book = root.optJSONObject("book")
        if (book == null || !root.optBoolean("found", false)) {
            return BookLookupResult(
                draft = BookDraft(
                    isbn13 = root.optString("isbn13"),
                    metadataSource = "not_found"
                ),
                diagnostics = diagnostics,
                resolverUsed = true,
                matchConfidence = root.optDouble("matchConfidence").takeIf { !it.isNaN() }
            )
        }
        val publicationType = PublicationType.entries.firstOrNull {
            it.name == book.optString("publicationType")
        } ?: PublicationType.BOOK
        return BookLookupResult(
            draft = BookDraft(
                isbn13 = book.optString("isbn13"),
                isbn10 = book.optString("isbn10").takeIf { it.isNotBlank() },
                title = book.optString("title"),
                subtitle = book.optString("subtitle"),
                authors = book.optString("authors"),
                publisher = book.optString("publisher"),
                publishedYear = book.optString("publishedYear"),
                pages = book.optString("pages"),
                publicationType = publicationType,
                categories = book.optString("categories"),
                description = book.optString("description"),
                coverRemoteUrl = book.optString("coverUrl").takeIf { it.isNotBlank() },
                metadataSource = book.optString("metadataSource", "resolver")
            ),
            diagnostics = diagnostics,
            resolverUsed = true,
            matchConfidence = root.optDouble("matchConfidence").takeIf { !it.isNaN() }
        )
    }

    private fun nationalLibraryRussia(isbn: String): RemoteBook? {
        val wanted = normalizeIsbn(isbn)
        val query = URLEncoder.encode(wanted, StandardCharsets.UTF_8.toString())
        val searchHtml = getText(
            "https://nb.nlr.ru/opac-search.pl?q=$query",
            accept = "text/html,application/xhtml+xml"
        )
        val ids = Regex(
            """(?:opac-detail|opac-MARCdetail)\.pl\?(?:[^\"'<>]*?(?:&|&amp;))*biblionumber=(\d+)""",
            RegexOption.IGNORE_CASE
        ).findAll(searchHtml)
            .map { it.groupValues[1] }
            .distinct()
            .take(6)
            .toList()

        for (id in ids) {
            val marc = runCatching {
                getText("https://nb.nlr.ru/opac-MARCdetail.pl?biblionumber=$id", "text/html,application/xhtml+xml")
            }.getOrNull() ?: continue
            val detail = runCatching {
                getText("https://nb.nlr.ru/opac-detail.pl?biblionumber=$id", "text/html,application/xhtml+xml")
            }.getOrNull().orEmpty()
            parseNlrPages(marc, detail, wanted)?.let { return it }
        }
        return null
    }

    private fun parseNlrPages(marcHtml: String, detailHtml: String, expectedIsbn: String): RemoteBook? {
        val marcText = htmlToText(marcHtml)
        val detailText = htmlToText(detailHtml)
        val combined = "$detailText\n$marcText"
        val isbns = extractIsbns(combined)
        if (expectedIsbn !in isbns) return null

        var title = Regex("""<title[^>]*>([\s\S]*?)</title>""", RegexOption.IGNORE_CASE)
            .find(detailHtml)?.groupValues?.getOrNull(1)
            ?.let(::htmlToText)
            .orEmpty()
            .replace(Regex("""^Подробности\s*:\s*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*[›|-]\s*Национальная библиография каталог.*$""", RegexOption.IGNORE_CASE), "")
            .trim()
        if (title.isBlank()) title = fieldAfter(marcText, listOf("Основное заглавие", "Заглавие", "Собственно заглавие")).orEmpty()

        val authors = listOfNotNull(
            fieldAfter(marcText, listOf("Первые сведения об ответственности", "Сведения об ответственности")),
            fieldAfter(marcText, listOf("Фамилия"))
        ).distinct().joinToString(", ")
        val publisher = fieldAfter(
            marcText,
            listOf("Имя издателя, распространителя", "Издательство, распространитель", "Издательство")
        )
        val year = extractYear(
            fieldAfter(marcText, listOf("Дата издания, распространения и т.д.", "Дата публикации", "Год издания"))
                ?: combined
        )
        val pages = fieldAfter(
            marcText,
            listOf("Специфическое обозначение материала и объем", "Физическое описание", "Объем")
        )?.let { Regex("""\b(\d{1,5})\b""").find(it)?.groupValues?.get(1) }
        val categories = listOfNotNull(
            fieldAfter(marcText, listOf("Тематический термин")),
            fieldAfter(marcText, listOf("Предметная рубрика"))
        ).distinct().joinToString(", ")
        val cover = Regex("""https://vivaldi\.nlr\.ru/[^\s<>\"']+/cover""", RegexOption.IGNORE_CASE)
            .find(combined)?.value

        if (title.isBlank()) return null
        return RemoteBook(
            source = "nlr_national_bibliography",
            isbn10 = isbns.firstOrNull { it.length == 10 },
            title = title,
            authors = authors.takeIf { it.isNotBlank() },
            publisher = publisher,
            publishedYear = year,
            pages = pages,
            categories = categories.takeIf { it.isNotBlank() },
            coverUrl = cover
        )
    }

    private fun htmlToText(html: String): String = html
        .replace(Regex("""<script\b[^>]*>[\s\S]*?</script>""", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("""<style\b[^>]*>[\s\S]*?</style>""", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("""</(?:tr|td|th|div|p|li|h\d|section|article)>""", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("""<[^>]+>"""), " ")
        .replace("&nbsp;", " ", ignoreCase = true)
        .replace("&amp;", "&", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&#39;", "'", ignoreCase = true)
        .replace("&lt;", "<", ignoreCase = true)
        .replace("&gt;", ">", ignoreCase = true)
        .replace(Regex("""[ \t]+"""), " ")
        .replace(Regex("""\n[ \t]+"""), "\n")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()

    private fun fieldAfter(text: String, labels: List<String>): String? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        for ((index, line) in lines.withIndex()) {
            for (label in labels) {
                val pos = line.indexOf(label, ignoreCase = true)
                if (pos < 0) continue
                val inline = line.substring(pos + label.length)
                    .replace(Regex("""^[\s|:\-–—]+"""), "")
                    .trim()
                if (inline.isNotBlank() && !looksLikeFieldHeader(inline)) return inline
                for (j in index + 1..minOf(index + 3, lines.lastIndex)) {
                    val value = lines[j].replace(Regex("""^[\s|:\-–—]+"""), "").trim()
                    if (value.isNotBlank() && !looksLikeFieldHeader(value)) return value
                }
            }
        }
        return null
    }

    private fun looksLikeFieldHeader(value: String): Boolean =
        Regex("""^(\d{3}\s*[#0-9A-Za-z]{0,2}\s*[-–—]|[-–—]+$|[A-ZА-ЯЁ0-9 ()/.-]{18,}$)""").containsMatchIn(value)

    private fun extractIsbns(text: String): List<String> =
        Regex("""(?:97[89][\s-]*)?[0-9Xx][0-9Xx\s-]{8,20}[0-9Xx]""")
            .findAll(text)
            .map { normalizeIsbn(it.value) }
            .filter { it.length == 10 || it.length == 13 }
            .distinct()
            .toList()

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
            if (url.contains("rusneb.ru") || url.contains("nb.nlr.ru")) {
                "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0 Mobile Safari/537.36"
            } else {
                "BookShelf/1.1.0 (Android; personal library app)"
            }
        )
        if (url.contains("rusneb.ru")) {
            connection.setRequestProperty("Referer", "https://rusneb.ru/")
        }
        if (url.contains("nb.nlr.ru")) {
            connection.setRequestProperty("Referer", "https://nb.nlr.ru/")
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
