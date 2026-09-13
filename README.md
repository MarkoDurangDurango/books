# BookShelf

BookShelf — local-first Android-каталог физических книг и изданий.

## Что уже работает

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
- светлая/тёмная системная тема;
- GitHub Actions для сборки debug и unsigned release APK.

## Стек

- Kotlin 2.2.21
- Jetpack Compose
- Android Gradle Plugin 8.13.2
- compileSdk 36.1 / targetSdk 36
- minSdk 23
- Room 2.8.5
- CameraX 1.6.2
- ML Kit Barcode Scanning 17.3.0

## Как собрать APK в GitHub

1. Создайте пустой GitHub-репозиторий.
2. Загрузите **всё содержимое этой папки** в корень репозитория.
3. Убедитесь, что основная ветка называется `main` или `master`.
4. Откройте вкладку **Actions**.
5. Запустите workflow **Build BookShelf APK** вручную либо просто сделайте push.
6. После успешной сборки откройте run и скачайте artifact:
   - `BookShelf-debug` — сразу устанавливаемый debug APK;
   - `BookShelf-release-unsigned` — release APK без подписи.

Debug APK будет находиться внутри artifact как:

`app-debug.apk`

## Локальная сборка

Проект рассчитан прежде всего на GitHub Actions. В workflow Gradle 8.13 устанавливается автоматически через `gradle/actions/setup-gradle`.

Если открываете проект в Android Studio, используйте JDK 17 и установленный Android SDK Platform 36.1 (targetSdk остаётся 36).

## Архитектура данных

`EditionEntity` хранит сведения об издании:

- ISBN;
- название;
- автор;
- издательство;
- год;
- страницы;
- тип;
- категории;
- описание;
- обложку;
- источник метаданных.

`BookCopyEntity` хранит конкретный экземпляр пользователя:

- ссылку на издание;
- состояние;
- заметку;
- дату добавления.

Поэтому один ISBN может иметь несколько физических экземпляров.

## Источники метаданных

Сначала BookShelf обращается к Google Books, затем дополняет отсутствующие данные Open Library.

Если издание уже встречалось раньше, карточка берётся из локальной Room-базы без сетевого запроса.

## Backup

`.bookshelf` — это ZIP-контейнер:

```text
manifest.json
books.json
covers/
```

При восстановлении текущая полка заменяется содержимым backup-файла.

## Что логично делать следующим этапом

- режим массового непрерывного сканирования;
- отдельный экран статистики коллекции;
- расширенные фильтры и сортировки;
- пользовательская замена обложки;
- экспорт PDF с настраиваемым шаблоном;
- полноценная release-подпись через GitHub Secrets;
- импорт CSV;
- автоматический локальный backup по расписанию;
- позже — опциональная облачная синхронизация без изменения local-first архитектуры.

## Build compatibility note

The project intentionally uses Android Gradle Plugin 8.11.1 with Kotlin 2.2.21.
This is within Kotlin 2.2.21's officially supported AGP range, while still supporting compileSdk/targetSdk 36.
The GitHub Actions workflow also uploads `BookShelf-build-diagnostics` on every run so failed test/compile logs are easy to inspect.

