# BookShelf Resolver

Cloudflare Worker for BookShelf 1.1.0.

Endpoints:

- `GET /health`
- `GET /v1/books/isbn/{isbn13}`
- `POST /v1/books/cover` with `Content-Type: image/jpeg`

ISBN resolver combines NLR National Bibliography, RSL, Google Books and Open Library. Cover recognition uses the Workers AI binding declared in `wrangler.jsonc`, then validates/searches extracted bibliographic text against catalog sources.

Run locally:

```bash
npm install
npm run check
npx wrangler dev
```

Deploy:

```bash
npx wrangler deploy
```

Optional Google Books key:

```bash
printf '%s' 'YOUR_KEY' | npx wrangler secret put GOOGLE_BOOKS_API_KEY
```
