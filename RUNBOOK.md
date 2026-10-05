# Episode Runbook

Per-episode procedure. Nothing here is automated — you drive it from the REPL
using the comment blocks at the bottom of `fireside.clj` and `link_hoarder.clj`.

## Per-episode variables

Every episode defines these once. Everything below refers to them:

| Var | Meaning | Example (687) |
|---|---|---|
| `EP` | episode number | `687` |
| `ADS-URL` | show-notes doc for the with-ads version | *(embedded in the scrape — confirm each episode)* |
| `ADFREE-URL` | show-notes doc for the ad-free version | `https://h.docs.lol/d81Y_BKPSaSOvgMMMk3qKw?both` |
| `ADS-GUID` | with-ads episode GUID — **scraped from the doc** | `c77dd843-0a44-4dec-9439-6e7a78f340a6` |
| `ADFREE-GUID` | ad-free episode GUID — **override, never in the doc** | `12359b43-105b-4997-926c-b5eb2d9f8b27` |

Each episode exists **twice** in Fireside as two separate records
(`linuxunplugged` and `adfree`). Same audio, same title, different records.
Every push happens twice.

## Order of operations

Chapters are gated on the audio arriving. Sponsors come after the show is
up because they refer to the broadcast. MP3 upload is last.

1. **Scrape LUP → `/tmp/data` → sync LUP Fireside** (metadata + links)
2. **Scrape adfree → override guid → sync adfree Fireside** (metadata + links)
3. **Verify** previews before anything is pushed (last cheap checkpoint)
4. **Chapters**, both feeds — only once the CSVs exist. Gated.
5. **Sponsors**, LUP only
6. **MP3s**, last

### Step 1: LUP scrape, handoff, and sync

The scrape first, the handoff immediately — before any overrides:

```clojure
(def data (-main ADS-URL))

(spit "/tmp/data" data)   ;; BEFORE any assoc. /tmp/data must carry
                           ;; the fetched state: correct :guid, :episode,
                           ;; :title, :links, :tags, :description.
```

⚠️ The `link_hoarder.clj` comment block blanks `:guid` to `""` *before*
its `spit`. Reloading it as-is overwrites the handoff with a guid-less
record. Refresh by hand as above and **check `:guid` afterwards**.

Then verify, then push:

```clojure
;; previews land in /dev/shm/ — open them, check links/tags/description
(save-preview data)

(publish-episode ADS-URL "linuxunplugged")
```

`publish-episode` sets metadata, purges existing links, and re-adds them.
The purge means re-running is safe for links but destroys hand-edits.

### Step 2: adfree scrape and sync

Separate scrape, same doc shape, different record. The scraped guid is the
*ads* guid, so override it:

```clojure
(def adfree-data
  (-> (lup/-main ADFREE-URL)
      (assoc :podcast "adfree")
      (assoc :guid ADFREE-GUID)))

(publish-episode ADFREE-URL "adfree" ADFREE-GUID)
```

Do **not** re-spill `/tmp/data` here — the handoff stays on the LUP scrape.

### Step 3: chapters (gated on CSVs)

CSVs land in `~/Downloads/workdir/`. Filename convention is feed-specific
and does not match the feed names:

| Feed | File |
|---|---|
| `linuxunplugged` | `Linux Unplugged <EP> (Ads) Chapters.csv` |
| `adfree` | `Linux Unplugged <EP> (Premium) Chapters.csv` |

```clojure
(sync-chapters {:client c :podcast "linuxunplugged"
                :episode-guid ADS-GUID
                :chapters-file (str work-dir "Linux Unplugged <EP> (Ads) Chapters.csv")})

(sync-chapters {:client c :podcast "adfree"
                :episode-guid ADFREE-GUID
                :chapters-file (str work-dir "Linux Unplugged <EP> (Premium) Chapters.csv")})
```

`sync-chapters` only appends and **refuses to run** if the episode already
has chapters. To redo:

```clojure
(doseq [{:keys [guid]} (fetch-chapter-ids c "linuxunplugged" ADS-GUID)]
  (delete-chapter {:client c :podcast "linuxunplugged"
                   :episode-guid ADS-GUID :chapter-guid guid}))
```

### Step 4: sponsorships — LUP only, never adfree

Two sponsors, always both:

| Sponsor | Campaign | Source |
|---|---|---|
| Nebula | Managed Nebula | in the Ads CSV |
| Jupiter Signal Network Membership | Jupiter Party Annual Membership | **manual — in no CSV** |

```clojure
(sync-sponsorship-times {:client c :podcast "linuxunplugged"
                         :episode-guid ADS-GUID
                         :ads-file (str work-dir "Linux Unplugged <EP> (Ads) Ads.csv")
                         :sponsor "Nebula"})
;; Jupiter membership: add by hand. It ships at 0 seconds on every episode.
```

Dynamic ad markers in the Ads CSV are **not** sponsorships — Fireside has no
equivalent field. They are skipped and reported in `:skipped`, never pushed.

### Step 5: audio — decode, encode, upload (last)

FLACs arrive in `~/Downloads/workdir/`. No WAVs or MP3s are kept — decode,
encode, upload, clean up as you go (disk is routinely at 95%).

```bash
cd ~/Downloads/workdir
ls *.flac | xargs -P$(nproc) -I {} flac -d {}
lame -m j --lowpass 20.5 -q 0 -b 128 --tn <EP> 'Linux Unplugged <EP> (Ads).wav'
lame -m j --lowpass 20.5 -q 0 -b 128 --tn <EP> 'Linux Unplugged <EP> (Premium).wav'
```

`--tn` must match the episode number — it writes the ID3 track number and
players use it for ordering. Unchanged since at least 675. `lame` and `flac`
are on the system PATH.

`done/upload.sh` (`rsync ./ fm2251@fm2251.rsync.net:wes/jb/casta_local_processed_flacs`)
archives the workdir to rsync.net — that is the FLAC archive, not the
episode MP3s. The Fireside audio-upload step is not in this repo; see open
questions.

## Publish time

Not handled by any function in this repo. `publish-episode` sets title,
description, keywords, and links — it sets no date, no schedule, no
embargo. Whatever controls when an episode goes live lives outside this
codebase (Fireside UI or another process).

Open questions, to be answered before this section can be written properly:

- Where is publish/schedule time set — Fireside web UI per episode, or
  somewhere else?
- Is there an embargo pattern (upload early, flip live at show time)?
- Do the two feeds go live together or does adfree lag / lead?
- Does the MP3 upload gate the publish (i.e. can't schedule until audio is
  attached)?

## Worked example: 687

- Title: *Linux by Proxy*
- `ADS-URL`: *(the doc the `c77dd843…` guid was scraped from — confirm)*
- `ADFREE-URL`: `https://h.docs.lol/d81Y_BKPSaSOvgMMMk3qKw?both`
- `ADS-GUID`: `c77dd843-0a44-4dec-9439-6e7a78f340a6`
- `ADFREE-GUID`: `12359b43-105b-4997-926c-b5eb2d9f8b27`
- 13 links, no dupes; `/tmp/data` holds the LUP scrape (2026-10-04)
- Chapters CSVs present (7 rows each); Ads CSV has `Nebula`, `Dynamic 1`,
  and a `Connecten` row at 00:42:49–00:44:22. Connecten turned out to be a
  real sponsor — onboarded 2026-10-04 (sponsor + `Connecten Internet`
  campaign, attached at 42:49). See GUIDE.md "Adding a brand-new sponsor".

## Known gaps

- **`/tmp/data` gets blanked by the comment block** (`link_hoarder.clj`
  runs fetch → override → override → spit, so the spill carries
  `:podcast "adfree"`, `:guid ""`). Fix: move the spill to immediately
  after the fetch, or give the handoff its own binding. Until then, refresh
  by hand and check `:guid`.
- **Stale hardcoded values in both comment blocks.** `premium-guid`,
  episode numbers in filenames, and `work-dir` are per-episode. The
  `fireside.clj` mid-file block still says 686. Stale values fail silently
  or hit the wrong episode.
- **Duplicate comment block in `fireside.clj`** (mid-file ~644 and bottom
  ~1002). The bottom one references an undefined bare `cookie` and throws
  if evaluated.
- **`fetch-page` / redirect handling.** An expired session is redirected to
  `/login` and served with a **200**. Reads return empty instead of
  erroring; writes may report success when nothing was written. Re-login
  before any run and verify by reading back.
