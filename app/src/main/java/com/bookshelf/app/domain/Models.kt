package com.bookshelf.app.domain

enum class PublicationType(val label: String) {
    BOOK("Книга"),
    COMIC("Комикс"),
    GRAPHIC_NOVEL("Графический роман"),
    MANGA("Манга"),
    ARTBOOK("Артбук"),
    TEXTBOOK("Учебник"),
    CHILDRENS_BOOK("Детская книга"),
    MAGAZINE("Журнал"),
    OTHER("Другое")
}

enum class BookCondition(val label: String) {
    NOT_SET("Не указано"),
    NEW("Новое"),
    EXCELLENT("Отличное"),
    GOOD("Хорошее"),
    FAIR("Удовлетворительное"),
    POOR("Плохое")
}

data class BookDraft(
    val copyId: Long? = null,
    val editionId: Long? = null,
    val isbn13: String = "",
    val isbn10: String? = null,
    val title: String = "",
    val subtitle: String = "",
    val authors: String = "",
    val publisher: String = "",
    val publishedYear: String = "",
    val pages: String = "",
    val publicationType: PublicationType = PublicationType.BOOK,
    val categories: String = "",
    val description: String = "",
    val coverLocalPath: String? = null,
    val coverRemoteUrl: String? = null,
    val metadataSource: String = "manual",
    val condition: BookCondition = BookCondition.NOT_SET,
    val notes: String = "",
    val existingCopies: Int = 0
)

data class MetadataDiagnostic(
    val source: String,
    val status: String,
    val detail: String = ""
)

data class BookLookupResult(
    val draft: BookDraft,
    val diagnostics: List<MetadataDiagnostic> = emptyList(),
    val resolverUsed: Boolean = false,
    val matchConfidence: Double? = null
)
