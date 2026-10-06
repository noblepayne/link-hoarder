(ns noblepayne.fireside
  (:gen-class)
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [hato.client :as http]
            [hickory.core :as hickory]
            [hickory.select :as hs]
            [noblepayne.json :as json]
            [noblepayne.link-hoarder :as lh]))

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
    (let [resp (http/post form-action {:http-client client
                                       :throw-exceptions false
                                       :form-params form-params})]
      (when (not (<= 200 (:status resp) 399))
        (throw (ex-info "Failed to add link"
                        {:status (:status resp) :episode-guid episode-guid
                         :title title :url url}))))
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
    (let [resp (http/request {:method :post
                              :url delete-url
                              :http-client client
                              :throw-exceptions false
                              :form-params {"_method" "delete"
                                            "authenticity_token" auth-token}})]
      (when (not (<= 200 (:status resp) 399))
        (throw (ex-info "Failed to delete link"
                        {:status (:status resp) :episode-guid episode-guid
                         :link-guid link-guid}))))
    true))

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

(defn fetch-link-count
  "Count data rows on the episode's /links page. Scripted replacement
  for opening the page and counting: assert this equals the scrape count
  after every purge + add run."
  [{:keys [client podcast episode-guid]}]
  (let [links-url (str/join "/" [FIRESIDE-BASE-URL "podcasts" podcast
                                 "episodes" episode-guid
                                 "links"])
        links-url-page (fetch-as-hickory {:http-client client
                                          :url links-url})]
    (count (hs/select (hs/and (hs/tag :tr)
                              (hs/attr :id))
                      links-url-page))))

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
        parsed-inputs (into {} (map (comp (juxt :name :value) :attrs) chapter-inputs))]
    (when-not (get parsed-inputs "authenticity_token")
      (throw (ex-info "No auth token on the new-chapter form; did Fireside change its markup?"
                      {:url new-url :input-names (vec (keys parsed-inputs))})))
    (let [form-params (assoc parsed-inputs
                             "chapter[timecode_as_words]" timecode
                             "chapter[note]" note)
          response (http/post form-action {:http-client client
                                           :form-params form-params})]
      (if (= 200 (:status response))
        true
        (throw (ex-info "Failed to add chapter"
                        {:status (:status response)
                         :timecode timecode
                         :note note}))))))

(defn delete-chapter
  "Delete one chapter from an episode by its guid.

  Guids come from fetch-chapter-ids. This is the recovery path for a
  half-finished sync, so it is deliberately not wrapped in anything
  clever - it posts the _method=delete override the way Rails expects."
  [{:keys [client podcast episode-guid chapter-guid]}]
  (let [url (str/join "/" [FIRESIDE-BASE-URL "podcasts" podcast
                           "episodes" episode-guid "chapters" chapter-guid])
        html (:body (http/request {:method :get
                                   :url (str url "/edit")
                                   :http-client client}))
        auth-token (second (re-find #"name=\"csrf-token\" content=\"([^\"]+)\"" html))]
    (when-not auth-token
      (throw (ex-info "No csrf token on the chapter edit page"
                      {:url url})))
    (let [response (http/post url {:http-client client
                                   :form-params {"_method" "delete"
                                                 "authenticity_token" auth-token}})]
      (if (<= 200 (:status response) 302)
        true
        (throw (ex-info "Failed to delete chapter"
                        {:status (:status response) :guid chapter-guid}))))))

;; CSV parsing
;;
;; The chapter/ads exports are plain single-line CSVs with a header row. A
;; full RFC-4180 parser is not warranted, but quoted fields are cheap to
;; honor so we do not silently mangle a note containing a comma.

(defn parse-csv-line
  "Split one CSV line into a vector of fields. Understands quoted fields
  and escaped quotes (\\\"\\\"\\\" inside a quoted field). Does NOT trim -
  parse-csv trims the fields it receives."
  [line]
  (loop [chars (seq line)
         field (StringBuilder.)
         fields []
         in-quotes? false]
    (if-let [c (first chars)]
      (cond
        (= c \")
        (if (and in-quotes? (= \" (second chars)))
          (recur (nnext chars) (.append field \") fields true)
          (recur (next chars) field fields (not in-quotes?)))
        (and (= c \,) (not in-quotes?))
        (recur (next chars) (StringBuilder.) (conj fields (str field)) false)
        :else
        (recur (next chars) (.append field c) fields in-quotes?))
      (conj fields (str field)))))

(defn parse-csv
  "Parse CSV text into a vector of maps keyed by the header row.
  Keys and values are strings; throws if a row's field count does not
  match the header."
  [csv-text source]
  (let [[header & rows] (remove str/blank? (str/split-lines csv-text))]
    (when-not header
      (throw (ex-info "CSV is empty" {:source source})))
    (let [ks (mapv (comp keyword str/trim) (parse-csv-line header))]
      (mapv (fn [line]
              (let [vs (mapv str/trim (parse-csv-line line))]
                (when-not (= (count ks) (count vs))
                  (throw (ex-info "CSV row does not match header"
                                  {:source source
                                   :expected (count ks)
                                   :got (count vs)
                                   :line line})))
                (zipmap ks vs)))
            rows))))

(defn read-csv [file]
  (parse-csv (slurp file) file))

(defn- str->double [s k source]
  (try
    (Double/parseDouble (str/trim s))
    (catch NumberFormatException _
      (throw (ex-info "Not a number" {:source source :field k :value s})))))

(defn- str->long [s k source]
  (try
    (Long/parseLong (str/trim s))
    (catch NumberFormatException _
      (throw (ex-info "Not an integer" {:source source :field k :value s})))))

(defn load-chapters-csv
  "Load the chapters CSV export produced alongside the audio.

  Expected header: number,position_seconds,timecode,name

  Returns [{:number Long
           :position-seconds Double
           :timecode \"HH:MM:SS\"
           :name String}]"
  [chapter-file]
  (mapv (fn [row]
          {:number (str->long (:number row) "number" chapter-file)
           :position-seconds (str->double (:position_seconds row) "position_seconds" chapter-file)
           :timecode (:timecode row)
           :name (:name row)})
        (read-csv chapter-file)))

(defn load-ads-csv
  "Load the dynamic ad marker CSV export.

  Expected header: number,start_seconds,end_seconds,start_timecode,end_timecode,name

  Returns [{:number Long
           :start-seconds Double
           :end-seconds Double
           :start-timecode String
           :end-timecode String
           :name String}]"
  [ads-file]
  (mapv (fn [row]
          {:number (str->long (:number row) "number" ads-file)
           :start-seconds (str->double (:start_seconds row) "start_seconds" ads-file)
           :end-seconds (str->double (:end_seconds row) "end_seconds" ads-file)
           :start-timecode (:start_timecode row)
           :end-timecode (:end_timecode row)
           :name (:name row)})
        (read-csv ads-file)))

(defn timecode->words
  "Convert a \"HH:MM:SS\" timecode to the words form Fireside wants.

  The CSV's timecode column truncates rather than rounds (1987.94 seconds
  is written as 00:33:07, not 00:33:08), so prefer the authored timecode
  over recomputing from position-seconds - otherwise chapters drift a
  second from what the editor intended."
  [timecode]
  (when (nil? timecode)
    (throw (ex-info "timecode->words got nil" {})))
  (when-let [[_ h m s] (re-matches #"(\d+):(\d+):(\d+)" (str/trim timecode))]
    (let [hours (Long/parseLong h)
          minutes (Long/parseLong m)
          secs (Long/parseLong s)]
      (str/join " " (cond-> []
                      (pos? hours) (conj (str hours "h"))
                      (pos? minutes) (conj (str minutes "m"))
                      :always (conj (str secs "s")))))))

(defn seconds->words
  "Fireside's chapter form takes a human-readable duration such as
  \"1h 35m 15s\" (see the chapter[timecode_as_words] placeholder), not a
  numeric timecode. Rounds to the nearest second and omits leading zero
  units, so 90.11 becomes \"1m 30s\" and 0.0 becomes \"0s\"."
  [seconds]
  (let [total (long (Math/floor (+ 0.5 (double seconds))))
        hours (quot total 3600)
        minutes (quot (rem total 3600) 60)
        secs (rem total 60)]
    (str/join " " (cond-> []
                    (pos? hours) (conj (str hours "h"))
                    (pos? minutes) (conj (str minutes "m"))
                    :always (conj (str secs "s"))))))

(defn load-chapters
  "Legacy parser for the old \"<timecode> <title>\" plain-text format.

  Superseded by load-chapters-csv, which reads the CSV exports that now
  ship with the audio. Kept because the audio house may still hand back
  .txt chapters for older episodes; note the 686 .txt was empty, so
  nothing currently produces this format."
  [chapter-file]
  (let [chapter-lines (clojure.string/split-lines (slurp chapter-file))
        chapter-xf (comp (map #(rest (re-matches #"([^ ]+?) (.+?)" %)))
                         (map vec)
                         (map (fn [[ts title]] {"startTime" ts #_`(~'ts->s ~ts)
                                                "title" title})))]
    (into [] chapter-xf chapter-lines)))

(defn fetch-chapter-ids
  "Return [{:guid ...}] for an episode's existing chapters. Chapters render
  as a table of rows, each linking to its own /edit page."
  [client podcast episode-guid]
  (let [url (str/join "/" [FIRESIDE-BASE-URL "podcasts" podcast
                           "episodes" episode-guid "chapters"])
        html (:body (http/request {:method :get :url url :http-client client}))]
    (into []
          (keep (fn [[_ row]]
                  (when-let [[_ guid] (re-find #"chapters/([0-9a-f-]{36})/edit" row)]
                    {:guid guid})))
          (re-seq #"(?s)<tr[^>]*>(.*?)</tr>" html))))

(defn sync-chapters
  "Load a chapters CSV and push every chapter to a Fireside episode.

  The CSV's timecode column is authoritative and position_seconds is only a
  fallback: the column truncates where recomputation rounds, so trusting
  seconds drifts a chapter by one.

  Refuses to run against an episode that already has chapters, because this
  only appends - a second run would duplicate every chapter. Delete the
  existing ones first, or pass :force? true if that is genuinely what you
  want. Returns a vector of the chapter maps that were sent."
  [{:keys [client podcast episode-guid chapters-file force?]}]
  (let [chapters (load-chapters-csv chapters-file)
        existing (fetch-chapter-ids client podcast episode-guid)]
    (when (and (seq existing) (not force?))
      (throw (ex-info "Episode already has chapters; sync-chapters only appends"
                      {:episode-guid episode-guid
                       :existing (count existing)
                       :csv (count chapters)
                       :hint "delete the existing chapters, or pass :force? true"})))
    (println "Adding" (count chapters) "chapters to" episode-guid)
    (doseq [{:keys [position-seconds timecode name]} chapters]
      (let [words (or (timecode->words timecode)
                      (seconds->words position-seconds))]
        (println "  " words name)
        (add-chapter {:client client
                      :podcast podcast
                      :episode-guid episode-guid
                      :timecode words
                      :note name})))
    chapters))

;; Sponsorships
;;
;; Two things about the sponsorship form that the chapter form does not
;; have: the sponsor and campaign are foreign keys picked from dropdowns
;; rather than free text, and the campaign dropdown is populated by
;; JavaScript, so options scraped from one page load are not reliably
;; paired with sponsors - we look each up by display name.
;;
;; The form declares enctype="multipart/form-data" but posting its fields
;; multipart returns 500; send them urlencoded.

;; decode-html-entities is defined further down; option-map and
;; parse-episode-rows both need it at compile time.
(declare decode-html-entities
         extract-sponsorships)

(defn- option-map
  "Map option value -> display text for every <option> in a select's HTML.

  Returns {} for nil input so a missing select degrades to an empty map the
  caller can check, rather than an NPE with no indication of what was
  missing. The value attribute is matched anywhere in the tag, not just
  last, because Rails renders option attributes in either order."
  [html]
  (if (nil? html)
    {}
    (into {}
          (keep (fn [[tag _value text]]
                  (when-let [value (second (re-find #"\bvalue=\"([^\"]*)\"" tag))]
                    [value (some-> text str/trim decode-html-entities)])))
          (re-seq #"(?s)(<option[^>]*>)(.*?)</option>" html))))

(defn- select-html
  "Return the raw HTML of the <select> with the given form field name, or nil.

  The field name contains square brackets (sponsorship[sponsor_id]) which
  would otherwise be read as a regex character class and never match, so
  it is Pattern-quoted before interpolation."
  [html field]
  (second (re-find (re-pattern (str "(?s)<select[^>]*name=\""
                                    (java.util.regex.Pattern/quote field)
                                    "\"[^>]*>(.*?)</select>"))
                   html)))

(defn fetch-campaigns
  "Campaigns available for a sponsor, as a {id name} map.

  The new-sponsorship form ships with a single placeholder campaign and
  populates the real list by hitting
  /episodes/<guid>/update_campaigns?sponsor_id=<id>, which responds with a
  jQuery snippet rather than HTML. Scraping the initial page therefore
  yields exactly one wrong option, which is how a naive implementation
  silently files a sponsorship under the wrong campaign."
  [client podcast episode-guid sponsor-id]
  (let [url (str/join "/" [FIRESIDE-BASE-URL "podcasts" podcast
                           "episodes" episode-guid "update_campaigns"])
        body (:body (http/request {:method :get
                                   :url (str url "?sponsor_id=" sponsor-id)
                                   :http-client client}))]
    ;; Unescape only the two sequences the endpoint actually emits. A
    ;; blanket str/repair of "\\" would also eat unicode escapes and
    ;; backslash-space inside a campaign name, and the label is what the
    ;; exact-match lookup keys on.
    (-> body
        (str/replace "\\\"" "\"")
        (str/replace "\\/" "/")
        option-map)))

(defn sponsorship-form
  "Fetch the 'new sponsorship' page and return
   {:action \"/podcasts/...\" :auth-token \"...\"
    :sponsors {id name} :campaigns {id name}}"
  [{:keys [client podcast episode-guid]}]
  (let [new-url (str/join "/" [FIRESIDE-BASE-URL
                               "podcasts" podcast
                               "episodes" episode-guid
                               "sponsorships" "new"])
        html (:body (http/request {:method :get :url new-url :http-client client}))
        page (hickory/as-hickory (hickory/parse (str html)))
        ;; hickory 0.7's hs/attr takes a plain value, not a predicate, so
        ;; find the create form by its action ending in /sponsorships.
        [form] (->> (hs/select (hs/tag :form) page)
                    (filter (fn [f]
                              (when-let [action (get-in f [:attrs :action])]
                                (boolean (re-find #"/sponsorships$" (str action)))))))
        auth-token (->> (hs/select (hs/tag :input) page)
                        (filter #(= "authenticity_token" (get-in % [:attrs :name])))
                        (map #(get-in % [:attrs :value]))
                        (first))]
    {:action (-> form :attrs :action)
     :auth-token auth-token
     :sponsors (option-map (select-html html "sponsorship[sponsor_id]"))
     :campaigns (option-map (select-html html "sponsorship[campaign_id]"))}))

(defn add-sponsorship
  "Create a sponsorship on an episode. Sponsor and campaign are matched by
  display name against Fireside's dropdowns, so callers pass the same
  strings an operator would read off the screen.

  With no :campaign, the sponsor's only campaign is used - which is the
  normal case, since most sponsors have exactly one."
  [{:keys [client podcast episode-guid sponsor campaign timecode]}]
  (let [{:keys [action auth-token sponsors]} (sponsorship-form {:client client
                                                                :podcast podcast
                                                                :episode-guid episode-guid})
        ;; (key ...) not the entry itself: a map seqs to MapEntry values,
        ;; and hato expands sequential values into a repeated key, so
        ;; posting the entry sends sponsor_id=2155&sponsor_id=Nebula and
        ;; Rails raises AssociationTypeMismatch.
        sponsor-id (key (first (filter (fn [[_ label]] (= label sponsor)) sponsors)))]
    (when-not sponsor-id
      (throw (ex-info "Unknown sponsor" {:sponsor sponsor :known (vec (vals sponsors))})))
    (let [campaigns (fetch-campaigns client podcast episode-guid sponsor-id)
          ;; Don't guess: picking an arbitrary campaign is the exact
          ;; failure this whole function exists to avoid.
          wanted (or campaign
                     (when (= 1 (count campaigns))
                       (first (vals campaigns)))
                     (throw (ex-info "Sponsor has several campaigns; pass :campaign"
                                     {:sponsor sponsor
                                      :campaigns (vec (vals campaigns))})))
          campaign-id (key (first (filter (fn [[_ label]] (= label wanted)) campaigns)))]
      (when-not campaign-id
        (throw (ex-info "Unknown campaign"
                        {:campaign wanted :sponsor sponsor
                         :known (vec (vals campaigns))})))
      (let [response (http/post (str FIRESIDE-BASE-URL action)
                                {:http-client client
                                 ;; urlencoded, not multipart - see
                                 ;; set-sponsorship-timecode
                                 :form-params {"authenticity_token" auth-token
                                               "sponsorship[timecode_as_words]" timecode
                                               "sponsorship[sponsor_id]" sponsor-id
                                               "sponsorship[campaign_id]" campaign-id}})]
        (if (<= 200 (:status response) 302)
          {:sponsor sponsor :campaign wanted :timecode timecode}
          (throw (ex-info "Failed to add sponsorship"
                          {:status (:status response) :sponsor sponsor :campaign wanted})))))))

(defn set-sponsorship-timecode
  "Update an existing sponsorship's timecode. Sponsorship guids come from
  fetch-sponsorship-ids.

  The edit page's form posts back to the sponsorship's own URL - not to
  /edit - with a _method=patch override, so both are needed."
  [{:keys [client podcast episode-guid sponsorship-guid timecode]}]
  (let [base (str/join "/" [FIRESIDE-BASE-URL
                            "podcasts" podcast
                            "episodes" episode-guid
                            "sponsorships"])
        html (:body (http/request {:method :get
                                   :url (str base "/" sponsorship-guid "/edit")
                                   :http-client client}))
        auth-token (second (re-find #"name=\"csrf-token\" content=\"([^\"]+)\"" html))
        selected (fn [field]
                   (second (re-find #"selected=\"selected\" value=\"([^\"]*)\""
                                    (str (select-html html field)))))
        ;; Despite enctype="multipart/form-data" on the form, posting these
        ;; fields urlencoded is what Fireside accepts - multipart returns 500.
        response (http/post (str base "/" sponsorship-guid)
                            {:http-client client
                             :form-params (cond-> {"authenticity_token" auth-token
                                                   "_method" "patch"
                                                   "sponsorship[timecode_as_words]" timecode}
                                            (selected "sponsorship[sponsor_id]")
                                            (assoc "sponsorship[sponsor_id]"
                                                   (selected "sponsorship[sponsor_id]"))
                                            (selected "sponsorship[campaign_id]")
                                            (assoc "sponsorship[campaign_id]"
                                                   (selected "sponsorship[campaign_id]")))})]
    (if (<= 200 (:status response) 302)
      true
      (throw (ex-info "Failed to set sponsorship timecode"
                      {:status (:status response) :guid sponsorship-guid})))))

(defn fetch-sponsorship-ids
  "Return [{:guid :sponsor :campaign :timecode-str :timecode-seconds}] for an
  episode's existing sponsorships, in page order.

  Sponsorships render as bootstrap accordions rather than table rows, and
  each accordion carries its own guid, so one pass over the page gets
  everything without a request per sponsorship."
  [client podcast episode-guid]
  (let [url (str/join "/" [FIRESIDE-BASE-URL "podcasts" podcast
                           "episodes" episode-guid "sponsorships"])
        html (:body (http/request {:method :get :url url :http-client client}))]
    (into []
          (keep (fn [[_ block]]
                  (let [[_ guid] (re-find #"id=\"collapse-sponsorship-([0-9a-f-]{36})\""
                                          block)
                        sponsorship (first (extract-sponsorships
                                            (str "<div>" block "</div>")))]
                    (when (and guid sponsorship)
                      (assoc sponsorship :guid guid)))))
          ;; Each accordion-item holds one sponsorship's heading and guid.
          (re-seq #"(?s)<div class=\"accordion-item\">(.*?)(?=<div class=\"accordion-item\">|</div>\s*</div>\s*</div>)"
                  html))))

(defn sync-sponsorship-times
  "Set the timecode of an existing sponsorship from an ads CSV row, matching
  the sponsorship by sponsor name.

  Prefer this over sync-sponsorships for episodes that already carry their
  sponsorships: it updates in place instead of creating a duplicate. Returns
  a vector of {:sponsor :timecode} maps, or throws if the CSV rows and the
  episode's sponsorships for that sponsor do not line up one-to-one."
  [{:keys [client podcast episode-guid ads-file sponsor]}]
  (let [rows (filterv #(= sponsor (:name %)) (load-ads-csv ads-file))
        existing (filterv #(= sponsor (:sponsor %))
                          (fetch-sponsorship-ids client podcast episode-guid))]
    (when (empty? rows)
      (throw (ex-info "No matching row in ads CSV" {:sponsor sponsor :file ads-file})))
    (when (empty? existing)
      (throw (ex-info "No such sponsorship on episode"
                      {:sponsor sponsor
                       :episode-guid episode-guid
                       :on-episode (mapv :sponsor
                                         (fetch-sponsorship-ids client podcast episode-guid))})))
    ;; Pair on an explicit key, not position: zipping two independently
    ;; filtered lists silently drops or misassigns rows whenever the counts
    ;; differ, and a sponsorship synced to the wrong episode entry is worse
    ;; than one that failed.
    (when-not (= (count rows) (count existing))
      (throw (ex-info "CSV rows and episode sponsorships do not line up"
                      {:sponsor sponsor
                       :csv-rows (count rows)
                       :on-episode (count existing)})))
    (reduce (fn [acc [row sponsorship]]
              (let [words (or (timecode->words (:start-timecode row))
                              (seconds->words (:start-seconds row)))]
                (println "  " sponsor "->" words)
                (set-sponsorship-timecode {:client client
                                           :podcast podcast
                                           :episode-guid episode-guid
                                           :sponsorship-guid (:guid sponsorship)
                                           :timecode words})
                (conj acc {:sponsor sponsor :timecode words})))
            []
            (map vector rows existing))))

(defn sync-sponsorships
  "Push the real sponsor reads from an ads CSV to an episode.

  The ads CSV also lists dynamic ad markers, which are NOT sponsorships -
  Fireside has no equivalent field, so those rows are skipped. Rows are
  matched by name against the known sponsors; anything unrecognized is
  returned in :skipped rather than silently dropped."
  [{:keys [client podcast episode-guid ads-file]}]
  (let [ads (load-ads-csv ads-file)
        form (sponsorship-form {:client client
                                :podcast podcast
                                :episode-guid episode-guid})
        sponsor-labels (set (vals (:sponsors form)))]
    (loop [remaining ads
           pushed []
           skipped []]
      (if-let [row (first remaining)]
        (if (sponsor-labels (:name row))
          (let [words (or (timecode->words (:start-timecode row))
                          (seconds->words (:start-seconds row)))]
            (println "  " words (:name row))
            (let [result (add-sponsorship {:client client
                                           :podcast podcast
                                           :episode-guid episode-guid
                                           :sponsor (:name row)
                                           :timecode words})]
              (recur (rest remaining) (conj pushed result) skipped)))
          (do (println "  skipping" (:name row) "- not a Fireside sponsor")
              (recur (rest remaining) pushed (conj skipped (:name row)))))
        {:pushed pushed :skipped skipped}))))

(defn fetch-page!
  "GET url and return the body. Throws unless we land a 200 on the URL we
  asked for: Fireside answers an expired session with a redirect to /login
  served as 200, which a bare status check would accept as success."
  [client url what]
  (let [resp (http/request {:method :get :url url :http-client client})
        uri (str (:uri resp))]
    (when (re-find #"/login(\?.*)?$" uri)
      (throw (ex-info (str "Not logged in while reading " what)
                      {:url url :landed-on uri
                       :hint "session expired - log in again"})))
    (when (not= 200 (:status resp))
      (throw (ex-info (str "Could not read " what)
                      {:url url :status (:status resp) :landed-on uri})))
    (:body resp)))

(defn s3-upload-form
  "Scrape the S3 presigned MP3 upload off the episode edit page. Returns
  {:url :fields} where fields is the full presign map to forward verbatim.
  The page carries four presigned forms (mp3, transcript, cover, header)
  with stable ids, so select episode_mp3_form_<guid> — never a form
  index — and insist on exactly one match."
  [{:keys [client podcast episode-guid]}]
  (let [url (str/join "/" [FIRESIDE-BASE-URL "podcasts" podcast
                           "episodes" episode-guid "edit"])
        html (fetch-page! client url "the episode edit page")
        pat (re-pattern (str "(?s)<form[^>]*id=\"episode_mp3_form_"
                             (java.util.regex.Pattern/quote episode-guid)
                             "\"[^>]*>"))
        ;; No capture group in pat, so each match is the whole tag string.
        tags (re-seq pat html)]
    (when (not= 1 (count tags))
      (throw (ex-info "Expected exactly one mp3 upload form on the edit page"
                      {:url url :found (count tags)})))
    (let [tag (first tags)
          data-url (second (re-find #"data-url=\"([^\"]+)\"" tag))
          [_ q1 q2] (re-find #"data-form-data=(?:\"([^\"]*)\"|'([^']*)')" tag)
          raw (or q1 q2)]
      (when (str/blank? data-url)
        (throw (ex-info "Mp3 upload form has no data-url" {:url url})))
      (when (str/blank? raw)
        (throw (ex-info "Mp3 upload form has no data-form-data" {:url url})))
      {:url data-url
       :fields (json/read-str (decode-html-entities raw))})))

(defn rewrite-s3-key
  "Replace the presigned key's trailing segment with a fresh <uuid>.mp3.
  Keeps pop-append semantics: whatever the last segment is, it goes, so a
  policy-side key-shape change cannot silently produce a wrong key."
  [s3-key]
  (let [parts (str/split s3-key #"/")]
    (str/join "/" (conj (vec (butlast parts)) (str (random-uuid) ".mp3")))))

(defn- utf8-bytes
  "Platform-independent byte encoding for multipart bodies. Values are
  ASCII presign data and episode text today; the point is the encoding
  never depends on JVM file.encoding."
  ^bytes [^String s]
  (.getBytes s java.nio.charset.StandardCharsets/UTF_8))

(defn multipart-bytes
  "Build a multipart/form-data body as a byte array with a known length.
  Needed because hato's multipart always streams chunked (no
  Content-Length) and S3 presigned POSTs answer 411 Length Required.
  Field order is load-bearing: presign fields first, then the Content-Type
  field, then the file part LAST. S3 ignores any field after the file
  part, so a trailing Content-Type fails the policy with a 403 even
  though the bytes are present. Returns {:body :boundary
  :content-length}."
  [fields file-name file-bytes]
  (let [boundary (str "------------------------" (random-uuid))
        out (java.io.ByteArrayOutputStream.)
        write-str (fn [s] (.write out (utf8-bytes (str s))))
        write-bytes (fn [b] (.write out ^bytes b))
        ;; The policy demands a Content-Type field. If the presign ever
        ;; starts including one, prefer it over the hardcoded value rather
        ;; than sending the field twice.
        fields (if (contains? fields "Content-Type")
                 fields
                 (assoc fields "Content-Type" "audio/mp3"))
        ;; ...but the value must travel BEFORE the file part regardless.
        ordered (concat (remove (fn [[k _]] (= k "Content-Type")) fields)
                        [["Content-Type" (get fields "Content-Type")]])]
    (doseq [[k v] ordered]
      (write-str (str "--" boundary "\r\n"))
      (write-str (str "Content-Disposition: form-data; name=\"" k "\"\r\n"))
      (write-str "\r\n")
      (write-bytes (utf8-bytes (str v)))
      (write-bytes (utf8-bytes "\r\n")))
    (write-str (str "--" boundary "\r\n"))
    (write-str (str "Content-Disposition: form-data; name=\"file\"; filename=\"" file-name "\"\r\n"))
    (write-str "Content-Type: application/octet-stream\r\n")
    (write-str "\r\n")
    (write-bytes file-bytes)
    (write-bytes (utf8-bytes "\r\n"))
    (write-str (str "--" boundary "--\r\n"))
    (let [bytes (.toByteArray out)]
      {:body bytes
       :boundary boundary
       :content-length (alength ^bytes bytes)})))

(defn multipart-fields-bytes
  "Build a fields-only multipart/form-data body with a known length.
  Same reason as multipart-bytes: hato streams chunked and some
  endpoints 500 on it. Multi-value names become repeated parts."
  [fields]
  (let [boundary (str "------------------------" (random-uuid))
        out (java.io.ByteArrayOutputStream.)
        write-str (fn [s] (.write out (utf8-bytes (str s))))
        parts (mapcat (fn [[k v]]
                        (if (sequential? v)
                          (map (fn [x] [k x]) v)
                          [[k v]]))
                      fields)]
    (doseq [[k v] parts]
      (write-str (str "--" boundary "\r\n"))
      (write-str (str "Content-Disposition: form-data; name=\"" k "\"\r\n"))
      (write-str "\r\n")
      (write-str (str v))
      (write-str "\r\n"))
    (write-str (str "--" boundary "--\r\n"))
    {:body (.toByteArray out)
     :boundary boundary}))

(defn temp-file-url
  "Build the staged temp-file URL the way the browser does: endpoint plus
  the S3 key with RAW slashes. S3's own Location echoes the key
  percent-encoded (%2F), and that encoded form makes CarrierWave report
  'Temp file no longer exists' on a file that fetches fine (proven live:
  restaging the identical object with raw slashes processed immediately)."
  [endpoint s3-key]
  (str (str/replace (str endpoint) #"/$" "") "/" s3-key))

(defn post-file-to-s3!
  "POST file to a presigned S3 URL with the presign fields. The body is
  built by multipart-bytes (known length: S3 answers 411 to hato's
  chunked streaming multipart). Returns the temp-file location. Handles
  303 (Location header), 201 (Location in XML body) and 204 (constructed
  from endpoint plus key), and throws on anything else. Never returns
  nil."
  [{:keys [url fields file]}]
  (let [f (clojure.java.io/file file)]
    (when-not (.exists f)
      (throw (ex-info "MP3 file does not exist" {:file (str file)})))
    (when (str/blank? (get fields "key"))
      (throw (ex-info "Presign fields have no key" {:url url})))
    ;; Bytes, not a stream: the JDK sets Content-Length from a byte[]
    ;; body automatically, which is what S3 demands.
    (let [file-bytes (java.nio.file.Files/readAllBytes (.toPath f))
          s3-key (rewrite-s3-key (get fields "key"))
          {:keys [body boundary]} (multipart-bytes (assoc fields "key" s3-key)
                                                   (.getName f)
                                                   file-bytes)]
      ;; hato's own errors carry the full request map including the 72MB
      ;; body, which sends clojure.main's error printer into a ten-minute
      ;; pprint spiral. Catch and rethrow with the body summarized.
      (try
        ;; 10-minute total cap: the shared client sets only a connect
        ;; timeout, and a stalled 70 MB upload must fail fast, not hang.
        (let [resp (http/post url {:body body
                                   :timeout 600000
                                   :headers {"Content-Type"
                                             (str "multipart/form-data; boundary=" boundary)}})]
          (cond
            (= 303 (:status resp))
            (temp-file-url url s3-key)

            (= 201 (:status resp))
            (do (when-not (re-find #"(?s)<Location>.*?</Location>" (str (:body resp)))
                  (throw (ex-info "S3 answered 201 with no Location in body"
                                  {:url url})))
                ;; Ignore S3's echoed Location: it percent-encodes the key
                ;; (%2F), which poisons Fireside's downloader. Rebuild from
                ;; the key we actually stored (browser shape). 201 already
                ;; proves the object is there.
                (temp-file-url url s3-key))

            (= 204 (:status resp))
            (temp-file-url url s3-key)

            :else
            (let [b (str (:body resp))]
              (throw (ex-info "S3 upload failed"
                              {:status (:status resp)
                               :body (subs b 0 (min 500 (count b)))})))))
        (catch clojure.lang.ExceptionInfo e
          (throw (ex-info (ex-message e)
                          (-> (ex-data e)
                              (dissoc :request)
                              (assoc :s3-url url
                                     :upload-bytes (alength ^bytes body))))))))))

(defn- successful-controls
  "Mirror a form the way the browser submits it: hidden and text inputs
  verbatim; checkboxes and radios only when checked (an unchecked box
  sends nothing); textareas by content; selects by selected option
  (scalar for one, vector for several). Collapsing multi-value names
  (host_ids[]) to a single value would silently unassign hosts — keep
  every value. Takes the whole page plus the form id, not just the form
  subtree: Rails renders some inputs (ignore_cover, ignore_chapters)
  OUTSIDE the <form> element with an HTML5 form= attribute, and a subtree
  scrape misses them — the missing params 500 the update.
  Elements come from scoped selects (descendants by construction) plus
  page-wide form= matches. Never value-equality against the subtree: all
  five forms carry an identical _method hidden input, and an equality test
  matches every copy page-wide (proven live: _method x5)."
  [page form-id]
  (let [[form] (hs/select (hs/id form-id) page)]
    (when-not form
      (throw (ex-info "Form not found on page" {:form-id form-id})))
    (let [scoped (concat (hs/select (hs/tag :input) form)
                         (hs/select (hs/tag :textarea) form)
                         (hs/select (hs/tag :select) form))
          ;; Identical element maps collapse in a set: two genuinely
          ;; repeated hidden inputs with the same name AND value would send
          ;; once instead of twice. The episode form has no such pair
          ;; (repeated names always differ in value), so overlap-guard wins.
          seen (into #{} scoped)
          outside (filter (fn [el]
                            (and (= form-id (get-in el [:attrs :form]))
                                 (not (contains? seen el))))
                          (concat (hs/select (hs/tag :input) page)
                                  (hs/select (hs/tag :textarea) page)
                                  (hs/select (hs/tag :select) page)
                                  (hs/select (hs/tag :button) page)))
          els (concat scoped outside)
          keep-el? (fn [{:keys [tag attrs]}]
                     (and (:name attrs)
                          ;; Disabled controls are never successful, and file
                          ;; inputs upload direct-to-S3 — an empty file part
                          ;; is exactly the kind of thing that 500s an update.
                          (not (contains? attrs :disabled))
                          (or (not= :input tag)
                              (let [t (:type attrs)]
                                (and (not= "file" t)
                                     (or (nil? t)
                                         (not (contains? #{"checkbox" "radio"} t))
                                         (contains? attrs :checked)))))))
          value-of (fn [{:keys [tag attrs content]}]
                     (case tag
                       :textarea [(get attrs :name) (clojure.string/join "" content)]
                       :select (let [options (filterv #(= :option (:tag %)) content)
                                     picked (into []
                                                  (comp (filter #(contains? (:attrs %) :selected))
                                                        (map #(get-in % [:attrs :value])))
                                                  options)]
                                 [(get attrs :name)
                                  (cond (= 1 (count picked)) (first picked)
                                        ;; No selected option: the browser
                                        ;; submits the first, not nothing.
                                        (and (empty? picked) (seq options))
                                        (get-in (first options) [:attrs :value])
                                        :else picked)])
                       [(get attrs :name) (get attrs :value)]))
          add-pair (fn [m [k v]]
                     (if (contains? m k)
                       (update m k (fn [old] (vec (concat (if (sequential? old) old [old]) [v]))))
                       (assoc m k v)))]
      (transduce (comp (filter keep-el?) (map value-of)) (completing add-pair) {} els))))

(defn stage-temp-url
  "Build the mp3_upload_url value the browser sends: the S3 temp URL plus
  a #filename=<name> fragment (the UI surfaces the original name on
  reload; the stored S3 key is a UUID). Any pre-existing fragment is
  stripped first — re-staging an already-staged URL must not stack
  fragments. Blank stays blank (the delete path)."
  [temp-url file-name]
  (if (str/blank? temp-url)
    ""
    (str (str/replace (str temp-url) #"#.*$" "")
         "#filename="
         (str/replace (java.net.URLEncoder/encode (str file-name) "UTF-8")
                      "+" "%20"))))

(defn cookies-for
  "Render the client's stored cookies for uri as a Cookie header value.
  Needed because the episode PATCH goes out via curl (redirect-following
  with the JDK client re-sends POST headers on the follow-up GET; curl
  lets us not follow and verify with a clean GET instead), and curl needs
  the session handed to it explicitly."
  [client uri]
  (let [mgr (.orElse (.cookieHandler ^java.net.http.HttpClient client) nil)]
    (when-not mgr
      (throw (ex-info "HTTP client has no cookie handler; cannot export session"
                      {:uri (str uri)})))
    (->> (.get (.getCookieStore ^java.net.CookieManager mgr)
               (java.net.URI/create (str uri)))
         (map (fn [c] (str (.getName ^java.net.HttpCookie c)
                           "=" (.getValue ^java.net.HttpCookie c))))
         (str/join "; "))))

(defn curl-post-form!
  "POST a multipart body with the curl binary and return
  {:status :location :body}. Never follows redirects: blindly refollowing
  a POST's 302 re-sends Content-Type/Content-Length/Origin on a bodiless
  GET, which 500s (or hangs) — that follow-up failure is what all our
  early 'attach 500s' were, while every write had actually applied. The
  caller verifies the write with a clean GET instead."
  [{:keys [url body content-type headers cookie timeout-s]}]
  (let [body-file (java.io.File/createTempFile "fireside-attach" ".bin")
        out-file (java.io.File/createTempFile "fireside-attach-resp" ".html")]
    (try
      (java.nio.file.Files/write (.toPath body-file) ^bytes body
                                 ^"[Ljava.nio.file.OpenOption;" (into-array java.nio.file.OpenOption []))
      (let [args (concat ["curl" "-s" "--http1.1"
                          "--max-time" (str (or timeout-s 120))
                          "-D" "-" "-o" (.getAbsolutePath out-file)
                          "-w" "\n%{http_code}"
                          "--data-binary" (str "@" (.getAbsolutePath body-file))
                          "--header" (str "Content-Type: " content-type)]
                         (mapcat (fn [[k v]] ["--header" (str k ": " v)]) headers)
                         ["--header" (str "Cookie: " cookie) url])
            {:keys [exit out err]} (apply shell/sh args)]
        (when (not= 0 exit)
          (throw (ex-info "curl failed to POST the episode form"
                          {:exit exit :err err :url url})))
        (let [lines (str/split-lines (str/trim out))
              code (Integer/parseInt (str/trim (last lines)))
              location (some-> (re-find #"(?im)^location:\s*(\S+)" out) second str/trim)
              resp-body (slurp (.getAbsolutePath out-file))]
          {:status code :location location :body resp-body}))
      (finally
        (.delete body-file)
        (.delete out-file)))))

(declare mp3-status)

(def required-episode-keys
  "Episode-form fields that must survive the scrape, or the POST is
  refused. attach-mp3! mirrors the whole form to change one field; a bad
  scrape would otherwise wipe title/status/publish schedule/hosts in the
  same write that touches audio. Presence only — values may be blank."
  ;; Note: episode[host_ids][] / episode[guest_ids][] are deliberately NOT
  ;; here. Podcasts with no people roster (e.g. adfree) render zero such
  ;; inputs, so absence is correct and must not refuse the POST. When the
  ;; inputs exist they are mirrored verbatim (checked-only, multi-values
  ;; accumulated — the browser behavior), which the unit tests pin down.
  #{"_method" "authenticity_token"
    "episode[title]" "episode[description]" "episode[subtitle]"
    "episode[status]" "episode[publish_at(1i)]" "episode[publish_at(2i)]"
    "episode[publish_at(3i)]" "episode[publish_at(4i)]" "episode[publish_at(5i)]"
    "episode[mp3_upload_url]"})

(defn assert-required-fields!
  "Throw unless every required-episode-keys member is present in params.
  Refusing a bad scrape beats wiping title/status/schedule in one write."
  [params episode-guid]
  (let [missing (remove #(contains? params %) required-episode-keys)]
    (when (seq missing)
      (throw (ex-info "Refusing to POST: episode form scrape missed required fields"
                      {:episode-guid episode-guid :missing (vec missing)})))
    true))

(defn- post-episode-form!
  "POST mirrored episode-form params via curl and never follow redirects.
  Throws on session expiry or a non-2xx/3xx answer. Returns
  {:post-status :post-location :resp-body}."
  [{:keys [client base params episode-guid]}]
  (let [{:keys [body boundary]} (multipart-fields-bytes params)
        {post-status :status post-location :location post-text :body}
        (curl-post-form!
         {:url base :body body
          :content-type (str "multipart/form-data; boundary=" boundary)
          :cookie (cookies-for client base)
          :headers {"Referer" (str base "/edit")
                    "Origin" FIRESIDE-BASE-URL
                    "User-Agent" "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36"
                    "Accept" "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"}})
        resp-body (str post-text)]
    (when (and post-location (re-find #"/login(\\?.*)?$" post-location))
      (throw (ex-info "Session expired during episode update: nothing was written"
                      {:episode-guid episode-guid :landed-on post-location})))
    (when (not (<= 200 post-status 399))
      (throw (ex-info "Failed to update episode"
                      {:status post-status :episode-guid episode-guid
                       :location post-location
                       :field-names (sort (keys params))
                       :field-count (count params)
                       :body-bytes (alength ^bytes body)
                       :response-snippet (subs resp-body 0 (min 2000 (count resp-body)))})))
    {:post-status post-status :post-location post-location :resp-body resp-body}))

(defn- await-fields!
  "Re-read the edit page until every key in expect matches the stored
  value (string-compared), retrying boundedly. Throws with
  expected-vs-stored on permanent mismatch."
  [client base form-id expect episode-guid]
  (loop [attempt 1]
    (let [fresh (fetch-as-hickory {:http-client client :url (str base "/edit")})
          stored (successful-controls fresh form-id)
          bad (remove (fn [[k v]] (= (str (get stored k ::missing)) (str v))) expect)]
      (cond
        (empty? bad) true
        (>= attempt 12)
        (throw (ex-info "Episode fields never matched after update"
                        {:episode-guid episode-guid
                         :mismatched (into {} (map (fn [[k v]] [k {:expected (str v)
                                                                   :stored (str (get stored k ::missing))}]) bad))}))
        :else (do (Thread/sleep 5000)
                  (recur (inc attempt)))))))

(defn attach-mp3!
  "Point the episode at temp-url and submit. Re-reads the edit page fresh
  (never submits a form parsed minutes ago), mirrors every successful
  control on the episode form, replaces only episode[mp3_upload_url], and
  posts MULTIPART like the browser does — urlencoded gets a 200 that saves
  nothing processable. The temp URL carries a #filename=<name> fragment
  exactly as the UI sends it; without it processing never starts. A blank
  temp-url clears the field (the delete path), with no fragment.
  Posts via curl and never follows the redirect: refollowing a POST's 302
  re-sends Content-Type/Content-Length/Origin on a bodiless GET, which is
  where every historical 'attach 500' came from — while the write had
  already applied. Success is decided by re-reading the page and comparing
  the stored mp3_upload_url to the staged value, never by status code."
  [{:keys [client podcast episode-guid temp-url file-name previous-url]
    :or {previous-url ::unset}}]
  (let [base (str/join "/" [FIRESIDE-BASE-URL "podcasts" podcast
                            "episodes" episode-guid])
        form-id (str "edit_episode_" episode-guid)
        page (fetch-as-hickory {:http-client client :url (str base "/edit")})
        staged (stage-temp-url temp-url file-name)
        controls (successful-controls page form-id)
        params (assoc controls
                      "episode[mp3_upload_url]" staged
                        ;; Mirror Ruby's .strip: the textarea scrape picks up
                        ;; the HTML formatting newlines around the content.
                      "episode[description]" (str/trim (str (get controls "episode[description]" "")))
                      "episode[subtitle]" (str/trim (str (get controls "episode[subtitle]" "")))
                      "button" "")
        missing (remove #(contains? params %) required-episode-keys)]
    (when (seq missing)
      (throw (ex-info "Refusing to POST: episode form scrape missed required fields"
                      {:episode-guid episode-guid :missing (vec missing)})))
    (let [{:keys [body boundary]} (multipart-fields-bytes params)
          ;; Via curl, not hato: only curl lets us not follow the redirect.
          {post-status :status post-location :location post-text :body}
          (curl-post-form!
           {:url base :body body
            :content-type (str "multipart/form-data; boundary=" boundary)
            :cookie (cookies-for client base)
            :headers {"Referer" (str base "/edit")
                      "Origin" FIRESIDE-BASE-URL
                      "User-Agent" "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                      "Accept" "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"}})
          resp-body (str post-text)]
      (when (and post-location (re-find #"/login(\?.*)?$" post-location))
        (throw (ex-info "Session expired during mp3 attach: nothing was written"
                        {:episode-guid episode-guid :landed-on post-location})))
      (when (not (<= 200 post-status 399))
        (throw (ex-info "Failed to attach mp3"
                        {:status post-status :episode-guid episode-guid
                         :location post-location
                         :field-names (sort (keys params))
                         :field-count (count params)
                         :body-bytes (alength ^bytes body)
                         :response-snippet (subs resp-body 0 (min 2000 (count resp-body)))})))
      ;; The 302 only means Rails accepted the POST. Confirm the write landed
      ;; with clean GETs, retrying: reads can race the commit across
      ;; Fireside's backends (proven live — the staged URL appeared on the
      ;; page after an immediate re-read missed it). Success is the staged
      ;; value present, OR the field consumed (blank) with processing
      ;; actually underway or done — Fireside clears the temp field once it
      ;; takes the file, which once false-failed a verification mid-run.
      (loop [attempt 1]
        (let [fresh (fetch-as-hickory {:http-client client :url (str base "/edit")})
              stored (get (successful-controls fresh form-id) "episode[mp3_upload_url]" ::missing)]
          (cond
            (= (str stored) staged) true
            (and (str/blank? (str stored))
                 (not (str/blank? staged))
                 (let [st (try (mp3-status {:client client :podcast podcast
                                            :episode-guid episode-guid})
                               (catch Exception _ nil))]
                 ;; A stale :url (previous pass's audio) must not count:
                 ;; only processing=true, or a url newer than the one we
                 ;; saw before attaching, proves OUR file was taken.
                   (and st (or (:processing st)
                               (and (:url st)
                                    (not= ::unset previous-url)
                                    (not= (:url st) previous-url))))))
            true
            (>= attempt 12)
            (throw (ex-info "Attach POST answered but the staged URL never appeared on the page"
                            {:status post-status :episode-guid episode-guid
                             :staged staged :stored stored}))
            :else (do (Thread/sleep 5000)
                      (recur (inc attempt)))))))))

(def episode-status-values
  "Visibility keyword -> episode[status] form value."
  {:public "1" :private "0" :unlisted "2"})

(defn schedule-episode!
  "Set visibility and publish time without touching audio. publish-at is
  Pacific wall-clock [year month day hour minute] as integers (Fireside
  renders the form in America/Los_Angeles). Minute must land on a
  15-minute step and hour is 24h; anything else throws rather than
  rounding silently. Values are encoded exactly as the selects carry
  them: year/month/day unpadded, hour/minute zero-padded. Verifies every
  written field by re-reading before returning true."
  [{:keys [client podcast episode-guid status publish-at]}]
  (let [s (get episode-status-values status)]
    (when-not s
      (throw (ex-info "Unknown visibility status"
                      {:status status :known (vec (keys episode-status-values))})))
    (let [[y mo d h mi] publish-at]
      (when-not (and (sequential? publish-at) (= 5 (count publish-at))
                     (every? integer? [y mo d h mi]))
        (throw (ex-info "publish-at must be [year month day hour minute] integers"
                        {:publish-at publish-at})))
      (when-not (and (<= 2000 y) (<= y 2100)
                     (<= 1 mo) (<= mo 12)
                     (<= 1 d) (<= d 31)
                     (<= 0 h) (<= h 23))
        (throw (ex-info "publish-at out of range"
                        {:publish-at publish-at})))
      (when-not (contains? #{0 15 30 45} mi)
        (throw (ex-info "Publish minute must be a 15-minute step (the form offers only 00/15/30/45)"
                        {:minute mi})))
      (let [base (str/join "/" [FIRESIDE-BASE-URL "podcasts" podcast
                                "episodes" episode-guid])
            form-id (str "edit_episode_" episode-guid)
            page (fetch-as-hickory {:http-client client :url (str base "/edit")})
            controls (successful-controls page form-id)
            overrides {"episode[status]" s
                       "episode[publish_at(1i)]" (str y)
                       "episode[publish_at(2i)]" (str mo)
                       "episode[publish_at(3i)]" (str d)
                       "episode[publish_at(4i)]" (format "%02d" h)
                       "episode[publish_at(5i)]" (format "%02d" mi)}
            params (merge controls overrides
                          {"episode[description]" (str/trim (str (get controls "episode[description]" "")))
                           "episode[subtitle]" (str/trim (str (get controls "episode[subtitle]" "")))
                           "button" ""})]
        (assert-required-fields! params episode-guid)
        (post-episode-form! {:client client :base base :params params
                             :episode-guid episode-guid})
        (await-fields! client base form-id overrides episode-guid)
        true))))

(defn mp3-status
  "Read check_mp3_status. Returns {:processing bool :url :download-url
  :error bool :error-message (or nil)}. A failed transcode reads
  {:processing false :error true} with the CarrierWave reason — never
  treat a nil :url alone as success."
  [{:keys [client podcast episode-guid]}]
  (let [url (str/join "/" [FIRESIDE-BASE-URL "podcasts" podcast
                           "episodes" episode-guid "check_mp3_status"])
        m (json/read-str (fetch-page! client url "mp3 status"))]
    {:processing (boolean (get m "processing")) :url (get m "url") :download-url (get m "download_url") :error (boolean (get m "error")) :error-message (get m "error_message")}))

;; NOTE (2026-10-05): delete-mp3! and poll-mp3-status were removed.
;; Clearing episode[mp3_upload_url] cannot remove already-processed audio
;; (the legacy delete_mp3 route 404s and the UI has no remove control), so a
;; delete-then-reupload "twice" flow is unachievable against current
;; Fireside. A single attach+process embeds the episode chapters exactly
;; (verified live on two episodes); upload-and-verify-mp3 does exactly that.

(defn await-processed
  "Poll check_mp3_status until a bytes url appears, processing fails, or
  the deadline passes. Returns success only once (:url st) is present —
  a bare processing=false also covers 'job not started yet'. Logs every
  poll."
  [{:keys [episode-guid] :as args}
   & {:keys [interval-ms deadline-ms]
      :or {interval-ms 10000 deadline-ms 1200000}}]
  (let [deadline (+ (System/currentTimeMillis) (long deadline-ms))]
    (loop [n 1]
      (let [st (mp3-status args)]
        (println (format "  mp3 await %d: processing=%s url=%s" n (:processing st) (boolean (:url st))))
        (cond
          (:error st)
          (throw (ex-info "mp3 processing failed"
                          {:episode-guid episode-guid
                           :error-message (:error-message st)
                           :status st}))
          (:url st) st
          (> (System/currentTimeMillis) deadline)
          (throw (ex-info "Timed out waiting for processed mp3 url"
                          {:episode-guid episode-guid :deadline-ms deadline-ms :status st}))
          :else (do (Thread/sleep (long interval-ms))
                    (recur (inc n))))))))

(defn parse-ffprobe-chapters
  "Parse ffprobe -show_chapters -print_format flat output into
  [{:start Double :title String}] ordered by chapter index."
  [flat-output]
  (let [starts (into {} (map (fn [[_ n v]] [(Integer/parseInt n) (Double/parseDouble v)])
                             (re-seq #"chapters\.chapter\.(\d+)\.start_time=\"([^\"]*)\"" flat-output)))
        titles (into {} (map (fn [[_ n v]] [(Integer/parseInt n) v])
                             (re-seq #"chapters\.chapter\.(\d+)\.tags\.title=\"([^\"]*)\"" flat-output)))]
    (mapv (fn [n] {:start (get starts n) :title (get titles n)})
          (sort (keys starts)))))

(defn compare-chapters
  "Check embedded chapters against the expected CSV rows. Compares count,
  then per-chapter trimmed-title exact match plus start within tolerance-s
  (default 2.0: LAME encoder delay plus Fireside's transcode shift starts,
  so exact float equality would false-red). End times are not compared;
  Fireside derives them. Returns true or throws with the deltas."
  [expected actual & {:keys [tolerance-s] :or {tolerance-s 2.0}}]
  (let [exp (mapv (fn [{:keys [name position-seconds]}]
                    {:title (str/trim (str name)) :start (double position-seconds)})
                  expected)]
    (when (not= (count exp) (count actual))
      (throw (ex-info "Chapter count mismatch"
                      {:expected (count exp) :actual (count actual)
                       :expected-titles (mapv :title exp)
                       :actual-titles (mapv :title actual)})))
    (doseq [[e a] (map vector exp actual)]
      (when (not= (:title e) (str/trim (str (:title a))))
        (throw (ex-info "Chapter title mismatch"
                        {:expected (:title e) :actual (:title a)})))
      (when (> (Math/abs (- (:start e) (double (:start a)))) tolerance-s)
        (throw (ex-info "Chapter start drifted past tolerance"
                        {:title (:title e)
                         :expected-start (:start e)
                         :actual-start (:start a)
                         :tolerance-s tolerance-s}))))
    true))

(defn ffprobe-chapters
  "Run ffprobe over mp3-path and return [{:start :title}]."
  [mp3-path]
  (let [{:keys [exit out err]} (shell/sh
                                "ffprobe" "-v" "error"
                                "-show_chapters" "-print_format" "flat"
                                (str mp3-path))]
    (when (not= 0 exit)
      (throw (ex-info "ffprobe failed" {:file (str mp3-path) :err err})))
    (parse-ffprobe-chapters out)))

(defn download-mp3!
  "Download url to dest-path. Used to fetch the processed file back for
  chapter verification (from the status url key, which is the bytes URL)."
  [url dest-path]
  (let [resp (http/request {:method :get :url url :as :byte-array :throw-exceptions false :timeout 600000})]
    (when (not= 200 (:status resp))
      (throw (ex-info "Could not download processed mp3"
                      {:url url :status (:status resp)})))
    (clojure.java.io/copy (:body resp) (clojure.java.io/file (str dest-path)))
    (str dest-path)))

(defn upload-mp3
  "Single pass: S3 upload, attach, wait till a bytes url exists. Returns
  the final status map (always with :url, or throws). Expects the file to
  exist; expects chapters to already be on the episode (embedded chapters
  come from Fireside's records at processing time, so sync-chapters must
  run first). Callers may pass :previous-url (the status url seen before
  this pass); it lets attach verification tell our fresh audio apart from
  stale bytes. Without it, attach snapshots the status itself."
  [{:keys [client podcast episode-guid mp3-path previous-url] :as args}]
  (println "Reading S3 presign...")
  (let [{:keys [url fields]} (s3-upload-form args)]
    (println "Uploading to S3...")
    (let [temp-url (post-file-to-s3! {:url url :fields fields :file mp3-path})
          known-url (if (contains? args :previous-url)
                      previous-url
                      (try (:url (mp3-status args))
                           (catch Exception _ ::unset)))]
      (println "Attaching to episode...")
      (attach-mp3! {:client client :podcast podcast
                    :episode-guid episode-guid :temp-url temp-url
                    :file-name (.getName (clojure.java.io/file (str mp3-path)))
                    :previous-url known-url})
      (println "Waiting for processing...")
      (await-processed args))))

(defn episode-lock-file
  "Path of the mutual-exclusion lock for one episode's audio flow."
  [episode-guid]
  (clojure.java.io/file (str "/tmp/opencode/episode-" episode-guid ".lock")))

(defn acquire-episode-lock!
  "Claim the episode lock or throw. Two concurrent twice-runs interleave
  S3 keys, temp urls and polls and verify each other's bytes, so the
  second run must refuse to start. A stale lock names its age so the
  operator can remove it by hand (never auto-stolen). Returns the lock
  file for release-episode-lock!."
  [episode-guid]
  (let [f (episode-lock-file episode-guid)]
    (.mkdirs (.getParentFile ^java.io.File f))
    (if (.createNewFile ^java.io.File f)
      (do (spit (.getPath ^java.io.File f)
                (str "pid=" (.getName (java.lang.management.ManagementFactory/getRuntimeMXBean))
                     " since=" (java.time.Instant/now) "\n"))
          f)
      (let [age-s (quot (- (System/currentTimeMillis) (.lastModified ^java.io.File f)) 1000)]
        (throw (ex-info "episode audio flow is already running elsewhere; refusing to start"
                        {:episode-guid episode-guid
                         :lock (.getPath ^java.io.File f)
                         :lock-age-seconds age-s
                         :hint "remove the lock file once no run is active"}))))))

(defn release-episode-lock!
  "Release a lock claimed by acquire-episode-lock!. Never throws."
  [lock-file]
  (try (.delete ^java.io.File lock-file)
       (catch Exception _ nil))
  nil)

(defn current-temp-url
  "Read the episode's staged mp3_upload_url, or nil when unset. Used to
  remember pre-run audio for rollback. Never throws: unknown reads as nil."
  [{:keys [client podcast episode-guid]}]
  (try
    (let [base (str/join "/" [FIRESIDE-BASE-URL "podcasts" podcast
                              "episodes" episode-guid])
          page (fetch-as-hickory {:http-client client :url (str base "/edit")})
          v (get (successful-controls page (str "edit_episode_" episode-guid))
                 "episode[mp3_upload_url]")]
      (when-not (str/blank? (str v)) (str v)))
    (catch Exception _ nil)))

(defn upload-and-verify-mp3
  "Single verified pass: S3 upload, attach, await processing, download
  the processed file and verify its embedded chapters exactly against
  chapters-file. A second pass adds nothing: current Fireside writes the
  episode's chapters into the file on the first processing (verified live
  on two episodes), and processed audio cannot be deleted anyway (the
  legacy delete_mp3 route 404s), so the old upload/delete/upload 'twice'
  ritual is retired. Holds the episode lock for the whole run. Safety rule:
  verify chapters BEFORE publishing — once the episode is Public, a bad
  file is already serving. The catch below best-effort re-stages the
  pre-run temp url on verification failure, but that url is usually
  consumed or stale by then, so treat rollback as a courtesy, not a
  guarantee. Returns {:status :report}."
  [{:keys [client podcast episode-guid mp3-path chapters-file]}]
  (let [expected (load-chapters-csv chapters-file)
        args {:client client :podcast podcast :episode-guid episode-guid}
        lock (acquire-episode-lock! episode-guid)
        fallback (current-temp-url args)]
    (try
      (println "Uploading...")
      (let [final (upload-mp3 (assoc args :mp3-path mp3-path))
            tmp (java.io.File/createTempFile "fireside-verify-" ".mp3")]
        (try
          (println "Downloading processed file for verification...")
          (download-mp3! (:url final) (.getAbsolutePath tmp))
          (println "Comparing embedded chapters...")
          (compare-chapters expected (ffprobe-chapters (.getAbsolutePath tmp)))
          (println "Chapters verified exactly.")
          {:status final
           :report {:chapters (count expected) :verified true}}
          (catch Throwable t
            (when fallback
              (println "Attempting rollback to pre-run audio...")
              (try
                (attach-mp3! {:client client :podcast podcast :episode-guid episode-guid
                              :temp-url fallback :file-name "rollback.mp3"})
                (println "Rollback staged the pre-run temp url.")
                (catch Exception r
                  (println "Rollback failed:" (ex-message r)))))
            (throw t))
          (finally (.delete tmp))))
      (finally
        (release-episode-lock! lock)))))

(comment

  (def work-dir "/home/wes/Downloads/workdir/")

  (def c (http-client))
  (login-to-fireside c)

  (load-chapters-csv (str work-dir "Linux Unplugged 686 (Ads) Chapters.csv"))
  (load-ads-csv (str work-dir "Linux Unplugged 686 (Ads) Ads.csv"))

  ;; what Fireside already has
  (fetch-sponsorships c (:podcast noblepayne.link-hoarder/data) (:guid noblepayne.link-hoarder/data))
  (fetch-sponsorship-ids c (:podcast noblepayne.link-hoarder/data) (:guid noblepayne.link-hoarder/data))

  ;; record the real pre-roll time for an existing sponsor
  (sync-sponsorship-times {:client c
                           :podcast (:podcast noblepayne.link-hoarder/data)
                           :episode-guid (:guid noblepayne.link-hoarder/data)
                           :ads-file (str work-dir "Linux Unplugged 686 (Ads) Ads.csv")
                           :sponsor "Nebula"})

  (try

    (delete-link {:client c
                  :podcast "linuxunplugged"
                  :episode-guid "869b643f-3e5b-4020-aec1-0ec3f2f26287"
                  :link-guid "19b95853-18b8-4713-a3fe-aaa75e4f3430"})
    (catch Exception e (def error e) (throw e)))

  (try
    (add-chapter {:client c
                  :podcast "linuxunplugged"
                  :episode-guid "b7a2d096-0fe0-48e9-8ed3-2cf129d1be4a"
                  :timecode "0"
                  :note "test"})
    (catch Exception e (def error e) (throw e)))

  ;; Episode 686 chapter sync. The (Ads) file belongs to the public
  ;; linuxunplugged feed; the (Premium) file belongs to the adfree feed.
  ;; Both feeds carry a separate episode even though the audio is the same,
  ;; so chapters must be pushed twice.
  (def ads-guid (:guid noblepayne.link-hoarder/data))
  (def premium-guid "9d553b2d-a2ac-4ca4-a7b4-00ffc319f4de")

  (sync-chapters {:client c
                  :podcast "linuxunplugged"
                  :episode-guid ads-guid
                  :chapters-file (str work-dir "Linux Unplugged 686 (Ads) Chapters.csv")})

  (sync-chapters {:client c
                  :podcast "adfree"
                  :episode-guid premium-guid
                  :chapters-file (str work-dir "Linux Unplugged 686 (Premium) Chapters.csv")}))

(defn prepare-data
  "Fetch markdown from url and prepare data with podcast association.
  Returns data map with :podcast set."
  [url podcast]
  (-> (lh/-main url)
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
                  lh/-main
                  (assoc :podcast podcast)
                  (update :guid #(or guid-override %)))
         guid (:guid data)
         links (:links data)
         total (count links)]
     ;; Not (not guid): "" is truthy in Clojure, so a blanked-out
     ;; placeholder would sail past and post to /episodes//edit.
     (when (clojure.string/blank? (str guid))
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
       ;; One binding only: a second sequence clause here would nest the
       ;; iteration and post every link N times (15 links became 225
       ;; posts on episode 687 before this was caught).
       (doseq [[idx {:keys [title href quote]}] (map-indexed vector links)
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

(defn decode-html-entities
  "Decode common HTML entities in a string. &amp; goes LAST: decoding it
  first would turn a literal &amp;lt; into &lt; and then into <."
  [s]
  (-> s
      (str/replace "&lt;" "<")
      (str/replace "&gt;" ">")
      (str/replace "&quot;" "\"")
      (str/replace "&#39;" "'")
      (str/replace "&amp;" "&")))

(defn parse-timecode-to-seconds
  "Convert timecode string like '51 seconds' or '2 minutes 30 seconds' to total seconds."
  [timecode-str]
  (let [parts (re-seq #"(\d+)\s+(second|minute|hour)s?" timecode-str)]
    (reduce (fn [acc [_ num unit]]
              (let [n (Integer/parseInt num)]
                (case unit
                  "second" (+ acc n)
                  "minute" (+ acc (* n 60))
                  "hour" (+ acc (* n 3600))
                  acc)))
            0 parts)))

(defn extract-sponsorships
  "Extract sponsorship data from a sponsorship page HTML."
  [html]
  (let [pattern #"(?s)<span class=\"accordion-heading__title\">(.*?)</span>.*?<span class=\"accordion-heading__metadata\"><i class=\"fas fa-clock\" aria-hidden=\"true\"></i>\s*(.*?)</span>.*?<span class=\"accordion-heading__subtitle\">(.*?)</span>"
        matches (re-seq pattern html)]
    (mapv (fn [[_ campaign timecode sponsor]]
            {:campaign (decode-html-entities (str/trim campaign))
             :timecode_str (str/trim timecode)
             :timecode_seconds (parse-timecode-to-seconds (str/trim timecode))
             :sponsor (decode-html-entities (str/trim sponsor))})
          matches)))

(defn fetch-sponsorships
  "Fetch sponsorships for a single episode. Retries once on transient errors."
  [client podcast episode-guid]
  (let [url (str/join "/" [FIRESIDE-BASE-URL "podcasts" podcast "episodes" episode-guid "sponsorships"])]
    (letfn [(fetch []
              (try
                (let [html (:body (http/request {:method :get :url url :http-client client}))]
                  (extract-sponsorships html))
                (catch Exception e
                  (when (re-find #"502|503|504" (str (.getMessage e)))
                    (println "  Retrying" (subs episode-guid 0 8) "...")
                    (Thread/sleep 2000)
                    (let [html (:body (http/request {:method :get :url url :http-client client}))]
                      (extract-sponsorships html))))))]
      (fetch))))

(defn strip-tags
  "Drop tags and collapse whitespace. Used for cell text that may contain
  inline markup, which a [^<]+ pattern would truncate mid-title."
  [s]
  (-> s
      (str/replace #"(?s)<[^>]*>" "")
      (decode-html-entities)
      (str/replace #"\s+" " ")
      (str/trim)))

(defn parse-episode-rows
  "Pull {:episode_num :title :guid} out of one episodes-list page.

  Parsed row-by-row rather than with a single regex spanning the number
  cell through the title link: the title anchor's href now ends in /edit,
  which the old pattern did not allow for, so it silently matched nothing
  and every caller saw zero episodes.

  Deliberately tolerant of attribute order, extra class names and
  newlines inside tags, since each of those is a way a strict pattern
  quietly starts matching nothing."
  [html]
  (into []
        (keep (fn [[_ row]]
                (let [num (second (re-find #"<td[^>]*>\s*(\d+)\s*</td>" row))
                      ;; capture the anchor's text content; the guid comes
                      ;; from the row as a whole since the row also holds
                      ;; the /edit link and an action icon
                      title (second (first (re-seq #"(?s)<a[^>]*data-table__link[^>]*>(.*?)</a>" row)))
                      guid (second (re-find #"/episodes/([0-9a-f-]{36})/edit" (str row)))]
                  (when (and num title guid)
                    {:episode_num (Integer/parseInt num)
                     :title (strip-tags title)
                     :guid guid}))))
        (re-seq #"(?s)<tr[^>]*>(.*?)</tr>" html)))

(defn fetch-episode-ids
  "Fetch episode GUIDs, numbers and titles from the episodes list page.
   Stops early once we have enough episodes. Returns vector of
   {:episode_num Int :title Str :guid Str}."
  [client podcast & {:keys [needed] :or {needed 90}}]
  (let [pages-needed (max 1 (+ (quot needed 25) 1))
        base (str/join "/" [FIRESIDE-BASE-URL "podcasts" podcast "episodes"])]
    (println "Fetching" needed "episodes (est." pages-needed "pages)...")
    (loop [page-num 1
           acc []]
      (if (or (> page-num pages-needed) (>= (count acc) needed))
        acc
        (let [url (if (= page-num 1) base (str base "?page=" page-num))
              html (:body (http/request {:method :get :url url :http-client client}))
              page-eps (parse-episode-rows html)
              combined (into acc page-eps)]
          (println "  Page" page-num ": +" (count page-eps) "episodes")
          (if (empty? page-eps)
            acc
            (recur (inc page-num) combined)))))))

(defn export-sponsorships
  "Export sponsorships for the most recent N episodes to an EDN file.
   Each entry includes :episode_num, :title, and :guid.
   Episodes without sponsorships are in :no_sponsors.
   :request-delay controls ms between API calls (default 100)."
  [client podcast & {:keys [episode-count output-file request-delay]
                     :or {episode-count 90 output-file "sponsorships.edn" request-delay 100}}]
  (println "Fetching episode list...")
  (let [all-episodes (vec (fetch-episode-ids client podcast :needed episode-count))
        recent-episodes (vec (take episode-count all-episodes))]
    (println "Got" (count all-episodes) "total, processing first" (count recent-episodes))
    (let [results (mapv (fn [{:keys [episode_num title guid]}]
                          (Thread/sleep (int request-delay))
                          (let [sponsorships (fetch-sponsorships client podcast guid)]
                            {:episode_num episode_num
                             :title title
                             :episode_guid guid
                             :sponsorships sponsorships}))
                        recent-episodes)
          with-sponsors (filterv #(seq (:sponsorships %)) results)
          without-sponsors (remove #(seq (:sponsorships %)) results)]
      (println "Episodes with sponsorships:" (count with-sponsors))
      (println "Episodes without sponsorships:" (count without-sponsors))
      (when (seq without-sponsors)
        (println "  Episodes:" (map :title without-sponsors)))
      (spit output-file (pr-str {:podcast podcast
                                 :total_count (count with-sponsors)
                                 :no_sponsors (vec (map #(dissoc % :sponsorships) without-sponsors))
                                 :sponsorships with-sponsors}))
      (println "Written to" output-file)
      results)))

(comment
  (def c (http-client))
  (login-to-fireside c)

  ;; Broken scratch: `cookie` was never defined. Left readable (not
  ;; deleted) in case the exploration is ever redone against a real class.
  #_(clojure.pprint/print-table
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

  (try
    (purge-links {:client c
                  :podcast (:podcast noblepayne.link-hoarder/data)
                  :episode-guid (:guid noblepayne.link-hoarder/data)})
    (catch Exception e (def error e) (throw e)))

  (doseq [{:keys [:title :href :quote]} (noblepayne.link-hoarder/data :links)]
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
