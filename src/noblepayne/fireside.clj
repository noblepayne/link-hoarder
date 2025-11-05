(ns noblepayne.fireside
  (:gen-class)
  (:require [clojure.string :as str]
            [hato.client :as http]
            [hickory.core :as hickory]
            [hickory.select :as hs]))

;; Setup
(set! *warn-on-reflection* true)

;; Env Vars
(def FIRESIDE-BASE-URL (System/getenv "FIRESIDE_BASE_URL"))
(def FIRESIDE-USER (System/getenv "FIRESIDE_USER"))
(def FIRESIDE-SECRET (System/getenv "FIRESIDE_SECRET"))

(defn http-client []
  (http/build-http-client
   {:connect-timeout 30000
    :version :http-1.1
    :redirect-policy :always
    :cookie-policy :all}))

(defn fetch-as-hickory [ring-map]
  (->> ring-map
       http/request
       :body
       hickory/parse
       hickory/as-hickory))

(defn form->map [form]
  (let [form-attrs (:attrs form)
        form-inputs (hs/select (hs/attr :type #{"hidden"}) #_(hs/attr :name)  form)]
    {:form-attrs form-attrs
     :form-inputs (into {} (comp (map (comp (juxt :name :value) :attrs))
                                 (filter first)) form-inputs)}))

(defn form-auth-token [form-map]
  (get-in form-map [:form-inputs "authenticity_token"]))

(defn login-to-fireside [client]
  (let [login-page (fetch-as-hickory {:http-client client
                                      :url (str FIRESIDE-BASE-URL "/login")})
        [login-form] (->> login-page
                          (hs/select (hs/class :form-signin)))
        form-action (-> login-form :attrs :action)
        inputs (hs/select (hs/tag :input) login-form)
        parsed-inputs (into {} (map (comp (juxt :name :value) :attrs)
                                    inputs))
        form-params (assoc parsed-inputs
                           "email" FIRESIDE-USER
                           "password" FIRESIDE-SECRET)]
    (http/post form-action {:http-client client
                            :form-params form-params})
    true))

(defn add-link [{:keys [client podcast episode-guid title url quote]}]
  (let [new-url (str/join "/" [FIRESIDE-BASE-URL
                               "podcasts" podcast
                               "episodes" episode-guid
                               "links"    "new"])
        new-url-page (fetch-as-hickory {:http-client client :url new-url})
        [links-form] (hs/select (hs/and (hs/id "new_link"))
                                new-url-page)
        form-action (str FIRESIDE-BASE-URL (-> links-form
                                               :attrs
                                               :action
                                               java.net.URI/create
                                               .getPath))
        links-inputs (hs/select (hs/tag :input) links-form)
        parsed-inputs (into {} (map (comp (juxt :name :value) :attrs) links-inputs))
        form-params (assoc parsed-inputs
                           "link[title]" title
                           "link[url]" url
                           "link[excerpt]" quote)]
    (http/post form-action {:http-client client
                            :form-params form-params})
    true))

(defn delete-link [{:keys [client podcast episode-guid link-guid]}]
  (let [delete-url (str/join "/" [FIRESIDE-BASE-URL
                                  "podcasts" podcast
                                  "episodes" episode-guid
                                  "links"    link-guid])
        link-url (str delete-url "/edit")
        links-url-page (fetch-as-hickory {:http-client client
                                          :url link-url})
        auth-token (let [[meta-tag] (hs/select (hs/and (hs/tag :meta)
                                                       (hs/attr :name #{"csrf-token"}))
                                               links-url-page)]
                     (-> meta-tag :attrs :content))]
    (http/request {:method :post
                   :url delete-url
                   :http-client client
                   :form-params {"_method" "delete"
                                 "authenticity_token" auth-token}})))

(defn purge-links [{:keys [client podcast episode-guid] :as args}]
  (let [links-url (str/join "/" [FIRESIDE-BASE-URL
                                 "podcasts" podcast
                                 "episodes" episode-guid
                                 "links"])
        links-url-page (fetch-as-hickory {:http-client client
                                          :url links-url})
        trs (hs/select (hs/and (hs/tag :tr)
                               ;; header row doesn't have id attr, we only want data rows
                               (hs/attr :id))
                       links-url-page)
        link-guids (mapv (fn [tr]
                           (let [[atag] (hs/select (hs/and (hs/tag :a)
                                                           (hs/attr :title #{"Edit Link"}))
                                                   tr)
                                 edit-link (-> atag :attrs :href)
                                 link-guid (->> (str/split edit-link #"/")
                                                reverse
                                                second)]
                             link-guid))
                         trs)]
    (doseq [link-guid link-guids]
      (println "Deleting" link-guid)
      (delete-link (assoc args :link-guid link-guid)))))

(defn add-chapter [{:keys [client podcast episode-guid timecode note]}]
  (let [new-url (str/join "/" [FIRESIDE-BASE-URL
                               "podcasts" podcast
                               "episodes" episode-guid
                               "chapters"    "new"])
        new-url-page (fetch-as-hickory {:http-client client :url new-url})
        [chapter-form] (hs/select (hs/and (hs/id "new_chapter"))
                                  new-url-page)
        form-action (str FIRESIDE-BASE-URL (-> chapter-form
                                               :attrs
                                               :action
                                               java.net.URI/create
                                               .getPath))
        chapter-inputs (hs/select (hs/tag :input) chapter-form)
        parsed-inputs (into {} (map (comp (juxt :name :value) :attrs) chapter-inputs))
        form-params (assoc parsed-inputs
                           "chapter[timecode_as_words]" timecode
                           "chapter[note]" note)]
    (http/post form-action {:http-client client
                            :form-params form-params})
    #_true))

(defn load-chapters [chapter-file]
  (let [chapter-lines (clojure.string/split-lines (slurp chapter-file))
        chapter-xf (comp (map #(rest (re-matches #"([^ ]+?) (.+?)" %)))
                         (map vec)
                         (map (fn [[ts title]] {"startTime" ts #_`(~'ts->s ~ts)
                                                "title" title})))]
    (into [] chapter-xf chapter-lines)))
  

(comment

  (try
    (delete-link {:client c
                  :podcast "linuxunplugged"
                  :episode-guid "869b643f-3e5b-4020-aec1-0ec3f2f26287"
                  :link-guid "19b95853-18b8-4713-a3fe-aaa75e4f3430"})
    (catch Exception e (def error e) (throw e)))

  (try
    (purge-links {:client c
                  :podcast "linuxunplugged"
                  :episode-guid "4c0a537d-10c6-40ca-b44c-9a43891313c6"})
    (catch Exception e (def error e) (throw e)))

  
  (try
    (add-chapter {:client c
                  :podcast "linuxunplugged"
                  :episode-guid "b7a2d096-0fe0-48e9-8ed3-2cf129d1be4a"
                  :timecode "0"
                  :note "test"})
    (catch Exception e (def error e) (throw e)))

  (doseq [{:strs [startTime title] :as chapter}
          (load-chapters "/home/wes/Downloads/workdir/Linux Unplugged 639 (Premium).txt")]
    (println title)
    (add-chapter {:client c
                  :podcast "adfree"
                  :episode-guid "6bb0d21f-755f-4a22-b42f-a5c956c95fc2" 
                  :timecode startTime
                  :note title}))

  )

(defn xmlparsed->xmlhiccup [tree]
  (if (string? tree)
    tree
    (let [tag (:tag tree)
          attrs (:attrs tree)
          content (:content tree)
          metadata (meta tree)
          ;; TODO: non-recusive version?
          translated-content (map xmlparsed->xmlhiccup content)]
      (with-meta
        (if (empty? attrs)
          ;; skip including empty attrs
          (into [tag] translated-content)
          (into [tag attrs] translated-content))
        metadata))))


(defn set-metedata [{:keys [client podcast episode-guid metadata]}]
  (let [action-url (str/join "/"
                             [FIRESIDE-BASE-URL
                              "podcasts" podcast
                              "episodes" episode-guid])
        post-url (str action-url "/edit")
        post-url-page (fetch-as-hickory {:http-client client
                                         :url post-url})
        [metadata-form] (hs/select (hs/id (str "edit_episode_" episode-guid))
                                   post-url-page)
        form-map (form->map metadata-form)
        auth-token (form-auth-token form-map)
        form-params (assoc metadata
                           "authenticity_token" auth-token
                           "_method" (get-in form-map [:form-inputs "_method"])
                           "utf8" (get-in form-map [:form-inputs "utf8"]))]
    (http/post action-url {:http-client client
                           :form-params form-params})
    true))

(defn set-show-meta [{:keys [client
                             podcast
                             episode-guid
                             title
                             description
                             tags]}]
  (set-metedata {:client client
                 :podcast podcast
                 :episode-guid episode-guid
                 :metadata {"episode[title]" title
                            "episode[subtitle]" description
                            "episode[description]" description
                            "episode[keywords]" tags
                            "episode[tag_list]" tags}}))

(comment
  (def c (http-client))
  (login-to-fireside c)

  (clojure.pprint/print-table
   (sort-by :name
            (filter :exception-types (:members (clojure.reflect/reflect cookie)))))

  (->> "https://app.fireside.fm/podcasts/linuxunplugged/episodes/bc95a92e-c86f-4577-90a7-7f6bf3f3f6db/edit"
       (#(http/get % {:http-client c}))
       :body
       hickory/parse
       hickory/as-hickory
       (hs/select (hs/tag :form))
       (#(nth % 0))
       form->map
       clojure.pprint/pprint)

  (set-metedata
   {:client c
    :podcast "linuxunplugged"
    :episode-guid "bc95a92e-c86f-4577-90a7-7f6bf3f3f6db"
    :metadata {"episode[title]" "TEST TITLE 11111Z"}})

  (add-link
   {:client c
    :podcast "linuxunplugged"
    :episode-guid "89cf45f9-394a-482e-8ef9-f2b530188274"
    :title "test title"
    :url "http://test.url"
    :quote "test quote"})


  (doseq [{:keys [:title :href :quote] :as link} (noblepayne.link-hoarder/data :links)]
    (println href)
    (add-link {:client c
               :podcast "adfree"
               :episode-guid "6bb0d21f-755f-4a22-b42f-a5c956c95fc2"
               :title title
               :url href
               :quote quote}))

  (try
    (set-show-meta {:client c
                    :podcast "adfree"
                    :episode-guid "6bb0d21f-755f-4a22-b42f-a5c956c95fc2"
                    :title (:title noblepayne.link-hoarder/data)
                    :description (:description noblepayne.link-hoarder/data)
                    :tags (:tags noblepayne.link-hoarder/data)})
    true
    (catch Exception e (def error e) (throw e))))