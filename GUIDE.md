# Link Hoarder + Fireside Guide

## What We Do

1. **Fetch show notes** from docs.lol URL via `link-hoarder/-main` or `fireside/prepare-data`
2. **Associate podcast + guid** - either:
   - `linuxunplugged` + guid from markdown
   - `adfree` + specific guid (different feed)
3. **Extract guests** from markdown (optional section with bio)
4. **Push to Fireside**:
   - `set-metadata` - title, description, tags (automatically unreverses reversed tags)
   - `purge-links` - clear old links (for updates)
   - `add-link` loop - add all links
   - Guests → podcast:person in RSS feed (via feed-fusion)

## Composable API

We've moved toward a more robust, composable API in `noblepayne.fireside` and `noblepayne.link-hoarder`.

### 1. Data Preparation
```clojure
(require '[noblepayne.link-hoarder :as lh])
(require '[noblepayne.fireside :as fs])

;; Fetch and prepare (sets podcast slug and handles initial parsing)
(def data (fs/prepare-data "https://h.docs.lol/URL" "linuxunplugged"))

;; For Ad-Free (override GUID)
(def adfree-data (assoc data :podcast "adfree" :guid "ADFREE-GUID-HERE"))
```

### 2. Previews
```clojure
;; HTML Preview (Dark mode, Pico CSS, includes robust copy buttons)
(lh/save-preview data) 
;; => "file:///dev/shm/link-hoarder-{guid}.html"

;; Rendered Feed Preview (Mobile-friendly, feed-style view)
(lh/save-preview-rendered data)
;; => "file:///dev/shm/link-hoarder-rendered-{guid}.html"

;; Markdown Preview (Episode Links style with blockquotes)
(lh/save-markdown data)
;; => "file:///dev/shm/episode-links-{guid}.md"
```

### 3. Publishing to Fireside
```clojure
;; Ensure login (creates client and authenticates)
(def client (fs/ensure-login))

;; Set Metadata (handles tag un-reversal for Fireside)
(fs/set-metadata client data)

;; Full Purge and Re-add Links
(do
  (fs/purge-links {:client client :podcast (:podcast data) :episode-guid (:guid data)})
  (doseq [l (:links data)]
    (fs/add-link {:client client :podcast (:podcast data) :episode-guid (:guid data)
                  :title (:title l) :url (:href l) :quote (:quote l)})))
```

## Step-by-Step Production Workflow

### 1. Load & Fetch
```clojure
(def url "https://h.docs.lol/...")
(def data (fs/prepare-data url "linuxunplugged"))
```

### 2. Verify
Open the HTML preview to check tags, links, and formatting.
```clojure
(lh/save-preview data)
```

### 3. Publish Linux Unplugged
```clojure
(def c (fs/ensure-login))
(fs/publish-episode url "linuxunplugged")
```

### 4. Publish Ad-Free
```clojure
(def adfree-guid "2eecbf63-3b5c-4a91-9a62-ff23c8687019")
(fs/publish-episode url "adfree" adfree-guid)
```

### 5. Sync Chapters
Chapters ship alongside the audio as CSV in `~/Downloads/workdir/`. The two
feeds have **separate episodes** even when the audio is identical, so push
chapters to both — the ad-free episode needs an explicit guid override.

```clojure
(def c (fs/ensure-login))

;; public feed, from the guid in the show notes
(fs/sync-chapters {:client c
                   :podcast "linuxunplugged"
                   :episode-guid (:guid data)
                   :chapters-file "/home/wes/Downloads/workdir/Linux Unplugged 686 (Ads) Chapters.csv"})

;; ad-free feed, needs its own guid
(fs/sync-chapters {:client c
                   :podcast "adfree"
                   :episode-guid "9d553b2d-a2ac-4ca4-a7b4-00ffc319f4de"
                   :chapters-file "/home/wes/Downloads/workdir/Linux Unplugged 686 (Premium) Chapters.csv"})
```

**Chapter CSV format** (`load-chapters-csv`):
`number,position_seconds,timecode,name`

**Ad marker CSV format** (`load-ads-csv`, parsed but not yet pushed):
`number,start_seconds,end_seconds,start_timecode,end_timecode,name`

**Timecode handling:** Fireside's `chapter[timecode_as_words]` field wants a
human duration like `1h 17m 4s`, not a numeric timecode. `timecode->words`
converts the CSV's `HH:MM:SS` column. Prefer the authored `timecode` column
over recomputing from `position_seconds` — the column truncates while
recomputation rounds, which drifts chapters by a second (1987.94s is written
as `00:33:07`, not `00:33:08`). `seconds->words` is the fallback when the
column is absent.

`sync-chapters` only appends, so it **refuses to run** against an episode that
already has chapters:

```clojure
(fs/fetch-chapter-ids c "linuxunplugged" guid)   ;; => [{:guid "..."} ...]
(fs/delete-chapter {:client c :podcast "linuxunplugged"
                    :episode-guid guid :chapter-guid "..."})
;; or, if duplicating is genuinely what you want:
(fs/sync-chapters {... :force? true})
```

Deleting a chapter posts `_method=delete` to
`/chapters/<chapter-guid>` (not `/edit`) with the `csrf-token` meta tag
from that chapter's edit page.

### 6. Sync Sponsorships

Sponsorships are a separate Fireside feature from chapters: a sponsor + a
campaign, attached to a timecode. Note the naming trap — LUP's sponsors are
called "sponsorships", not "ads".

**For an episode that already has its sponsorships** (the normal case —
episodes carry recurring sponsors), update the timecode in place:

```clojure
(fs/sync-sponsorship-times {:client c
                            :podcast "linuxunplugged"
                            :episode-guid (:guid data)
                            :ads-file "/home/wes/Downloads/workdir/Linux Unplugged 686 (Ads) Ads.csv"
                            :sponsor "Nebula"})
```

**For an episode with no sponsorships yet**, create them from the ads CSV:

```clojure
(fs/sync-sponsorships {:client c
                       :podcast "linuxunplugged"
                       :episode-guid (:guid data)
                       :ads-file "/home/wes/Downloads/workdir/Linux Unplugged 686 (Ads) Ads.csv"})
;; => {:pushed [...] :skipped ["Dynamic 1" "Dynamic 2"]}
```

The ads CSV mixes real sponsor reads with dynamic ad markers. **Dynamic
markers are not sponsorships** — Fireside has no equivalent — so they are
skipped and reported in `:skipped` rather than silently dropped.

**Inspecting what's already there:**

```clojure
(fs/fetch-sponsorship-ids c "linuxunplugged" guid)
;; => [{:sponsor "Nebula" :campaign "Managed Nebula"
;;      :timecode_str "1 minute 1 second" :timecode_seconds 61
;;      :guid "b8416e65-..."} ...]
```

#### Sponsorship form gotchas

Three things that will waste your time if you don't know them:

1. **The campaign dropdown is populated by JavaScript.** The new-sponsorship
   page ships with a single placeholder option ("1Password Extended Access
   Management"). The real list comes from
   `/episodes/<guid>/update_campaigns?sponsor_id=<id>`, which returns a
   *jQuery snippet*, not HTML. Scraping the page directly yields exactly one
   wrong option and would silently file a sponsorship under the wrong
   campaign. Use `fetch-campaigns`.
2. **Post urlencoded, not multipart, despite the form saying
   `enctype="multipart/form-data"`.** Multipart returns **500**.
3. **Updates post to the sponsorship's own URL with `_method=patch`** — not
   to `/edit`. The `/edit` page is only where you read the current values.

#### Recurring sponsors on LUP

Every recent episode carries the same two, and neither comes from the ads
CSV:

| Sponsor | Campaign |
|---|---|
| Nebula | Managed Nebula |
| Jupiter Signal Network Membership | Jupiter Party Annual Membership |

Only Nebula is in the ads CSV. The Jupiter membership is added by hand and
should be left alone.

#### Adding a brand-new sponsor (worked example: Connecten, ep 687)

When a new sponsor appears in the Ads CSV and is not in Fireside's sponsor
dropdown, `sync-sponsorships` skips it and reports it in `:skipped`. To
onboard it, create the sponsor and one campaign first — all of this was
done from code on 2026-10-04 and every step verified live:

1. **Read the creation forms, don't guess them.** Sponsor fields live on
   `/podcasts/<slug>/sponsors/new`; campaign fields on
   `/podcasts/<slug>/sponsors/<sponsor-uuid>/campaigns/new`. Both forms
   declare `enctype="multipart/form-data"` but accept urlencoded posts
   (same as every other write path in this file).

2. **Sponsor fields** (`sponsor[title]`, `sponsor[url]`, `sponsor[status]`):
   - `title` must **exactly** equal the Ads CSV `name` column
     (`Connecten`, not `ConnecTen Internet`). `sync-sponsorships` matches
     by exact string, so any other spelling silently skips every future
     episode.
   - `url` with protocol (`https://connecteninternet.com/discount/Jupiter35`).
   - `status` `1` = Public (the default), `0` = Private. Read the options
     off the form; do not hardcode from memory.

3. **Campaign fields** (`campaign[title]`, `campaign[url]`,
   `campaign[promo_code]`, `campaign[message]`, `campaign[script]`):
   - `title` follows the product-name pattern (`Managed Nebula`, so
     `Connecten Internet` — the name the show itself uses).
   - `promo_code` is the offer code (`Jupiter35`).
   - `message` is the read copy, taken from the show notes.
   - `script` may be blank (Nebula's is).
   - Create **exactly one** campaign. `sync-sponsorships` auto-picks the
     campaign only when there is one; a second forces an explicit
     `:campaign` on every future episode.

4. **Attach it to the episode** with `add-sponsorship` (sponsor + words
   timecode). This was the first successful `add-sponsorship` call — the
   MapEntry bug meant sponsorship creation had never worked before.

Source the copy from the show notes, not from memory: the link text, the
thanks line, and the read itself usually carry the product name, the URL,
and the offer.

## Tag Handling Logic
- **Storage/Markdown:** Tags are kept in the order they appear.
- **`extract-metadata`:** Automatically **reverses** tags so that the most specific/recent ones appear first in some views.
- **`set-show-meta`:** Automatically **un-reverses** tags back to original order before pushing to Fireside API, ensuring the web interface remains consistent.

## Troubleshooting

### Timeouts
The full `publish-episode` task involves 40-60+ HTTP requests. In high-latency environments or via some orchestrators (like MCP), it may timeout.
**Solution:** Run the steps manually (Metadata, then Purge, then Add Links) to ensure completion.

### Missing Metadata
If metadata isn't appearing, check `FIRESIDE_BASE_URL`. Ensure it includes the protocol (e.g., `https://app.fireside.fm`).
