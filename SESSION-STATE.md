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

## Fresh episode checklist (693+)

Inputs needed before starting: doc URL, Fireside drafts on both feeds (guids),
and the workdir bundle: `(Ads)` + `(Premium)` FLACs, `(Ads)` Ads.csv, and both
chapters CSVs. Missing any one of these blocks a feed, not the whole run.

1. **Scrape.** `(prepare-data DOC podcast {:scope :show})` is the published set;
   `{:scope :all}` is the members edition (never published). LUP and ad-free
   share the show set.
2. **Doc gates.** `(lh/pick-audit zip)` must be `:ok? true` (every pick heading
   starts with `Pick:`/`Bonus Pick:`). Title must be one line — multi-line
   titles concatenate. Check tag count; a 4-tag tail means the audit hasn't run.
3. **Fireside sync.** set-show-meta both feeds; purge+add links both (show
   scope); sync each feed's own chapters CSV; set-participants! with the three
   hosts plus the guest's numeric checkbox id (look it up on the edit page —
   the person UUID is not the checkbox value).
4. **Sponsors (main only).** Timecodes come from the Ads CSV. `plan-sponsors`
   first, read `:op`/`:new-sponsor?`, then `apply-plan!` with `:commit?` equal
   to the episode guid. Sale/episode-specific copy gets its own campaign
   (`create-campaign!`) so past episodes keep theirs.
5. **Encode.** `ffmpeg -i in.flac -c:a pcm_s16le out.wav` then
   `lame -m j --lowpass 20.5 -q 0 -b 128 --tn <EP>`. Do NOT rely on `flac -d`:
   it failed on 692 with a metadata END_OF_STREAM error while `flac -t` passed.
   Delete WAVs immediately; encode sequentially when disk is tight.
6. **Audio.** `upload-and-verify-mp3` in a loop up to 4 passes per feed; the
   first passes come back chapterless. Success is `:report {:verified true}`.
7. **/tmp/data.** Write the 17-key payload (guid, title, description, tags,
   sponsors with Fireside identity, guests, links, links-members,
   sponsorships, episode, show, doc, lup-guid, adfree-guid, chapters, audio,
   published) and update it as each phase lands. feed-fusion consumes it.
8. **Publish.** Ad-free and LUP can go separately; time snaps DOWN to the
   quarter hour Pacific. Confirm both public pages after.

### /tmp/data payload contract (feed-fusion staging)

`:status` is one of `:preliminary`, `:ready`, `:published`. `:final?` is true
only at `:published`, so a consumer can tell "this is the payload that went
live" from "this is a snapshot of work in progress".

`:checks` carries one verdict per section — `:ok`, `:partial`, or `:pending` —
and `:pending` lists every section that is not `:ok` (`:partial` counts as
pending). `:updated-at` is ISO-8601 with the Pacific offset.

| Section | `:ok` means |
|---|---|
| `:meta` | title and description non-blank |
| `:links` | both feeds carry the same count as the doc's show set |
| `:tags` | more than the four house tags (4 = `:partial`, episode tags missing) |
| `:participants` | hosts written to the main feed |
| `:chapters` | both feeds have the same positive chapter count |
| `:audio` | `mp3-status` reports a URL and no error on both feeds |
| `:sponsors` | main feed has at least as many sponsorships as the doc lists |

Treat a section as trustworthy only when its check is `:ok`; anything else
means the value is a draft. Sponsor `:timecode` stays nil until the Ads CSV
lands, and `:campaign` may change at `apply-plan!` time (e.g. Hostinger's
sale campaign) — those are decided against live copy, not guessed here.

## Episode state (all live, all verified on public pages)
- **693 "Two Cities, One Linux" — LIVE 2026-11-15 21:15 PST, both feeds
  together.** LUP `8bb69d65-59ba-4ac8-b186-1ce10e9819a2` / adfree
  `0b17b3f2-83dc-4127-b709-1898c2959d80`. Doc
  https://h.docs.lol/tLR5yvPgSvCfAmfN1SaoLg. 41 links both feeds (45 members),
  90 tags, 7/7 chapters each feed's own CSV, audio verified 7/7 on the first
  pass for both. Sponsors: Nebula 52s, Incogni 0s, Namecheap 0s, Hostinger
  28m03s on Hostinger Black Friday. The Ads FLAC is named
  `Linux Unplugged <EP>.flac` (no "(Ads)" suffix) — ffprobe the pair before
  encoding rather than trusting the filename.


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
- Three editions: LUP and ad-free **share the same links** (`:scope :show`);
  the members edition gets the extra above-the-line links (`:scope :all`) and
  is NOT published. Sponsors on LUP only.
- Sponsor copy lives in the doc's `#### SPONSORS` block; `sync-episode-sponsors!`
  reconciles it (create + message + attach). A leading "Name:" is stripped by
  the parser, because the site renders the title as a heading. Script stays blank.
- Every doc update = full resync (meta + links) + fresh previews.
- Publish approval always required. Never auto-publish.
- Sponsor prep is two steps when the Ads CSV is late: attach all sponsors at
  `0s` with the right campaigns, let the team verify the list, then retime from
  the CSV with `sync-sponsorship-times`. `/tmp/data` tracks these separately as
  `:sponsors` and `:sponsor-times`. Never rewrite a shared campaign for one
  episode's copy — pin a sale campaign via `:campaign-titles`.

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
9a. Sponsor writes go through plan-sponsors -> apply-plan! (or the
   sync-episode-sponsors! wrapper). `read-sponsorships` refuses to report zero
   unless it positively saw the page (new-sponsorship link + rendered==parsed),
   because zero rows would otherwise authorize re-attaching everything. A
   commit-then-throw counts as success on re-read, so retries cannot duplicate.
   `delete-sponsorship!` rolls back one row by guid.
9. Fireside `/update_campaigns` broke 2026-10-25 (422 for every sponsor and
   episode, incl. published). Do not use it. Chain now: campaigns come from
   the sponsor's `/campaigns` index (uuids), and the sponsorship POST needs the
   **numeric** Rails id, which is only exposed as `id="edit_campaign_<n>"` on
   the campaign edit page. `add-sponsorship` resolves uuid -> numeric itself.
   Known ids: Nebula "Managed Nebula" 2069, Hostinger "Hostinger AI Builder"
   2142, Incogni 2143, Namecheap 2144.
10. Workdir filenames reflect a human editor, not a schema. 693 arrived as
   `Linux Unplugged 693.flac` (the Ads cut) next to `Linux Unplugged 693
   (Premium).flac`, with no `(Ads)` suffix — the editor had a long day. Never
   infer the feed from the filename: `ffprobe` both FLACs, pick the longer one
   as Ads, confirm against the two chapter CSVs, then rename to the convention
   before encoding. Cheap check, expensive mistake.
11. Operator scaffolding hides in plain links. 694's doc carried
   `Boosts!` -> `http://100.120.212.39:3223/...`, a tailnet address that
   parses as an ordinary link and would have shipped to both feeds.
   `unpublishable-link?` now drops private/loopback/CGNAT/.local addresses
   alongside h.docs.lol; if a new internal endpoint appears, extend that
   predicate, not the doc.

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

**692 "Ubuntu's Core Reset" — LIVE 2026-11-08. Ad-free 08:00 PST, main 08:45 PST.**
LUP `369cb64a-440a-4255-ae7f-ad8433eed283` / adfree
`288ac143-955f-4995-91b5-8fcf1ffb4716`. Doc https://h.docs.lol/ad5Q-NjaRQCaG1Jfm3go9Q.
43 links both feeds, 70 tags, 7/7 chapters, audio verified 7/7 (LUP 3 passes,
adfree 2). Guest Jon Seager (31952). Sponsors: Nebula 45s, Incogni 0s,
Namecheap 0s, Hostinger 22m50s on Hostinger Black Friday. Note: the Ads FLAC
needed ffmpeg to decode — `flac -d` failed on metadata though `flac -t` passed.

**691 "Fedora's Hidden Upgrades" — LIVE 2026-11-01 17:00 PST both feeds.**
LUP `b13bcff0-5d43-46c9-af4e-b21fd70d4c88` / adfree
`dd65a4e4-4da4-4394-bd52-0dcdbfdc6a08`. Doc https://h.docs.lol/jOSJo4utTca81tZNptDFfw.
27 public links / 32 members, 7/7 chapters both, audio verified 7/7 (LUP 2 passes,
adfree 3). Sponsors: Nebula 58s, Incogni 0s (copy reworded globally), Namecheap 0s,
Hostinger 17m35s on the new **Hostinger Black Friday** campaign (2145) so 688 keeps
its copy. 3 hosts, no guest.

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
