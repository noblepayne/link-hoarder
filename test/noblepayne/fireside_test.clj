(ns noblepayne.fireside-test
  (:require [clojure.test :refer [deftest testing is]]
            [noblepayne.fireside :as f]))

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
    (let [html "<span class=\"accordion-heading__title\">Campaign A</span>
<span class=\"accordion-heading__metadata\"><i class=\"fas fa-clock\" aria-hidden=\"true\"></i>
      51 seconds</span>
<span class=\"accordion-heading__subtitle\">Sponsor One</span>

<span class=\"accordion-heading__title\">Campaign B</span>
<span class=\"accordion-heading__metadata\"><i class=\"fas fa-clock\" aria-hidden=\"true\"></i>
      2 minutes 30 seconds</span>
<span class=\"accordion-heading__subtitle\">Sponsor Two</span>"
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
    (let [html "<span class=\"accordion-heading__title\">Test &amp; More</span>
<span class=\"accordion-heading__metadata\"><i class=\"fas fa-clock\" aria-hidden=\"true\"></i>
      10 seconds</span>
<span class=\"accordion-heading__subtitle\">Sponsor &lt;Name&gt;</span>"
          results (f/extract-sponsorships html)]
      (is (= "Test & More" (:campaign (first results))))
      (is (= "Sponsor <Name>" (:sponsor (first results)))))))
