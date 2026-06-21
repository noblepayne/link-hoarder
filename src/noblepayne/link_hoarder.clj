(ns noblepayne.link-hoarder
  (:gen-class)
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.zip :as zip]
            [cybermonday.core :as markdown]
            [hato.client :as http]
            [hickory.select :as hs]
            [hickory.zip :as hz]
            [clojure.string :as str]))

(set! *warn-on-reflection* true)

;; From https://github.com/retrogradeorbit/bootleg
(defn- collapse-nested-lists [form]
  (if (and (vector? form) (keyword? (first form)))
    (->> form
         (mapv #(if (seq? %) % [%]))
         (apply concat)
         (into []))
    form))

;; From https://github.com/retrogradeorbit/bootleg
(defn xmlhiccup->xmlparsed [tree]
  (cond
    (string? tree) tree
    (number? tree) (str tree)
    (keyword? tree) (str tree)
    (contains? tree :content) tree
    :else (let [metadata (meta tree)
                tree (collapse-nested-lists tree)
                [tag maybe-attrs & remain] tree
                attrs? (map? maybe-attrs)
                attrs (if attrs? maybe-attrs {})
                content (if attrs? remain (concat [maybe-attrs] remain))]
            (-> {:tag tag
                 :attrs attrs
                 :content (map xmlhiccup->xmlparsed content)}
                (with-meta metadata)))))

(defn inner-content [node]
  (let [content-val (cond
                      (sequential? node) (str/join (map inner-content node))
                      (map? node) (inner-content (:content node))
                      :else (str node))]
    (if (string? content-val)
      (str/trim content-val)
      content-val)))

;; TODO: how to handle bold or other style that makes separate elements than just one blockquote
(defn get-related-blockquote [a-ziploc]
  (let [possible-quote (-> a-ziploc
                           ;; grab next element in tree
                           hs/after-subtree
                           zip/node)
        blockquote? (= (:tag possible-quote) :blockquote)]
    (when blockquote?
      ;; extract blockquote text, which is embedded in a `p`.
      (inner-content possible-quote))))

(defn extract-link-data [link-node]
  {:href (-> link-node :attrs :href)
   :title (inner-content link-node)})

;; TODO: how to handle multiple? use first?
;; TODO: more distinct signal? Links could be reused.
(defn find-links
  ([initial-ziploc]
   (find-links initial-ziploc
               ;; if we have a `### Links` starting point, use that.
               (or (hs/select-next-loc (hs/id "show-links") initial-ziploc)
                   initial-ziploc)))
  ([_ current-ziploc]
   (let [next-link (hs/select-next-loc
                    (hs/tag :a)
                    current-ziploc
                    zip/next
                    ;; continue scanning until end or `### End Links`
                    #(or (zip/end? %)
                         ((hs/id :end-links) %)))]
     (when next-link
       (lazy-seq (cons
                  next-link
                  (find-links (zip/next next-link))))))))

(defn extract-links [md-zip]
  (vec
   (for [loc (find-links md-zip)
         :let [link-data (extract-link-data (zip/node loc))]
         :when (not (str/starts-with? (:title link-data) "READ:"))]
     (assoc (extract-link-data (zip/node loc))
            :quote
            (get-related-blockquote loc)))))

(defn extract-guest-data [link-node]
  {:name (inner-content link-node)
   :href (-> link-node :attrs :href)
   :img (-> link-node :attrs :title)
   :role "guest"
   :bio nil})

(defn find-guests
  ([initial-ziploc]
   (find-guests initial-ziploc
                (or (hs/select-next-loc (hs/id "show-guests") initial-ziploc)
                    (hs/select-next-loc (hs/id "guest-profiles") initial-ziploc)
                    (hs/select-next-loc (hs/id "guests") initial-ziploc)
                    initial-ziploc)))
  ([_ current-ziploc]
   (let [next-link (hs/select-next-loc
                    (hs/tag :a)
                    current-ziploc
                    zip/next
                    #(or (zip/end? %)
                         ((hs/id :end-guests) %)))]
     (when next-link
       (lazy-seq (cons
                  next-link
                  (find-guests (zip/next next-link))))))))

(defn extract-guests [md-zip]
  (vec
   (for [loc (find-guests md-zip)
         :let [guest-data (extract-guest-data (zip/node loc))]
         :when (seq (:name guest-data))]
     (assoc guest-data
            :bio
            (get-related-blockquote loc)))))

(defn- extract-single-meta [md-zip id]
  (let [id-name (name id)]
    (when-let [ziploc (hs/select-next-loc (hs/id id-name) md-zip)]
      ;; navigate to the next sibling that isn't a text node (if any) or just the next subtree
      (let [val (inner-content (xmlhiccup->xmlparsed (zip/node (hs/after-subtree ziploc))))]
        (if (string? val) (str/trim val) val)))))

(defn extract-metadata [md-zip]
  {:guid (extract-single-meta md-zip :guid)
   :show (extract-single-meta md-zip :show)
   :episode (extract-single-meta md-zip :episode)
   :title (extract-single-meta md-zip :title)
   :description (extract-single-meta md-zip :description)
   :tags (->> (extract-single-meta md-zip :tags)
              (#(str/split % #","))
              (map str/trim)
              (filter seq)
              distinct
              ;; Reverse to match Fireside display order (Fireside reverses on ingest)
              reverse
              (str/join ", "))})

(defn fix-hdocs-url
  "Convert h.docs.lol links to markdown download version
   for convenience."
  [url]
  (let [parsed-url (io/as-url url)
        protocol (.getProtocol parsed-url)
        host (.getHost parsed-url)
        path (.getPath parsed-url)]
    (if (= host "h.docs.lol")
      ;; drop query params and add /download to the path
      (str protocol "://" host path "/download")
      url)))

(defn fetch-markdown [url]
  (let [content (if (and (string? url) (-> url io/as-file .exists))
                  ;; read from local filesystem
                  (slurp url)
                  ;; read via http
                  (-> url fix-hdocs-url http/get :body))]
    (-> content
        markdown/parse-body
        xmlhiccup->xmlparsed
        hz/hickory-zip)))

(defn parse-data-from-markdown [mdzip]
  (assoc (extract-metadata mdzip)
         :links
         (extract-links mdzip)
         :guests
         (extract-guests mdzip)))

(defn -main
  "Invoke me with clojure -M -m noblepayne.link-hoarder"
  [url]
  (-> url
      fetch-markdown
      parse-data-from-markdown
      #_pprint/pprint))

(defn grab-link-data [doc-url]
  (->> doc-url
       fetch-markdown
       extract-links))

(defn- escape-html [s]
  (if (nil? s)
    ""
    (str/replace (str s) #"[&<>\"']"
                 {"&" "&amp;"
                  "<" "&lt;"
                  ">" "&gt;"
                  "\"" "&quot;"
                  "'" "&#39;"})))

(declare preview-markdown)

(defn- render-hiccup [form]
  (let [void-elements #{"area" "base" "br" "col" "embed" "hr" "img" "input" "link" "meta" "param" "source" "track" "wbr"}]
    (cond
      (string? form) (escape-html form)
      (number? form) (str form)
      (vector? form) (let [tag (first form)
                           tag-name (name tag)
                           maybe-attrs (second form)
                           attrs? (map? maybe-attrs)
                           attrs (if attrs? maybe-attrs {})
                           content (if attrs? (drop 2 form) (drop 1 form))
                           attr-str (str/join " " (for [[k v] attrs] (str (name k) "=\"" (escape-html v) "\"")))]
                       (if (void-elements (str/lower-case tag-name))
                         (str "<" tag-name (when (seq attr-str) (str " " attr-str)) ">")
                         (str "<" tag-name (when (seq attr-str) (str " " attr-str)) ">"
                              (str/join "" (map render-hiccup content))
                              "</" tag-name ">")))
      (sequential? form) (str/join "" (map render-hiccup form))
      :else (escape-html (str form)))))

(defn- preview-full [data]
  (let [guest-count (count (:guests data))
        link-count (count (:links data))
        md (preview-markdown data :episode)
        hiccup [:article
                [:header {:class "episode-header"}
                 [:h1 (:title data)]
                 [:div {:class "episode-meta"}
                  [:span [:strong "Show: "] (:show data)]
                  [:span [:strong "Episode: "] (:episode data)]]
                 (when (:guid data) [:p {:class "guid"} (str "GUID: " (:guid data))])]
                [:section {:class "description"} (:description data)]
                (when (seq (:tags data))
                  [:div {:class "tags"}
                   (for [t (str/split (:tags data) #",\s*")]
                     [:span t])])
                (when (pos? guest-count)
                  [:section {:class "guests"}
                   [:h2 (str "Guests (" guest-count ")")]
                   [:table
                    [:thead [:tr [:th "Name"] [:th "URL"] [:th "Bio"]]]
                    [:tbody
                     (for [g (:guests data)]
                       [:tr
                        [:td (:name g)]
                        [:td (if (:href g) [:a {:href (:href g)} (:href g)] "-")]
                        [:td (or (:bio g) "-")]])]]])
                [:section {:class "links-section"}
                 [:h2 (str "Links (" link-count ")")]
                 [:table
                  [:thead [:tr [:th "Title"] [:th "URL"] [:th "Quote"]]]
                  [:tbody
                   (for [l (:links data)]
                     [:tr
                      [:td (:title l)]
                      [:td [:a {:href (:href l)} (:href l)]]
                      [:td (or (:quote l) "-")]])]]]
                [:section {:class "markdown-section"}
                 [:h2 "Markdown"]
                 [:p
                  [:button {:id "copyMarkdown" :onclick "copyMarkdown()"} "Copy Markdown"]
                  [:button {:id "downloadMarkdown" :onclick "downloadMarkdown()"} "Download Markdown"]]
                 [:textarea {:id "markdown" :style "display:none"} md]
                 [:pre {:id "markdown-source"} [:code md]]]]]
    (str "<!DOCTYPE html><html><head><meta charset='UTF-8'><title>Link Hoarder</title>"
         "<link rel='stylesheet' href='https://unpkg.com/@picocss/pico@2/css/pico.min.css'>"
         "<script>document.documentElement.classList.add('dark')</script>"
         "<style>"
         "main { max-width: 1000px; margin: 0 auto; padding: 1rem; } "
         ".episode-header { border-bottom: 1px solid var(--pico-muted-border-color); margin-bottom: 1.5rem; } "
         ".episode-meta span { margin-right: 1.5rem; } "
         ".tags span { background: var(--pico-muted-background); padding: 2px 8px; border-radius: 4px; margin-right: 6px; font-size: 0.8rem; border: 1px solid var(--pico-muted-border-color); } "
         "#markdown-source { margin-top: 1rem; padding: 1rem; background: var(--pico-background-color); border: 1px solid var(--pico-muted-border-color); border-radius: 4px; overflow-x: auto; font-family: monospace; font-size: 0.875rem; white-space: pre-wrap; } "
         "</style></head><body><main>"
         (render-hiccup hiccup)
         "</main><script>"
         "function downloadTextArea(textAreaId, fileName) {"
         "  const textArea = document.getElementById(textAreaId);"
         "  const blob = new Blob([textArea.value], { type: 'text/plain' });"
         "  const url = URL.createObjectURL(blob);"
         "  const a = document.createElement('a');"
         "  a.href = url; a.download = fileName || 'download.txt';"
         "  document.body.appendChild(a); a.click(); document.body.removeChild(a); URL.revokeObjectURL(url);"
         "}"
         "function downloadMarkdown() { downloadTextArea('markdown', 'episode-links.md'); }"
         "function copyMarkdown() {"
         "  const md = document.getElementById('markdown').value;"
         "  navigator.clipboard.writeText(md).then(() => {"
         "    const btn = document.getElementById('copyMarkdown');"
         "    const old = btn.textContent; btn.textContent = 'Copied!';"
         "    setTimeout(() => btn.textContent = old, 1500);"
         "  });"
         "}"
         "</script></body></html>")))

(defn preview-markdown
  "Generate markdown preview from data.
  Format: :episode (with ##### Episode Links header), :plain (just links)"
  ([data]
   (preview-markdown data :episode))
  ([data format]
   (let [header (case format
                  :episode "##### Episode Links\n"
                  "")
         links-str (str/join "\n"
                             (for [l (:links data)]
                               (let [link-line (str "* [" (:title l) "](" (:href l) ")")]
                                 (if (seq (:quote l))
                                   (str link-line "\n  > " (:quote l))
                                   link-line))))]
     (str header links-str "\n"))))

(defn- preview-links [data]
  (let [hiccup [:article
                [:h1 "Links"]
                [:ul {:style "padding-left: 0;"}
                 (for [l (:links data)]
                   [:li {:style "list-style: none; margin-bottom: 1rem;"}
                    [:a {:href (:href l)} (:title l)]
                    (when (seq (:quote l))
                      [:blockquote {:style "margin-top: 0.5rem;"} (:quote l)])])]]]
    (str "<!DOCTYPE html><html><head><meta charset='UTF-8'><title>Links</title>"
         "<link rel='stylesheet' href='https://unpkg.com/@picocss/pico@2/css/pico.min.css'>"
         "<script>document.documentElement.classList.add('dark')</script>"
         "<style>main { max-width: 800px; margin: 0 auto; padding: 1rem; }</style>"
         "</head><body><main>"
         (render-hiccup hiccup)
         "</main></body></html>")))

(defn preview
  "Generate HTML preview string from parsed data.
  Supported formats: :full (default), :links"
  ([data]
   (preview data :full))
  ([data format]
   (let [fmt (or format :full)]
     (case fmt
       :links (preview-links data)
       (preview-full data)))))

(defn save-preview
  "Save HTML preview to /dev/shm/link-hoarder-{guid}.html.
  Returns file:// URL for easy access.
  Supported formats: :full (default), :links"
  ([data]
   (save-preview data :full))
  ([data format]
   (let [guid (:guid data)
         filename (if guid
                    (str "link-hoarder-" guid ".html")
                    "link-hoarder.html")
         path (str "/dev/shm/" filename)]
     (spit path (preview data format))
     (str "file://" path))))

(defn save-markdown
  "Save markdown preview to /dev/shm/episode-links-{guid}.md.
  Returns file:// URL.
  Format: :episode (default, with header), :plain (just links)"
  ([data]
   (save-markdown data :episode))
  ([data format]
   (let [guid (:guid data)
         filename (if guid
                    (str "episode-links-" guid ".md")
                    "episode-links.md")
         path (str "/dev/shm/" filename)]
     (spit path (preview-markdown data format))
     (str "file://" path))))

(defn preview-rendered
  "Generate mobile-friendly feed-style preview (Hiccup -> HTML)."
  [data]
  (let [{:keys [title show episode description tags links guests guid]} data
        hiccup [:article
                [:header {:class "episode-header"}
                 [:h1 title]
                 [:div {:class "episode-meta"}
                  [:span show]
                  [:span (str "Episode " episode)]]
                 (when guid [:p {:class "guid"} (str "GUID: " guid)])]
                [:section {:class "description"} description]
                (when (and tags (not (str/blank? tags)))
                  [:div {:class "tags"}
                   (for [t (str/split tags #",\s*")]
                     [:span t])])
                (when (pos? (count guests))
                  [:section {:class "guests"}
                   [:h2 "Guests"]
                   [:div {:class "guest-grid"}
                    (for [g guests]
                      [:div {:class "guest-card"}
                       [:img {:src (or (:img g) "https://www.jupiterbroadcasting.com/images/people/guest.jpg")
                              :alt (:name g)}]
                       [:a {:href (:href g)} (:name g)]])]])
                [:section {:class "links-section"}
                 [:h2 (str "Links (" (count links) ")")]
                 [:ul {:style "padding-left: 0;"}
                  (for [l links]
                    [:li {:class "link-item", :style "list-style: none; margin-bottom: 1.25rem;"}
                     [:div {:class "link-title"}
                      [:a {:href (:href l) :title (:title l) :rel "nofollow" :style "text-decoration: none; font-weight: bold;"} (:title l)]
                      (when (:quote l) [:span {:class "link-sep"} " — "])]
                     (when (:quote l)
                       [:div {:class "link-quote"} (:quote l)])])]]]]
    (str "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\">"
         "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">"
         "<link rel=\"stylesheet\" href=\"https://unpkg.com/@picocss/pico@2/css/pico.min.css\">"
         "<script>document.documentElement.classList.add('dark')</script>"
         "<style>"
         "main { max-width: 1000px; margin: 0 auto; padding: 1rem; } "
         "@media (max-width: 1000px) { main { max-width: 100%; } } "
         ".episode-header { border-bottom: 1px solid var(--pico-muted-border-color); margin-bottom: 1rem; padding-bottom: 0.5rem; } "
         ".episode-meta span { margin-right: 1rem; color: var(--pico-muted-color); font-size: 0.9rem; } "
         ".guid { font-family: monospace; font-size: 0.75rem; color: var(--pico-muted-color); } "
         ".tags { margin: 1rem 0; } "
         ".tags span { background: var(--pico-muted-background); padding: 2px 8px; border-radius: 4px; margin-right: 6px; font-size: 0.8rem; border: 1px solid var(--pico-muted-border-color); color: var(--pico-color); } "
         ".guest-grid { display: flex; flex-wrap: wrap; gap: 1rem; } "
         ".guest-card { display: flex; align-items: center; } "
         ".guest-card img { width: 40px; height: 40px; border-radius: 50%; margin-right: 8px; border: 1px solid var(--pico-muted-border-color); } "
         ".link-title { margin-bottom: 0.25rem; line-height: 1.2; } "
         ".link-sep { color: var(--pico-muted-color); font-weight: normal; } "
         ".link-quote { color: var(--pico-muted-color); font-size: 0.95rem; font-style: italic; padding-left: 1rem; border-left: 2px solid var(--pico-muted-border-color); margin-top: 0.25rem; } "
         "</style></head><body><main>"
         (render-hiccup hiccup)
         "</main></body></html>")))

(defn save-preview-rendered
  "Save feed-style HTML preview to /dev/shm/link-hoarder-rendered-{guid}.html."
  [data]
  (let [guid (:guid data)
        filename (if guid (str "link-hoarder-rendered-" guid ".html") "link-hoarder-rendered.html")
        path (str "/dev/shm/" filename)]
    (spit path (preview-rendered data))
    (str "file://" path)))

(comment
  (use 'clojure.repl 'clojure.pprint)

  ;; TODO empty ### breaks
  (def data
    (-main
     ""))

  data
  ;; ads
  (def data (assoc data :podcast "linuxunplugged"))
  (def data (assoc data :guid ""))
  ;; adfree
  (def data (assoc data :podcast "adfree"))
  (def data (assoc data :guid ""))

  (save-preview data)
  (save-markdown data)
  (save-preview-rendered data)

  (spit "/tmp/data" data)

  (grab-link-data ""))
