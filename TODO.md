# TODO

## OverDrive: lending partnerships (Libby 22.1+)

Blocked until one of our linked libraries has a partnership to test against. As of 2026-10-09 none
does: Thunder `/v2/libraries/{key}` returns `visitableLibraries: []` for jocolibrary, kclibrary,
lawrencepl, mcpl and tscpl.

What 22.1.1 added, read from the bundle (no capture yet):

- A home card (`residency() == "home"`, not an instant card) can borrow at the libraries in its
  library's `visitableLibraries`. Libby offers "Partner Libraries" on that card's view, not in the
  main menu, and only when a partner exists that the patron has no card at.
- Access is created by `POST auth/exchange/{homeCardId}/{partnerWebsiteId}` with the chip bearer
  (`bosses/sentry.js` `exchangeForPartnerCard`), optionally after a home-card verify step
  (`interview-circ-partner-card.js`). Success adds a card at the partner library to the chip, then
  Libby re-syncs. Failures: `card_suspended` and permanent failures show "not entitled".
- `circ.error.partnership-ended`: no new borrows or renewals; existing loans and holds keep working.

Plan, once a partnership exists:

1. Detect it: have the daily `LibbyClientVersionGuard` pass (or the auto-sync) note when any linked
   library's `visitableLibraries` becomes non-empty, and surface it on the settings page.
2. Capture a HAR in Firefox: card view → Partner Libraries → Enable (with verify if asked), then a
   hold on a partner-only title at a second partner. Record the request, response and following sync
   in `scripts/libby-client/PRIORS.md`; add `interview-circ-partner-card.js` and
   `models/items/library.js` to the `libby-diff` tracked modules.
3. Decide on borrow limits before building: whether partner loans count against the home card at
   OverDrive, and whether a partner card gets its own ceilings or shares the home card's.
4. Read-only first: show partner-only titles in catalog search ("available at X via partnership").
5. Only then, the exchange itself, matching the capture exactly and versioned through
   `LibbyClientProfile`. Treat a card that appears on the chip without being linked in Grimmory (an
   exchange done in Libby) as a normal card on the next sync.
