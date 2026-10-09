# What we copied out of the Libby client, and where it came from

Each entry is a fact `OverDriveService` depends on, the module it was read out of, and how it was
confirmed. When `libby-diff` reports one of these modules as changed, this is the list to re-read.

Last checked against **dewey-22.1.2** (live as of 2026-10-09).

---

## Client version string

`c=d:22.1.2` on `POST /chip`.

- **Module:** not in the bundle — the version is the asset directory, `/dewey-<version>/`.
- **Ours:** one `LibbyClientProfile` per verified build, in `LibbyClientProfiles`. The server reads
  the live build off the index page once a day (`LibbyClientVersionGuard`) and speaks as its
  profile; for a build with no profile it sends nothing to OverDrive at all until one is added.
- **How to check:** `bin/libby-current`, or `curl -s https://libbyapp.com/ | grep -o 'dewey-[0-9.]*'`.
- **History:** `22.0.2` (July 2026) → `22.1.1` (by September 2026) → `22.1.2` (by 2026-10-09). We
  announced `22.0.2` until 2026-09-22, roughly two months after it stopped being served. `22.1.2`
  was adopted from the bundle alone; no browser capture of it yet.

## Chip mint query shape

- Anonymous mint: `POST /chip?c=d:<version>&s=0` — no `v`.
- Re-mint (bearer present): `POST /chip?c=d:<version>&s=0&v=<chip-id-prefix>`, where the prefix is
  the first dash-separated segment of the bearer's `chip.id`.
- **Module:** `app/base/bosses/sentry.js`, `app/base/services/service-sentry.js`.
- **22.1.1 → 22.1.2:** `bosses/sentry.js` changed, but not on the wire. Chip jobs are now queued with
  `prioritize: true` (the client's own queue ordering), and the default request headers are conveyed
  to the native shell as `sentry.headers`. `service-sentry.js` and `server.js` (where
  `_defaultRequestHeaders` lives) are unchanged.
- **Ours:** `requestChip()` and `refreshIdentity()`.
- **Confirmed:** browser HAR, 2026-07-16 (anonymous) and 2026-09-22 (re-mint). Both captures agree
  the parameter is `c`, not `client`. We sent `?client=dewey` until 2026-09-22 — it minted chips
  perfectly well, which is why it survived unnoticed.

## The Accept-Language shibboleth

On `POST /chip`, the client sends a two-letter `Accept-Language` derived from the bearer identity:
strip the JWT to `[a-z]`, reverse it, take 2 characters at an offset equal to the request path
length (`"chip"` → 4).

- **Module:** `obf/shib.js` — the only deliberately obfuscated module in the bundle.
- **Ours:** `chipShibboleth()`.
- **Confirmed:** end-to-end. A re-mint carrying it returns a `prbn`-absent identity that fulfills;
  without it the identity is stamped `prbn=v` and fulfillment returns `403 {"result":"whoa"}`.
- **22.0.2 → 22.1.1:** constants rotated (`a0putz` → `a0dress`, string-table indices shifted), shape
  identical. The algorithm did not change.
- **22.1.1 → 22.1.2:** rotated again, same shape.
- **Per-token, confirmed:** across the captures the chip mints carry fourteen different values —
  `bh bn dd eu ln lo ly nf pu sa uk uz vr zw` — which is what a derivation from the identity looks
  like, and why a fixed guess would be worse than computing it.
- **Open question:** the anonymous mint also carries one (`bh`, 2026-07-16) with no bearer to derive
  it from. The module has a fallback chain for that case — `a` falls back through two `APP` lookups
  and a default before being reversed — which is where the answer is, if it is wanted. We now send
  `en-US,en` there (from the common headers) rather than nothing; that is closer to a browser than
  silence, but it is not what the browser sends and is **not** verified as acceptable to the server.

## What a `whoa` means — and the notice that distinguishes two of them

`403 {"result":"whoa"}` on the fulfill endpoint comes in two forms that share a result code and mean
entirely different things. Matching on `result` alone cannot tell them apart.

- **Bare.** Body is exactly `{"result":"whoa"}` — 17 bytes, no `notice`, no Link header. **The Libby
  web client gets this.** Twice in the July captures (loans 12765179 and 537142, card 107556024), and
  its entire response was to POST the error to `sage.libbyapp.com/submit/error` and abandon that
  fulfillment — no re-mint, no re-link. That card kept working for months afterwards. Transient, per
  loan, no consequence.
- **With the notice.** Body carries a paragraph naming dewey a private API, calling reverse
  engineering of its requests prohibited, and citing account suspension and legal action; the response
  also carries `Link: <…/terms-and-conditions>; rel="terms-of-service"`. **The browser never receives
  this.** All ten of ours did, without exception. This is OverDrive stating it has identified the
  caller as something other than Libby.
- **Ours:** `identifiedAsAnotherClient()` in `OverDriveService` decides between them; only the second
  freezes the card.

## The fulfillment flow

The first fulfill attempt failing with `403 missing_chip` is **normal** — it happens on every
fulfillment the browser makes. The flow is:

```
GET  /card/{card}/loan/{loan}/fulfill/{format}   → 403 missing_chip
POST /chip?c=d:<ver>&s=0&v=<chip-id prefix>      → 200   (re-mint, with the shibboleth)
GET  /card/{card}/loan/{loan}/fulfill/{format}   → 200
GET  fulfill.contentreserve.com/….acsm           → 200
```

Seven of seven captured fulfillments follow it exactly. **The browser never re-links during
fulfillment** — there is no third recovery step. Ours adds one (`relinkCard`), which mints a brand-new
chip from the stored card + PIN on every failed pass; six fresh chips on one card in a day during the
September failures. That step is our most non-standard behaviour on this path and is not in the client.

## Headers

`User-Agent` a normal desktop Firefox, `Accept: application/json`, `Origin: https://libbyapp.com`,
and `Accept-Language: en-US,en` on **every** call.

- **Ours:** `libbyHeaders()`, `apiRequest()`, `ACCEPT_LANGUAGE`; the user agent from `FirefoxVersion`,
  which since 2026-10-09 picks the newest Firefox major released at least a week ago from Mozilla's
  `firefox_history_major_releases.json`, refreshed in the daily version check. Firefox ships every
  two weeks from September 2026, so a fixed version went stale twice a month.
- **Confirmed:** every HAR, without exception — 79 audiobook reads, 11 syncs, 16 fulfillments, all
  `en-US,en`. The only requests carrying anything else are the chip mints (see the shibboleth above).
- **History:** we sent **no** `Accept-Language` at all, on any request, until 2026-09-23. A browser
  always sends one, so its absence marked every call we made — including the fulfillments, which are
  what started being refused. Our UA also claimed Firefox 152 until 2026-09-22, where captures showed
  156.
- **No `Referer`, ever.** The page carries `<meta name="referrer" content="no-referrer">`, so the
  browser strips it from every API call: 205 of 205 sentry requests and 197 of 197 first-party thunder
  requests carry none. The twenty that do are from the reader/listener iframes on
  `dewey-<hash>.read.libbyapp.com`, a separate origin. We sent `https://libbyapp.com/` on every
  request until 2026-09-23; combined with the absent Accept-Language, our traffic carried two
  independent tells on every call — a header no real client sends, and the absence of one they all
  send. (An earlier note here said this applied only to the chip POST. It was based on one request
  and was wrong about the scope.)

## Circulation error codes

- `WEBSITE_MIGRATING` — new in 22.1.2 (`interview-circ-base.js`). The client shows
  `circ.error.website-migrating` and exits; it is the library moving platforms, not a refusal of the
  caller. **Ours:** not handled specifically; it surfaces as an ordinary upstream failure.

## Thunder client id

`x-client-id=dewey` as a query parameter on `thunder.api.overdrive.com` calls.

- **Ours:** `THUNDER_CLIENT_ID` in `OverDriveParser`.
- **Confirmed:** HAR, 2026-09-22. Unchanged since July.

---

## Not a prior, but worth recording

**One chip, many cards.** In the browser, all five library cards sit on a single chip
(`chip.cards` lists five entries under one `chip.id`). Our stored tokens are five separate
single-card chips, and every automatic re-link mints another. An account accumulating single-card
chips looks nothing like a browser account, independent of any version string. `unifyChip` exists to
collapse them.
