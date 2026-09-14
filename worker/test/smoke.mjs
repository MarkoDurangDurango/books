import assert from "node:assert/strict";
import worker from "../src/index-v2.js";

const SAMPLE_ISBN = "9785915225021";
const SAMPLE_TITLE = "Динамика неустойчивости. Кинетическое моделирование и методы управления";
const LIVE_RESOLVER = "https://bookshelf-resolver.innernote.workers.dev";
const nativeFetch = globalThis.fetch;

globalThis.caches = {
  default: {
    async match() { return null; },
    async put() {},
  },
};

globalThis.fetch = async (input, init = {}) => {
  const url = typeof input === "string" ? input : input.url;

  if (url === "https://search.rsl.ru/ru/search") {
    return new Response('<html><head><meta name="csrf-token" content="test-token"></head></html>', {
      headers: { "set-cookie": "session=test; Path=/" },
    });
  }
  if (url.includes("search.rsl.ru/site/ajax-search")) {
    return new Response('<a href="/ru/record/01012345678">record</a>');
  }
  if (url.includes("search.rsl.ru/ru/record/01012345678")) {
    return new Response(`
      <html><head><title>Варфоломеев, Сергей Дмитриевич. - ${SAMPLE_TITLE} - Search RSL</title></head><body>
      <div>020 | ##</div><div>$a 978-5-91522-502-1</div>
      <div>245 | 10</div><div>$a ${SAMPLE_TITLE}</div><div>$c Сергей Варфоломеев</div>
      <div>260 | ##</div><div>$a Москва</div><div>$b Научный мир</div><div>$c 2021</div>
      <div>300 | ##</div><div>$a 281 с.</div>
      <div>650 | #0</div><div>$a биологическая кинетика</div>
      <div>Заглавие | Содержание</div>
      <div>Сведения об ответственности | Сергей Варфоломеев</div>
      <div>Выходные данные | Москва : Научный мир, 2021</div>
      <div>Физическое описание | 281 с.</div>
      </body></html>
    `);
  }

  if (url.startsWith("https://nb.nlr.ru/opac-search.pl")) {
    return new Response('<html><a href="/opac-detail.pl?biblionumber=123">record</a></html>');
  }
  if (url.includes("nb.nlr.ru/opac-MARCdetail.pl")) {
    return new Response(`
      <div>Номер (ISBN)</div><div>978-5-91522-502-1</div>
      <div>Первые сведения об ответственности</div><div>Сергей Варфоломеев</div>
      <div>Имя издателя, распространителя</div><div>Научный мир</div>
      <div>Дата издания, распространения и т.д.</div><div>2021</div>
      <div>Специфическое обозначение материала и объем</div><div>281 с.</div>
    `);
  }
  if (url.includes("nb.nlr.ru/opac-detail.pl")) {
    return new Response(`<html><head><title>Подробности: ${SAMPLE_TITLE} › Национальная библиография каталог</title></head><body>978-5-91522-502-1</body></html>`);
  }

  if (url.includes("googleapis.com/books")) return Response.json({ totalItems: 0 });
  if (url.includes("openlibrary.org/api/books")) return Response.json({});
  if (url.includes("openlibrary.org/search.json")) return Response.json({ docs: [] });
  throw new Error(`Unexpected fetch: ${url} ${init?.method || "GET"}`);
};

const context = { waitUntil() {} };

const health = await worker.fetch(new Request("https://bookshelf.test/health"), {}, context);
assert.equal(health.status, 200);
const healthJson = await health.json();
assert.equal(healthJson.version, "1.1.1");
assert.equal(healthJson.isbnResolver, "v2");

const isbnResponse = await worker.fetch(
  new Request(`https://bookshelf.test/v1/books/isbn/${SAMPLE_ISBN}`),
  {},
  context,
);
const isbnJson = await isbnResponse.json();
assert.equal(isbnJson.found, true);
assert.equal(isbnJson.book.title, SAMPLE_TITLE);
assert.equal(isbnJson.book.authors, "Сергей Варфоломеев");
assert.equal(isbnJson.book.publisher, "Научный мир");
assert.equal(isbnJson.book.publishedYear, "2021");
assert.equal(isbnJson.book.pages, "281");
assert.equal(isbnJson.book.isbn13, SAMPLE_ISBN);
assert.notEqual(isbnJson.book.isbn10, "0008210210");

const aiEnv = {
  AI: {
    async run() {
      return {
        response: JSON.stringify({
          title: SAMPLE_TITLE,
          authors: ["Сергей Варфоломеев"],
          isbn13: "",
          publisher: "",
          publicationType: "BOOK",
          confidence: 0.94,
        }),
      };
    },
  },
};

const coverResponse = await worker.fetch(
  new Request("https://bookshelf.test/v1/books/cover", {
    method: "POST",
    headers: { "content-type": "image/jpeg" },
    body: new Uint8Array([1, 2, 3, 4]),
  }),
  aiEnv,
  context,
);
const coverJson = await coverResponse.json();
assert.equal(coverJson.found, true);
assert.equal(coverJson.book.title, SAMPLE_TITLE);
assert.equal(coverJson.book.isbn13, SAMPLE_ISBN);
assert.ok(coverJson.matchConfidence >= 0.9);

console.log("BookShelf resolver v2 local smoke tests passed");

if (process.env.SKIP_LIVE_RESOLVER_TEST !== "1") {
  const liveHealth = await nativeFetch(`${LIVE_RESOLVER}/health`);
  const liveHealthText = await liveHealth.text();
  assert.equal(liveHealth.status, 200, `Live health failed: HTTP ${liveHealth.status} ${liveHealthText}`);

  const liveIsbn = await nativeFetch(`${LIVE_RESOLVER}/v1/books/isbn/${SAMPLE_ISBN}`);
  const liveIsbnText = await liveIsbn.text();
  console.log(`Live ISBN response: HTTP ${liveIsbn.status} ${liveIsbnText}`);
  assert.equal(liveIsbn.status, 200, `Live ISBN failed: HTTP ${liveIsbn.status} ${liveIsbnText}`);
  JSON.parse(liveIsbnText);
  console.log("BookShelf resolver live smoke test passed");
}
