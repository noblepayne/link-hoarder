(ns com.noblepayne.link-hoarder
  (:gen-class)
  (:require [clojure.pprint :as pprint]
            [clojure.zip :as zip]
            [cybermonday.core :as cm]
            [hato.client :as http]
            [hickory.select :as hs]))

;; Stolen from https://github.com/retrogradeorbit/bootleg
(defn- collapse-nested-lists [form]
  (cond
    (string? form)
    form

    (and (vector? form) (keyword? (first form)))
    (->> form
         (mapv #(if (seq? %) % [%]))
         (apply concat)
         (into []))

    :else
    form))

;; Stolen from https://github.com/retrogradeorbit/bootleg
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

(defn get-related-blockquote [a-ziploc]
  (let [;; two `zip/next`s is a heuristic to find a "close enough" blockquote.
        possible-quote (-> a-ziploc zip/next zip/next zip/node)
        blockquote? (= (:tag possible-quote) :blockquote)]
    (when blockquote?
      ;; extract blockquote text, which is embedded in a `p`.
      (-> possible-quote :content first :content first))))

(defn extract-link-data [link-node]
  {:href (-> link-node :attrs :href)
   :title (-> link-node :content first)})

(defn extract-links [url]
  (let [;; fetch markdown from url
        raw-markdown (-> url http/get :body)
        ;; parse markdown into hickory style AST
        parsed-markdown (-> raw-markdown cm/parse-body xmlhiccup->xmlparsed)
        ;; find zipper locations for all links in parsed markdown
        link-locs (hs/select-locs (hs/tag :a) parsed-markdown)]
    ;; extract link data from each link zipper location
    (for [loc link-locs]
      (assoc
       ;; use extracted link info map as base response
       (extract-link-data (zip/node loc))
       ;; add any associated blockquote text
       :quote
       (get-related-blockquote loc)))))

;; (defn exec
;;   "Invoke me with clojure -X com.noblepayne.link-hoarder/exec"
;;   [opts]
;;   (println "exec with" opts))

(defn -main
  "Invoke me with clojure -M -m com.noblepayne.link-hoarder"
  [url]
  (pprint/pprint (extract-links url)))
