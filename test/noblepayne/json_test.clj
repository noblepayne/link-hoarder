(ns noblepayne.json-test
  (:require [clojure.test :refer [deftest is testing]]
            [noblepayne.json :as json]))

(deftest read-str-test
  (testing "flat string maps (the S3 presign shape)"
    (is (= {"key" "a/b/c.mp3" "policy" "xyz"}
           (json/read-str "{\"key\": \"a/b/c.mp3\", \"policy\": \"xyz\"}"))))

  (testing "status payload shape"
    (let [m (json/read-str "{\"processing\": false, \"url\": \"https://x/y.mp3\", \"n\": 3}")]
      (is (false? (get m "processing")))
      (is (= "https://x/y.mp3" (get m "url")))
      (is (= 3 (get m "n")))))

  (testing "booleans, null, nesting, escapes"
    (is (= {"a" [1 2.5 true false nil {"b" "x\ny\"q\""}]}
           (json/read-str "{\"a\": [1, 2.5, true, false, null, {\"b\": \"x\\ny\\\"q\\\"\"}]}"))))

  (testing "unicode escapes"
    (is (= {"t" "Café"} (json/read-str "{\"t\": \"Caf\\u00e9\"}"))))

  (testing "throws on malformed input instead of returning partial data"
    (is (thrown? clojure.lang.ExceptionInfo (json/read-str "{\"a\": }")))
    (is (thrown? clojure.lang.ExceptionInfo (json/read-str "{\"a\": \"x")))
    (is (thrown? clojure.lang.ExceptionInfo (json/read-str "[1, 2")))
    (is (thrown? clojure.lang.ExceptionInfo (json/read-str "{\"a\": 1} garbage")))
    (is (thrown? clojure.lang.ExceptionInfo (json/read-str "")))
    (is (thrown? clojure.lang.ExceptionInfo (json/read-str nil)))))
