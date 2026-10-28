# Session state — Fireside automation (written 2026-10-11, branch `ep687-audio-flow`)

Read this first after a compaction or fresh session. Everything below is
ground truth as of the last pushed commit. `/tmp/opencode/*.clj` scratch
scripts are GONE (tmpfs) — the repo (code + runbook + REPL comments) is the
complete record. Start there, not in /tmp.

## Where things live

- Repo: `~/src/link-hoarder` (this dir), branch `main`
  (`ep687-audio-flow` is fully merged).
- Docs: `EPISODE-RUNBOOK.md` (weekly procedure), `GUIDE.md` (sponsor onboarding),
  `(comment ...)` scratch blocks in both `src` files (parameterized REPL template).
- Creds: `.env` in repo root (`FIRESIDE_BASE_URL=https://app.fireside.fm`,
  `FIRESIDE_USER`, `FIRESIDE_SECRET`). Load with `set -a; . ./.env; set +a`.
- Checks: `clojure -X:test`, `nix run nixpkgs#cljfmt -- check src/ test/`,
  `nix run nixpkgs#clj-kondo -- --lint src test` (currently zero/zero).

## Episode state (all live, all verified on public pages)

- **687** LUP `c77dd843-0a44-4dec-9439-6e7a78f340a6` / adfree `12359b43-105b-4997-926c-b5eb2d9f8b27`:
  Public since 2026-10-04. 15 links, 7 chapters, LUP sponsors (membership,
  Nebula, Connecten), chapter-verified audio both feeds.
- **688 "Eyes on Deck"** LUP `0faf7699-79d1-4814-bab2-1b805b0c9b7c` / adfree
  `2206fdae-b7f1-4bba-ad17-c2bd0ef04576`: Public since 2026-10-11 16:45 PT.
  29 links, 7 chapters, 3 hosts fixed mid-week (see below), sponsors Nebula +
  Hostinger (AI Builder, UNPLUGGED) + membership, chapter-verified audio both.
- 687 workdir files were cleaned; 688 FLACs/MP3s/CSVs live in
  `~/Downloads/workdir/` (disk routinely 95% — delete WAVs immediately).

## Standing conventions (Wes's rules — do not reinterpret)

- LUP: publish immediately — Public + publish time snapped DOWN to quarter-hour PT.
- Hosts default: Chris 1848, Wes 1849, Brent 2108. NO guests unless named.
  (`set-participants!`; adfree podcast has no roster at all.)
- Sponsor copy lives in the doc's `#### SPONSORS` block; `sync-episode-sponsors!`
  reconciles it (create + message + attach). A leading "Name:" is stripped by
  the parser, because the site renders the title as a heading. Script stays blank.
- Every doc update = full resync (meta + links) + fresh previews.
- Publish approval always required. Never auto-publish.

## Proven gotchas (all in code comments + tests — this is the index)

1. POST 302s, then VERIFY by re-read: never trust status; never auto-follow
   redirects (follow-up GET re-sends POST headers → 500). curl, no follow.
2. S3 temp URLs must use RAW slashes: `%2F`-encoded makes CarrierWave report
   "Temp file no longer exists" on a fetchable file (`temp-file-url`).
3. Chapters embed on the SECOND processing, not the first — always use
   `upload-twice-and-verify-mp3` (no delete step; re-attach over the top).
4. Rails renders `ignore_cover`/`ignore_chapters` OUTSIDE `<form>` with
   `form=` — scrape page-wide, not subtree. Five identical `_method` inputs
   exist page-wide: never value-equality match (was `_method` x5).
5. Sponsorship forms take urlencoded, never multipart (500s).
6. `check_mp3_status` failure shape `{:error true :error-message}` must
   propagate — never read `:url` alone.
7. Episode lock: `/tmp/opencode/episode-<guid>.lock` (gone after reboot).
8. Drafts can carry wrong hosts — check participants before publishing.
9. Fireside `/update_campaigns` broke 2026-10-25 (422 for every sponsor and
   episode, incl. published). Do not use it. Chain now: campaigns come from
   the sponsor's `/campaigns` index (uuids), and the sponsorship POST needs the
   **numeric** Rails id, which is only exposed as `id="edit_campaign_<n>"` on
   the campaign edit page. `add-sponsorship` resolves uuid -> numeric itself.
   Known ids: Nebula "Managed Nebula" 2069, Hostinger "Hostinger AI Builder"
   2142, Incogni 2143, Namecheap 2144.

## Open / deferred (not blockers)

- Feed fan-out (parallel LUP+adfree; locks already per-guid).
- Old `RUNBOOK.md` has stale 687-era notes; `EPISODE-RUNBOOK.md` is canonical.
- Nice-to-haves: curl `-K` cookie file, login verification fn, session fast
  path, optgroup/single-quote edge cases in scrapers.

## 689 tag decision (approved 2026-10-18)

Feed order, specific-first, house tail intact:
`Aaron Seigo, KDE, Plasma 6.8, KDE documentary, KDE 4.0, Wayland, Elixir, Esri, Konveyor, RememberWindowPositions, open source, Linux Unplugged, Linux Podcast, Jupiter Broadcasting`.
Doc paste (least-specific-first, pipeline net-reverses):
`Jupiter Broadcasting, Linux Podcast, Linux Unplugged, open source, RememberWindowPositions, Konveyor, Esri, Elixir, Wayland, KDE 4.0, KDE documentary, Plasma 6.8, KDE, Aaron Seigo`.
Deferred idea: separate `?members` tag set for the adfree/members feed — not needed for 689, revisit later.

**689 "First Kontact" — LIVE 2026-10-18 15:30 PT both feeds.** Meta, links 22×2,
chapters 7/6, sponsors (Nebula + membership), 3 hosts + Seigo (34606), audio
verified 7/7 + 6/6 (3 passes each — twice is a minimum, not a guarantee).
Public-confirmed both pages. /tmp/data EDN + /tmp/data-desc-689.txt current.

**690 "The S in PHP is for Security" — LIVE 2026-10-25 13:45 PDT both feeds.**
LUP `001d7139-1aed-4d0f-9f38-91cbc11cda8b` / adfree
`0d60a306-cdba-4e2c-99c7-2880056e48b9`. Doc https://h.docs.lol/TEIVKIcUSb2KpMUS9woTkg.
(Doc late pass: Telescope de-duped; SeaGL/KSMBD/Niri/Umbriel/NetBird/meshSidecar/
OSMC/HelixNotes block added; tags grew to 71.) 43 links ×2, chapters 7/7,
3 hosts, no guest. Sponsor plan: **Nebula + Dynamic ads only, NO membership** —
Nebula 1m13s via campaign-id 2069 (/update_campaigns broken server-side, gotcha #9).
Audio verified 7/7 both feeds. Public-confirmed; timestamps read 2026-10-25T13:45:00-07:00.

## Key IDs

- Hostinger sponsor `8bfb1e79-a40f-4352-91cb-725df5f56da2`, campaign
  `94e7e1f4-80fe-4cea-a39d-d6521027f2f6` ("Hostinger AI Builder", UNPLUGGED).
- Membership: sponsor "Jupiter Signal Network Membership", campaign
  "Jupiter Party Annual Membership" (multi-campaign: pass `:campaign`
  explicitly) at `0s`.
- Previews served via `python3 -m http.server 9999` + bore tunnel; bore
  secret lives ONLY in fish history / env — opencode shells can't reuse it
  reliably (Wes launches bore himself).
