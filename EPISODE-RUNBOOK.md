# Episode Runbook (weekly, dual-feed)

Draft status. Dual-feed pattern proven on Linux Unplugged 687.
Covers: scrape → set-show-meta → purge/add links → chapters → sponsors
(main feed only) → upload-and-verify-mp3 → schedule-episode! → public-site check.

Conventions: `EP` = episode number, e.g. `687`. All REPL snippets assume
`(require '[noblepayne.fireside :as fs] '[noblepayne.link-hoarder :as lh])`.

## 0. Prerequisites

- Branch: `ep687-audio-flow`. Work from the repo root.
- `.env` in repo root with exactly:
  ```
  FIRESIDE_BASE_URL=https://app.fireside.fm
  FIRESIDE_USER=<login email>
  FIRESIDE_SECRET=<password>
  ```
  `FIRESIDE_BASE_URL` must include the `https://` protocol or metadata writes go nowhere.
- Binaries on PATH: `curl`, `ffprobe`, `flac`, `lame` (all present on this workstation).
- Workdir `~/Downloads/workdir/` per episode carries six files (names are load-bearing):
  ```
  Linux Unplugged <EP> (Ads) Chapters.csv
  Linux Unplugged <EP> (Premium) Chapters.csv
  Linux Unplugged <EP> (Ads) Ads.csv
  Linux Unplugged <EP> (Ads).mp3         # encoded, see step 6
  Linux Unplugged <EP> (Premium).mp3
  ```
  Feed ↔ file mapping: `linuxunplugged` = `(Ads)` file, `adfree` = `(Premium)` file.
  Timings differ between the two (ad reads shift everything) — never push one
  feed's CSV to the other feed.
- Run tests first: `clojure -X:test`. Must be green before touching Fireside.

## 1. Scrape show notes (one scrape per feed)

```clojure
(def EP 687)
(def ADS-URL "<with-ads HedgeDoc URL — confirm per episode>")
(def ADFREE-URL "https://h.docs.lol/d81Y_BKPSaSOvgMMMk3qKw?both") ; 687 value; confirm per episode

(def data (fs/prepare-data ADS-URL "linuxunplugged"))
;; Expected: {:podcast "linuxunplugged" :guid "<ads-guid>" :episode "687"
;;            :title ... :links [...] ...}
(count (:links data))   ; 687 => 15
(:guid data)            ; 687 => "c77dd843-0a44-4dec-9439-6e7a78f340a6"

(def adfree-data
  (-> (fs/prepare-data ADFREE-URL "adfree")
      (assoc :guid "12359b43-105b-4997-926c-b5eb2d9f8b27")))
;; adfree guid is an OVERRIDE — it never comes from the doc.
```

Verify before anything is pushed:

```clojure
(lh/save-preview data)          ; => file:///dev/shm/link-hoarder-<guid>.html — open, read it
(lh/save-markdown data)         ; => file:///dev/shm/episode-links-<guid>.md
(str (:tags data))              ; tags look reversed here; set-show-meta un-reverses on push
```

Gate: title/description/tags/links correct in the preview. Cheap to fix here,
expensive everywhere below.

### Discover guids via the episodes list (when the doc guid is stale)

Episode numbers are the Fireside slugs; guids come from the list page:

```clojure
(def c (fs/ensure-login))
(filter #(= EP (:episode_num %)) (fs/fetch-episode-ids c "linuxunplugged"))
(filter #(= EP (:episode_num %)) (fs/fetch-episode-ids c "adfree"))
;; => [{:episode_num 687 :title "Linux by Proxy" :guid "c77dd843-..."}]
```

## 2. set-show-meta on EACH episode (both feeds)

```clojure
(def c (fs/ensure-login))

(fs/set-show-meta {:client c :podcast "linuxunplugged"
                   :episode-guid (:guid data)
                   :title (:title data)
                   :description (:description data)
                   :tags (:tags data)})
(fs/set-show-meta {:client c :podcast "adfree"
                   :episode-guid (:guid adfree-data)
                   :title (:title adfree-data)
                   :description (:description adfree-data)
                   :tags (:tags adfree-data)})
```

Verify (re-read, never trust the POST status):

```clojure
;; re-scrape the edit page via set path is write-only; confirm via Fireside UI
;; episode edit page title/keywords, or via the public site after step 7.
```

### 2b. participants (hosts/guests)

Episodes default to the three regular hosts (Chris 1848, Wes 1849, Brent
2108) with no guests. New drafts sometimes carry wrong assignments — check
and fix before publishing; the audio flow preserves whatever is checked.

```clojure
;; read what's checked (labels need the label lookup; checked boxes only)
(set-participants! {:client c :podcast "linuxunplugged"
                    :episode-guid (:guid data)})
;; with guests:
(set-participants! {:client c :podcast "linuxunplugged"
                    :episode-guid (:guid data)
                    :guest-ids ["<fireside-person-id>"]})
;; => true (both fields re-read and matched before returning)
```

## 3. purge + add links (both feeds, 15 for 687)

```clojure
(fs/purge-links {:client c :podcast "linuxunplugged"
                 :episode-guid (:guid data)})
(doseq [l (:links data)]
  (fs/add-link {:client c :podcast "linuxunplugged"
                :episode-guid (:guid data)
                :title (:title l) :url (:href l) :quote (:quote l)}))

(fs/purge-links {:client c :podcast "adfree"
                 :episode-guid (:guid adfree-data)})
(doseq [l (:links adfree-data)]
  (fs/add-link {:client c :podcast "adfree"
                :episode-guid (:guid adfree-data)
                :title (:title l) :url (:href l) :quote (:quote l)}))
```

Or `fs/publish-episode` (does meta + purge + add in one call, 40–60 HTTP
requests — may time out via MCP; run the manual steps above instead):

```clojure
(fs/publish-episode ADS-URL "linuxunplugged")
(fs/publish-episode ADFREE-URL "adfree" "12359b43-105b-4997-926c-b5eb2d9f8b27")
;; => {:link-count 15 :meta-set? true :purged? true ...}
```

Known trap (proven on 687): a double sequence binding in the doseq posts every
link N times (15 links became 225). One binding only — `map-indexed` + `:let`,
no second `:when`/sequence clause.

Verify: open each feed's `/links` page in Fireside, count rows = link count.
Purge destroys hand-edits — re-running is safe for links only.

## 4. sync chapters (both feeds, per-feed CSV)

```clojure
(def work-dir "/home/wes/Downloads/workdir/")

(fs/sync-chapters {:client c :podcast "linuxunplugged"
                   :episode-guid (:guid data)
                   :chapters-file (str work-dir "Linux Unplugged 687 (Ads) Chapters.csv")})
;; => 7 chapter maps

(fs/sync-chapters {:client c :podcast "adfree"
                   :episode-guid (:guid adfree-data)
                   :chapters-file (str work-dir "Linux Unplugged 687 (Premium) Chapters.csv")})
;; => 7 chapter maps
```

`sync-chapters` only appends and **refuses** when chapters exist. To redo:

```clojure
(fs/fetch-chapter-ids c "linuxunplugged" (:guid data)) ; => [{:guid ...} ...]
(doseq [{:keys [guid]} (fs/fetch-chapter-ids c "linuxunplugged" (:guid data))]
  (fs/delete-chapter {:client c :podcast "linuxunplugged"
                      :episode-guid (:guid data) :chapter-guid guid}))
```

Verify: `fetch-chapter-ids` count == CSV data rows (687: 7 each).

## 5. sponsors — main feed ONLY, never adfree

Normal case (episode already carries recurring sponsors): update timecodes in place.

```clojure
(fs/fetch-sponsorship-ids c "linuxunplugged" (:guid data))
;; => [{:sponsor "Nebula" :campaign "Managed Nebula" ...} ...]

(fs/sync-sponsorship-times {:client c :podcast "linuxunplugged"
                            :episode-guid (:guid data)
                            :ads-file (str work-dir "Linux Unplugged 687 (Ads) Ads.csv")
                            :sponsor "Nebula"})
```

New episode with no sponsorships yet: create from the ads CSV.

```clojure
(fs/sync-sponsorships {:client c :podcast "linuxunplugged"
                       :episode-guid (:guid data)
                       :ads-file (str work-dir "Linux Unplugged 687 (Ads) Ads.csv")})
;; => {:pushed [...] :skipped ["Dynamic 1" "Dynamic 2"]}
```

`Dynamic N` rows are skipped (Fireside has no dynamic-marker field) — expected,
not an error. The Jupiter membership (`Jupiter Signal Network Membership` /
`Jupiter Party Annual Membership`) is in no CSV; leave it alone.

New sponsor onboarding (proven 2026-10-04 with Connecten on 687): title must
exactly equal the Ads CSV `name` (`Connecten`), one campaign only
(`Connecten Internet`, promo `Jupiter35`). Full procedure in GUIDE.md
"Adding a brand-new sponsor".

Verify: `fetch-sponsorship-ids` shows each sponsor with the CSV's start
timecode in words (`00:00:59` → `59s`).

## 6. upload-and-verify-mp3 (per feed, last)

Encode first (no WAVs/MP3s kept in workdir — disk routinely at 95%):

```bash
cd ~/Downloads/workdir
flac -d 'Linux Unplugged <EP> (Ads).flac' 'Linux Unplugged <EP> (Premium).flac'
# The two encodes are independent — run together (~7 min saved, measured 687).
lame -m j --lowpass 20.5 -q 0 -b 128 --tn <EP> 'Linux Unplugged <EP> (Ads).wav' &
lame -m j --lowpass 20.5 -q 0 -b 128 --tn <EP> 'Linux Unplugged <EP> (Premium).wav' &
wait
```

`--tn` must equal the episode number (ID3 track number). Then:

```clojure
(fs/upload-and-verify-mp3 {:client c :podcast "linuxunplugged"
                           :episode-guid (:guid data)
                           :mp3-path (str work-dir "Linux Unplugged 687 (Ads).mp3")
                           :chapters-file (str work-dir "Linux Unplugged 687 (Ads) Chapters.csv")})
;; => {:status {... :url "https://...mp3"} :report {:chapters 7 :verified true}}

(fs/upload-and-verify-mp3 {:client c :podcast "adfree"
                           :episode-guid (:guid adfree-data)
                           :mp3-path (str work-dir "Linux Unplugged 687 (Premium).mp3")
                           :chapters-file (str work-dir "Linux Unplugged 687 (Premium) Chapters.csv")})
```

What it does: S3 presign → upload → attach → poll `check_mp3_status` →
download processed bytes → ffprobe chapter compare (count + trimmed-title +
start within 2.0s). Chapters must already be on the episode — Fireside embeds
them at processing time. One pass is enough (verified live on two episodes).

Verify: `:report {:verified true}`. Independently:

```clojure
(fs/mp3-status {:client c :podcast "linuxunplugged" :episode-guid (:guid data)})
;; => {:processing false :url "https://..." :download-url ... :error false ...}
```

## 7. publish via schedule-episode!

LUP convention is publish-immediately: status `:public`, `publish-at` snapped
DOWN to the quarter-hour, Pacific wall-clock, 24h hour + 15-min-step minutes.
Other shows may pass a future slot.

```clojure
;; LUP go-live: e.g. show ends 13:07 Pacific → [2026 10 4 13 0]
(fs/schedule-episode! {:client c :podcast "linuxunplugged"
                       :episode-guid (:guid data)
                       :status :public :publish-at [2026 10 4 13 0]})
(fs/schedule-episode! {:client c :podcast "adfree"
                       :episode-guid (:guid adfree-data)
                       :status :public :publish-at [2026 10 4 13 0]})
;; => true (each field re-read and matched before returning)
```

Validation is strict: unknown status throws; minutes must be exactly
0/15/30/45 (never rounded). 687 sat at Private on both feeds until the
2026-10-04 go-live (21:15 PT), when both flipped to `:public`.

## 8. Verification gates (after EVERY step)

- Never trust a 302. Every write fn re-reads (`attach-mp3!`, `schedule-episode!`,
  `await-fields!` retry up to 12×5s — reads can race the commit across
  Fireside backends). If your own check is a bare POST status, re-read the page.
- Expired session redirects to `/login` served as **200**. `fetch-page!` throws
  on it; raw `http/request` does not. On `Not logged in` — `ensure-login` again.
- Public-site check per feed: `linuxunplugged.com/<n>`, `adfree.fireside.fm/<n>` —
  title, duration, download size, links, sponsors all present and correct.
- Link count == scrape count on both feeds. Chapter count == CSV rows on both
  feeds. Sponsors present on main, absent on adfree.

## Rollback / retry

- Episode lock: `/tmp/opencode/episode-<guid>.lock`. `upload-and-verify-mp3`
  holds it for the whole run; a second run for the same guid refuses with
  `already running` + lock age. Remove by hand only when no run is active.
  Never auto-steal.
- `mp3-status` failure shape: `{:processing false :error true
  :error-message "CarrierWave::DownloadError: ..." :url nil}`. Act on
  `:error-message`, not on `:url` alone (`processing=false` + no url also means
  "job not started yet" — `await-processed` keeps polling up to 1h).
- On processing `error:true`: fix the cause (usually a dead temp URL), then
  re-run `upload-and-verify-mp3` — attach is idempotent, processing restarts.
- Temp-URL expiry: S3 temp URLs go stale. Never reuse a staged
  `mp3_upload_url` across sessions; always S3-upload fresh, then attach.
  On verify failure after new audio went live, `upload-and-verify-mp3`
  best-effort restores the pre-run temp url, then rethrows.
- Chapters duplicated (ran with `:force? true` by mistake): delete-all via the
  `fetch-chapter-ids`/`delete-chapter` loop in step 4, re-sync once.
- Links: purge + re-add is safe (destroys hand-edits, nothing else).

## Timing expectations

- Scrape + meta + links per feed: ~1–3 min (publish-episode = 40–60 HTTP requests).
- Chapters per feed: ~1 min (7 POSTs + reads).
- Sponsors: seconds per sponsor.
- MP3 per feed: S3 upload ~1–2 min (70+ MB), Fireside transcode ~1–5 min
  measured on 687 (`await-processed` polls every 10s, deadline 20 min).
- Full dual-feed run: ~30–60 min wall-clock, mostly waiting on transcodes.
- Schedule/publish: seconds + 12×5s verify loop worst case.

## Appendix: 687 worked example (real values)

| Item | Value |
|---|---|
| Title | Linux by Proxy |
| EP | 687 |
| ADFREE-URL | https://h.docs.lol/d81Y_BKPSaSOvgMMMk3qKw?both (687 used this SAME doc for both feeds; the adfree guid below is still an override — confirm per episode) |
| ADS-URL | same doc as ADFREE-URL for 687 — confirm per episode |
| ADS-GUID (`linuxunplugged`) | c77dd843-0a44-4dec-9439-6e7a78f340a6 |
| ADFREE-GUID (`adfree`, override) | 12359b43-105b-4997-926c-b5eb2d9f8b27 |
| Links | 15, added to both feeds |
| Ads Chapters CSV | 7 rows: Intro 00:00:00, Housekeeping 00:01:40, A Place to Call Home 00:05:05, All Your Base are Belong to Us 00:25:33, Shout-Outs 00:43:21, Picks 01:10:13, Outro 01:13:40 |
| Premium Chapters CSV | 7 rows, earlier timecodes (no ad reads): Housekeeping 00:00:59, A Place to Call Home 00:03:44, All Your Base 00:24:12, Shout-Outs 00:41:21, Picks 01:07:12, Outro 01:10:39 |
| Ads CSV | 3 rows: Nebula 00:00:59–00:02:20, Dynamic 1 (skipped), Connecten 00:42:49–00:44:22 |
| Sponsors (main feed only) | Nebula → Managed Nebula (timecode from CSV); Jupiter Signal Network Membership → Jupiter Party Annual Membership (manual, 0s); Connecten → Connecten Internet, onboarded 2026-10-04 (sponsor url https://connecteninternet.com/discount/Jupiter35, promo Jupiter35) |
| Publish | `:public`, Pacific wall-clock snapped down to quarter-hour (both feeds Private until go-live) |

Note: the older RUNBOOK.md "687: 13 links" line is stale — the publish-episode
regression comment in `fireside.clj` (15 links → 225 double-posts) and the live
run both show 15.
