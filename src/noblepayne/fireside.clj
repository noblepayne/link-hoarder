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
        form-inputs (hs/select (hs/attr :type #{"hidden"}) #_(hs/attr :name) form)]
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
                               "links" "new"])
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
                                  "links" link-guid])
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
        link-guids (->> trs
                        (map (fn [tr]
                               (let [[atag] (hs/select (hs/and (hs/tag :a)
                                                               (hs/class "data-table__link"))
                                                       tr)
                                     edit-link (-> atag :attrs :href)]
                                 (when edit-link
                                   (-> (str/split edit-link #"/")
                                       reverse
                                       second)))))
                        (filter identity)
                        vec)]
    (doseq [link-guid link-guids]
      (println "Deleting" link-guid)
      (delete-link (assoc args :link-guid link-guid)))))

(defn add-chapter [{:keys [client podcast episode-guid timecode note]}]
  (let [new-url (str/join "/" [FIRESIDE-BASE-URL
                               "podcasts" podcast
                               "episodes" episode-guid
                               "chapters" "new"])
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
                  :podcast (:podcast noblepayne.link-hoarder/data)
                  :episode-guid (:guid noblepayne.link-hoarder/data)})
    (catch Exception e (def error e) (throw e)))

  (try
    (add-chapter {:client c
                  :podcast "linuxunplugged"
                  :episode-guid "b7a2d096-0fe0-48e9-8ed3-2cf129d1be4a"
                  :timecode "0"
                  :note "test"})
    (catch Exception e (def error e) (throw e)))

  (doseq [{:strs [startTime title] :as chapter}
          (load-chapters "/home/wes/Downloads/workdir/Linux Unplugged 671 Ads.txt")]
    (println title)
    (add-chapter {:client c
                  :podcast (:podcast noblepayne.link-hoarder/data)
                  :episode-guid (:guid noblepayne.link-hoarder/data)
                  :timecode startTime
                  :note title})))

(defn prepare-data
  "Fetch markdown from url and prepare data with podcast association.
  Returns data map with :podcast set."
  [url podcast]
  (-> (noblepayne.link-hoarder/-main url)
      (assoc :podcast podcast)))

(defn- unreverse-tags
  "Reverse tags back to original order for Fireside.
   Handles string input and ensures clean comma separation."
  [tags]
  (if (string? tags)
    (->> (clojure.string/split tags #",")
         (map clojure.string/trim)
         (filter seq)
         reverse
         (clojure.string/join ", "))
    tags))

(defn ensure-login
  "Create client and login to Fireside. Returns client."
  []
  (let [client (http-client)]
    (login-to-fireside client)
    client))

(declare set-show-meta)
(defn set-metadata
  "Set episode metadata (title, description, tags).
  Requires logged-in client and prepared data."
  [client data]
  (set-show-meta {:client client
                  :podcast (:podcast data)
                  :episode-guid (:guid data)
                  :title (:title data)
                  :description (:description data)
                  :tags (:tags data)}))

(defn publish-episode
  "Full workflow: fetch markdown data and push to Fireside.
  
  url - docs.lol show notes URL
  podcast - podcast slug (e.g. \"linuxunplugged\", \"adfree\")
  guid-override - optional GUID to use instead of markdown GUID (for adfree)
  
  Returns: {:data parsed-data :client client :link-count N :meta-set? true :purged? true}"
  ([url]
   (publish-episode url "linuxunplugged" nil))
  ([url podcast]
   (publish-episode url podcast nil))
  ([url podcast guid-override]
   (let [_ (println "Fetching markdown data from" url)
         data (-> url
                  noblepayne.link-hoarder/-main
                  (assoc :podcast podcast)
                  (update :guid #(or guid-override %)))
         guid (:guid data)
         links (:links data)
         total (count links)]
     (when (not guid)
       (throw (ex-info "No GUID available - check markdown or provide guid-override"
                       {:podcast podcast :url url})))
     (println "Episode GUID:" guid)
     (println "Podcast:" podcast)
     (println "Links to add:" total)
     (println "Guests:" (count (:guests data)))

     (println "Creating HTTP client...")
     (let [client (http-client)]
       (println "Logging in to Fireside...")
       (login-to-fireside client)
       (println "Login successful")

       (println "Setting episode metadata...")
       (set-show-meta {:client client
                       :podcast podcast
                       :episode-guid guid
                       :title (:title data)
                       :description (:description data)
                       :tags (:tags data)})
       (println "Metadata updated")

       (println "Purging existing links...")
       (purge-links {:client client
                     :podcast podcast
                     :episode-guid guid})
       (println "Links purged")

       (println "Adding" total "new links...")
       (doseq [{:keys [title href quote]} links
               [idx] (map-indexed vector links)
               :let [link-num (inc idx)]]
         (println (format "  [%d/%d] %s" link-num total (or title href)))
         (add-link {:client client
                    :podcast podcast
                    :episode-guid guid
                    :title title
                    :url href
                    :quote quote}))
       (println "All links added")

       {:data data
        :client client
        :link-count total
        :guest-count (count (:guests data))
        :meta-set? true
        :purged? true}))))

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
                           "utf8" (get-in form-map [:form-inputs "utf8"]))
        response (http/post action-url {:http-client client
                                        :form-params form-params})]
    (if (= 200 (:status response))
      true
      (throw (ex-info "Failed to set metadata" {:status (:status response) :body (:body response)})))))

(defn set-show-meta [{:keys [client
                             podcast
                             episode-guid
                             title
                             description
                             tags]}]
  (let [tags-normalized (unreverse-tags tags)]
    (set-metedata {:client client
                   :podcast podcast
                   :episode-guid episode-guid
                   :metadata {"episode[title]" title
                              "episode[subtitle]" description
                              "episode[description]" description
                              "episode[keywords]" tags-normalized
                              "episode[tag_list]" tags-normalized}})))

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
               :podcast (:podcast noblepayne.link-hoarder/data)
               :episode-guid (:guid noblepayne.link-hoarder/data)
               :title title
               :url href
               :quote quote}))

  (try
    (set-show-meta {:client c
                    :podcast (:podcast noblepayne.link-hoarder/data)
                    :episode-guid (:guid noblepayne.link-hoarder/data)
                    :title (:title noblepayne.link-hoarder/data)
                    :description (:description noblepayne.link-hoarder/data)
                    :tags (:tags noblepayne.link-hoarder/data)})
    true
    (catch Exception e (def error e) (throw e))))