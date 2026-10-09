# Libby client archive

Grimmory talks to OverDrive by presenting itself as the Libby web client. That means a set of facts
copied out of somebody else's minified JavaScript — the version string we announce, the query shape
on `/chip`, the `Accept-Language` shibboleth, the headers — and those facts go stale silently. This
folder is how they stop going stale silently.

## Why it exists

**OverDrive keeps exactly one version.** The moment `22.1.1` shipped, `/dewey-22.0.2/` began
returning 404. There is no archive, no tags, no changelog. A version not captured while it was live
is gone, and with it any chance of asking what changed when something breaks.

We announced `d:22.0.2` for two months after OverDrive stopped serving it. The only surviving copy of
that bundle is the one recovered from a browser HAR in `~/Downloads` — which is exactly the situation
this folder exists to prevent happening again.

**Stale is a signature.** A client claiming a browser and a Libby build that never shipped together
is trivially separable from real traffic. That is worth avoiding on its own terms, quite apart from
whether any particular refusal was caused by it.

## The scripts

They run in sequence, and each does one thing:

| | |
|---|---|
| `libby-current` | What version is live, what we announce, do they match. Exits non-zero on drift. |
| `libby-fetch [version]` | Archive the live version's assets into `versions/<v>/raw/`. |
| `libby-from-har <har\|dir>` | Recover a version from saved browser HARs — the only route to one already rolled. |
| `libby-split <version-dir>` | Split `main.js` into one file per AMD module, then beautify. |
| `libby-diff <old> <new>` | Compare two versions module by module. |
| `libby-assets` | The per-version asset list and UA, sourced by the others. |

Typical use, when a release lands or something starts being refused:

```sh
bin/libby-current                      # 22.1.2 live, we say 22.1.1 → drift
bin/libby-fetch                        # archive it before it rolls again
bin/libby-split versions/22.1.2
bin/libby-diff 22.1.1 22.1.2           # what actually moved
bin/libby-diff 22.1.1 22.1.2 obf/shib.js   # read the one that matters
```

## Why split the bundle

`main.js` is 2.3MB of `define("module/id", [deps], factory)` — 763 modules concatenated. Diffing two
versions whole tells you nothing: the minifier renames every local, so everything differs. Diffing
module by module works, because **module ids are stable across releases**. "Did `obf/shib.js` change"
is a question with an answer; "did main.js change" is not.

Even then the raw comparison is too noisy to read — 581 of 755 modules differed textually between
22.0.2 and 22.1.1. So `libby-diff` compares each module twice: literally, and again after
normalising away what a minifier is free to change (local names, whitespace, comments). String
literals are deliberately kept, because they carry the facts we depend on: the `c=d:` query shape,
endpoint paths, header names. That cuts 581 down to 125 real changes.

For the obfuscated modules there is a third comparison. `obf/shib.js` is re-keyed on every build —
`a0putz(158,"vPDU")` becomes `a0dress(476,"Q^xv")` — so it always differs, whether or not the
algorithm moved. `libby-diff` blanks string and number *contents* to compare shape, which separates
"they rotated the obfuscation" from "they changed what it computes". Between 22.0.2 and 22.1.1 the
shibboleth was the former: constants rotated, algorithm intact.

## What's tracked in git, and what isn't

`versions/` is ignored. It is several megabytes per release of OverDrive's proprietary bundle, and
vendoring somebody else's minified app into this repo serves no purpose that a `libby-fetch` does not
— except for versions no longer served, which is precisely why the HARs matter and why they should be
kept somewhere durable.

What *is* tracked is `PRIORS.md`: the facts we copied out, where each came from, and which module to
re-read when it changes. That file is the actual output of this folder. The bundles are working
material.

## Adding a prior

When something new is reverse-engineered out of the client, add it to `PRIORS.md` **and** to the
`PRIORS` list in `libby-diff`, so the next release tells you whether it still holds.
