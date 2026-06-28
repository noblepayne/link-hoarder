(ns noblepayne.link-hoarder-test
  (:require [clojure.test :refer [deftest is testing]]
            [noblepayne.link-hoarder :refer [dedup-links]]))

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
