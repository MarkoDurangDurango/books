package com.bookshelf.app.data

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.net.Uri
import com.bookshelf.app.domain.BookCondition
import com.bookshelf.app.domain.BookDraft
import com.bookshelf.app.domain.PublicationType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class AppContainer(context: Context) {
    private val db = BookShelfDatabase.get(context)
    val repository = BookRepository(context.applicationContext, db.bookDao(), MetadataService())
}

class BookRepository(
    private val context: Context,
    private val dao: BookDao,
    private val metadata: MetadataService
) {
    val shelf: Flow<List<BookWithEdition>> = dao.observeShelf()

    suspend fun lookup(isbn13: String): BookDraft = withContext(Dispatchers.IO) {
        val existing = dao.getEditionByIsbn(isbn13)
        if (existing != null) {
            return@withContext existing.toDraft(existingCopies = dao.getCopyCount(existing.id))
        }

        val remote = metadata.lookup(isbn13) ?: BookDraft(isbn13 = isbn13)
        val localCover = remote.coverRemoteUrl?.let { downloadCover(isbn13, it) }
        remote.copy(coverLocalPath = localCover)
    }

    suspend fun getDraft(copyId: Long): BookDraft? = withContext(Dispatchers.IO) {
        val book = dao.getBook(copyId) ?: return@withContext null
        book.edition.toDraft(
            copyId = book.copy.id,
            condition = enumOrDefault<BookCondition>(book.copy.condition, BookCondition.NOT_SET),
            notes = book.copy.notes,
            existingCopies = dao.getCopyCount(book.edition.id)
        )
    }

    suspend fun save(draft: BookDraft): Long = withContext(Dispatchers.IO) {
        val isbn = draft.isbn13.trim()
        var edition = when {
            draft.editionId != null -> dao.getEditionByIsbn(isbn)
            isbn.isNotBlank() -> dao.getEditionByIsbn(isbn)
            else -> null
        }

        val localCover = when {
            draft.coverLocalPath?.let(::File)?.exists() == true -> draft.coverLocalPath
            !draft.coverRemoteUrl.isNullOrBlank() && isbn.isNotBlank() -> downloadCover(isbn, draft.coverRemoteUrl)
            else -> draft.coverLocalPath
        }

        if (edition == null) {
            val newEdition = draft.toEntity(localCover)
            val id = dao.insertEdition(newEdition)
            edition = newEdition.copy(id = id)
        } else {
            val updated = draft.toEntity(localCover).copy(id = edition.id)
            dao.updateEdition(updated)
            edition = updated
        }

        if (draft.copyId != null) {
            dao.updateCopy(
                BookCopyEntity(
                    id = draft.copyId,
                    editionId = edition.id,
                    condition = draft.condition.name,
                    notes = draft.notes,
                    addedAt = dao.getBook(draft.copyId)?.copy?.addedAt ?: System.currentTimeMillis()
                )
            )
            draft.copyId
        } else {
            dao.insertCopy(
                BookCopyEntity(
                    editionId = edition.id,
                    condition = draft.condition.name,
                    notes = draft.notes
                )
            )
        }
    }

    suspend fun delete(copyId: Long) = withContext(Dispatchers.IO) {
        dao.getBook(copyId)?.let { dao.deleteCopy(it.copy) }
    }

    suspend fun snapshot(): List<BookSnapshotRow> = withContext(Dispatchers.IO) { dao.snapshot() }

    suspend fun exportJson(uri: Uri) = withContext(Dispatchers.IO) {
        val array = rowsToJson(dao.snapshot())
        context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
            it.write(JSONObject().put("version", 1).put("books", array).toString(2))
        } ?: error("Не удалось открыть файл")
    }

    suspend fun exportCsv(uri: Uri) = withContext(Dispatchers.IO) {
        val rows = dao.snapshot()
        context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { w ->
            w.appendLine("ISBN-13;Название;Автор;Издательство;Год;Тип;Состояние;Заметка")
            rows.forEach { r ->
                w.appendLine(
                    listOf(
                        r.isbn13, r.title, r.authors, r.publisher.orEmpty(),
                        r.publishedYear?.toString().orEmpty(), r.publicationType,
                        r.condition, r.notes
                    ).joinToString(";") { csv(it) }
                )
            }
        } ?: error("Не удалось открыть файл")
    }

    suspend fun exportPdf(uri: Uri) = withContext(Dispatchers.IO) {
        val rows = dao.snapshot()
        val doc = PdfDocument()
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.rgb(32, 36, 33)
        }
        val secondary = android.graphics.Paint(paint).apply {
            color = android.graphics.Color.rgb(90, 96, 92)
        }

        var pageNo = 1
        var index = 0
        while (index < rows.size || (rows.isEmpty() && pageNo == 1)) {
            val pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNo).create()
            val page = doc.startPage(pageInfo)
            val canvas = page.canvas

            paint.textSize = 26f
            paint.isFakeBoldText = true
            canvas.drawText("BookShelf", 42f, 58f, paint)
            secondary.textSize = 12f
            secondary.isFakeBoldText = false
            canvas.drawText("Моя книжная полка • ${rows.size} экз.", 42f, 82f, secondary)

            var y = 126f
            repeat(6) {
                if (index >= rows.size) return@repeat
                val row = rows[index]
                val coverFile = row.coverLocalPath?.let(::File)?.takeIf(File::exists)
                if (coverFile != null) {
                    BitmapFactory.decodeFile(coverFile.path)?.let { bmp ->
                        val src = android.graphics.Rect(0, 0, bmp.width, bmp.height)
                        val dst = android.graphics.RectF(42f, y, 112f, y + 102f)
                        canvas.drawBitmap(bmp, src, dst, null)
                    }
                } else {
                    val p = android.graphics.Paint().apply { color = android.graphics.Color.rgb(236, 233, 225) }
                    canvas.drawRoundRect(42f, y, 112f, y + 102f, 8f, 8f, p)
                }

                paint.textSize = 14f
                paint.isFakeBoldText = true
                drawEllipsized(canvas, row.title, 130f, y + 20f, 410f, paint)
                secondary.textSize = 11f
                drawEllipsized(canvas, row.authors.ifBlank { "Автор не указан" }, 130f, y + 40f, 410f, secondary)
                val meta = listOfNotNull(
                    row.publisher?.takeIf(String::isNotBlank),
                    row.publishedYear?.toString(),
                    row.isbn13.takeIf(String::isNotBlank)
                ).joinToString(" • ")
                drawEllipsized(canvas, meta, 130f, y + 60f, 410f, secondary)
                val condition = enumOrDefault<BookCondition>(row.condition, BookCondition.NOT_SET).label
                drawEllipsized(canvas, "Состояние: $condition", 130f, y + 80f, 410f, secondary)

                y += 116f
                index++
            }

            secondary.textSize = 9f
            canvas.drawText("Страница $pageNo", 42f, 815f, secondary)
            doc.finishPage(page)
            pageNo++
            if (rows.isEmpty()) break
        }

        context.contentResolver.openOutputStream(uri)?.use { doc.writeTo(it) }
            ?: error("Не удалось открыть файл")
        doc.close()
    }

    suspend fun createBackup(uri: Uri) = withContext(Dispatchers.IO) {
        val rows = dao.snapshot()
        val out = context.contentResolver.openOutputStream(uri) ?: error("Не удалось открыть файл")
        ZipOutputStream(out.buffered()).use { zip ->
            val manifest = JSONObject()
                .put("formatVersion", 1)
                .put("createdAt", System.currentTimeMillis())
                .put("app", "BookShelf")
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toString(2).toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("books.json"))
            zip.write(JSONObject().put("books", rowsToJson(rows)).toString(2).toByteArray())
            zip.closeEntry()

            rows.mapNotNull { it.coverLocalPath }.distinct().forEach { path ->
                val file = File(path)
                if (file.exists()) {
                    zip.putNextEntry(ZipEntry("covers/${file.name}"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    suspend fun restoreBackup(uri: Uri) = withContext(Dispatchers.IO) {
        val coversDir = File(context.filesDir, "covers").apply { mkdirs() }
        var booksText: String? = null

        val input = context.contentResolver.openInputStream(uri) ?: error("Не удалось открыть backup")
        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                when {
                    entry.name == "books.json" -> booksText = zip.readBytes().toString(Charsets.UTF_8)
                    entry.name.startsWith("covers/") && !entry.isDirectory -> {
                        val name = File(entry.name).name
                        File(coversDir, name).outputStream().use { zip.copyTo(it) }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        val json = JSONObject(booksText ?: error("В backup нет books.json"))
        val array = json.getJSONArray("books")

        dao.clearCopies()
        dao.clearEditions()

        val editionIds = mutableMapOf<String, Long>()
        for (i in 0 until array.length()) {
            val b = array.getJSONObject(i)
            val isbn = b.optString("isbn13")
            val editionId = editionIds[isbn] ?: run {
                val coverName = b.optString("coverFile").takeIf { it.isNotBlank() }
                val coverPath = coverName?.let { File(coversDir, it).absolutePath }
                val id = dao.insertEdition(
                    EditionEntity(
                        isbn13 = isbn,
                        isbn10 = b.optNullable("isbn10"),
                        title = b.optString("title"),
                        subtitle = b.optNullable("subtitle"),
                        authors = b.optString("authors"),
                        publisher = b.optNullable("publisher"),
                        publishedYear = b.optIntOrNull("publishedYear"),
                        pages = b.optIntOrNull("pages"),
                        publicationType = b.optString("publicationType", PublicationType.BOOK.name),
                        categories = b.optString("categories"),
                        description = b.optNullable("description"),
                        coverLocalPath = coverPath,
                        coverRemoteUrl = b.optNullable("coverRemoteUrl"),
                        metadataSource = b.optString("metadataSource", "backup")
                    )
                )
                editionIds[isbn] = id
                id
            }

            dao.insertCopy(
                BookCopyEntity(
                    editionId = editionId,
                    condition = b.optString("condition", BookCondition.NOT_SET.name),
                    notes = b.optString("notes"),
                    addedAt = b.optLong("addedAt", System.currentTimeMillis())
                )
            )
        }
    }

    fun suggestedFileName(ext: String): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return "BookShelf_$date.$ext"
    }

    private fun rowsToJson(rows: List<BookSnapshotRow>): JSONArray =
        JSONArray().apply {
            rows.forEach { r ->
                put(
                    JSONObject()
                        .put("isbn13", r.isbn13)
                        .putNullable("isbn10", r.isbn10)
                        .put("title", r.title)
                        .putNullable("subtitle", r.subtitle)
                        .put("authors", r.authors)
                        .putNullable("publisher", r.publisher)
                        .putNullable("publishedYear", r.publishedYear)
                        .putNullable("pages", r.pages)
                        .put("publicationType", r.publicationType)
                        .put("categories", r.categories)
                        .putNullable("description", r.description)
                        .putNullable("coverRemoteUrl", r.coverRemoteUrl)
                        .put("coverFile", r.coverLocalPath?.let { File(it).name }.orEmpty())
                        .put("metadataSource", r.metadataSource)
                        .put("condition", r.condition)
                        .put("notes", r.notes)
                        .put("addedAt", r.addedAt)
                )
            }
        }

    private fun downloadCover(isbn: String, remoteUrl: String): String? {
        val dir = File(context.filesDir, "covers").apply { mkdirs() }
        val file = File(dir, "$isbn.jpg")
        if (file.exists() && file.length() > 1024) return file.absolutePath

        return runCatching {
            val connection = URL(remoteUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 7000
            connection.readTimeout = 7000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "BookShelf-Android/1.0")
            try {
                if (connection.responseCode !in 200..299) return@runCatching null
                connection.inputStream.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                file.absolutePath
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    private fun EditionEntity.toDraft(
        copyId: Long? = null,
        condition: BookCondition = BookCondition.NOT_SET,
        notes: String = "",
        existingCopies: Int = 0
    ) = BookDraft(
        copyId = copyId,
        editionId = id,
        isbn13 = isbn13,
        isbn10 = isbn10,
        title = title,
        subtitle = subtitle.orEmpty(),
        authors = authors,
        publisher = publisher.orEmpty(),
        publishedYear = publishedYear?.toString().orEmpty(),
        pages = pages?.toString().orEmpty(),
        publicationType = enumOrDefault(publicationType, PublicationType.BOOK),
        categories = categories,
        description = description.orEmpty(),
        coverLocalPath = coverLocalPath,
        coverRemoteUrl = coverRemoteUrl,
        metadataSource = metadataSource,
        condition = condition,
        notes = notes,
        existingCopies = existingCopies
    )

    private fun BookDraft.toEntity(localCover: String?) = EditionEntity(
        isbn13 = isbn13.trim(),
        isbn10 = isbn10?.trim()?.takeIf { it.isNotBlank() },
        title = title.trim().ifBlank { "Без названия" },
        subtitle = subtitle.trim().takeIf { it.isNotBlank() },
        authors = authors.trim(),
        publisher = publisher.trim().takeIf { it.isNotBlank() },
        publishedYear = publishedYear.toIntOrNull(),
        pages = pages.toIntOrNull(),
        publicationType = publicationType.name,
        categories = categories.trim(),
        description = description.trim().takeIf { it.isNotBlank() },
        coverLocalPath = localCover,
        coverRemoteUrl = coverRemoteUrl,
        metadataSource = metadataSource,
        updatedAt = System.currentTimeMillis()
    )

    private fun csv(value: String): String =
        "\"" + value.replace("\"", "\"\"").replace("\n", " ") + "\""

    private fun drawEllipsized(
        canvas: android.graphics.Canvas,
        text: String,
        x: Float,
        y: Float,
        maxWidth: Float,
        paint: android.graphics.Paint
    ) {
        val value = android.text.TextUtils.ellipsize(
            text,
            android.text.TextPaint(paint),
            maxWidth,
            android.text.TextUtils.TruncateAt.END
        ).toString()
        canvas.drawText(value, x, y, paint)
    }
}

private fun JSONObject.putNullable(key: String, value: Any?): JSONObject =
    put(key, value ?: JSONObject.NULL)

private fun JSONObject.optNullable(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (!has(key) || isNull(key)) null else optInt(key).takeIf { it > 0 }

private inline fun <reified T : Enum<T>> enumOrDefault(value: String, default: T): T =
    enumValues<T>().firstOrNull { it.name == value } ?: default
