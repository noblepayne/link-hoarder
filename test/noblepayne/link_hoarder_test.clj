(ns noblepayne.link-hoarder-test
  (:require [clojure.test :refer [deftest is testing]]
            [noblepayne.link-hoarder :refer [dedup-links
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
