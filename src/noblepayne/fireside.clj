(ns noblepayne.fireside
  (:gen-class)
  (:require [clojure.string :as str]
            [hato.client :as http]
            [hickory.core :as hickory]
            [hickory.select :as hs]
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

(comment

  (def work-dir "/home/wes/Downloads/workdir/")

  (def c (http-client))
  (login-to-fireside c)

  (load-chapters-csv (str work-dir "Linux Unplugged 686 (Ads) Chapters.csv"))
  (load-ads-csv (str work-dir "Linux Unplugged 686 (Ads) Ads.csv"))

  ;; what Fireside already has
  (fetch-sponsorships c (:podcast data) (:guid data))
  (fetch-sponsorship-ids c (:podcast data) (:guid data))

  ;; record the real pre-roll time for an existing sponsor
  (sync-sponsorship-times {:client c
                           :podcast (:podcast data)
                           :episode-guid (:guid data)
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

(defn decode-html-entities
  "Decode common HTML entities in a string."
  [s]
  (-> s
      (str/replace "&amp;" "&")
      (str/replace "&lt;" "<")
      (str/replace "&gt;" ">")
      (str/replace "&quot;" "\"")
      (str/replace "&#39;" "'")))

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

  (try
    (purge-links {:client c
                  :podcast (:podcast noblepayne.link-hoarder/data)
                  :episode-guid (:guid noblepayne.link-hoarder/data)})
    (catch Exception e (def error e) (throw e)))

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
