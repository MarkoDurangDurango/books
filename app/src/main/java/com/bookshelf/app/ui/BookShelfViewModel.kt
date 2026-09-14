package com.bookshelf.app.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.bookshelf.app.data.BookRepository
import com.bookshelf.app.data.BookWithEdition
import com.bookshelf.app.domain.BookDraft
import com.bookshelf.app.domain.Isbn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class UiMessage(val text: String, val isError: Boolean = false)

class BookShelfViewModel(
    private val repository: BookRepository
) : ViewModel() {
    val shelf: StateFlow<List<BookWithEdition>> = repository.shelf.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList()
    )

    private val _draft = MutableStateFlow<BookDraft?>(null)
    val draft: StateFlow<BookDraft?> = _draft.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<UiMessage?>(null)
    val message: StateFlow<UiMessage?> = _message.asStateFlow()

    fun clearMessage() {
        _message.value = null
    }

    fun newManual() {
        _draft.value = BookDraft()
    }

    fun lookup(isbn: String, onReady: () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            runCatching { repository.lookup(isbn) }
                .onSuccess { result ->
                    _draft.value = result.draft
                    if (result.draft.title.isBlank()) {
                        val failed = result.diagnostics
                            .filter { it.status != "FOUND" }
                            .joinToString(", ") { "${it.source}: ${it.status}" }
                            .take(220)
                        _message.value = UiMessage(
                            buildString {
                                append("ISBN распознан, но метаданные пока не найдены.")
                                if (failed.isNotBlank()) append(" $failed")
                            },
                            true
                        )
                    }
                    onReady()
                }
                .onFailure {
                    _message.value = UiMessage("Не удалось получить данные книги: ${it.message}", true)
                }
            _busy.value = false
        }
    }

    fun recognizeCover(path: String, onReady: () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            runCatching { repository.recognizeCover(path) }
                .onSuccess { result ->
                    if (result.draft.title.isBlank()) {
                        val reason = result.diagnostics.joinToString(", ") { "${it.source}: ${it.status}" }.take(240)
                        _message.value = UiMessage(
                            if (reason.isBlank()) "Не удалось распознать книгу по обложке." else "Не удалось распознать книгу по обложке. $reason",
                            true
                        )
                    } else {
                        _draft.value = result.draft
                        if (result.draft.isbn13.isBlank()) {
                            _message.value = UiMessage("Название и автор распознаны. ISBN не найден автоматически — проверьте карточку перед сохранением.")
                        }
                        onReady()
                    }
                }
                .onFailure {
                    _message.value = UiMessage("Ошибка распознавания обложки: ${it.message}", true)
                }
            _busy.value = false
        }
    }

    fun resolverUrl(): String = repository.resolverUrl()

    fun saveResolverUrl(value: String) {
        repository.setResolverUrl(value)
        _message.value = UiMessage(
            if (value.isBlank()) "Resolver отключён. ISBN будет искаться напрямую в каталогах."
            else "Resolver сохранён. Распознавание обложек и расширенный поиск включены."
        )
    }

    fun testResolver() {
        viewModelScope.launch {
            _busy.value = true
            runCatching { repository.testResolver() }
                .onSuccess { version -> _message.value = UiMessage("Resolver работает • версия $version") }
                .onFailure { _message.value = UiMessage("Resolver недоступен: ${it.message}", true) }
            _busy.value = false
        }
    }

    fun edit(copyId: Long, onReady: () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            runCatching { repository.getDraft(copyId) }
                .onSuccess { draft ->
                    if (draft != null) {
                        _draft.value = draft
                        onReady()
                    }
                }
                .onFailure {
                    _message.value = UiMessage("Не удалось открыть книгу", true)
                }
            _busy.value = false
        }
    }

    fun updateDraft(transform: (BookDraft) -> BookDraft) {
        _draft.value = _draft.value?.let(transform)
    }

    fun save(onSaved: () -> Unit) {
        val value = _draft.value ?: return
        if (value.title.isBlank()) {
            _message.value = UiMessage("Укажите название книги", true)
            return
        }
        if (value.isbn13.isBlank()) {
            _message.value = UiMessage("Укажите ISBN-13", true)
            return
        }
        val normalizedIsbn = Isbn.normalize(value.isbn13)
        if (normalizedIsbn == null) {
            _message.value = UiMessage("ISBN некорректен. Проверьте 13 цифр на обложке книги.", true)
            return
        }

        viewModelScope.launch {
            _busy.value = true
            runCatching { repository.save(value.copy(isbn13 = normalizedIsbn)) }
                .onSuccess {
                    _draft.value = null
                    _message.value = UiMessage("Книга сохранена")
                    onSaved()
                }
                .onFailure {
                    _message.value = UiMessage("Ошибка сохранения: ${it.message}", true)
                }
            _busy.value = false
        }
    }

    fun delete(copyId: Long, onDeleted: () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            runCatching { repository.delete(copyId) }
                .onSuccess {
                    _message.value = UiMessage("Экземпляр удалён")
                    onDeleted()
                }
                .onFailure { _message.value = UiMessage("Не удалось удалить книгу", true) }
            _busy.value = false
        }
    }

    fun exportJson(uri: Uri) = launchFileAction("JSON экспортирован") { repository.exportJson(uri) }
    fun exportCsv(uri: Uri) = launchFileAction("CSV экспортирован") { repository.exportCsv(uri) }
    fun exportPdf(uri: Uri) = launchFileAction("PDF экспортирован") { repository.exportPdf(uri) }
    fun backup(uri: Uri) = launchFileAction("Резервная копия создана") { repository.createBackup(uri) }
    fun restore(uri: Uri) = launchFileAction("Полка восстановлена из резервной копии") { repository.restoreBackup(uri) }

    fun fileName(ext: String): String = repository.suggestedFileName(ext)

    private fun launchFileAction(success: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            runCatching { block() }
                .onSuccess { _message.value = UiMessage(success) }
                .onFailure { _message.value = UiMessage("Ошибка: ${it.message}", true) }
            _busy.value = false
        }
    }
}

class BookShelfViewModelFactory(
    private val repository: BookRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return BookShelfViewModel(repository) as T
    }
}
