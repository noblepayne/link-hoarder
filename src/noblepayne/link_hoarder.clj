(ns noblepayne.link-hoarder
  (:gen-class)
  (:require [clojure.pprint :as pprint]
            [clojure.zip :as zip]
            [cybermonday.core :as markdown]
            [hato.client :as http]
            [hickory.select :as hs]
            [hickory.zip :as hz]))

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
  (cond
    (sequential? node) (recur (first node))
    (map? node) (recur (:content node))
    :else node))

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

(defn find-links
  ([initial-ziploc]
   (find-links initial-ziploc
               ;; if we have a `### Links` starting point, use that.
               (or (hs/select-next-loc (hs/id :links) initial-ziploc)
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
  (for [loc (find-links md-zip)]
    (assoc
       ;; use extracted link info map as base response
     (extract-link-data (zip/node loc))
       ;; add any associated blockquote text
     :quote
     (get-related-blockquote loc))))

(defn- extract-single-meta [md-zip id]
  (when-let [ziploc (hs/select-next-loc (hs/id id) md-zip)]
    (->> ziploc
         hs/after-subtree
         zip/node
         inner-content)))

(defn extract-metadata [md-zip]
  {:episode     (extract-single-meta md-zip :episode)
   :title       (extract-single-meta md-zip :title)
   :description (extract-single-meta md-zip :description)
   :tags        (extract-single-meta md-zip :tags)})

(defn fix-hdocs-url
  "Convert h.docs.lol links to markdown download version
   for convenience."
  [url]
  (let [parsed-url (java.net.URI/create url)
        scheme (.getScheme parsed-url)
        host (.getHost parsed-url)
        path (.getPath parsed-url)]
    (if (= host "h.docs.lol")
      ;; drop query params and add /download to the path
      (str scheme "://" host path "/download")
      url)))

(defn fetch-markdown [url]
  (-> url
      fix-hdocs-url
      http/get
      :body
      markdown/parse-body
      xmlhiccup->xmlparsed
      hz/hickory-zip))

(defn parse-data-from-markdown [mdzip]
  (assoc (extract-metadata mdzip)
         :links
         (extract-links mdzip)))

;; (defn exec
;;   "Invoke me with clojure -X noblepayne.link-hoarder/exec"
;;   [opts]
;;   (println "exec with" opts))

(defn -main
  "Invoke me with clojure -M -m noblepayne.link-hoarder"
  [url]
  (-> url
      fetch-markdown
      parse-data-from-markdown
      pprint/pprint))