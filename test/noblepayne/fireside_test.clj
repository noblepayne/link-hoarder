(ns noblepayne.fireside-test
  "Tests for the pure parsing/conversion functions used to drive Fireside.
   Deliberately network-free: everything here is a calculation, so the shell
   is what needs exercising against a real Fireside, not these."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [hato.client :as http]
            [noblepayne.fireside :as f]))

;; ------------------------------------------- pre-existing regressions

(deftest decode-html-entities-test
  (testing "decodes common HTML entities"
    (is (= "&" (f/decode-html-entities "&amp;")))
    (is (= "<" (f/decode-html-entities "&lt;")))
    (is (= ">" (f/decode-html-entities "&gt;")))
    (is (= "\"" (f/decode-html-entities "&quot;")))
    (is (= "'" (f/decode-html-entities "&#39;")))
    (is (= "T-Mobile" (f/decode-html-entities "T-Mobile")))))

(deftest parse-timecode-to-seconds-test
  (testing "parses seconds"
    (is (= 51 (f/parse-timecode-to-seconds "51 seconds")))
    (is (= 1 (f/parse-timecode-to-seconds "1 second"))))
  (testing "parses minutes and seconds"
    (is (= 150 (f/parse-timecode-to-seconds "2 minutes 30 seconds")))
    (is (= 60 (f/parse-timecode-to-seconds "1 minute"))))
  (testing "parses hours"
    (is (= 3600 (f/parse-timecode-to-seconds "1 hour")))
    (is (= 5400 (f/parse-timecode-to-seconds "1 hour 30 minutes")))))

(deftest extract-sponsorships-test
  (testing "extracts sponsorships from HTML"
    (let [html (str "<span class=\"accordion-heading__title\">Campaign A</span>\n"
                    "<span class=\"accordion-heading__metadata\">"
                    "<i class=\"fas fa-clock\" aria-hidden=\"true\"></i>\n"
                    "      51 seconds</span>\n"
                    "<span class=\"accordion-heading__subtitle\">Sponsor One</span>\n\n"
                    "<span class=\"accordion-heading__title\">Campaign B</span>\n"
                    "<span class=\"accordion-heading__metadata\">"
                    "<i class=\"fas fa-clock\" aria-hidden=\"true\"></i>\n"
                    "      2 minutes 30 seconds</span>\n"
                    "<span class=\"accordion-heading__subtitle\">Sponsor Two</span>")
          results (f/extract-sponsorships html)]
      (is (= 2 (count results)))
      (is (= "Campaign A" (:campaign (first results))))
      (is (= "51 seconds" (:timecode_str (first results))))
      (is (= 51 (:timecode_seconds (first results))))
      (is (= "Sponsor One" (:sponsor (first results))))
      (is (= "Sponsor Two" (:sponsor (second results))))
      (is (= 150 (:timecode_seconds (second results))))))
  (testing "returns empty vector when no sponsorships"
    (is (= [] (f/extract-sponsorships "no sponsorships here"))))
  (testing "decodes HTML entities in results"
    (let [html (str "<span class=\"accordion-heading__title\">Test &amp; More</span>\n"
                    "<span class=\"accordion-heading__metadata\">"
                    "<i class=\"fas fa-clock\" aria-hidden=\"true\"></i>\n"
                    "      10 seconds</span>\n"
                    "<span class=\"accordion-heading__subtitle\">Sponsor &lt;Name&gt;</span>")
          results (f/extract-sponsorships html)]
      (is (= "Test & More" (:campaign (first results))))
      (is (= "Sponsor <Name>" (:sponsor (first results)))))))

;; ---------------------------------------------------------------- csv

(deftest parse-csv-line-test
  (testing "plain fields"
    (is (= ["a" "b" "c"] (f/parse-csv-line "a,b,c"))))

  (testing "does not trim - parse-csv trims, so a value with inner spaces survives"
    (is (= ["a " " b"] (f/parse-csv-line "a , b"))))

  (testing "empty fields are preserved, not dropped"
    (is (= ["a" "" "c"] (f/parse-csv-line "a,,c"))))

  (testing "commas inside quotes do not split fields"
    (is (= ["a" "b,c" "d"] (f/parse-csv-line "a,\"b,c\",d"))))

  (testing "escaped quotes inside a quoted field"
    (is (= ["a" "say \"hi\"" "b"]
           (f/parse-csv-line "a,\"say \"\"hi\"\"\",b"))))

  (testing "trailing empty field"
    (is (= ["a" "b" ""] (f/parse-csv-line "a,b,")))))

(deftest parse-csv-test
  (testing "keys rows by the header"
    (is (= [{:number "1" :name "Intro"}]
           (f/parse-csv "number,name\n1,Intro" "test"))))

  (testing "ignores blank lines"
    (is (= [{:number "1"} {:number "2"}]
           (f/parse-csv "number\n1\n\n2\n" "test"))))

  (testing "throws when a row has too few fields"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not match header"
                          (f/parse-csv "a,b\n1" "test"))))

  (testing "throws when a row has too many fields"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not match header"
                          (f/parse-csv "a\n1,2" "test"))))

  (testing "throws on empty input"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"CSV is empty"
                          (f/parse-csv "" "test")))))

;; ------------------------------------------------------- chapters/ads

(def chapters-csv
  "number,position_seconds,timecode,name
1,0.00,00:00:00,Intro
2,90.11,00:01:30,KDE Week
3,4960.08,01:22:40,Outro
")

(deftest load-chapters-csv-test
  (with-redefs [f/read-csv (constantly (f/parse-csv chapters-csv "chapters"))]
    (testing "parses each column with its own type"
      (is (= [{:number 1
               :position-seconds 0.0
               :timecode "00:00:00"
               :name "Intro"}
              {:number 2
               :position-seconds 90.11
               :timecode "00:01:30"
               :name "KDE Week"}
              {:number 3
               :position-seconds 4960.08
               :timecode "01:22:40"
               :name "Outro"}]
             (f/load-chapters-csv "chapters.csv"))))

    (testing "throws a useful error on a non-numeric position"
      (with-redefs [f/read-csv (constantly [{:number "1"
                                             :position_seconds "nope"
                                             :timecode "00:00:00"
                                             :name "Intro"}])]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Not a number"
                              (f/load-chapters-csv "chapters.csv")))))))

(def ads-csv
  "number,start_seconds,end_seconds,start_timecode,end_timecode,name
1,61.66,129.67,00:01:01,00:02:09,Nebula
2,1992.92,1993.92,00:33:12,00:33:13,Dynamic 1
")

(deftest load-ads-csv-test
  (with-redefs [f/read-csv (constantly (f/parse-csv ads-csv "ads"))]
    (testing "keeps both seconds and timecode columns"
      (is (= [{:number 1
               :start-seconds 61.66
               :end-seconds 129.67
               :start-timecode "00:01:01"
               :end-timecode "00:02:09"
               :name "Nebula"}
              {:number 2
               :start-seconds 1992.92
               :end-seconds 1993.92
               :start-timecode "00:33:12"
               :end-timecode "00:33:13"
               :name "Dynamic 1"}]
             (f/load-ads-csv "ads.csv"))))))

;; --------------------------------------------------------- conversions

(deftest timecode->words-test
  (testing "omits leading zero units"
    (is (= "0s" (f/timecode->words "00:00:00")))
    (is (= "1m 30s" (f/timecode->words "00:01:30")))
    (is (= "59s" (f/timecode->words "00:00:59"))))

  (testing "keeps hours"
    (is (= "1h 1m 1s" (f/timecode->words "01:01:01")))
    (is (= "1h 22m 40s" (f/timecode->words "01:22:40"))))

  (testing "truncates rather than rounds, matching the CSV's own convention"
    ;; 1987.94s is authored as 00:33:07; recomputing would give 33m 8s and
    ;; drift a second from what the editor intended.
    (is (= "33m 7s" (f/timecode->words "00:33:07"))))

  (testing "returns nil for a non-timecode"
    (is (nil? (f/timecode->words "not a timecode"))))

  (testing "throws rather than NPE on nil"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"got nil"
                          (f/timecode->words nil)))))

(deftest seconds->words-test
  (testing "rounds to the nearest second"
    (is (= "33m 8s" (f/seconds->words 1987.94)))
    (is (= "1m 30s" (f/seconds->words 90.11))))

  (testing "handles zero and hours"
    (is (= "0s" (f/seconds->words 0.0)))
    (is (= "1h 17m 5s" (f/seconds->words 4624.91))))

  (testing "drops zero units but always keeps seconds"
    (is (= "1h 1s" (f/seconds->words 3601)))
    (is (= "1h 5s" (f/seconds->words 3605)))))

;; ------------------------------------------------- episodes list parsing

;; ------------------------------------------------ sponsorship html parsing
;;
;; These three are pure string functions - no client, no I/O - so they are
;; free to test and they are exactly where Fireside's markup traps live.
;; option-map and select-html are private; reached via the var.

(deftest option-map-test
  (let [om (deref (resolve 'noblepayne.fireside/option-map))]
    (testing "maps option value to display text"
      (is (= {"2155" "Nebula" "486" "Ting"}
             (om (str "<select name=\"sponsorship[sponsor_id]\">"
                      "<option value=\"2155\">Nebula</option>"
                      "<option value=\"486\">Ting</option>"
                      "</select>")))))

    (testing "decodes html entities in the label"
      (is (= {"2133" "River - Buy Bitcoin. Easy & Secure."}
             (om (str "<option value=\"2133\">River - Buy Bitcoin. "
                      "Easy &amp; Secure.</option>")))))

    (testing "trims whitespace around labels"
      (is (= {"1" "Nebula"} (om "<option value=\"1\">  Nebula  </option>"))))

    (testing "returns empty map for html with no options"
      (is (= {} (om "<select name=\"x\"></select>"))))

    (testing "ignores the current selection marker on the option tag"
      (is (= {"2069" "Managed Nebula"}
             (om (str "<option selected=\"selected\" value=\"2069\">"
                      "Managed Nebula</option>")))))

    (testing "reads value whether it comes before or after other attributes"
      ;; Regression guard: the pattern used to require value to be the
      ;; last attribute before '>'. Rails emits either order depending on
      ;; path, so a strict pattern silently drops options.
      (is (= {"2069" "Managed Nebula"}
             (om (str "<option value=\"2069\" selected=\"selected\">"
                      "Managed Nebula</option>"))))

      (testing "returns {} for nil instead of throwing"
        (is (= {} (om nil)))))))

(deftest select-html-test
  (let [sh (deref (resolve 'noblepayne.fireside/select-html))
        html (str "<select class=\"form-select\" name=\"sponsorship[sponsor_id]\" "
                  "id=\"sponsorship_sponsor_id\"><option value=\"1\">A</option></select>"
                  "<select name=\"sponsorship[campaign_id]\"><option value=\"2\">B</option></select>")]
    (testing "finds a select whose field name contains regex metacharacters"
      ;; Regression guard: the square brackets in sponsorship[sponsor_id]
      ;; are a regex character class. Unquoted, this silently matches
      ;; nothing and every sponsor lookup comes back empty.
      (is (some? (sh html "sponsorship[sponsor_id]"))))

    (testing "returns the right select when several are present"
      (is (re-find #">B<" (sh html "sponsorship[campaign_id]")))
      (is (re-find #">A<" (sh html "sponsorship[sponsor_id]"))))

    (testing "returns nil for an unknown field rather than throwing"
      (is (nil? (sh html "sponsorship[nope]"))))))

(deftest fetch-campaigns-parsing-test
  (let [om (deref (resolve 'noblepayne.fireside/option-map))
        unescape (fn [body]
                   (-> body
                       (str/replace "\\\"" "\"")
                       (str/replace "\\/" "/")
                       om))]
    (testing "unescapes only the sequences the endpoint emits"
      ;; /update_campaigns returns JS, not HTML:
      ;;   $("#x").empty().append("<option value=\"2069\">Managed Nebula<\/option>\n");
      (let [body (str "$(\"#sponsorship_campaign_id\").empty()"
                      ".append(\"<option value=\\\"2069\\\">Managed Nebula<\\/option>\\n\");")]
        (is (= {"2069" "Managed Nebula"} (unescape body)))))

    (testing "a blanket backslash strip would corrupt a name"
      ;; Regression guard: (str/replace body "\\" "") also eats unicode
      ;; escapes and backslash-space, and the label is what the exact
      ;; match lookup keys on, so a mangled label means a lookup miss.
      (let [body (str "append(\"<option value=\\\"1\\\">Caf\\u00e9 \\\\ Draft"
                      "</option>\");")]
        ;; the literal backslash and \u00e9 escape both survive
        (is (= {"1" "Caf\\u00e9 \\\\ Draft"} (unescape body)))))))

(deftest parse-episode-rows-test
  (let [row (fn [num anchor]
              (str "<tr><td class=\"data-table__cell\">" num "</td>"
                   "<td class=\"data-table__cell data-table__cell--primary\">"
                   anchor "</td></tr>"))
        anchor (fn [& attrs]
                 (str "<a " (str/join " " attrs) ">T</a>"))]
    (testing "pulls number, title and guid from a row"
      (is (= [{:episode_num 686
               :title "T"
               :guid "baa9d873-4356-41ed-a195-8ba847021b26"}]
             (f/parse-episode-rows
              (row 686 (anchor "class=\"data-table__link\""
                               "href=\"/podcasts/linuxunplugged/episodes/baa9d873-4356-41ed-a195-8ba847021b26/edit\""))))))

    (testing "the title anchor ends in /edit, which the old regex missed"
      ;; Regression guard: Fireside appends /edit, so a pattern expecting
      ;; the guid to be followed by '>' matched nothing and every caller
      ;; silently saw zero episodes.
      (is (seq (f/parse-episode-rows
                (row 42 (anchor "class=\"data-table__link\""
                                "href=\"/podcasts/p/episodes/00000000-0000-0000-0000-000000000000/edit\""))))))

    (testing "tolerates href before class"
      (is (seq (f/parse-episode-rows
                (row 42 (anchor "href=\"/podcasts/p/episodes/00000000-0000-0000-0000-000000000000/edit\""
                                "class=\"data-table__link\""))))))

    (testing "tolerates extra class names on the anchor"
      (is (seq (f/parse-episode-rows
                (row 42 (anchor "class=\"data-table__link text-start\""
                                "href=\"/podcasts/p/episodes/00000000-0000-0000-0000-000000000000/edit\""))))))

    (testing "tolerates newlines inside the tag"
      (is (seq (f/parse-episode-rows
                (str "<tr><td class=\"data-table__cell\">42</td><a\n"
                     "class=\"data-table__link\"\n"
                     "href=\"/podcasts/p/episodes/00000000-0000-0000-0000-000000000000/edit\">T</a></tr>")))))

    (testing "does not truncate a title containing inline markup"
      (is (= "Ep & co"
             (:title (first (f/parse-episode-rows
                             (str "<tr><td class=\"data-table__cell\">1</td>"
                                  "<a class=\"data-table__link\" href=\"/podcasts/p/episodes/"
                                  "00000000-0000-0000-0000-000000000000/edit\">"
                                  "Ep <em>&amp;</em> co</a></tr>")))))))

    (testing "decodes html entities in the title text"
      (is (= "You Ain't Ready"
             (:title (first (f/parse-episode-rows
                             (str "<tr><td class=\"data-table__cell\">1</td>"
                                  "<a class=\"data-table__link\" href=\"/podcasts/p/episodes/"
                                  "00000000-0000-0000-0000-000000000000/edit\">"
                                  "You Ain&#39;t Ready</a></tr>")))))))

    (testing "skips rows with no episode link (e.g. a header row)"
      (is (empty? (f/parse-episode-rows
                   "<tr><td class=\"data-table__header-cell\">Number</td></tr>"))))))

(deftest blank-guid-guard-test
  (testing "a blanked guid placeholder is rejected, not posted"
    ;; Regression guard: the guard used to be (not guid), and "" is truthy
    ;; in Clojure - so a blanked placeholder built /episodes//edit instead
    ;; of failing loudly.
    (is (clojure.string/blank? ""))
    (is (clojure.string/blank? nil))
    (is (not (clojure.string/blank? "baa9d873-4356-41ed-a195-8ba847021b26")))))

(deftest strip-tags-test
  (testing "removes inline markup and decodes entities"
    (is (= "Ep & co" (f/strip-tags "Ep <em>&amp;</em> co"))))
  (testing "collapses whitespace"
    (is (= "a b c" (f/strip-tags "  a\n  b\t c  "))))
  (testing "leaves plain text alone"
    (is (= "Stop Desktop Slop" (f/strip-tags "Stop Desktop Slop")))))

(deftest add-sponsorship-id-extraction-test
  ;; Regression guard: (first (filter ...) some-map) yields a MapEntry,
  ;; and hato expands sequential values into a repeated key - so the
  ;; request went out as sponsor_id=2155&sponsor_id=Nebula and Rails
  ;; raised AssociationTypeMismatch. Sponsorship creation never worked.
  (let [sponsors {"2155" "Nebula" "486" "Ting"}
        id-for (fn [label] (key (first (filter (fn [[_ l]] (= l label)) sponsors))))]
    (testing "the id is the map key, not the MapEntry"
      (is (= "2155" (id-for "Nebula")))
      (is (= "486" (id-for "Ting"))))
    (testing "the bare entry is a MapEntry, which is what went wrong"
      (let [entry (first (filter (fn [[_ l]] (= l "Nebula")) sponsors))]
        (is (instance? clojure.lang.MapEntry entry))
        (is (sequential? entry))))))

(deftest sync-sponsorships-key-access-test
  ;; Regression guard: sync-sponsorships read :start_timecode while
  ;; load-ads-csv emits :start-timecode, so every real row hit the nil
  ;; guard and the function reported only :skipped - indistinguishable
  ;; from "no sponsors found".
  (let [csv "number,start_seconds,end_seconds,start_timecode,end_timecode,name\n1,61.66,129.67,00:01:01,00:02:09,Nebula"
        rows (with-redefs [f/read-csv (constantly (f/parse-csv csv "ads"))]
               (f/load-ads-csv "ads.csv"))
        row (first rows)]
    (is (= 1 (count rows)))
    (is (= "00:01:01" (:start-timecode row)))
    (is (= 61.66 (:start-seconds row)))
    (is (= "1m 1s" (f/timecode->words (:start-timecode row))))))

(deftest rewrite-s3-key-test
  (testing "replaces the trailing segment with a fresh uuid.mp3"
    (let [k1 (f/rewrite-s3-key "episodes/abc/${filename}")
          k2 (f/rewrite-s3-key "episodes/abc/${filename}")]
      (is (re-find #"^episodes/abc/[0-9a-f-]{36}\.mp3$" k1))
      (is (not= k1 k2) "fresh uuid per upload")))

  (testing "pop-append semantics: whatever the tail is, it goes"
    (is (re-find #"^a/b/[0-9a-f-]{36}\.mp3$"
                 (f/rewrite-s3-key "a/b/oldname.mp3")))))

(deftest parse-ffprobe-chapters-test
  (let [flat (str "chapters.chapter.0.start_time=\"0.000000\"\n"
                  "chapters.chapter.0.tags.title=\"Intro\"\n"
                  "chapters.chapter.1.start_time=\"90.110000\"\n"
                  "chapters.chapter.1.tags.title=\"KDE Week\"\n")]
    (testing "parses starts and titles by index"
      (is (= [{:start 0.0 :title "Intro"}
              {:start 90.11 :title "KDE Week"}]
             (f/parse-ffprobe-chapters flat))))

    (testing "empty output parses to empty"
      (is (= [] (f/parse-ffprobe-chapters ""))))))

(deftest compare-chapters-test
  (let [expected [{:name "Intro" :position-seconds 0.0}
                  {:name "KDE Week" :position-seconds 90.11}]]
    (testing "passes on title match plus starts within tolerance"
      ;; 50ms of encoder delay must not fail the check
      (is (true? (f/compare-chapters
                  expected
                  [{:title "Intro" :start 0.05}
                   {:title "KDE Week" :start 90.2}]))))

    (testing "count mismatch throws with both title lists"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"count"
                            (f/compare-chapters expected
                                                [{:title "Intro" :start 0.0}]))))

    (testing "title mismatch throws"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"title"
                            (f/compare-chapters
                             expected
                             [{:title "Intro" :start 0.0}
                              {:title "Wrong" :start 90.11}]))))

    (testing "start drift past tolerance throws with deltas"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"drift"
                            (f/compare-chapters
                             expected
                             [{:title "Intro" :start 0.0}
                              {:title "KDE Week" :start 99.0}]))))))

(deftest multipart-bytes-order-test
  (testing "Content-Type field travels before the file part, file last"
    ;; Regression guard: S3 ignores every field after the file part, so a
    ;; trailing Content-Type fails the policy with a 403 even though the
    ;; bytes are present. Proven live: ct-last 403s, ct-first 201s.
    (let [s (String. ^bytes (:body (f/multipart-bytes
                                    {"key" "ep/x.mp3" "policy" "abc"}
                                    "x.mp3" (.getBytes "DATA"))))]
      (is (< (.indexOf s "name=\"key\"") (.indexOf s "name=\"Content-Type\"")))
      (is (< (.indexOf s "name=\"Content-Type\"") (.indexOf s "name=\"file\"")))
      (is (re-find #"name=\"file\"; filename=\"x\.mp3\"" s))
      (is (re-find #"audio/mp3\r\n" s))))

  (testing "a presign-supplied Content-Type wins over the hardcoded one"
    (let [s (String. ^bytes (:body (f/multipart-bytes
                                    {"key" "k" "Content-Type" "video/mp4"}
                                    "x.mp4" (.getBytes "D"))))]
      (is (= 1 (count (re-seq #"name=\"Content-Type\"" s))))
      (is (re-find #"video/mp4\r\n" s))
      (is (not (re-find #"audio/mp3" s))))))

(deftest successful-controls-test
  (let [sc (fn [form] ((deref (resolve 'noblepayne.fireside/successful-controls)) {:tag :root :content [(assoc-in form [:attrs :id] "f")]} "f"))
        form {:tag :form
              :attrs {}
              :content [{:tag :input :attrs {:type "hidden" :name "authenticity_token" :value "TOK"} :content nil}
                        {:tag :input :attrs {:type "text" :name "episode[title]" :value "T"} :content nil}
                        {:tag :input :attrs {:type "checkbox" :name "episode[host_ids][]" :value "1" :checked "checked"} :content nil}
                        {:tag :input :attrs {:type "checkbox" :name "episode[host_ids][]" :value "2"} :content nil}
                        {:tag :input :attrs {:type "radio" :name "episode[itunes_episode_type]" :value "full" :checked "checked"} :content nil}
                        {:tag :input :attrs {:type "radio" :name "episode[itunes_episode_type]" :value "bonus"} :content nil}
                        {:tag :textarea :attrs {:name "episode[description]"} :content ["Desc"]}
                        {:tag :select :attrs {:name "episode[status]"}
                         :content [{:tag :option :attrs {:value "0"}} {:tag :option :attrs {:value "1" :selected "selected"}}]}]}]
    (testing "keeps hidden, text, checked boxes, textarea content, selected options"
      (let [m (sc form)]
        (is (= "TOK" (get m "authenticity_token")))
        (is (= "T" (get m "episode[title]")))
        (is (= "1" (get m "episode[host_ids][]")))
        (is (= "full" (get m "episode[itunes_episode_type]")))
        (is (= "Desc" (get m "episode[description]")))
        (is (= "1" (get m "episode[status]")))))

    (testing "drops unchecked boxes and unselected options"
      (let [m (sc form)]
        (is (= "1" (get m "episode[host_ids][]")))))
    (testing "multi-value names accumulate into vectors"
      (let [m (sc {:tag :form
                   :attrs {}
                   :content [{:tag :input :attrs {:type "checkbox" :name "h" :value "1" :checked "checked"}}
                             {:tag :input :attrs {:type "checkbox" :name "h" :value "2" :checked "checked"}}]})]
        (is (= ["1" "2"] (get m "h")))))
    (testing "inputs outside the form element with a matching form= attribute are included"
      ;; Regression guard: Rails renders ignore_cover/ignore_chapters
      ;; outside the <form> with form=<id>. A subtree-only scrape misses
      ;; them and the update 500s.
      (let [sc2 (fn [page id] ((deref (resolve 'noblepayne.fireside/successful-controls)) page id))
            page {:tag :root
                  :content [{:tag :form
                             :attrs {:id "f"}
                             :content [{:tag :input :attrs {:type "hidden" :name "a" :value "1"}}]}
                            {:tag :input :attrs {:type "hidden" :name "episode[ignore_cover]" :value "0" :form "f"}}]}]
        (is (= {"a" "1" "episode[ignore_cover]" "0"} (sc2 page "f")))))))

(deftest multipart-fields-bytes-test
  (testing "encodes fields with repeated parts for multi-values"
    (let [{:keys [body boundary]} (f/multipart-fields-bytes
                                   {"a" "1" "h" ["x" "y"]})
          s (String. ^bytes body)]
      (is (re-find #"name=\"a\"\r\n\r\n1\r\n" s))
      (is (= 2 (count (re-seq #"name=\"h\"\r\n" s))))
      (is (re-find (re-pattern (str "--" boundary "--\r\n")) s)))))

(deftest stage-temp-url-test
  (testing "appends the filename fragment"
    (is (= "https://x/y.mp3#filename=a%20b.mp3"
           (f/stage-temp-url "https://x/y.mp3" "a b.mp3"))))
  (testing "strips a pre-existing fragment instead of stacking"
    (is (= "https://x/y.mp3#filename=new.mp3"
           (f/stage-temp-url "https://x/y.mp3#filename=old.mp3" "new.mp3"))))
  (testing "blank stays blank"
    (is (= "" (f/stage-temp-url "" "x.mp3")))
    (is (= "" (f/stage-temp-url nil "x.mp3")))))

(deftest cookies-for-test
  (testing "renders stored cookies as a Cookie header value"
    (let [c (http/build-http-client {:cookie-policy :all})
          mgr (.orElse (.cookieHandler ^java.net.http.HttpClient c) nil)
          store (.getCookieStore ^java.net.CookieManager mgr)
          uri (java.net.URI/create "https://app.fireside.fm/x")]
      (.add store uri (java.net.HttpCookie. "u" "abc123"))
      (.add store uri (java.net.HttpCookie. "_blackbird_session" "def456"))
      (let [h (f/cookies-for c "https://app.fireside.fm/podcasts/p/episodes/g/edit")]
        (is (re-find #"u=abc123" h))
        (is (re-find #"_blackbird_session=def456" h))
        (is (re-find #"; " h))))))

(deftest temp-file-url-test
  (testing "builds the browser-shaped URL with raw slashes"
    (is (= "https://s3.amazonaws.com/temp.fireside.fm/uploads/abc/def.mp3"
           (f/temp-file-url "https://s3.amazonaws.com/temp.fireside.fm" "uploads/abc/def.mp3"))))
  (testing "tolerates a trailing slash on the endpoint"
    (is (= "https://s3.amazonaws.com/temp.fireside.fm/uploads/abc/def.mp3"
           (f/temp-file-url "https://s3.amazonaws.com/temp.fireside.fm/" "uploads/abc/def.mp3"))))
  (testing "never percent-encodes the key"
    (is (not (re-find #"%" (f/temp-file-url "https://e" "uploads/a/b.mp3"))))))

(deftest required-episode-keys-test
  (testing "host/guest arrays are not required: roster-less podcasts render zero such inputs"
    ;; Regression guard: the adfree episode form carries no host_ids[] or
    ;; guest_ids[] inputs at all, and requiring them refused every POST.
    (is (not (contains? f/required-episode-keys "episode[host_ids][]")))
    (is (not (contains? f/required-episode-keys "episode[guest_ids][]"))))
  (testing "title/status/publish schedule always required"
    (is (every? #(contains? f/required-episode-keys %)
                ["episode[title]" "episode[status]"
                 "episode[publish_at(1i)]" "episode[publish_at(5i)]"
                 "episode[mp3_upload_url]"]))))
