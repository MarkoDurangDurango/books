import legacy from "./index.js";

const JSON_HEADERS = {
  "content-type": "application/json; charset=utf-8",
  "cache-control": "no-store",
};
const USER_AGENT = "BookShelfResolver/1.1.1 (+personal-library-app)";
const CACHE_VERSION = "v2";

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    if (request.method === "GET" && url.pathname === "/health") {
      return withCors(json({
        ok: true,
        service: "bookshelf-resolver",
        version: "1.1.1",
        ai: Boolean(env.AI),
        isbnResolver: "v2",
      }));
    }

    const match = url.pathname.match(/^\/v1\/books\/isbn\/(\d{13})$/);
    if (request.method === "GET" && match) {
      const isbn = normalizeIsbn(match[1]);
      if (!isValidIsbn13(isbn)) {
        return withCors(json({ ok: false, found: false, error: "invalid_isbn", diagnostics: [] }, 400));
      }

      const cache = caches.default;
      const cacheKey = new Request(`${url.origin}/cache/${CACHE_VERSION}/isbn/${isbn}`);
      const cached = await cache.match(cacheKey);
      if (cached) return withCors(cached);

      const result = await resolveIsbn(isbn, env);
      const response = json(result, 200, {
        "cache-control": result.found ? "public, max-age=86400" : "public, max-age=900",
      });
      ctx.waitUntil(cache.put(cacheKey, response.clone()));
      return withCors(response);
    }

    return legacy.fetch(request, env, ctx);
  },
};

async function resolveIsbn(isbn, env) {
  const diagnostics = [];
  const tasks = [
    capture("rsl", diagnostics, () => rslByIsbn(isbn), 6500),
    capture("open_library", diagnostics, () => openLibraryByIsbn(isbn), 3500),
  ];

  if (env.GOOGLE_BOOKS_API_KEY) {
    tasks.push(capture("google_books", diagnostics, () => googleByIsbn(isbn, env.GOOGLE_BOOKS_API_KEY), 3500));
  } else {
    diagnostics.push({ source: "google_books", status: "SKIPPED_NO_KEY", detail: "API key not configured" });
  }

  const candidates = (await Promise.all(tasks)).filter(Boolean);
  if (!candidates.length) {
    return { ok: true, found: false, isbn13: isbn, book: null, diagnostics };
  }

  const book = mergeBooks(isbn, candidates);
  return { ok: true, found: Boolean(book.title), isbn13: isbn, book, diagnostics };
}

async function capture(source, diagnostics, fn, timeoutMs) {
  const started = Date.now();
  try {
    const value = await withTimeout(fn(), timeoutMs);
    diagnostics.push({ source, status: value ? "FOUND" : "NOT_FOUND", detail: `${Date.now() - started}ms` });
    return value;
  } catch (error) {
    diagnostics.push({ source, status: statusFromError(error), detail: `${Date.now() - started}ms ${safeDetail(error)}`.trim() });
    return null;
  }
}

function withTimeout(promise, timeoutMs) {
  return Promise.race([
    promise,
    new Promise((_, reject) => setTimeout(() => reject(new Error("TIMEOUT")), timeoutMs)),
  ]);
}

async function rslByIsbn(isbn) {
  const landing = await fetchText("https://search.rsl.ru/ru/search", 3500, "text/html,application/xhtml+xml");
  const token = landing.text.match(/<meta[^>]+name=["']csrf-token["'][^>]+content=["']([^"']+)["']/i)?.[1]
    || landing.text.match(/<meta[^>]+content=["']([^"']+)["'][^>]+name=["']csrf-token["']/i)?.[1];
  if (!token) throw new Error("PARSE_ERROR: csrf token not found");

  const form = new URLSearchParams();
  form.set("SearchFilterForm[search]", `isbn:${isbn}`);
  const ajax = await fetchRaw("https://search.rsl.ru/site/ajax-search?language=ru", 3500, {
    method: "POST",
    headers: {
      ...browserHeaders("application/json,text/html,*/*"),
      "content-type": "application/x-www-form-urlencoded; charset=UTF-8",
      "x-csrf-token": decodeHtml(token),
      "x-requested-with": "XMLHttpRequest",
      referer: "https://search.rsl.ru/ru/search",
      cookie: landing.cookies,
    },
    body: form.toString(),
  });
  if (!ajax.response.ok) throw httpError(ajax.response.status, ajax.text);

  const ids = extractUnique(ajax.text, /(?:\/ru\/record\/|record["'\\/:]+)(0?\d{7,12})/gi, 8);
  for (const id of ids) {
    const record = await fetchText(`https://search.rsl.ru/ru/record/${id}`, 3500, "text/html,application/xhtml+xml");
    const parsed = parseRslPage(record.text, isbn);
    if (parsed) return parsed;
  }
  return null;
}

function parseRslPage(html, expectedIsbn) {
  const text = htmlToText(html);
  const isbns = extractIsbns(text);
  if (expectedIsbn && !isbns.includes(expectedIsbn)) return null;

  const marcTitle = marcSubfield(text, "245", "a");
  const marcSubtitle = marcSubfield(text, "245", "b");
  const descriptionTitles = fieldCandidates(text, "Заглавие")
    .filter(isPlausibleTitle)
    .sort((a, b) => b.length - a.length);
  const pageTitle = cleanRslPageTitle(htmlTitle(html));
  const title = cleanText(marcTitle || descriptionTitles[0] || pageTitle);
  if (!isPlausibleTitle(title)) return null;

  const authors = cleanText(
    fieldAfter(text, ["Сведения об ответственности"])
      || marcSubfield(text, "245", "c")
      || ""
  );

  const output = fieldAfter(text, ["Выходные данные"]);
  const publisher = cleanText(
    marcSubfield(text, "264", "b")
      || marcSubfield(text, "260", "b")
      || publisherFromOutput(output)
      || ""
  );
  const year = extractYear(
    marcSubfield(text, "264", "c")
      || marcSubfield(text, "260", "c")
      || output
      || text
  );
  const physical = marcSubfield(text, "300", "a") || fieldAfter(text, ["Физическое описание"]);
  const categories = uniqueNonBlank([
    ...marcSubfields(text, "650", "a"),
    ...fieldCandidates(text, "Термины-указатели"),
    ...fieldCandidates(text, "Тематика"),
  ]).slice(0, 10).join(", ");

  const isbn10 = isbns.find((x) => x.length === 10 && isValidIsbn10(x)) || "";
  return {
    source: "rsl",
    isbn13: expectedIsbn,
    isbn10,
    title,
    subtitle: cleanText(marcSubtitle || ""),
    authors,
    publisher,
    publishedYear: year,
    pages: firstNumber(physical || ""),
    categories,
    description: "",
    coverUrl: extractImageUrl(html),
    publicationType: classify(categories),
  };
}

async function openLibraryByIsbn(isbn) {
  const key = `ISBN:${isbn}`;
  const { text } = await fetchText(
    `https://openlibrary.org/api/books?bibkeys=${encodeURIComponent(key)}&jscmd=data&format=json`,
    3000,
    "application/json"
  );
  const root = JSON.parse(text);
  const b = root[key];
  if (!b) return null;
  const ids = b.identifiers || {};
  const candidate10 = normalizeIsbn(Array.isArray(ids.isbn_10) ? ids.isbn_10[0] || "" : "");
  return {
    source: "open_library",
    isbn13: isbn,
    isbn10: isValidIsbn10(candidate10) ? candidate10 : "",
    title: cleanText(b.title || ""),
    subtitle: cleanText(b.subtitle || ""),
    authors: objectNames(b.authors),
    publisher: objectNames(b.publishers),
    publishedYear: extractYear(b.publish_date || ""),
    pages: positiveString(b.number_of_pages),
    categories: objectNames(b.subjects),
    description: typeof b.description === "string" ? cleanText(b.description) : cleanText(b.description?.value || ""),
    coverUrl: String(b.cover?.large || b.cover?.medium || b.cover?.small || "").replace(/^http:/, "https:"),
    publicationType: classify(objectNames(b.subjects)),
  };
}

async function googleByIsbn(isbn, apiKey) {
  const q = encodeURIComponent(`isbn:${isbn}`);
  const { text } = await fetchText(
    `https://www.googleapis.com/books/v1/volumes?q=${q}&maxResults=10&printType=books&key=${encodeURIComponent(apiKey)}`,
    3000,
    "application/json"
  );
  const root = JSON.parse(text);
  for (const item of Array.isArray(root.items) ? root.items : []) {
    const info = item?.volumeInfo || {};
    const identifiers = Array.isArray(info.industryIdentifiers) ? info.industryIdentifiers : [];
    if (!identifiers.some((it) => normalizeIsbn(it?.identifier || "") === isbn)) continue;
    const ids = Object.fromEntries(identifiers.map((x) => [x?.type, normalizeIsbn(x?.identifier || "")]));
    const categories = Array.isArray(info.categories) ? info.categories.join(", ") : "";
    const images = info.imageLinks || {};
    return {
      source: "google_books",
      isbn13: isbn,
      isbn10: isValidIsbn10(ids.ISBN_10 || "") ? ids.ISBN_10 : "",
      title: cleanText(info.title || ""),
      subtitle: cleanText(info.subtitle || ""),
      authors: Array.isArray(info.authors) ? info.authors.map(cleanText).filter(Boolean).join(", ") : "",
      publisher: cleanText(info.publisher || ""),
      publishedYear: extractYear(info.publishedDate || ""),
      pages: positiveString(info.pageCount),
      categories,
      description: cleanText(info.description || ""),
      coverUrl: String(images.extraLarge || images.large || images.medium || images.thumbnail || "").replace(/^http:/, "https:"),
      publicationType: classify(categories),
    };
  }
  return null;
}

function mergeBooks(isbn, candidates) {
  const priority = { rsl: 0, google_books: 1, open_library: 2 };
  const preferred = [...candidates].sort((a, b) => (priority[a.source] ?? 9) - (priority[b.source] ?? 9));
  const pick = (field) => preferred.map((x) => cleanText(x?.[field] || "")).find(Boolean) || "";
  const categories = uniqueNonBlank(candidates.map((x) => x.categories || "")).join(", ");
  const isbn10 = pick("isbn10");
  return {
    isbn13: isbn,
    isbn10: isValidIsbn10(isbn10) ? isbn10 : "",
    title: pick("title"),
    subtitle: pick("subtitle"),
    authors: pick("authors"),
    publisher: pick("publisher"),
    publishedYear: pick("publishedYear"),
    pages: pick("pages"),
    categories,
    description: pick("description"),
    coverUrl: preferred.map((x) => x.coverUrl).find(Boolean) || `https://covers.openlibrary.org/b/isbn/${isbn}-L.jpg?default=false`,
    publicationType: preferred.map((x) => x.publicationType).find((x) => x && x !== "BOOK") || classify(categories),
    metadataSource: [...new Set(preferred.map((x) => x.source))].join("+"),
  };
}

async function fetchText(url, timeoutMs, accept = "*/*") {
  return fetchRaw(url, timeoutMs, { headers: browserHeaders(accept) });
}

async function fetchRaw(url, timeoutMs, init = {}) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(url, { ...init, signal: controller.signal, redirect: "follow" });
    const text = await response.text();
    if (!response.ok) throw httpError(response.status, text);
    return { response, text, cookies: readSetCookie(response.headers) };
  } catch (error) {
    if (String(error?.name || "").includes("Abort") || /abort|timeout/i.test(String(error))) throw new Error("TIMEOUT");
    throw error;
  } finally {
    clearTimeout(timer);
  }
}

function marcSubfield(text, tag, subfield) {
  const values = marcSubfields(text, tag, subfield);
  return values[0] || "";
}

function marcSubfields(text, tag, subfield) {
  const lines = String(text || "").split(/\n+/).map((x) => x.trim()).filter(Boolean);
  const out = [];
  for (let i = 0; i < lines.length; i++) {
    if (!new RegExp(`^${tag}\\b`).test(lines[i])) continue;
    for (let j = i; j < Math.min(lines.length, i + 12); j++) {
      if (j > i && /^\d{3}\b/.test(lines[j])) break;
      const m = lines[j].match(new RegExp(`^\\$${subfield}\\s*(.+)$`, "i"));
      if (m) out.push(cleanText(m[1]));
    }
  }
  return uniqueNonBlank(out);
}

function fieldCandidates(text, label) {
  const lines = String(text || "").split(/\n+/).map(cleanText).filter(Boolean);
  const out = [];
  for (let i = 0; i < lines.length; i++) {
    const pos = lines[i].toLowerCase().indexOf(label.toLowerCase());
    if (pos < 0) continue;
    const inline = cleanText(lines[i].slice(pos + label.length).replace(/^[\s|:\-–—]+/, ""));
    if (inline) out.push(inline);
    for (let j = i + 1; j < Math.min(lines.length, i + 3); j++) out.push(lines[j]);
  }
  return uniqueNonBlank(out);
}

function fieldAfter(text, labels) {
  for (const label of labels) {
    const candidates = fieldCandidates(text, label).filter((x) => !looksLikeFieldHeader(x));
    if (candidates.length) return candidates[0];
  }
  return "";
}

function isPlausibleTitle(value) {
  const s = cleanText(value);
  if (s.length < 4) return false;
  if (/^(содержание|заглавие|описание|карточка|marc21|search rsl|книги)$/i.test(s)) return false;
  return true;
}

function cleanRslPageTitle(value) {
  let s = cleanText(value).replace(/\s*-\s*Search RSL.*$/i, "").trim();
  const segments = s.split(/\s+-\s+/).map(cleanText).filter(Boolean);
  if (segments.length > 1 && /^[А-ЯЁA-Z][^:]{1,80}[,.]?\s+[А-ЯЁA-Z]/.test(segments[0])) {
    s = segments.slice(1).join(" - ");
  }
  return cleanText(s);
}

function publisherFromOutput(output) {
  const m = String(output || "").match(/:\s*([^,;]+)(?:,|;|$)/);
  return m ? cleanText(m[1]) : "";
}

function htmlToText(html) {
  return decodeHtml(String(html || "")
    .replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, " ")
    .replace(/<style\b[^>]*>[\s\S]*?<\/style>/gi, " ")
    .replace(/<\/(?:tr|td|th|div|p|li|h\d|section|article)>/gi, "\n")
    .replace(/<br\s*\/?\s*>/gi, "\n")
    .replace(/<[^>]+>/g, " "))
    .replace(/[ \t]+/g, " ")
    .replace(/\n[ \t]+/g, "\n")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
}

function htmlTitle(html) {
  const m = String(html || "").match(/<title[^>]*>([\s\S]*?)<\/title>/i);
  return cleanText(m ? htmlToText(m[1]) : "");
}

function decodeHtml(value) {
  return String(value || "")
    .replace(/&nbsp;|&#160;/gi, " ")
    .replace(/&amp;/gi, "&")
    .replace(/&quot;/gi, '"')
    .replace(/&#39;|&apos;/gi, "'")
    .replace(/&lt;/gi, "<")
    .replace(/&gt;/gi, ">")
    .replace(/&#(\d+);/g, (_, n) => String.fromCodePoint(Number(n)))
    .replace(/&#x([0-9a-f]+);/gi, (_, n) => String.fromCodePoint(parseInt(n, 16)));
}

function extractIsbns(text) {
  const out = [];
  const regex = /(?:97[89][\s-]*)?[0-9Xx][0-9Xx\s-]{8,20}[0-9Xx]/g;
  for (const match of String(text || "").matchAll(regex)) {
    const value = normalizeIsbn(match[0]);
    if (value.length === 13 && isValidIsbn13(value) && !out.includes(value)) out.push(value);
    if (value.length === 10 && isValidIsbn10(value) && !out.includes(value)) out.push(value);
  }
  return out;
}

function extractUnique(text, regex, limit = 10) {
  const values = [];
  let m;
  while ((m = regex.exec(text)) && values.length < limit) {
    if (!values.includes(m[1])) values.push(m[1]);
  }
  return values;
}

function extractImageUrl(html) {
  const urls = [...String(html || "").matchAll(/https?:\\?\/\\?\/[^"'<>\s]+\.(?:jpg|jpeg|png|webp)(?:\?[^"'<>\s]*)?/gi)]
    .map((m) => m[0].replace(/\\\//g, "/").replace(/^http:/, "https:"));
  return urls.find((x) => !/logo|icon|sprite|avatar/i.test(x)) || "";
}

function browserHeaders(accept) {
  return { accept, "accept-language": "ru,en;q=0.8", "user-agent": USER_AGENT };
}

function readSetCookie(headers) {
  if (typeof headers.getSetCookie === "function") return headers.getSetCookie().map((x) => x.split(";", 1)[0]).join("; ");
  return String(headers.get("set-cookie") || "").split(/,(?=[^;,]+=)/).map((x) => x.split(";", 1)[0]).join("; ");
}

function httpError(status, text) {
  return new Error(`HTTP_${status}: ${String(text || "").replace(/\s+/g, " ").slice(0, 120)}`);
}

function statusFromError(error) {
  const s = String(error?.message || error || "");
  if (/TIMEOUT|Abort/i.test(s)) return "TIMEOUT";
  const http = s.match(/HTTP_(\d{3})/);
  if (http) return `HTTP_${http[1]}`;
  if (/PARSE_ERROR|JSON/i.test(s)) return "PARSE_ERROR";
  return "ERROR";
}

function safeDetail(error) {
  return String(error?.message || error || "unknown error").replace(/\s+/g, " ").slice(0, 180);
}

function normalizeIsbn(value) {
  return String(value || "").toUpperCase().replace(/[^0-9X]/g, "");
}

function isValidIsbn13(value) {
  const s = normalizeIsbn(value);
  if (!/^\d{13}$/.test(s) || !/^97[89]/.test(s)) return false;
  let sum = 0;
  for (let i = 0; i < 12; i++) sum += Number(s[i]) * (i % 2 === 0 ? 1 : 3);
  return (10 - (sum % 10)) % 10 === Number(s[12]);
}

function isValidIsbn10(value) {
  const s = normalizeIsbn(value);
  if (!/^\d{9}[\dX]$/.test(s)) return false;
  let sum = 0;
  for (let i = 0; i < 10; i++) sum += (10 - i) * (s[i] === "X" ? 10 : Number(s[i]));
  return sum % 11 === 0;
}

function firstNumber(value) {
  const m = String(value || "").match(/\b(\d{1,5})\b/);
  return m ? m[1] : "";
}

function extractYear(value) {
  const m = String(value || "").match(/\b(1[5-9]\d{2}|20\d{2}|21\d{2})\b/);
  return m ? m[1] : "";
}

function positiveString(value) {
  const n = Number(value);
  return Number.isFinite(n) && n > 0 ? String(Math.trunc(n)) : "";
}

function objectNames(values) {
  if (!Array.isArray(values)) return "";
  return values.map((x) => cleanText(typeof x === "string" ? x : x?.name || "")).filter(Boolean).join(", ");
}

function cleanText(value) {
  return decodeHtml(String(value || "")).replace(/\s+/g, " ").trim().replace(/[\s/:;,.\-–—]+$/, "").trim();
}

function uniqueNonBlank(values) {
  return [...new Set(values.map(cleanText).filter(Boolean))];
}

function looksLikeFieldHeader(value) {
  return /^(\d{3}\s*[#0-9A-Za-z]{0,2}\s*[-–—]|[-–—]+$|[A-ZА-ЯЁ0-9 ()/.-]{18,}$)/.test(value);
}

function classify(categories) {
  const c = String(categories || "").toLowerCase().replace(/ё/g, "е");
  if (/манга|manga/.test(c)) return "MANGA";
  if (/графическ|graphic novel/.test(c)) return "GRAPHIC_NOVEL";
  if (/комикс|comic/.test(c)) return "COMIC";
  if (/артбук|artbook/.test(c)) return "ARTBOOK";
  if (/учебник|textbook|education/.test(c)) return "TEXTBOOK";
  if (/детск|children|juvenile/.test(c)) return "CHILDRENS_BOOK";
  if (/журнал|magazine|periodical/.test(c)) return "MAGAZINE";
  return "BOOK";
}

function json(value, status = 200, extraHeaders = {}) {
  return new Response(JSON.stringify(value), { status, headers: { ...JSON_HEADERS, ...extraHeaders } });
}

function withCors(response) {
  const headers = new Headers(response.headers);
  headers.set("access-control-allow-origin", "*");
  return new Response(response.body, { status: response.status, statusText: response.statusText, headers });
}
