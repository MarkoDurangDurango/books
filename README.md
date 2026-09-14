# BookShelf

BookShelf — local-first Android-каталог физических книг и изданий.

## Сборочная конфигурация v1.0.2

- Kotlin 2.2.21
- Android Gradle Plugin 8.11.1
- Gradle 8.13
- JDK 17
- compileSdk 36 / targetSdk 36
- Compose BOM 2026.06.00 (Compose 1.11.x, совместим с compileSdk 36)
- Room 2.8.5
- CameraX 1.6.2
- ML Kit Barcode Scanning 17.3.0

Важно: Compose BOM 2026.08.00 в этой ветке намеренно не используется. Он переводит Compose 1.12 на compileSdk 37 и требует AGP 9.x, что несовместимо с выбранной связкой API 36 + AGP 8.11.1.

## Что уже реализовано

- сканирование ISBN-13 камерой через CameraX + ML Kit;
- проверка и нормализация ISBN;
- поиск метаданных через Google Books и Open Library;
- локальное сохранение обложек;
- локальная база Room;
- отдельные сущности издания и физического экземпляра;
- несколько экземпляров одного ISBN;
- состояние экземпляра и заметка;
- поиск и фильтры по типу издания;
- ручное редактирование карточки;
- экспорт PDF / CSV / JSON;
- локальный backup/restore в формате `.bookshelf`;
- светлая/тёмная системная тема.

## Сборка APK в GitHub

Загрузите всё содержимое проекта в корень репозитория. Workflow `Build BookShelf APK` запускается при push в `main` / `master` или вручную.

CI выполняет три отдельные проверки:

1. `checkDebugAarMetadata` — ловит несовместимость AndroidX / Compose / compileSdk / AGP до компиляции кода;
2. `testDebugUnitTest` — компилирует debug-вариант и запускает unit-тесты;
3. `assembleDebug` — собирает устанавливаемый APK.

После успешной сборки скачайте artifact `BookShelf-debug`. Внутри будет `app-debug.apk`.

Artifact `BookShelf-build-diagnostics` создаётся всегда и содержит отдельные логи каждого этапа.

Release/minify в этой версии намеренно не участвуют в CI: сначала фиксируем воспроизводимую debug-сборку и проверяем приложение на устройстве, после чего добавим signing и release pipeline отдельно.

## Метаданные по ISBN (v1.0.3)

В версии 1.0.3 исправлен главный сценарий автозаполнения. Теперь BookShelf сначала использует Open Library Books API по точному ISBN, затем Search API как fallback. Эти запросы не требуют ключа.

Google Books оставлен дополнительным источником, потому что официальный Books API требует идентификатор приложения (API key) даже для публичных данных. Чтобы включить его в GitHub Actions, создайте repository secret `GOOGLE_BOOKS_API_KEY`. Если secret отсутствует, сборка всё равно работает через Open Library.

Путь в GitHub: **Settings → Secrets and variables → Actions → New repository secret**.
