import assert from "node:assert/strict";
import worker from "../src/index.js";

const SAMPLE_ISBN = "9785915225021";
const SAMPLE_TITLE = "Динамика неустойчивости. Кинетическое моделирование и методы управления";

globalThis.caches = {
  default: {
    async match() { return null; },
    async put() {},
  },
};

globalThis.fetch = async (input) => {
  const url = typeof input === "string" ? input : input.url;
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
  if (url.includes("search.rsl.ru/ru/search")) return new Response("blocked", { status: 403 });
  if (url.includes("googleapis.com/books")) return Response.json({ totalItems: 0 });
  if (url.includes("openlibrary.org/api/books")) return Response.json({});
  if (url.includes("openlibrary.org/search.json")) return Response.json({ docs: [] });
  throw new Error(`Unexpected fetch: ${url}`);
};

const context = { waitUntil() {} };

const health = await worker.fetch(new Request("https://bookshelf.test/health"), {}, context);
assert.equal(health.status, 200);
assert.equal((await health.json()).version, "1.1.0");

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

console.log("BookShelf resolver smoke tests passed");
