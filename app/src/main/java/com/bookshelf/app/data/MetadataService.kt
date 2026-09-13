package com.bookshelf.app.data

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
        val google = runCatching { googleBooks(isbn13) }.getOrNull()
        val needsFallback = google == null ||
            google.title.isNullOrBlank() ||
            google.authors.isNullOrBlank() ||
            google.publishedYear.isNullOrBlank() ||
            google.coverUrl.isNullOrBlank()
        val open = if (needsFallback) {
            runCatching { openLibrary(isbn13) }.getOrNull()
        } else {
            null
        }

        if (google == null && open == null) return null

        val categories = firstNonBlank(google?.categories, open?.categories).orEmpty()
        return BookDraft(
            isbn13 = isbn13,
            isbn10 = firstNonBlank(google?.isbn10, open?.isbn10),
            title = firstNonBlank(google?.title, open?.title).orEmpty(),
            subtitle = firstNonBlank(google?.subtitle, open?.subtitle).orEmpty(),
            authors = firstNonBlank(google?.authors, open?.authors).orEmpty(),
            publisher = firstNonBlank(google?.publisher, open?.publisher).orEmpty(),
            publishedYear = firstNonBlank(google?.publishedYear, open?.publishedYear).orEmpty(),
            pages = firstNonBlank(google?.pages, open?.pages).orEmpty(),
            publicationType = classify(categories),
            categories = categories,
            description = firstNonBlank(google?.description, open?.description).orEmpty(),
            coverRemoteUrl = firstNonBlank(google?.coverUrl, open?.coverUrl),
            metadataSource = when {
                google != null && open != null -> "google_books+open_library"
                google != null -> "google_books"
                else -> "open_library"
            }
        )
    }

    private fun googleBooks(isbn: String): RemoteBook? {
        val url = "https://www.googleapis.com/books/v1/volumes?q=" +
            URLEncoder.encode("isbn:$isbn", StandardCharsets.UTF_8.toString()) +
            "&maxResults=1"
        val root = getJson(url)
        val items = root.optJSONArray("items") ?: return null
        if (items.length() == 0) return null
        val volume = items.getJSONObject(0).optJSONObject("volumeInfo") ?: return null

        val ids = volume.optJSONArray("industryIdentifiers")
        var isbn10: String? = null
        if (ids != null) {
            for (i in 0 until ids.length()) {
                val item = ids.optJSONObject(i) ?: continue
                if (item.optString("type") == "ISBN_10") isbn10 = item.optString("identifier")
            }
        }

        val imageLinks = volume.optJSONObject("imageLinks")
        val cover = imageLinks?.optString("thumbnail")
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

    private fun openLibrary(isbn: String): RemoteBook? {
        val fields = "title,author_name,first_publish_year,publish_year,publisher,number_of_pages_median,subject,cover_i,isbn"
        val url = "https://openlibrary.org/search.json?isbn=$isbn&limit=1&fields=$fields"
        val root = getJson(url)
        val docs = root.optJSONArray("docs") ?: return null
        if (docs.length() == 0) return null
        val doc = docs.getJSONObject(0)

        val isbnArray = doc.optJSONArray("isbn")
        var isbn10: String? = null
        if (isbnArray != null) {
            for (i in 0 until isbnArray.length()) {
                val v = isbnArray.optString(i)
                if (v.length == 10) {
                    isbn10 = v
                    break
                }
            }
        }

        val coverId = doc.optLong("cover_i", 0)
        val coverUrl = if (coverId > 0) {
            "https://covers.openlibrary.org/b/id/$coverId-L.jpg"
        } else {
            "https://covers.openlibrary.org/b/isbn/$isbn-L.jpg?default=false"
        }

        return RemoteBook(
            isbn10 = isbn10,
            title = doc.optString("title").takeIf { it.isNotBlank() },
            authors = join(doc.optJSONArray("author_name")),
            publisher = first(doc.optJSONArray("publisher")),
            publishedYear = when {
                doc.has("first_publish_year") -> doc.optInt("first_publish_year").takeIf { it > 0 }?.toString()
                else -> first(doc.optJSONArray("publish_year"))
            },
            pages = doc.optInt("number_of_pages_median", 0).takeIf { it > 0 }?.toString(),
            categories = join(doc.optJSONArray("subject")),
            coverUrl = coverUrl
        )
    }

    private fun getJson(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 7000
        connection.readTimeout = 7000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "BookShelf-Android/1.0")

        return try {
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code")
            val text = BufferedInputStream(connection.inputStream).bufferedReader().use { it.readText() }
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

    private fun first(array: JSONArray?): String? =
        if (array != null && array.length() > 0) array.optString(0).takeIf { it.isNotBlank() } else null

    private fun extractYear(raw: String): String? =
        Regex("""\b(1[5-9]\d{2}|20\d{2}|21\d{2})\b""").find(raw)?.value

    private fun firstNonBlank(a: String?, b: String?): String? =
        a?.takeIf { it.isNotBlank() } ?: b?.takeIf { it.isNotBlank() }

    private fun classify(categories: String): PublicationType {
        val c = categories.lowercase()
        return when {
            "manga" in c -> PublicationType.MANGA
            "graphic novel" in c -> PublicationType.GRAPHIC_NOVEL
            "comic" in c -> PublicationType.COMIC
            "art" in c && ("book" in c || "design" in c) -> PublicationType.ARTBOOK
            "textbook" in c || "study" in c || "education" in c -> PublicationType.TEXTBOOK
            "juvenile" in c || "children" in c -> PublicationType.CHILDRENS_BOOK
            "magazine" in c || "periodical" in c -> PublicationType.MAGAZINE
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
    )
}
