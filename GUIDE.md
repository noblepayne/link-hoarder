# Link Hoarder + Fireside Guide

## What We Do

1. **Fetch show notes** from docs.lol URL via `link-hoarder/-main`
2. **Associate podcast + guid** - either:
   - `linuxunplugged` + guid from markdown
   - `adfree` + specific guid (different feed)
3. **Push to Fireside**:
   - `set-show-meta` - title, description, tags
   - `purge-links` - clear old links (for updates)
   - `add-link` loop - add all links

## Step-by-Step Guide

### 1. Load namespaces

```clojure
(require 'noblepayne.link-hoarder)
(require 'noblepayne.fireside)
```

### 2. Login to Fireside

```clojure
(def c (noblepayne.fireside/http-client))
(noblepayne.fireside/login-to-fireside c)
```

### 3. Fetch and prep data (in link-hoarder namespace)

```clojure
(in-ns 'noblepayne.link-hoarder)

;; Fetch from docs.lol
(def data (-main "https://h.docs.lol/URL?both"))

;; Associate podcast slug and guid
;; For linuxunplugged (uses guid from markdown):
(def data (assoc data :podcast "linuxunplugged"))

;; For adfree (uses specific guid):
(def data (assoc data :podcast "adfree" :guid "GUID-HERE"))

;; Optionally save to file
(spit "/tmp/data" data)
```

### 4. Push to Fireside (switch back to user namespace)

```clojure
(in-ns 'user)

;; Set show metadata (title, description, tags)
(noblepayne.fireside/set-show-meta
  {:client c
   :podcast (:podcast noblepayne.link-hoarder/data)
   :episode-guid (:guid noblepayne.link-hoarder/data)
   :title (:title noblepayne.link-hoarder/data)
   :description (:description noblepayne.link-hoarder/data)
   :tags (:tags noblepayne.link-hoarder/data)})

;; For UPDATES: purge old links first, then add new ones
(noblepayne.fireside/purge-links
  {:client c
   :podcast (:podcast noblepayne.link-hoarder/data)
   :episode-guid (:guid noblepayne.link-hoarder/data)})

;; Add all links
(doseq [{:keys [title href quote]} (:links noblepayne.link-hoarder/data)]
  (noblepayne.fireside/add-link
    {:client c
     :podcast (:podcast noblepayne.link-hoarder/data)
     :episode-guid (:guid noblepayne.link-hoarder/data)
     :title title
     :url href
     :quote quote}))
```

## Automation Ideas

### 1. Single function for full workflow

```clojure
(defn publish-episode [url podcast & [guid]]
  (let [data (-> url
                 link-hoarder/-main
                 (assoc :podcast podcast)
                 (assoc :guid (or guid (:guid data))))]
    (fireside/set-show-meta {:client c
                            :podcast (:podcast data)
                            :episode-guid (:guid data)
                            :title (:title data)
                            :description (:description data)
                            :tags (:tags data)})
    (fireside/purge-links {:client c
                          :podcast (:podcast data)
                          :episode-guid (:guid data)})
    (doseq [{:keys [title href quote]} (:links data)]
      (fireside/add-link {:client c
                         :podcast (:podcast data)
                         :episode-guid (:guid data)
                         :title title
                         :url href
                         :quote quote}))))
```

### 2. Batch mode

Process multiple shows at once:

```clojure
(def shows
  [{:url "https://h.docs.lol/URL1?both" :podcast "linuxunplugged"}
   {:url "https://h.docs.lol/URL2?both" :podcast "adfree" :guid "GUID"}])

(doseq [show shows]
  (publish-episode (:url show) (:podcast show) (:guid show)))
```

### 3. Dry-run mode

Preview what would be pushed without making API calls:

```clojure
(defn dry-run [url podcast]
  (let [data (-> url link-hoarder/-main (assoc :podcast podcast))]
    {:title (:title data)
     :description (:description data)
     :tags (:tags data)
     :link-count (count (:links data))
     :links (mapv :href (:links data))}))

(dry-run "https://h.docs.lol/URL?both" "linuxunplugged")
```

### 4. Auto-detect guid

Use guid from markdown for linuxunplugged, override for adfree:

```clojure
(defn determine-guid [podcast data existing-guid]
  (case podcast
    "linuxunplugged" (:guid data)
    "adfree" (or existing-guid (:guid data))))
```

### 5. Persist login state

Save the authenticated client to a file or atom for reuse across REPL restarts.

### 6. Chapter loading integration

Incorporate the chapter file loading from fireside.clj to add chapters automatically:

```clojure
(defn load-chapters-from-file [filepath]
  (doseq [{:strs [startTime title]} (fireside/load-chapters filepath)]
    (fireside/add-chapter {:client c
                          :podcast (:podcast data)
                          :episode-guid (:guid data)
                          :timecode startTime
                          :note title})))
```
