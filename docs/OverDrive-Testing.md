# OverDrive / Libby + ACSM — follow-up testing guide

Much of the OverDrive borrow → fulfill → ACSM → import chain was built and unit-tested, but the
end-to-end behaviour depends on a live Libby account, a configured Adobe/ACSM tool, and OverDrive's
rate limits — so it needs manual verification in a real deployment. This is the checklist.

See also [OverDrive-Libby-API.md](OverDrive-Libby-API.md) for the API/endpoint reference.

## 0. Prerequisites / configuration

- `OVERDRIVE_ENABLED=true` (feature flag).
- Metadata provider **library key** set + validated (Settings → OverDrive/Libby → *Check*).
- For Adobe-DRM titles, an external **ACSM handler** (e.g. go-degourou) configured:
  - `ACSM_ENABLED=true`, `ACSM_TOOL_PATH`, `ACSM_TOOL_ARGS` (`-f {acsm} -o {output} -account …`).
  - The account dir must be a **fully activated ADE account** and the mount **read-write** (the tool
    caches the license-service cert into `activation.xml` at fulfill time). See the ACSM notes in
    `deploy/compose/docker-compose.yml`.
- Optional: `OVERDRIVE_CREDENTIAL_KEY` (base64 16/24/32 bytes) to enable encrypted card-credential
  storage + silent re-link on token expiry. Unset ⇒ token-only (no auto-relink); everything else works.

A scripted reproduction of the borrow→fulfill contract lives in the session scratchpad
(`overdrive_verify.py`) — run it **once at a time** (repeated fulfills trip the `whoa` rate limit).

## 1. Account linking (three methods)

| Method | Where | Expected |
|--------|-------|----------|
| **Setup code** (8-digit) | Settings + catalog Connect | Links all cards on the Libby account; browse/borrow. |
| **Card number + PIN** | Settings + catalog | Links a **primary** chip; cards appear; creds stored encrypted iff `OVERDRIVE_CREDENTIAL_KEY` set. |
| **Paste identity token** | Settings + catalog | Links the cards on a browser identity token (primary). |

Verify for each: the card(s) appear in the picker, and `GET /api/overdrive/cards` lists them.
When no credential key is set, the card+PIN help text shows the `OVERDRIVE_CREDENTIAL_KEY` note.

## 2. Catalog search + availability

- Search by title/author/ISBN; results show across the admin key + all linked-card libraries, deduped.
- Availability drives the action: **Borrow & Import** when available, **Place Hold** when
  `!available && holdable`, dash otherwise. Wait-list shows est. days / holds.
- Union dedupe prefers an **available** copy over a holdable-only one for the same title.

## 3. Borrow & import

- **DRM-free (open EPUB/PDF)**: borrow → fulfills directly → imports. Book appears **live** (no reload).
- **Adobe-DRM**: borrow → ACSM fetched from Libby → handed to the ACSM handler → decrypted → imported.
  Requires the activated account (see §0). Book appears live.
- **Resume**: if a prior attempt borrowed but failed at fulfill/import, re-invoking borrow-and-import
  should **resume the existing loan** (no double-borrow), not error.
- Confirm the imported book carries the OverDrive cover/metadata.

## 4. ACSM upload (manual path — no server-side fulfillment needed)

Download a `.acsm` from Libby in a browser, then verify all three ingest paths convert it via the
handler and import the produced EPUB/PDF (only when a handler is configured):

- **Upload dialog → library destination** (`/api/v1/files/upload`).
- **Upload dialog → BookDrop destination** (`/api/v1/files/upload/bookdrop`) → appears in BookDrop review.
- **BookDrop folder on disk** → picked up by the periodic scan (`BookdropPeriodicScanTask`), converted
  in place (`.acsm` → `.epub`/`.pdf`), then ingested. (Real-time watcher does *not* convert — periodic only.)

The `.acsm` option only appears in the upload dialog when `acsmHandlerConfigured` is true.

## 5. Diagnostics (read-only)

- OverDrive page → **Diagnostics → Load** returns a passive snapshot: config, linked cards (with the
  chip decoded: **primary vs secondary**, account group, credential storage), and locally-recorded loans.
- It must make **no** live OverDrive calls and take no input (it must not affect rate limiting).

## 6. Error surfacing

- A failing ACSM tool (bad/short activation, wrong key, rate-limited) surfaces the tool's **actual
  output** in the UI error (via `AcsmHandler`), not a generic message.
- `whoa` from Libby is treated as a hard **rate-limit**: fail fast with a clear message, no auto-retry.

## Known caveats / open questions

- **`whoa` = rate limit.** Repeated fulfill attempts (esp. during debugging) trip it; back off (hours).
- **Primary vs secondary chip.** Whether Adobe fulfillment *requires* a primary (card+PIN / pasted
  token) chip vs a setup-code (secondary) chip is **unverified** — every "secondary fails" data point
  during development was confounded by rate limiting. Worth a clean, un-throttled test: fulfill with a
  plain setup-code token. If it works, card+PIN/token are conveniences, not requirements.
- **One-shot ACSM.** An `.acsm` can only be fulfilled once, bound to the first Adobe identity; a burned
  token returns `E_LIC_ALREADY_FULFILLED_BY_ANOTHER_USER`. Return + re-borrow for a fresh one.
- **Account consistency (go-degourou).** `activation.xml` and `adobekey.der` must come from the *same*
  activation; the tool was patched to always re-export the key and to refuse a non-empty account dir.
- **Live import latency.** Borrowed books should appear immediately (complete `BookAddedEvent` payload);
  if a lag remains it's the async event timing, not the payload.
