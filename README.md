# BookShelf 1.1.0

Local-first Android-каталог домашней библиотеки с автоматическим заполнением карточек по ISBN и распознаванием книги по фотографии обложки.

## Что изменилось в 1.1.0

Главное изменение — новый двухконтурный поиск метаданных.

### ISBN

Приложение сначала проверяет локальную Room-базу. Для нового ISBN используется BookShelf Resolver, если он настроен. Resolver объединяет данные из:

- Национальной библиографии Российской национальной библиотеки (РНБ, `nb.nlr.ru`);
- Российской государственной библиотеки (РГБ, `search.rsl.ru`);
- Google Books;
- Open Library.

Resolver возвращает не только результат, но и диагностику по каждому источнику (`FOUND`, `NOT_FOUND`, `HTTP_...`, `TIMEOUT`, `PARSE_ERROR`). Если resolver не настроен или недоступен, APK выполняет прямой fallback: РНБ → Google Books → НЭБ → Open Library.

Тестовый ISBN, из-за которого менялась архитектура: `9785915225021`.

### Поиск по обложке

На главном экране появилась кнопка с камерой и пункт **«Распознать по обложке»**.

1. Приложение фотографирует лицевую сторону книги.
2. Перед отправкой изображение уменьшается на устройстве примерно до 1600 px и JPEG 82%.
3. Фото отправляется только в настроенный пользователем BookShelf Resolver.
4. Workers AI извлекает видимые название/автора/ISBN.
5. Resolver повторно ищет кандидата в РНБ/РГБ/Google Books/Open Library и сверяет название/автора.
6. В Android возвращается готовая карточка. Если точный ISBN не удалось определить, приложение всё равно заполняет распознанные поля и просит проверить ISBN перед сохранением.

Полная пользовательская полка на resolver не загружается.

## Android

- Kotlin 2.2.21
- Jetpack Compose
- AGP 8.11.1
- Gradle 8.13
- JDK 17
- compileSdk 36
- targetSdk 36
- Room 2.8.5
- CameraX 1.6.2
- ML Kit Barcode Scanning 17.3.0

## Сборка APK в GitHub

Загрузите содержимое этой папки в корень GitHub-репозитория.

Workflow **Build BookShelf APK** запускается автоматически после push в `main`/`master` и вручную через Actions. Он:

1. проверяет JavaScript resolver;
2. проверяет Android AAR metadata;
3. запускает unit tests;
4. собирает debug APK;
5. публикует artifact `BookShelf-debug`.

Для обычной ISBN-сборки Cloudflare не обязателен: прямой fallback остаётся в APK. Но для максимального покрытия российских каталогов и распознавания по обложке рекомендуется настроить resolver.

## Развёртывание BookShelf Resolver в Cloudflare

Resolver находится в `worker/` и рассчитан на Cloudflare Workers + Workers AI.

### 1. Создать Cloudflare API token

В Cloudflare создайте API token, которому разрешено редактировать Workers Scripts вашего аккаунта. Также понадобится Account ID.

### 2. Добавить GitHub Secrets

Repository → Settings → Secrets and variables → Actions:

- `CLOUDFLARE_API_TOKEN`
- `CLOUDFLARE_ACCOUNT_ID`
- `GOOGLE_BOOKS_API_KEY` — необязательно

### 3. Запустить deploy

Actions → **Deploy BookShelf Resolver** → **Run workflow**.

Workflow также попробует автоматически принять лицензию Meta для vision-модели. Если токен не имеет права запускать Workers AI и этот шаг выдаст предупреждение, resolver ISBN всё равно развернётся. Для распознавания обложек один раз выполните команду из официальной инструкции Cloudflare:

```bash
curl "https://api.cloudflare.com/client/v4/accounts/$CLOUDFLARE_ACCOUNT_ID/ai/run/@cf/meta/llama-3.2-11b-vision-instruct" \
  -X POST \
  -H "Authorization: Bearer $CLOUDFLARE_API_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"prompt":"agree"}'
```

В логе Wrangler появится адрес вида:

```text
https://bookshelf-resolver.<ваш-subdomain>.workers.dev
```

Проверьте:

```text
https://bookshelf-resolver.<ваш-subdomain>.workers.dev/health
```

Ожидаемый ответ:

```json
{"ok":true,"service":"bookshelf-resolver","version":"1.1.0","ai":true}
```

### 4. Подключить resolver к приложению

Есть два способа.

Самый простой: в BookShelf открыть **⋮ → Настройки → Расширенный поиск**, вставить URL Worker и нажать **«Сохранить resolver»**, затем **«Проверить соединение»**. Пересобирать APK не требуется.

Либо добавить в GitHub Secret:

```text
BOOKSHELF_RESOLVER_URL
```

Тогда URL будет уже встроен в APK при сборке.

## Быстрая проверка после установки

1. Откройте настройки и проверьте resolver — должно появиться сообщение `Resolver работает • версия 1.1.0`.
2. Отсканируйте `9785915225021` или другой российский ISBN.
3. Карточка должна заполняться автоматически, если запись присутствует хотя бы в одном подключённом каталоге.
4. Для книги без результата нажмите кнопку камеры на главном экране, сфотографируйте обложку и дождитесь распознавания.

## Локальные данные

Room-база и загруженные обложки находятся на устройстве. Системный Android cloud backup отключён. Пользователь может создавать `.bookshelf` backup, а также экспортировать полку в PDF, CSV и JSON.

## Ограничения

Ни один бесплатный каталог не гарантирует наличие абсолютно каждого ISBN. Российские библиотечные сайты также могут менять HTML или вводить антибот-защиту. Именно поэтому парсеры вынесены в Worker: исправление источника не требует выпускать новый APK.

Распознавание обложки использует Workers AI и зависит от доступной квоты Cloudflare. Фото используется для текущего запроса распознавания; BookShelf не загружает на resolver всю библиотеку пользователя.
