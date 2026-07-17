# OverDrive / Libby API reference

Notes on the (undocumented) OverDrive/Libby endpoints the Grimmory OverDrive integration uses, with
example queries and observed response shapes. "Libby" is a client of OverDrive's APIs — there is no
separate Libby backend. These APIs are unofficial and may change without notice.

Legend: ✅ verified live from this repo · ⚠️ from the reference / unverified against a live linked
account (needs a real setup code to confirm exact shapes).

Reference implementation used: <https://github.com/sgmoore/libby-calibre-plugin> (and pylibby).

---

## Hosts

| Host | Purpose | Auth |
|------|---------|------|
| `https://sentry.libbyapp.com` | Libby identity, account **sync**, loans/holds, fulfillment | Bearer chip-identity JWT |
| `https://thunder.api.overdrive.com` | OverDrive "Thunder" **discovery/catalog** + library directory | none |
| `https://libbyapp.com` | Web app + branding manifest | none |

**Required headers** (Libby rejects non-browser agents):

```
User-Agent: Mozilla/5.0 (compatible; Grimmory)
Accept: application/json
Referer: https://libbyapp.com/
```

## Auth model

- A **chip** is an anonymous identity. `POST /chip` returns an `identity` JWT — **that JWT is the
  bearer token** for all authenticated calls.
- Redeeming a Libby **8-digit setup code** (`/chip/clone/code`) links a Libby account — and **all its
  library cards** — to that chip identity. One identity can hold many cards; a user can redeem several
  codes to link several accounts.
- Grimmory stores one token row **per (user, card)**; cards from the same setup code share the token.
  The card id (`cardId`) is what loans/borrow/holds are keyed on; the library `preferredKey`
  (advantage key, e.g. `lapl`) is what catalog search is scoped by.

---

## sentry.libbyapp.com (authenticated)

### 1. Obtain a chip identity ✅

```bash
curl -sS -X POST 'https://sentry.libbyapp.com/chip?client=dewey' \
  -H 'User-Agent: Mozilla/5.0 (compatible; Grimmory)' \
  -H 'Accept: application/json' -H 'Referer: https://libbyapp.com/'
```

```json
{ "chip": "155f7d87-…", "identity": "eyJhbGciOiJSUzI1NiJ9.…<JWT>…", "syncable": false, "primary": true }
```

`identity` is the bearer token. `syncable:false` until a card is linked.

### 2. Redeem a setup code (link an account + its cards) ⚠️

```bash
curl -sS -X POST 'https://sentry.libbyapp.com/chip/clone/code' \
  -H "Authorization: Bearer $IDENTITY" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -H 'User-Agent: Mozilla/5.0 (compatible; Grimmory)' -H 'Referer: https://libbyapp.com/' \
  --data 'code=12345678'
```

Get the code at libbyapp.com → Settings → **Copy to another device** (8 digits, short-lived,
single-use). After this, `GET /chip/sync` returns the linked cards.

### 3. Sync (cards, loans, holds) ✅ (shape confirmed on an unlinked chip)

```bash
curl -sS 'https://sentry.libbyapp.com/chip/sync' \
  -H "Authorization: Bearer $IDENTITY" \
  -H 'User-Agent: Mozilla/5.0 (compatible; Grimmory)' -H 'Accept: application/json' \
  -H 'Referer: https://libbyapp.com/'
```

```json
{ "result": "synchronized", "cards": [], "loans": [], "holds": [], "stashes": {}, "summary": {} }
```

- **No top-level `libraries`** field (do not rely on one).
- ⚠️ Populated `cards[]` entries carry (per reference): `cardId`, `advantageKey` (the library's
  preferred key), and a `library` object (with `name`/`advantageKey`). Grimmory resolves the display
  name from Thunder instead (see library lookup below), falling back to these.
- `loans[]` / `holds[]` carry `id` (the **title id**), `title`, `creators[]`, `formats[]` (`{id}`),
  `expireDate`, `estimatedWaitDays`, etc.

### 4. Borrow a title ⚠️

```bash
curl -sS -X POST "https://sentry.libbyapp.com/card/$CARD_ID/loan/$TITLE_ID" \
  -H "Authorization: Bearer $IDENTITY" -H 'Content-Type: application/json' \
  -H 'User-Agent: Mozilla/5.0 (compatible; Grimmory)' -H 'Referer: https://libbyapp.com/' \
  --data '{"period":21,"units":"days","title_format":"ebook"}'
```

Borrow is by **title id** (not format). Response is a loan object including `id` and available
`formats[]`.

### 5. Return / holds ⚠️

```bash
# return a loan
curl -X DELETE "https://sentry.libbyapp.com/card/$CARD_ID/loan/$TITLE_ID"  -H "Authorization: Bearer $IDENTITY" …
# place a hold
curl -X POST   "https://sentry.libbyapp.com/card/$CARD_ID/hold/$TITLE_ID"  -H "Authorization: Bearer $IDENTITY" …
# cancel a hold
curl -X DELETE "https://sentry.libbyapp.com/card/$CARD_ID/hold/$TITLE_ID"  -H "Authorization: Bearer $IDENTITY" …
```

### 6. Fulfill a loan (download) ⚠️

```bash
curl -sSL "https://sentry.libbyapp.com/card/$CARD_ID/loan/$LOAN_ID/fulfill/$FORMAT" \
  -H "Authorization: Bearer $IDENTITY" -H 'Accept: */*' \
  -H 'User-Agent: Mozilla/5.0 (compatible; Grimmory)' -H 'Referer: https://libbyapp.com/'
```

`$FORMAT` is one of:

| format id | DRM | Grimmory handling |
|-----------|-----|-------------------|
| `ebook-epub-open` | none | fulfill → 302 → CDN → **EPUB bytes**, imported directly |
| `ebook-pdf-open`  | none | fulfill → 302 → CDN → **PDF bytes**, imported directly |
| `ebook-epub-adobe`| Adobe ACSM | returns a `.acsm` fulfillment token → external ACSM handler |
| `ebook-pdf-adobe` | Adobe ACSM | returns a `.acsm` fulfillment token → external ACSM handler |

Open formats redirect `API → fulfill.contentreserve.com → openepub/openpdf CDN`. The CDN hop is known
to 403 some HTTP clients (the reference uses a bare urllib opener). Adobe formats return only the ACSM
(a pointer, not the book); an operator-supplied ACSM handler must fulfill+decrypt it. Default format
preference: open EPUB → Adobe EPUB → open PDF → Adobe PDF.

---

## thunder.api.overdrive.com (no auth)

### 7. Catalog search ✅

```bash
curl -sS 'https://thunder.api.overdrive.com/v2/libraries/lapl/media?query=dune&mediaTypes=ebook&perPage=20&page=1' \
  -H 'User-Agent: Mozilla/5.0 (compatible; Grimmory)' -H 'Accept: application/json'
```

```json
{
  "totalItems": 123,
  "items": [
    {
      "id": "<titleId>", "title": "Dune", "subtitle": "…",
      "creators": [{ "name": "Frank Herbert", "role": "Author" }],
      "covers": { "cover150Wide": {"href": "…"}, "cover300Wide": {…}, "cover510Wide": {…} },
      "formats": [{ "id": "ebook-epub-adobe", "isbn": "9780441013593", "identifiers": [{"type":"ISBN","value":"…"}] }],
      "publisher": {"name": "…"}, "subjects": [{"name":"…"}], "languages": [{"name":"English"}],
      "publishDate": "…", "starRating": 4.5, "detailedSeries": {"seriesName":"Dune","readingOrder":"1"}
    }
  ]
}
```

`items[].id` is the **title id** (consistent across libraries) used to borrow. Grimmory searches the
admin `libraryKey` **plus** each of the user's card libraries, deduping by title id.

### 7b. Catalog browse with availability + facets ✅

The same `/media` endpoint accepts richer params for browsing (no `query=` needed) and returns
**availability** per item plus facet aggregations:

```bash
curl -sS 'https://thunder.api.overdrive.com/v2/libraries/jocolibrary/media?\
showOnlyAvailable=true&sortBy=mostpopular&mediaTypes=ebook&perPage=24&page=1&\
truncateDescription=false&\
includedFacets=availability&includedFacets=formats&includedFacets=subjects&includedFacets=languages&\
x-client-id=dewey' \
  -H 'User-Agent: Mozilla/5.0 (compatible; Grimmory)' -H 'Accept: application/json'
```

Notable params:

- `showOnlyAvailable=true` — only titles borrowable right now.
- `sortBy=` — one of `sortOptions[]`: `mostpopular` (Popularity), `relevance`, `releasedate`, `title`,
  `author`, …
- `perPage` / `page` with `totalItems` for pagination (e.g. jocolibrary ebooks: `totalItems: 42877`).
- `includedFacets=` (repeatable) — request facet aggregations; response `facets` is keyed by:
  `availability, mediaTypes, formats, maturityLevels, subjects, bisacCodes, languages, boolean,
  addedDates, atosLevels, lexileScores, interestLevels, gradeLevels, awards, audiobookDuration, …`
- `format=` — filters to Libby "read" formats (`ebook-overdrive`, …); **not** the download format
  ids in §6. Leave it off for general ebook browse.

Response top keys: `queryKeys, facets, sortOptions, items, totalItems, totalItemsText, links`.

Each `items[]` here carries **availability** fields (in addition to the search fields in §7):

```
reserveId (== id/titleId), isAvailable, availableCopies, ownedCopies, holdsCount, holdsRatio,
estimatedWaitDays, isHoldable, availabilityType ("normal"), isFastlane, isPreReleaseTitle,
luckyDayAvailableCopies, isOwned, starRating, starRatingCount, formats, covers, creators, subjects
```

Useful for the borrow UI: drive **Borrow (available) vs Place Hold (not available)** off `isAvailable`
/ `isHoldable`, show `estimatedWaitDays` and `holdsCount`, sort by popularity, and offer facet filters.
Grimmory now requests `includedFacets=availability` and threads these onto `OverDriveCatalogItem`
(see below).

### 8. Library lookup by preferred key ✅ — best source for library name/logo

```bash
curl -sS 'https://thunder.api.overdrive.com/v2/libraries/lapl' \
  -H 'User-Agent: Mozilla/5.0 (compatible; Grimmory)' -H 'Accept: application/json'
```

Returns `preferredKey`, `websiteId`, `name`, `fulfillmentId`, and
`settings.{primaryColor,secondaryColor,logo140X60.href}`, etc. Examples:

- `/v2/libraries/lapl` → `preferredKey: lapl`, `websiteId: 78`, `name: "Los Angeles Public Library"`,
  logo `https://thunder.cdn.overdrive.com/logo-resized/1047?…`
- `/v2/libraries/jocolibrary` → `websiteId: 370274`, `name: "Johnson County Library and Olathe Public Library"`

Grimmory uses this to resolve a linked card's display name from its `preferredKey` (no auth needed).

### 9. Library lookup by website id ✅

```bash
curl -sS 'https://thunder.api.overdrive.com/v2/libraries/?websiteIds=370274&x-client-id=dewey' \
  -H 'User-Agent: Mozilla/5.0 (compatible; Grimmory)' -H 'Accept: application/json'
```

Returns `{ "items": [ { "preferredKey", "websiteId", "name", … } ], "totalItems" }`. Accepts multiple
comma-separated `websiteIds`.

---

## libbyapp.com

### 10. Branding manifest ✅ (limited value)

```bash
curl -sS 'https://libbyapp.com/api/branding/libraries.json' \
  -H 'User-Agent: Mozilla/5.0 (compatible; Grimmory)'
```

Dict keyed by numeric library id → `{ key: "<advantageKey>", logos[], logomarks[], colors, urls }`.
~188 curated libraries; **names are almost entirely absent** (2/188), logo paths are relative to
`https://libbyapp.com/branding/…`. Not a name directory — use Thunder library lookup (#8) for names.

---

## How Grimmory maps these

| Grimmory | Endpoint |
|----------|----------|
| Connect (setup code) | `POST /chip` then `POST /chip/clone/code`; then `/chip/sync` to enumerate cards |
| Card display name | `GET thunder …/v2/libraries/{preferredKey}` → `name` |
| Catalog search | `GET thunder …/v2/libraries/{key}/media` across admin key + user card keys |
| Sync loans/holds | `GET /chip/sync` |
| Borrow & import | `POST /card/{cardId}/loan/{titleId}` → fulfill (open direct, Adobe via ACSM handler) → import |
| Return / holds | `DELETE`/`POST /card/{cardId}/loan|hold/{titleId}` |

Config: `app.overdrive.*` (feature flag, sentry base URL, client id, auto-return), the metadata
provider's `libraryKey` (admin), and `app.acsm.*` for the operator-supplied ACSM handler.

---

## Follow-ups / not yet implemented

Known open work and caveats for the OverDrive integration (as of this doc):

### Availability-aware search + Borrow-vs-Hold ✅ (implemented)

`OverDriveParser.fetchItems` now requests `includedFacets=availability`, and the per-item availability
fields from §7b (`isAvailable`, `isHoldable`, `availableCopies`, `ownedCopies`, `holdsCount`,
`estimatedWaitDays`, `isPreReleaseTitle`) are parsed onto `OverDriveApiResponse.Item` and threaded onto
`OverDriveCatalogItem` (backend record + frontend interface) as `available`, `holdable`,
`availableCopies`, `ownedCopies`, `holdsCount`, `estimatedWaitDays`, `preRelease`. Union-search dedupe
now **prefers an available copy** over an identical title that is only holdable in another library.

The catalog UI (`overdrive-catalog.component`) shows an **Availability** column and offers **Borrow &
Import when `available`**, **Place Hold when `!available && holdable`**, and no action for pre-release /
non-holdable titles, surfacing `estimatedWaitDays`/`holdsCount`/copy counts.

Still optional / not done: a "Show only available" (`showOnlyAvailable`) checkbox, a
popularity/relevance `sortBy` control, and facet filters.

### Other open items

- **Live shapes unverified.** Populated `cards[]` (advantageKey/library fields) and the full
  borrow → fulfill round trip have only been validated against the reference + an *unlinked* chip.
  Confirm with a real 8-digit setup code (see §2–§6). Card display names now come from Thunder (§8),
  which sidesteps the `cards[].library.name` uncertainty.
- **Open-format CDN 403.** The `…/fulfill/ebook-epub-open|pdf-open` → contentreserve → CDN hop can 403
  for some HTTP clients; the reference uses a bare `urllib` opener. If Spring's `RestClient` 403s on
  the final hop, fetch the redirect `Location` with a plain `HttpURLConnection`/`HttpClient`
  (`OverDriveService.fulfillOpen`).
- **Borrow card routing.** Union search dedupes by title id across libraries; borrow uses the
  *selected* card. If that card's library lacks the title the borrow errors — consider auto-routing to
  a card whose library has it.
- **Admin `libraryKey` validation.** ✅ Implemented. `GET /api/overdrive/resolve-library?key=…`
  (backed by `OverDriveService.resolveLibrary` → the parser's Thunder `/v2/libraries/{key}` lookup, §8)
  validates the key and returns the library's display name; the OverDrive settings page resolves on load
  and via a **Check** button, showing the resolved name or a "not found" error (a bogus key 404s →
  `valid=false`). Gating the borrow-vs-ACSM decision on a library's `formats[]` was considered and
  **skipped**: it is library-wide (not per-title) and `borrowAndImport` already picks the format from
  the authoritative per-loan `formats[]`. Autocomplete/type-ahead is still open.
- **Optional library logos.** `settings.logo140X60.href` (§8) or the branding manifest (§10) could
  brand the card picker; partial coverage, external hotlinking.
- **Uncommitted.** Multi-card, holds, union search, and Thunder name resolution are implemented and
  green but not yet committed/pushed; no fresh preview image built for them.
