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

class MetadataService {
    suspend fun lookup(isbn13: String): BookDraft? {
        // Open Library does not require an API key, so it is the reliable baseline.
        val openExact = runCatching { openLibraryBooksApi(isbn13) }.getOrNull()
        val openSearch = if (openExact == null || openExact.isIncomplete()) {
            runCatching { openLibrarySearch(isbn13) }.getOrNull()
        } else {
            null
        }

        // Google Books requires an application identifier (API key) for public data.
        // It is optional here and can be supplied through GOOGLE_BOOKS_API_KEY.
        val google = BuildConfig.GOOGLE_BOOKS_API_KEY
            .takeIf { it.isNotBlank() }
            ?.let { key -> runCatching { googleBooks(isbn13, key) }.getOrNull() }

        val candidates = listOfNotNull(openExact, google, openSearch)
        if (candidates.isEmpty()) return null

        fun pick(selector: (RemoteBook) -> String?): String? =
            candidates.asSequence().mapNotNull(selector).firstOrNull { it.isNotBlank() }

        val categories = pick { it.categories }.orEmpty()
        val sources = buildList {
            if (openExact != null) add("open_library")
            if (openSearch != null) add("open_library_search")
            if (google != null) add("google_books")
        }.distinct().joinToString("+")

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
            coverRemoteUrl = pick { it.coverUrl },
            metadataSource = sources.ifBlank { "not_found" }
        )
    }

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
        val url = "https://openlibrary.org/search.json?q=$query&limit=1&fields=" +
            URLEncoder.encode(fields, StandardCharsets.UTF_8.toString())
        val root = getJson(url)
        val docs = root.optJSONArray("docs") ?: return null
        if (docs.length() == 0) return null
        val doc = docs.optJSONObject(0) ?: return null

        val isbnArray = doc.optJSONArray("isbn")
        var isbn10: String? = null
        if (isbnArray != null) {
            for (i in 0 until isbnArray.length()) {
                val value = isbnArray.optString(i)
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

    private fun googleBooks(isbn: String, apiKey: String): RemoteBook? {
        val query = URLEncoder.encode("isbn:$isbn", StandardCharsets.UTF_8.toString())
        val key = URLEncoder.encode(apiKey, StandardCharsets.UTF_8.toString())
        val url = "https://www.googleapis.com/books/v1/volumes?q=$query&maxResults=1&key=$key"
        val root = getJson(url)
        val items = root.optJSONArray("items") ?: return null
        if (items.length() == 0) return null
        val volume = items.optJSONObject(0)?.optJSONObject("volumeInfo") ?: return null

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

        return RemoteBook(
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

    private fun getJson(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Accept-Language", "ru,en;q=0.8")
        connection.setRequestProperty("User-Agent", "BookShelf/1.0.3 (Android; personal library app)")

        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.let { BufferedInputStream(it).bufferedReader().use { reader -> reader.readText() } }.orEmpty()
            if (code !in 200..299) error("HTTP $code${text.takeIf { it.isNotBlank() }?.let { ": ${it.take(180)}" }.orEmpty()}")
            JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

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
        fun isIncomplete(): Boolean = title.isNullOrBlank() || authors.isNullOrBlank() || coverUrl.isNullOrBlank()
    }
}
