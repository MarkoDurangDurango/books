const JSON_HEADERS = {
  "content-type": "application/json; charset=utf-8",
  "cache-control": "no-store",
};

const SOURCE_TIMEOUT_MS = 9000;
const USER_AGENT = "BookShelfResolver/1.1 (+personal-library-app)";

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    if (request.method === "GET" && url.pathname === "/health") {
      return json({ ok: true, service: "bookshelf-resolver", version: "1.1.0", ai: Boolean(env.AI) });
    }

    const isbnMatch = url.pathname.match(/^\/v1\/books\/isbn\/(\d{13})$/);
    if (request.method === "GET" && isbnMatch) {
      const isbn = normalizeIsbn(isbnMatch[1]);
      if (!isValidIsbn13(isbn)) {
        return json({ ok: false, found: false, error: "invalid_isbn", diagnostics: [] }, 400);
      }

      const cache = caches.default;
      const cacheKey = new Request(`${url.origin}/cache/isbn/${isbn}`, { method: "GET" });
      const cached = await cache.match(cacheKey);
      if (cached) return withCors(cached);

      const result = await resolveIsbn(isbn, env);
      const response = json(result, result.ok ? 200 : 502, { "cache-control": result.found ? "public, max-age=86400" : "public, max-age=1800" });
      ctx.waitUntil(cache.put(cacheKey, response.clone()));
      return withCors(response);
    }

    if (request.method === "POST" && url.pathname === "/v1/books/cover") {
      if (!env.AI) {
        return json({ ok: false, found: false, error: "workers_ai_not_configured", diagnostics: [] }, 503);
      }

      const contentType = request.headers.get("content-type") || "";
      if (!contentType.startsWith("image/")) {
        return json({ ok: false, found: false, error: "image_required", diagnostics: [] }, 415);
      }

      const bytes = new Uint8Array(await request.arrayBuffer());
      if (bytes.byteLength === 0 || bytes.byteLength > 7_000_000) {
        return json({ ok: false, found: false, error: "image_too_large", diagnostics: [] }, 413);
      }

      return withCors(json(await resolveCover(bytes, contentType, env)));
    }

    if (request.method === "OPTIONS") {
      return new Response(null, {
        status: 204,
        headers: {
          "access-control-allow-origin": "*",
          "access-control-allow-methods": "GET,POST,OPTIONS",
          "access-control-allow-headers": "content-type",
          "access-control-max-age": "86400",
        },
      });
    }

    return json({ ok: false, error: "not_found" }, 404);
  },
};

async function resolveIsbn(isbn, env) {
  const diagnostics = [];
  const jobs = [
    capture("nlr", diagnostics, () => nlrByQuery(isbn, isbn)),
    capture("rsl", diagnostics, () => rslSearch(`isbn:${isbn}`, isbn)),
    capture("google_books", diagnostics, () => googleByIsbn(isbn, env.GOOGLE_BOOKS_API_KEY || "")),
    capture("open_library", diagnostics, () => openLibraryByIsbn(isbn)),
  ];

  const settled = await Promise.all(jobs);
  const candidates = settled.filter(Boolean);
  if (!candidates.length) {
    return { ok: true, found: false, isbn13: isbn, book: null, diagnostics };
  }

  const book = mergeBooks(isbn, candidates);
  return {
    ok: true,
    found: Boolean(book.title),
    isbn13: isbn,
    book,
    diagnostics,
  };
}

async function resolveCover(bytes, contentType, env) {
  const diagnostics = [];
  let extracted;

  try {
    const base64 = bytesToBase64(bytes);
    const dataUrl = `data:${contentType.split(";")[0]};base64,${base64}`;
    const prompt = [
      "Это фотография обложки физической книги.",
      "Извлеки только то, что действительно видно или однозначно читается на обложке.",
      "Не выдумывай ISBN, издательство, год или автора.",
      "Верни строго JSON без markdown:",
      '{"title":"","authors":[""],"isbn13":"","publisher":"","publicationType":"BOOK|COMIC|GRAPHIC_NOVEL|MANGA|ARTBOOK|TEXTBOOK|CHILDRENS_BOOK|MAGAZINE|OTHER","confidence":0.0}',
      "Русский и кириллица допустимы. Если ISBN на лицевой стороне не виден, оставь isbn13 пустым.",
    ].join("\n");

    const aiResult = await env.AI.run("@cf/meta/llama-3.2-11b-vision-instruct", {
      messages: [
        { role: "system", content: "Ты OCR-библиограф. Ничего не додумывай; извлекай данные только с изображения." },
        { role: "user", content: prompt },
      ],
      image: dataUrl,
      max_tokens: 320,
      temperature: 0.1,
    });

    const raw = typeof aiResult === "string" ? aiResult : (aiResult?.response || aiResult?.result || JSON.stringify(aiResult));
    extracted = parseAiJson(raw);
    diagnostics.push({ source: "workers_ai_vision", status: "FOUND", detail: `confidence=${Number(extracted.confidence || 0).toFixed(2)}` });
  } catch (error) {
    diagnostics.push({ source: "workers_ai_vision", status: statusFromError(error), detail: safeDetail(error) });
    return { ok: false, found: false, error: "cover_recognition_failed", diagnostics };
  }

  const extractedIsbn = normalizeIsbn(extracted.isbn13 || "");
  if (isValidIsbn13(extractedIsbn)) {
    const resolved = await resolveIsbn(extractedIsbn, env);
    return {
      ...resolved,
      diagnostics: [...diagnostics, ...(resolved.diagnostics || [])],
      recognition: sanitizeExtraction(extracted),
    };
  }

  const title = cleanText(extracted.title || "");
  const authors = Array.isArray(extracted.authors) ? extracted.authors.map(cleanText).filter(Boolean) : [cleanText(extracted.authors || "")].filter(Boolean);
  if (!title) {
    return { ok: true, found: false, book: null, diagnostics, recognition: sanitizeExtraction(extracted) };
  }

  const query = [title, authors[0]].filter(Boolean).join(" ");
  const searchDiagnostics = [];
  const candidates = (await Promise.all([
    capture("nlr_title", searchDiagnostics, () => nlrByQuery(query, null)),
    capture("rsl_title", searchDiagnostics, () => rslSearch(buildRslTextQuery(title, authors[0] || ""), null)),
    capture("google_books_title", searchDiagnostics, () => googleByText(title, authors[0] || "", env.GOOGLE_BOOKS_API_KEY || "")),
    capture("open_library_title", searchDiagnostics, () => openLibraryByText(title, authors[0] || "")),
  ])).filter(Boolean);

  diagnostics.push(...searchDiagnostics);
  const ranked = candidates
    .map((book) => ({ book, score: candidateScore(book, title, authors) }))
    .sort((a, b) => b.score - a.score);

  const best = ranked[0];
  if (best && best.score >= 0.46) {
    const isbn = normalizeIsbn(best.book.isbn13 || "");
    const merged = mergeBooks(isValidIsbn13(isbn) ? isbn : "", candidates);
    merged.metadataSource = uniqueSources(candidates).join("+");
    return {
      ok: true,
      found: true,
      book: merged,
      diagnostics,
      recognition: sanitizeExtraction(extracted),
      matchConfidence: Math.min(0.99, best.score),
    };
  }

  return {
    ok: true,
    found: true,
    book: {
      isbn13: "",
      isbn10: "",
      title,
      subtitle: "",
      authors: authors.join(", "),
      publisher: cleanText(extracted.publisher || ""),
      publishedYear: "",
      pages: "",
      categories: "",
      description: "",
      coverUrl: "",
      publicationType: normalizePublicationType(extracted.publicationType),
      metadataSource: "workers_ai_cover",
    },
    diagnostics,
    recognition: sanitizeExtraction(extracted),
    matchConfidence: Number(extracted.confidence || 0),
  };
}

async function capture(source, diagnostics, fn) {
  const started = Date.now();
  try {
    const value = await fn();
    diagnostics.push({
      source,
      status: value ? "FOUND" : "NOT_FOUND",
      detail: `${Date.now() - started}ms`,
    });
    return value;
  } catch (error) {
    diagnostics.push({
      source,
      status: statusFromError(error),
      detail: `${Date.now() - started}ms ${safeDetail(error)}`.trim(),
    });
    return null;
  }
}

async function googleByIsbn(isbn, apiKey) {
  const q = encodeURIComponent(`isbn:${isbn}`);
  const key = apiKey ? `&key=${encodeURIComponent(apiKey)}` : "";
  const root = await fetchJson(`https://www.googleapis.com/books/v1/volumes?q=${q}&maxResults=10&printType=books${key}`);
  const items = Array.isArray(root.items) ? root.items : [];
  for (const item of items) {
    const info = item?.volumeInfo || {};
    const identifiers = Array.isArray(info.industryIdentifiers) ? info.industryIdentifiers : [];
    if (!identifiers.some((it) => normalizeIsbn(it?.identifier || "") === isbn)) continue;
    return googleInfoToBook(info, isbn);
  }
  return null;
}

async function googleByText(title, author, apiKey) {
  const raw = [`intitle:${quoteForGoogle(title)}`, author ? `inauthor:${quoteForGoogle(author)}` : ""].filter(Boolean).join(" ");
  const key = apiKey ? `&key=${encodeURIComponent(apiKey)}` : "";
  const root = await fetchJson(`https://www.googleapis.com/books/v1/volumes?q=${encodeURIComponent(raw)}&maxResults=10&printType=books${key}`);
  const items = Array.isArray(root.items) ? root.items : [];
  let best = null;
  let bestScore = 0;
  for (const item of items) {
    const info = item?.volumeInfo || {};
    const book = googleInfoToBook(info, "");
    const score = candidateScore(book, title, author ? [author] : []);
    if (score > bestScore) {
      bestScore = score;
      best = book;
    }
  }
  return bestScore >= 0.40 ? best : null;
}

function googleInfoToBook(info, expectedIsbn) {
  const ids = Array.isArray(info.industryIdentifiers) ? info.industryIdentifiers : [];
  const isbn13 = normalizeIsbn(ids.find((x) => x?.type === "ISBN_13")?.identifier || expectedIsbn || "");
  const isbn10 = normalizeIsbn(ids.find((x) => x?.type === "ISBN_10")?.identifier || "");
  const images = info.imageLinks || {};
  const coverUrl = images.extraLarge || images.large || images.medium || images.small || images.thumbnail || images.smallThumbnail || "";
  const categories = Array.isArray(info.categories) ? info.categories.join(", ") : "";
  return {
    source: "google_books",
    isbn13,
    isbn10,
    title: cleanText(info.title || ""),
    subtitle: cleanText(info.subtitle || ""),
    authors: Array.isArray(info.authors) ? info.authors.map(cleanText).filter(Boolean).join(", ") : "",
    publisher: cleanText(info.publisher || ""),
    publishedYear: extractYear(info.publishedDate || ""),
    pages: positiveString(info.pageCount),
    categories,
    description: cleanText(info.description || ""),
    coverUrl: String(coverUrl).replace(/^http:/, "https:").replace("&edge=curl", "").replace("zoom=1", "zoom=2"),
    publicationType: classify(categories),
  };
}

async function openLibraryByIsbn(isbn) {
  const key = `ISBN:${isbn}`;
  const root = await fetchJson(`https://openlibrary.org/api/books?bibkeys=${encodeURIComponent(key)}&jscmd=data&format=json`);
  const b = root[key];
  if (!b) return null;
  const ids = b.identifiers || {};
  return {
    source: "open_library",
    isbn13: isbn,
    isbn10: normalizeIsbn(Array.isArray(ids.isbn_10) ? ids.isbn_10[0] || "" : ""),
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

async function openLibraryByText(title, author) {
  const params = new URLSearchParams({ title, limit: "10" });
  if (author) params.set("author", author);
  params.set("fields", "title,subtitle,author_name,first_publish_year,publish_year,publisher,number_of_pages_median,subject,cover_i,isbn");
  const root = await fetchJson(`https://openlibrary.org/search.json?${params}`);
  const docs = Array.isArray(root.docs) ? root.docs : [];
  let best = null;
  let bestScore = 0;
  for (const d of docs) {
    const isbns = Array.isArray(d.isbn) ? d.isbn.map(normalizeIsbn) : [];
    const isbn13 = isbns.find((x) => x.length === 13 && isValidIsbn13(x)) || "";
    const book = {
      source: "open_library_search",
      isbn13,
      isbn10: isbns.find((x) => x.length === 10) || "",
      title: cleanText(d.title || ""),
      subtitle: cleanText(d.subtitle || ""),
      authors: Array.isArray(d.author_name) ? d.author_name.map(cleanText).filter(Boolean).join(", ") : "",
      publisher: Array.isArray(d.publisher) ? cleanText(d.publisher[0] || "") : "",
      publishedYear: positiveString(d.first_publish_year || (Array.isArray(d.publish_year) ? d.publish_year[0] : "")),
      pages: positiveString(d.number_of_pages_median),
      categories: Array.isArray(d.subject) ? d.subject.slice(0, 12).join(", ") : "",
      description: "",
      coverUrl: d.cover_i ? `https://covers.openlibrary.org/b/id/${d.cover_i}-L.jpg` : "",
      publicationType: classify(Array.isArray(d.subject) ? d.subject.join(" ") : ""),
    };
    const score = candidateScore(book, title, author ? [author] : []);
    if (score > bestScore) {
      best = book;
      bestScore = score;
    }
  }
  return bestScore >= 0.40 ? best : null;
}

async function nlrByQuery(query, expectedIsbn) {
  const searchUrl = `https://nb.nlr.ru/opac-search.pl?q=${encodeURIComponent(query)}`;
  const html = await fetchText(searchUrl, "text/html,application/xhtml+xml");
  const ids = extractUnique(html, /(?:opac-detail|opac-MARCdetail)\.pl\?(?:[^"'<>]*?&(?:amp;)?)*biblionumber=(\d+)/gi, 6);
  for (const id of ids) {
    const marc = await fetchText(`https://nb.nlr.ru/opac-MARCdetail.pl?biblionumber=${encodeURIComponent(id)}`, "text/html,application/xhtml+xml");
    const detail = await fetchText(`https://nb.nlr.ru/opac-detail.pl?biblionumber=${encodeURIComponent(id)}`, "text/html,application/xhtml+xml");
    const book = parseNlrPages(marc, detail, expectedIsbn);
    if (book) return book;
  }
  return null;
}

function parseNlrPages(marcHtml, detailHtml, expectedIsbn) {
  const marcText = htmlToText(marcHtml);
  const detailText = htmlToText(detailHtml);
  const all = `${detailText}\n${marcText}`;
  const foundIsbns = extractIsbns(all);
  if (expectedIsbn && !foundIsbns.includes(expectedIsbn)) return null;

  let title = htmlTitle(detailHtml)
    .replace(/^Подробности\s*:\s*/i, "")
    .replace(/\s*[›|-]\s*Национальная библиография каталог.*$/i, "")
    .trim();
  if (!title || /Национальная библиография каталог/i.test(title)) {
    title = fieldAfter(marcText, ["Основное заглавие", "Заглавие", "Собственно заглавие"]);
  }

  const authors = uniqueNonBlank([
    fieldAfter(marcText, ["Первые сведения об ответственности", "Сведения об ответственности"]),
    ...fieldAll(marcText, ["Фамилия", "Имя лица"]),
  ]).join(", ");

  const publisher = fieldAfter(marcText, ["Имя издателя, распространителя", "Издательство, распространитель", "Издательство"]);
  const publishedYear = extractYear(
    fieldAfter(marcText, ["Дата издания, распространения и т.д.", "Дата публикации", "Год издания"]) || all
  );
  const pagesRaw = fieldAfter(marcText, ["Специфическое обозначение материала и объем", "Физическое описание", "Объем"]);
  const pages = firstNumber(pagesRaw || "");
  const categories = uniqueNonBlank(fieldAll(marcText, ["Тематический термин", "Предметная рубрика", "Ключевое слово"]).slice(0, 10)).join(", ");
  const isbn13 = foundIsbns.find((x) => x.length === 13 && isValidIsbn13(x)) || expectedIsbn || "";
  const isbn10 = foundIsbns.find((x) => x.length === 10) || "";
  const coverUrl = firstUrl(all, /https:\/\/vivaldi\.nlr\.ru\/[^\s<]+\/cover/i);

  if (!title) return null;
  return {
    source: "nlr_national_bibliography",
    isbn13,
    isbn10,
    title: cleanText(title),
    subtitle: "",
    authors: cleanText(authors),
    publisher: cleanText(publisher || ""),
    publishedYear,
    pages,
    categories,
    description: "",
    coverUrl,
    publicationType: classify(categories),
  };
}

async function rslSearch(query, expectedIsbn) {
  const searchPage = await fetchRaw("https://search.rsl.ru/ru/search", { headers: browserHeaders("text/html,application/xhtml+xml") });
  const pageHtml = await searchPage.text();
  if (!searchPage.ok) throw httpError(searchPage.status, pageHtml);
  const token = pageHtml.match(/<meta[^>]+name=["']csrf-token["'][^>]+content=["']([^"']+)["']/i)?.[1]
    || pageHtml.match(/<meta[^>]+content=["']([^"']+)["'][^>]+name=["']csrf-token["']/i)?.[1];
  if (!token) throw new Error("PARSE_ERROR: csrf token not found");
  const cookies = readSetCookie(searchPage.headers);

  const form = new URLSearchParams();
  form.set("SearchFilterForm[search]", query);
  const response = await fetchRaw("https://search.rsl.ru/site/ajax-search?language=ru", {
    method: "POST",
    headers: {
      ...browserHeaders("application/json,text/html,*/*"),
      "content-type": "application/x-www-form-urlencoded; charset=UTF-8",
      "x-csrf-token": decodeHtml(token),
      "x-requested-with": "XMLHttpRequest",
      referer: "https://search.rsl.ru/ru/search",
      cookie: cookies,
    },
    body: form.toString(),
  });
  const body = await response.text();
  if (!response.ok) throw httpError(response.status, body);

  const ids = extractUnique(body, /(?:\/ru\/record\/|record["'\\/:]+)(0?\d{7,12})/gi, 6);
  for (const id of ids) {
    const html = await fetchText(`https://search.rsl.ru/ru/record/${id}`, "text/html,application/xhtml+xml");
    const book = parseRslPage(html, expectedIsbn);
    if (book) return book;
  }
  return null;
}

function parseRslPage(html, expectedIsbn) {
  const text = htmlToText(html);
  const isbns = extractIsbns(text);
  if (expectedIsbn && !isbns.includes(expectedIsbn)) return null;

  let title = fieldAfter(text, ["Заглавие"]);
  const authors = fieldAfter(text, ["Сведения об ответственности"]);
  const output = fieldAfter(text, ["Выходные данные"]);
  const physical = fieldAfter(text, ["Физическое описание"]);
  const categories = uniqueNonBlank(fieldAll(text, ["Термины-указатели", "Тематика"])).join(", ");

  const pageTitle = htmlTitle(html).replace(/\s*-\s*Search RSL.*$/i, "").trim();
  if (!title && pageTitle) {
    const segments = pageTitle.split(/\s+-\s+/).map(cleanText).filter(Boolean);
    title = segments.length > 1 ? segments[1] : segments[0] || "";
  }

  let publisher = "";
  if (output) {
    const m = output.match(/:\s*([^,;]+)(?:,|;|$)/);
    if (m) publisher = cleanText(m[1]);
  }

  const isbn13 = isbns.find((x) => x.length === 13 && isValidIsbn13(x)) || expectedIsbn || "";
  return title ? {
    source: "rsl",
    isbn13,
    isbn10: isbns.find((x) => x.length === 10) || "",
    title: cleanText(title),
    subtitle: "",
    authors: cleanText(authors || ""),
    publisher,
    publishedYear: extractYear(output || text),
    pages: firstNumber(physical || ""),
    categories,
    description: "",
    coverUrl: extractImageUrl(html),
    publicationType: classify(categories),
  } : null;
}

function mergeBooks(isbn, candidates) {
  const preferred = [...candidates].sort((a, b) => sourcePriority(a.source) - sourcePriority(b.source));
  const coverPreferred = [...candidates].sort((a, b) => coverPriority(a.source) - coverPriority(b.source));
  const pick = (field, list = preferred) => list.map((x) => cleanText(x?.[field] || "")).find(Boolean) || "";
  const categoryParts = uniqueNonBlank(candidates.map((x) => x.categories || ""));
  const categories = categoryParts.join(", ");
  const isbn13 = normalizeIsbn(isbn || pick("isbn13"));
  return {
    isbn13,
    isbn10: normalizeIsbn(pick("isbn10")),
    title: pick("title"),
    subtitle: pick("subtitle"),
    authors: pick("authors"),
    publisher: pick("publisher"),
    publishedYear: pick("publishedYear"),
    pages: pick("pages"),
    categories,
    description: pick("description"),
    coverUrl: pick("coverUrl", coverPreferred) || (isbn13 ? `https://covers.openlibrary.org/b/isbn/${isbn13}-L.jpg?default=false` : ""),
    publicationType: preferred.map((x) => x.publicationType).find((x) => x && x !== "BOOK") || classify(categories),
    metadataSource: uniqueSources(candidates).join("+"),
  };
}

function sourcePriority(source) {
  if (source === "nlr_national_bibliography") return 0;
  if (source === "rsl") return 1;
  if (source === "google_books") return 2;
  if (String(source).startsWith("open_library")) return 3;
  return 9;
}
function coverPriority(source) {
  if (source === "google_books") return 0;
  if (String(source).startsWith("open_library")) return 1;
  if (source === "nlr_national_bibliography") return 2;
  return 5;
}
function uniqueSources(candidates) {
  return [...new Set(candidates.map((x) => x.source).filter(Boolean))];
}

function candidateScore(book, wantedTitle, wantedAuthors) {
  const titleScore = textSimilarity(book.title || "", wantedTitle || "");
  const authorNeedle = Array.isArray(wantedAuthors) ? wantedAuthors.join(" ") : String(wantedAuthors || "");
  const authorScore = authorNeedle ? textSimilarity(book.authors || "", authorNeedle) : 0.5;
  return titleScore * 0.78 + authorScore * 0.22;
}

function textSimilarity(a, b) {
  const aa = tokens(a);
  const bb = tokens(b);
  if (!aa.size || !bb.size) return 0;
  let intersection = 0;
  for (const t of aa) if (bb.has(t)) intersection++;
  const union = new Set([...aa, ...bb]).size;
  return union ? intersection / union : 0;
}
function tokens(value) {
  return new Set(normalizeText(value).split(/\s+/).filter((x) => x.length > 1));
}
function normalizeText(value) {
  return String(value || "").toLowerCase().replace(/ё/g, "е").replace(/[^a-zа-я0-9]+/gi, " ").trim();
}

async function fetchJson(url) {
  return JSON.parse(await fetchText(url, "application/json"));
}
async function fetchText(url, accept = "*/*") {
  const response = await fetchRaw(url, { headers: browserHeaders(accept) });
  const text = await response.text();
  if (!response.ok) throw httpError(response.status, text);
  return text;
}
async function fetchRaw(url, init = {}) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort("timeout"), SOURCE_TIMEOUT_MS);
  try {
    return await fetch(url, { ...init, signal: controller.signal, redirect: "follow" });
  } catch (error) {
    if (String(error?.name || "").includes("Abort") || String(error).toLowerCase().includes("timeout")) {
      throw new Error("TIMEOUT");
    }
    throw error;
  } finally {
    clearTimeout(timer);
  }
}
function browserHeaders(accept) {
  return {
    accept,
    "accept-language": "ru,en;q=0.8",
    "user-agent": USER_AGENT,
  };
}
function httpError(status, text) {
  return new Error(`HTTP_${status}: ${String(text || "").replace(/\s+/g, " ").slice(0, 120)}`);
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
function htmlTitle(html) {
  const m = String(html || "").match(/<title[^>]*>([\s\S]*?)<\/title>/i);
  return cleanText(m ? htmlToText(m[1]) : "");
}
function fieldAfter(text, labels) {
  const lines = String(text || "").split(/\n+/).map(cleanText).filter(Boolean);
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    for (const label of labels) {
      const index = line.toLowerCase().indexOf(label.toLowerCase());
      if (index >= 0) {
        const inline = cleanText(line.slice(index + label.length).replace(/^\s*[|:\-–—]+\s*/, ""));
        if (inline && inline.toLowerCase() !== label.toLowerCase()) return inline;
        for (let j = i + 1; j < Math.min(lines.length, i + 4); j++) {
          const candidate = cleanText(lines[j].replace(/^\s*[|:\-–—]+\s*/, ""));
          if (candidate && !looksLikeFieldHeader(candidate)) return candidate;
        }
      }
    }
  }
  return "";
}
function fieldAll(text, labels) {
  const out = [];
  const lines = String(text || "").split(/\n+/).map(cleanText).filter(Boolean);
  for (let i = 0; i < lines.length; i++) {
    for (const label of labels) {
      if (lines[i].toLowerCase().includes(label.toLowerCase())) {
        const inline = cleanText(lines[i].slice(lines[i].toLowerCase().indexOf(label.toLowerCase()) + label.length).replace(/^\s*[|:\-–—]+\s*/, ""));
        const candidate = inline || cleanText(lines[i + 1] || "");
        if (candidate && !looksLikeFieldHeader(candidate)) out.push(candidate);
      }
    }
  }
  return out;
}
function looksLikeFieldHeader(value) {
  return /^(\d{3}\s*[#0-9A-Za-z]{0,2}\s*[-–—]|[-–—]+$|[A-ZА-ЯЁ0-9 ()/.-]{18,}$)/.test(value);
}
function extractUnique(text, regex, limit = 10) {
  const values = [];
  let m;
  while ((m = regex.exec(text)) && values.length < limit) {
    if (!values.includes(m[1])) values.push(m[1]);
  }
  return values;
}
function extractIsbns(text) {
  const out = [];
  const regex = /(?:97[89][\s-]*)?[0-9Xx][0-9Xx\s-]{8,20}[0-9Xx]/g;
  for (const match of String(text || "").matchAll(regex)) {
    const value = normalizeIsbn(match[0]);
    if ((value.length === 10 || value.length === 13) && !out.includes(value)) out.push(value);
  }
  return out;
}
function extractImageUrl(html) {
  const urls = [...String(html || "").matchAll(/https?:\\?\/\\?\/[^"'<>\s]+\.(?:jpg|jpeg|png|webp)(?:\?[^"'<>\s]*)?/gi)]
    .map((m) => m[0].replace(/\\\//g, "/").replace(/^http:/, "https:"));
  return urls.find((x) => !/logo|icon|sprite|avatar/i.test(x)) || "";
}
function firstUrl(text, regex) {
  const m = String(text || "").match(regex);
  return m ? m[0].replace(/[),.;]+$/, "") : "";
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
function classify(categories) {
  const c = normalizeText(categories);
  if (/\bманга\b|\bmanga\b/.test(c)) return "MANGA";
  if (c.includes("графическ") || c.includes("graphic novel")) return "GRAPHIC_NOVEL";
  if (c.includes("комикс") || c.includes("comic")) return "COMIC";
  if (c.includes("артбук") || c.includes("artbook")) return "ARTBOOK";
  if (c.includes("учебник") || c.includes("textbook") || c.includes("education")) return "TEXTBOOK";
  if (c.includes("детск") || c.includes("children") || c.includes("juvenile")) return "CHILDRENS_BOOK";
  if (c.includes("журнал") || c.includes("magazine") || c.includes("periodical")) return "MAGAZINE";
  return "BOOK";
}
function normalizePublicationType(value) {
  const allowed = new Set(["BOOK", "COMIC", "GRAPHIC_NOVEL", "MANGA", "ARTBOOK", "TEXTBOOK", "CHILDRENS_BOOK", "MAGAZINE", "OTHER"]);
  const v = String(value || "").toUpperCase();
  return allowed.has(v) ? v : "BOOK";
}
function quoteForGoogle(value) {
  return `"${String(value || "").replace(/["\\]/g, " ").trim()}"`;
}
function buildRslTextQuery(title, author) {
  if (title && author) return `title:${title} author:${author}`;
  return title || author;
}
function parseAiJson(raw) {
  const value = String(raw || "").replace(/```(?:json)?/gi, "").replace(/```/g, "").trim();
  const first = value.indexOf("{");
  const last = value.lastIndexOf("}");
  if (first < 0 || last <= first) throw new Error("PARSE_ERROR: AI returned no JSON");
  return JSON.parse(value.slice(first, last + 1));
}
function sanitizeExtraction(value) {
  return {
    title: cleanText(value?.title || ""),
    authors: Array.isArray(value?.authors) ? value.authors.map(cleanText).filter(Boolean) : [],
    isbn13: normalizeIsbn(value?.isbn13 || ""),
    publisher: cleanText(value?.publisher || ""),
    publicationType: normalizePublicationType(value?.publicationType),
    confidence: Math.max(0, Math.min(1, Number(value?.confidence || 0))),
  };
}
function bytesToBase64(bytes) {
  let binary = "";
  const chunk = 0x8000;
  for (let i = 0; i < bytes.length; i += chunk) {
    binary += String.fromCharCode(...bytes.subarray(i, Math.min(i + chunk, bytes.length)));
  }
  return btoa(binary);
}
function readSetCookie(headers) {
  if (typeof headers.getSetCookie === "function") {
    return headers.getSetCookie().map((x) => x.split(";", 1)[0]).join("; ");
  }
  return String(headers.get("set-cookie") || "").split(/,(?=[^;,]+=)/).map((x) => x.split(";", 1)[0]).join("; ");
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
function json(value, status = 200, extraHeaders = {}) {
  return new Response(JSON.stringify(value), { status, headers: { ...JSON_HEADERS, ...extraHeaders } });
}
function withCors(response) {
  const headers = new Headers(response.headers);
  headers.set("access-control-allow-origin", "*");
  return new Response(response.body, { status: response.status, statusText: response.statusText, headers });
}
