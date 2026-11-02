(ns noblepayne.link-hoarder-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [noblepayne.link-hoarder :as lh :refer [dedup-links
                                                    dedupe-tags
                                                    normalize-href
                                                    preview-markdown]]))

(deftest dedup-links-test
  (testing "preserves order, first occurrence wins"
    (is (= [{:href "a" :title "first"}]
           (dedup-links [{:href "a" :title "first"}
                         {:href "a" :title "second"}]))))

  (testing "removes no duplicates when all unique"
    (is (= [{:href "a"} {:href "b"} {:href "c"}]
           (dedup-links [{:href "a"} {:href "b"} {:href "c"}]))))

  (testing "preserves original order"
    (is (= [{:href "b"} {:href "a"}]
           (dedup-links [{:href "b"} {:href "a"} {:href "b"}]))))

  (testing "handles empty input"
    (is (= [] (dedup-links []))))

  (testing "handles single element"
    (is (= [{:href "x"}]
           (dedup-links [{:href "x"}]))))

  (testing "all same href"
    (is (= [{:href "a"}]
           (dedup-links [{:href "a"} {:href "a"} {:href "a"}]))))

  (testing "preserves other keys from first occurrence"
    (is (= [{:href "a" :title "first" :quote "q1"}]
           (dedup-links [{:href "a" :title "first" :quote "q1"}
                         {:href "a" :title "second" :quote "q2"}]))))

  (testing "nil href values are deduped together"
    (is (= [{:href nil :title "only"}]
           (dedup-links [{:href nil :title "only"}
                         {:href nil :title "also"}])))))

;; ---------------------------------------------------------------------------
;; strip-nils tests — the nil-filtering for hiccup before hickory rendering
;; ---------------------------------------------------------------------------

(def ^:private strip-nils
  (resolve 'noblepayne.link-hoarder/strip-nils))

(deftest strip-nils-test
  (testing "passes through strings and numbers"
    (is (= "hello" (strip-nils "hello")))
    (is (= 42 (strip-nils 42))))

  (testing "nil becomes nil (filtered by caller)"
    (is (nil? (strip-nils nil))))

  (testing "strips nil children from vector"
    (is (= [:div [:p "a"] [:p "b"]]
           (strip-nils [:div [:p "a"] nil [:p "b"]]))))

  (testing "flattens seq children into parent vector"
    (is (= [:ul [:li "a"] [:li "b"]]
           (strip-nils [:ul (for [x ["a" "b"]] [:li x])]))))

  (testing "handles when expression returning nil"
    (is (= [:div [:p "visible"]]
           (strip-nils [:div (when true [:p "visible"]) (when false [:p "hidden"])]))))

  (testing "handles mixed when expressions"
    (is (= [:div [:p "a"]]
           (strip-nils [:div (when true [:p "a"]) (when false [:p "b"])]))))

  (testing "preserves attributes"
    (is (= [:p {:class "foo"} "text"]
           (strip-nils [:p {:class "foo"} "text"]))))

  (testing "nested nils are stripped recursively"
    (is (= [:div [:ul [:li "x"]]]
           (strip-nils [:div [:ul [:li "x"] nil]])))))

;; ---------------------------------------------------------------------------
;; render-hiccup integration — verify hickory output matches expected HTML
;; ---------------------------------------------------------------------------

(def ^:private render-hiccup
  (resolve 'noblepayne.link-hoarder/render-hiccup))

(deftest render-hiccup-test
  (testing "renders basic hiccup to HTML"
    (is (= "<p>hello</p>"
           (render-hiccup [:p "hello"]))))

  (testing "renders attributes"
    (is (= "<a href=\"https://example.com\">link</a>"
           (render-hiccup [:a {:href "https://example.com"} "link"]))))

  (testing "renders void elements"
    (is (= "<br>"
           (render-hiccup [:br]))))

  (testing "renders nested structure"
    (is (= "<article><h1>title</h1><p>body</p></article>"
           (render-hiccup [:article [:h1 "title"] [:p "body"]]))))

  (testing "nil children are stripped"
    (is (= "<div><p>a</p><p>c</p></div>"
           (render-hiccup [:div [:p "a"] nil [:p "c"]]))))

  (testing "seq children from for are flattened"
    (is (= "<ul><li>a</li><li>b</li></ul>"
           (render-hiccup [:ul (for [x ["a" "b"]] [:li x])])))))

;; ---------------------------------------------------------------------------
;; preview-markdown regression
;; ---------------------------------------------------------------------------

(deftest preview-markdown-test
  (testing "episode format with header"
    (is (= "##### Episode Links\n* [Example](https://example.com)\n  > A quote\n* [No Quote](https://other.com)\n"
           (preview-markdown {:links [{:href "https://example.com" :title "Example" :quote "A quote"}
                                      {:href "https://other.com" :title "No Quote" :quote nil}]}
                             :episode))))

  (testing "plain format without header"
    (is (= "* [Example](https://example.com)\n"
           (preview-markdown {:links [{:href "https://example.com" :title "Example" :quote nil}]}
                             :plain)))))

(deftest normalize-href-test
  (testing "prepends https to bare domains"
    (is (= "https://connecteninternet.com/discount/Jupiter35"
           (normalize-href "connecteninternet.com/discount/Jupiter35"))))

  (testing "leaves explicit schemes alone"
    (is (= "https://example.com/x" (normalize-href "https://example.com/x")))
    (is (= "http://example.com/x" (normalize-href "http://example.com/x"))))

  (testing "leaves relative links and anchors alone"
    (is (= "/foo/bar" (normalize-href "/foo/bar")))
    (is (= "#section" (normalize-href "#section"))))

  (testing "leaves strings without dots alone"
    (is (= "a" (normalize-href "a"))))

  (testing "a bare domain and its https twin collapse to one link"
    (is (= [{:href "https://connecteninternet.com/discount/Jupiter35" :title "t"}]
           (dedup-links [{:href "https://connecteninternet.com/discount/Jupiter35" :title "t"}
                         {:href "connecteninternet.com/discount/Jupiter35" :title "t"}])))))

(deftest dedupe-tags-test
  (testing "drops case-insensitive duplicates, first casing wins"
    (is (= ["Linux Podcast" "NixOS"]
           (dedupe-tags ["Linux Podcast" "NixOS" "Linux podcast"]))))

  (testing "exact duplicates still collapse"
    (is (= ["a"] (dedupe-tags ["a" "a"]))))

  (testing "order is preserved"
    (is (= ["b" "a"] (dedupe-tags ["b" "a" "B"]))))

  (testing "empty in, empty out"
    (is (= [] (dedupe-tags [])))))

(deftest extract-sponsors-test
  ;; The doc grew a structured SPONSORS block (2026-10-28). The site renders
  ;; the sponsor title as a heading, so a blurb that repeats the name must be
  ;; trimmed, and the **Code:** paragraph must be dropped structurally.
  (let [md (str "#### SPONSORS\n\n"
                "- **[Nebula](https://defined.net/unplugged)**  \n"
                "  Meet Managed Nebula from Defined Networking.\n\n"
                "- **[Incogni](https://incogni.com/unplugged)**  \n"
                "  Incogni: They can't harm you if they can't find you! "
                "Use code **UNPLUGGED** for 60% off.\n\n"
                "  **Code:** `UNPLUGGED`\n")
        tmp (java.io.File/createTempFile "sponsors" ".md")]
    (try
      (spit tmp md)
      (let [sponsors (lh/extract-sponsors (lh/fetch-markdown (.getAbsolutePath tmp)))]
        (testing "one entry per sponsor"
          (is (= 2 (count sponsors))))
        (testing "name, url and code are read"
          (is (= ["Nebula" "Incogni"] (map :name (take 2 sponsors))))
          (is (= "https://defined.net/unplugged" (:url (first sponsors))))
          (is (= "UNPLUGGED" (:code (second sponsors)))))
        (testing "a leading sponsor name is stripped from the blurb"
          (is (str/starts-with? (:blurb (second sponsors)) "They can't harm you")))
        (testing "the code word inside the blurb is preserved"
          (is (str/includes? (:blurb (second sponsors)) "Use code UNPLUGGED")))
        (testing "the Code: paragraph is not part of the blurb"
          (is (not (str/includes? (:blurb (second sponsors)) "Code:")))))
      (finally (.delete tmp)))))

(deftest extract-sponsors-edge-cases-test
  ;; Guards exact regressions found in review on 2026-10-28.
  (let [parse (fn [md]
                (let [f (java.io.File/createTempFile "sponsor-probe" ".md")]
                  (try (spit f md)
                       (lh/extract-sponsors (lh/fetch-markdown (.getAbsolutePath f)))
                       (finally (.delete f)))))]
    (testing "inline code in the blurb survives; the Code line supplies :code"
      (let [[s] (parse (str "#### SPONSORS\n\n- **[Acme](https://acme.test)**  \n"
                            "  Save with `SAVE60` today.\n\n  **Code:** `UNPLUGGED`\n"))]
        (is (= "Save with SAVE60 today." (:blurb s)))
        (is (= "UNPLUGGED" (:code s)))))
    (testing "a sponsor name that prefixes a word does not mangle the blurb"
      (let [[s] (parse (str "#### SPONSORS\n\n- **[Secure](https://secure.test)**  \n"
                            "  Securely connect to your files from anywhere.\n"))]
        (is (= "Securely connect to your files from anywhere." (:blurb s)))))
    (testing "no space is inserted before punctuation"
      (let [[s] (parse (str "#### SPONSORS\n\n- **[Acme](https://acme.test)**  \n"
                            "  Use code **UNPLUGGED**.\n"))]
        (is (= "Use code UNPLUGGED." (:blurb s)))))
    (testing "an item whose name is not a link is skipped"
      (is (empty? (parse (str "#### SPONSORS\n\n- **Acme**  \n  No link here.\n")))))
    (testing "no SPONSORS block yields []"
      (is (empty? (parse "# Just a doc\n\n+ nothing\n"))))))

(deftest link-scope-test
  ;; `### Show Links` is the public/members line: links above it are
  ;; members-only material, so the regular feed starts below it. :all is the
  ;; members/bootleg variant that keeps everything after the sponsors block.
  (let [md (str "#### SPONSORS\n\n- **[Acme](https://acme.test)**  \n  blurb\n\n"
                "### [Members only news](https://news.test/story)\n\n"
                "### Show Links\n\n"
                "+ [Public link](https://public.test/one)\n")
        tmp (java.io.File/createTempFile "scope-probe" ".md")]
    (try
      (spit tmp md)
      (let [z (lh/fetch-markdown (.getAbsolutePath tmp))
            show (set (map :href (lh/extract-links z)))
            all (set (map :href (lh/extract-links z {:scope :all})))]
        (testing "default scope stops at Show Links"
          (is (contains? show "https://public.test/one"))
          (is (not (contains? show "https://news.test/story")))
          (is (not (contains? show "https://acme.test")) "sponsors are never show links"))
        (testing ":all includes above-the-line links"
          (is (contains? all "https://news.test/story"))
          (is (contains? all "https://public.test/one"))
          (is (not (contains? all "https://acme.test")) "still not sponsor links"))
        (testing ":all is a superset"
          (is (every? all show))))
      (finally (.delete tmp)))))

(deftest strip-tracking-params-test
  (testing "tracking params go, real params stay"
    (is (= "https://x.test/a?id=3"
           (lh/strip-tracking-params "https://x.test/a?utm_source=chatgpt.com&id=3"))))
  (testing "the fragment survives"
    (is (= "https://x.test/a#frag"
           (lh/strip-tracking-params "https://x.test/a?utm_source=x#frag"))))
  (testing "an all-tracking query disappears entirely"
    (is (= "https://x.test/a"
           (lh/strip-tracking-params "https://x.test/a?fbclid=1&utm_medium=z"))))
  (testing "a clean url is untouched"
    (is (= "https://x.test/a?q=hi" (lh/strip-tracking-params "https://x.test/a?q=hi"))))
  (testing "relative paths and anchors are left alone"
    (is (= "/a?utm_source=x" (lh/strip-tracking-params "/a?utm_source=x")))
    (is (= "#utm_source=x" (lh/strip-tracking-params "#utm_source=x"))))
  (testing "normalize-href strips after adding the scheme"
    (is (= "https://x.test/a?id=1" (lh/normalize-href "x.test/a?utm_source=q&id=1")))))

(deftest internal-links-filtered-test
  ;; Stream Tease points at the working docs; it is scaffolding, not a show
  ;; link, and must not reach any feed - including the members variant.
  (let [md (str "#### SPONSORS\n\n- **[Acme](https://acme.test)**  \n  blurb\n\n"
                "### Show Links\n\n"
                "+ [Stream Tease - HedgeDoc](https://h.docs.lol/SD46XolSS2iqg?both)\n"
                "+ [Real link](https://real.test/story)\n")
        tmp (java.io.File/createTempFile "internal-probe" ".md")]
    (try
      (spit tmp md)
      (let [all (lh/extract-links (lh/fetch-markdown (.getAbsolutePath tmp)) {:scope :all})
            hrefs (set (map :href all))]
        (is (contains? hrefs "https://real.test/story"))
        (is (not (contains? hrefs "https://h.docs.lol/SD46XolSS2iqg?both"))))
      (finally (.delete tmp)))))
