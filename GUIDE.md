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
