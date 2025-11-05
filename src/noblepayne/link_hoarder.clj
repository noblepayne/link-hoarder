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
  (cond
    (sequential? node) (recur (first node))
    (map? node) (recur (:content node))
    :else node))

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
  (for [loc (find-links md-zip)
        :let [link-data (extract-link-data (zip/node loc))]
        ;; n.b. leaving as (:title link-data) to blow up on empty title)
        ;; TODO: proper validation
        :when (not (str/starts-with? (:title link-data) "READ:"))]
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
         inner-content
         str/trim)))

(defn extract-metadata [md-zip]
  {:show        (extract-single-meta md-zip :show)
   :episode     (extract-single-meta md-zip :episode)
   :title       (extract-single-meta md-zip :title)
   :description (extract-single-meta md-zip :description)
   :tags        (->> (extract-single-meta md-zip :tags)
                     (#(str/split % #","))
                     (map str/trim)
                     (filter seq)
                     distinct
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
  (let [content (if (-> url io/as-file .exists)
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
      #_pprint/pprint))


(comment
  (use 'clojure.repl 'clojure.pprint)

  ;; TODO empty ### breaks
  (def data
    (-main
     ""))
  
  data

  (spit "/tmp/data" data)
  )
