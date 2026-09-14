@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bookshelf.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import com.bookshelf.app.data.BookWithEdition
import com.bookshelf.app.domain.BookCondition
import com.bookshelf.app.domain.BookDraft
import com.bookshelf.app.domain.PublicationType

private enum class Screen { SHELF, SCANNER, COVER_SCANNER, EDITOR, DETAIL, SETTINGS }

@Composable
fun BookShelfApp(vm: BookShelfViewModel) {
    val shelf by vm.shelf.collectAsState()
    val draft by vm.draft.collectAsState()
    val busy by vm.busy.collectAsState()
    val message by vm.message.collectAsState()

    var screen by rememberSaveable { mutableStateOf(Screen.SHELF) }
    var selectedCopyId by rememberSaveable { mutableStateOf<Long?>(null) }
    val snackbar = remember { SnackbarHostState() }

    val goShelf = {
        screen = Screen.SHELF
        selectedCopyId = null
    }

    BackHandler(screen != Screen.SHELF) {
        when (screen) {
            Screen.EDITOR, Screen.SCANNER, Screen.COVER_SCANNER, Screen.SETTINGS, Screen.DETAIL -> goShelf()
            else -> Unit
        }
    }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it.text)
            vm.clearMessage()
        }
    }

    Box(Modifier.fillMaxSize()) {
        when (screen) {
            Screen.SHELF -> ShelfScreen(
                books = shelf,
                onScan = { screen = Screen.SCANNER },
                onCover = { screen = Screen.COVER_SCANNER },
                onManual = {
                    vm.newManual()
                    screen = Screen.EDITOR
                },
                onSettings = { screen = Screen.SETTINGS },
                onBook = {
                    selectedCopyId = it
                    screen = Screen.DETAIL
                }
            )

            Screen.SCANNER -> ScannerScreen(
                onBack = goShelf,
                onIsbn = { isbn ->
                    vm.lookup(isbn) { screen = Screen.EDITOR }
                }
            )

            Screen.COVER_SCANNER -> CoverScannerScreen(
                onBack = goShelf,
                onCaptured = { path ->
                    vm.recognizeCover(path) { screen = Screen.EDITOR }
                }
            )

            Screen.EDITOR -> {
                if (draft == null) {
                    LaunchedEffect(Unit) { goShelf() }
                } else {
                    EditorScreen(
                        draft = draft!!,
                        onBack = goShelf,
                        onChange = vm::updateDraft,
                        onSave = { vm.save { goShelf() } }
                    )
                }
            }

            Screen.DETAIL -> {
                val book = shelf.firstOrNull { it.copy.id == selectedCopyId }
                if (book == null) {
                    LaunchedEffect(selectedCopyId) { goShelf() }
                } else {
                    DetailScreen(
                        book = book,
                        onBack = goShelf,
                        onEdit = {
                            vm.edit(book.copy.id) { screen = Screen.EDITOR }
                        },
                        onDelete = {
                            vm.delete(book.copy.id) { goShelf() }
                        }
                    )
                }
            }

            Screen.SETTINGS -> SettingsScreen(
                vm = vm,
                bookCount = shelf.size,
                onBack = goShelf
            )
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(16.dp)
        )

        if (busy) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.28f)),
                contentAlignment = Alignment.Center
            ) {
                Card(shape = RoundedCornerShape(24.dp)) {
                    Column(
                        modifier = Modifier.padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Text(
                            "Получаем данные…",
                            modifier = Modifier.padding(top = 14.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ShelfScreen(
    books: List<BookWithEdition>,
    onScan: () -> Unit,
    onCover: () -> Unit,
    onManual: () -> Unit,
    onSettings: () -> Unit,
    onBook: (Long) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("ALL") }
    var moreOpen by remember { mutableStateOf(false) }

    val filtered = remember(books, query, filter) {
        books.filter { book ->
            val e = book.edition
            val matchesText = query.isBlank() ||
                e.title.contains(query, true) ||
                e.authors.contains(query, true) ||
                e.isbn13.contains(query, true)
            val matchesType = filter == "ALL" || e.publicationType == filter
            matchesText && matchesType
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                title = {
                    Column {
                        Text("BookShelf", fontWeight = FontWeight.Bold)
                        Text(
                            "${books.size} ${copyWord(books.size)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { moreOpen = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "Ещё")
                    }
                    DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Распознать по обложке") },
                            leadingIcon = { Icon(Icons.Outlined.CameraAlt, null) },
                            onClick = {
                                moreOpen = false
                                onCover()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Добавить вручную") },
                            leadingIcon = { Icon(Icons.Outlined.Add, null) },
                            onClick = {
                                moreOpen = false
                                onManual()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Настройки и экспорт") },
                            leadingIcon = { Icon(Icons.Outlined.Settings, null) },
                            onClick = {
                                moreOpen = false
                                onSettings()
                            }
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                SmallFloatingActionButton(onClick = onCover) {
                    Icon(Icons.Outlined.CameraAlt, contentDescription = "Распознать обложку")
                }
                Spacer(Modifier.height(10.dp))
                ExtendedFloatingActionButton(
                    onClick = onScan,
                    icon = { Icon(Icons.Outlined.QrCodeScanner, null) },
                    text = { Text("Сканировать ISBN") }
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Название, автор или ISBN") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(18.dp)
            )

            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    "ALL" to "Все",
                    PublicationType.BOOK.name to "Книги",
                    PublicationType.COMIC.name to "Комиксы",
                    PublicationType.MANGA.name to "Манга",
                    PublicationType.GRAPHIC_NOVEL.name to "Графические"
                ).forEach { (value, label) ->
                    FilterChip(
                        selected = filter == value,
                        onClick = { filter = value },
                        label = { Text(label) }
                    )
                }
            }

            if (filtered.isEmpty()) {
                EmptyShelf(
                    hasBooks = books.isNotEmpty(),
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 148.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 12.dp,
                        bottom = 104.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(filtered, key = { it.copy.id }) { book ->
                        BookCard(book = book, onClick = { onBook(book.copy.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyShelf(hasBooks: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Outlined.QrCodeScanner,
            contentDescription = null,
            modifier = Modifier.size(52.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            if (hasBooks) "Ничего не найдено" else "Полка пока пустая",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 18.dp)
        )
        Text(
            if (hasBooks) "Попробуйте изменить запрос или фильтр."
            else "Отсканируйте ISBN на обороте книги — карточка заполнится автоматически.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun BookCard(book: BookWithEdition, onClick: () -> Unit) {
    val e = book.edition
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        CoverImage(
            localPath = e.coverLocalPath,
            contentDescription = e.title,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.70f)
        )
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                e.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                e.authors.ifBlank { "Автор не указан" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun DetailScreen(
    book: BookWithEdition,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var confirmDelete by remember { mutableStateOf(false) }
    val e = book.edition
    val condition = BookCondition.entries.firstOrNull { it.name == book.copy.condition } ?: BookCondition.NOT_SET
    val type = PublicationType.entries.firstOrNull { it.name == e.publicationType } ?: PublicationType.BOOK

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Карточка издания") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Outlined.Edit, "Изменить")
                    }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Outlined.Delete, "Удалить")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Row {
                CoverImage(
                    e.coverLocalPath,
                    e.title,
                    Modifier
                        .width(132.dp)
                        .aspectRatio(0.70f)
                        .clip(RoundedCornerShape(18.dp))
                )
                Column(
                    modifier = Modifier
                        .padding(start = 18.dp)
                        .weight(1f)
                ) {
                    Text(e.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    if (e.subtitle.orEmpty().isNotBlank()) {
                        Text(e.subtitle.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        e.authors.ifBlank { "Автор не указан" },
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                    Text(
                        type.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            InfoRow("Издательство", e.publisher.orEmpty().ifBlank { "—" })
            InfoRow("Год", e.publishedYear?.toString() ?: "—")
            InfoRow("Страниц", e.pages?.toString() ?: "—")
            InfoRow("ISBN-13", e.isbn13)
            InfoRow("Состояние", condition.label)

            if (book.copy.notes.isNotBlank()) {
                Section("Заметка") { Text(book.copy.notes) }
            }
            if (e.description.orEmpty().isNotBlank()) {
                Section("Описание") { Text(e.description.orEmpty()) }
            }
            if (e.categories.isNotBlank()) {
                Section("Категории") {
                    Text(e.categories, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                "Метаданные: ${e.metadataSource}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 24.dp)
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить экземпляр?") },
            text = { Text("Карточка этого экземпляра будет удалена с вашей полки.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Отмена") }
            }
        )
    }
}

@Composable
private fun EditorScreen(
    draft: BookDraft,
    onBack: () -> Unit,
    onChange: ((BookDraft) -> BookDraft) -> Unit,
    onSave: () -> Unit
) {
    var typeMenu by remember { mutableStateOf(false) }
    var conditionMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (draft.copyId == null) "Добавить книгу" else "Изменить книгу") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Назад")
                    }
                }
            )
        },
        bottomBar = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .navigationBarsPadding()
                    .padding(16.dp)
            ) {
                Button(
                    onClick = onSave,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Сохранить") }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                CoverImage(
                    draft.coverLocalPath,
                    draft.title,
                    Modifier
                        .width(116.dp)
                        .aspectRatio(0.70f)
                        .clip(RoundedCornerShape(18.dp))
                )
                Column(
                    modifier = Modifier
                        .padding(start = 16.dp)
                        .weight(1f)
                ) {
                    Text(
                        when {
                            draft.metadataSource == "manual" -> "Ручное заполнение"
                            draft.metadataSource == "not_found" -> "Метаданные не найдены"
                            draft.metadataSource.contains("+") -> "Несколько источников"
                            draft.metadataSource == "google_books" -> "Google Books"
                            draft.metadataSource.startsWith("open_library") -> "Open Library"
                            draft.metadataSource.contains("nlr_national_bibliography") -> "Российская национальная библиотека"
                            draft.metadataSource.contains("rsl") -> "Российская государственная библиотека"
                            draft.metadataSource == "workers_ai_cover" -> "Распознано по обложке"
                            draft.metadataSource == "cover_resolver_not_configured" -> "Resolver не настроен"
                            else -> draft.metadataSource
                        },
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge
                    )
                    if (draft.existingCopies > 0 && draft.copyId == null) {
                        Text(
                            "Такое издание уже есть: ${draft.existingCopies} экз. Можно добавить ещё один.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }

            EditorField(
                "ISBN-13",
                draft.isbn13,
                { v -> onChange { it.copy(isbn13 = v.filter(Char::isDigit).take(13)) } },
                KeyboardType.Number
            )
            EditorField("Название", draft.title, { v -> onChange { it.copy(title = v) } })
            EditorField("Подзаголовок", draft.subtitle, { v -> onChange { it.copy(subtitle = v) } })
            EditorField("Автор", draft.authors, { v -> onChange { it.copy(authors = v) } })
            EditorField("Издательство", draft.publisher, { v -> onChange { it.copy(publisher = v) } })
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) {
                    EditorField(
                        "Год",
                        draft.publishedYear,
                        { v -> onChange { it.copy(publishedYear = v.filter(Char::isDigit).take(4)) } },
                        KeyboardType.Number
                    )
                }
                Box(Modifier.weight(1f)) {
                    EditorField(
                        "Страниц",
                        draft.pages,
                        { v -> onChange { it.copy(pages = v.filter(Char::isDigit).take(5)) } },
                        KeyboardType.Number
                    )
                }
            }

            Text("Тип издания", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp))
            Box {
                OutlinedButton(
                    onClick = { typeMenu = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(draft.publicationType.label) }
                DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                    PublicationType.entries.forEach { type ->
                        DropdownMenuItem(
                            text = { Text(type.label) },
                            onClick = {
                                onChange { it.copy(publicationType = type) }
                                typeMenu = false
                            }
                        )
                    }
                }
            }

            Text("Состояние экземпляра", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 16.dp))
            Box {
                OutlinedButton(
                    onClick = { conditionMenu = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(draft.condition.label) }
                DropdownMenu(expanded = conditionMenu, onDismissRequest = { conditionMenu = false }) {
                    BookCondition.entries.forEach { condition ->
                        DropdownMenuItem(
                            text = { Text(condition.label) },
                            onClick = {
                                onChange { it.copy(condition = condition) }
                                conditionMenu = false
                            }
                        )
                    }
                }
            }

            EditorField("Заметка о состоянии / экземпляре", draft.notes, { v -> onChange { it.copy(notes = v) } }, singleLine = false)
            EditorField("Категории", draft.categories, { v -> onChange { it.copy(categories = v) } }, singleLine = false)
            EditorField("Описание", draft.description, { v -> onChange { it.copy(description = v) } }, singleLine = false)
            Spacer(Modifier.height(96.dp))
        }
    }
}

@Composable
private fun EditorField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
private fun SettingsScreen(
    vm: BookShelfViewModel,
    bookCount: Int,
    onBack: () -> Unit
) {
    var confirmRestore by remember { mutableStateOf<android.net.Uri?>(null) }
    var resolverUrl by remember { mutableStateOf(vm.resolverUrl()) }

    val json = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { it?.let(vm::exportJson) }

    val csv = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { it?.let(vm::exportCsv) }

    val pdf = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { it?.let(vm::exportPdf) }

    val backup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { it?.let(vm::backup) }

    val restore = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) confirmRestore = uri }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Назад")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(18.dp)
        ) {
            Text(
                "На полке $bookCount ${copyWord(bookCount)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            SectionTitle("Расширенный поиск")
            Text(
                "Resolver повышает покрытие российских ISBN через каталоги РНБ/РГБ и включает распознавание книги по фотографии обложки.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
            OutlinedTextField(
                value = resolverUrl,
                onValueChange = { resolverUrl = it },
                label = { Text("URL BookShelf Resolver") },
                placeholder = { Text("https://bookshelf-resolver....workers.dev") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                shape = RoundedCornerShape(16.dp)
            )
            Button(
                onClick = { vm.saveResolverUrl(resolverUrl) },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
            ) {
                Text("Сохранить resolver")
            }
            OutlinedButton(
                onClick = vm::testResolver,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Text("Проверить соединение")
            }

            SectionTitle("Экспорт")
            ActionCard(
                "Красивый PDF-каталог",
                "Обложки, названия, авторы, ISBN и состояние.",
                { pdf.launch(vm.fileName("pdf")) }
            )
            ActionCard(
                "CSV",
                "Для Excel, Google Sheets и собственного анализа.",
                { csv.launch(vm.fileName("csv")) }
            )
            ActionCard(
                "JSON",
                "Структурированный экспорт для других приложений.",
                { json.launch(vm.fileName("json")) }
            )

            SectionTitle("Локальный backup")
            ActionCard(
                "Создать резервную копию",
                "Файл .bookshelf содержит все карточки и локальные обложки.",
                { backup.launch(vm.fileName("bookshelf")) }
            )
            ActionCard(
                "Восстановить резервную копию",
                "Текущая полка будет заменена содержимым backup-файла.",
                { restore.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }
            )

            Text(
                "Полка и резервные копии хранятся локально. При поиске в сеть передаётся только ISBN; при распознавании по обложке — выбранная фотография обложки в настроенный вами BookShelf Resolver. Полный каталог пользователя на сервер не отправляется.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 24.dp)
            )
        }
    }

    confirmRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { Text("Заменить текущую полку?") },
            text = { Text("Перед восстановлением лучше создать свежую резервную копию. Все текущие записи будут удалены.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = null
                    vm.restore(uri)
                }) { Text("Восстановить") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestore = null }) { Text("Отмена") }
            }
        )
    }
}

@Composable
private fun ActionCard(title: String, description: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.FileDownload, null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.padding(start = 14.dp).weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 24.dp, bottom = 2.dp)
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
    ) {
        Text(
            label,
            modifier = Modifier.weight(0.38f),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, modifier = Modifier.weight(0.62f))
    }
    HorizontalDivider()
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
    )
    content()
}

private fun copyWord(count: Int): String {
    val mod100 = count % 100
    val mod10 = count % 10
    return when {
        mod100 in 11..14 -> "экземпляров"
        mod10 == 1 -> "экземпляр"
        mod10 in 2..4 -> "экземпляра"
        else -> "экземпляров"
    }
}
