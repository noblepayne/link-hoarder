(ns noblepayne.test-runner
  "Test runner that provides an :exec-fn for clojure -X:test."
  (:require [clojure.test :as t]))

(def test-namespaces
  "All test namespaces to run."
  '[noblepayne.link-hoarder-test
    noblepayne.fireside-test
    noblepayne.json-test])

(defn run-tests
  "Run all test namespaces. Accepts options:
   - :verbose true/false
   - :reporter fn
   - :exclude #{ns-symbols}
   - :on-failure! fn
   - :on-success! fn"
  [& {:keys [reporter]
      :as opts}]
  (let [opts (dissoc opts :reporter)]
    (doseq [ns test-namespaces]
      (require ns)
      (println "Testing" ns)
      (if (fn? reporter)
        (apply t/run-tests ns (concat (mapcat identity (seq opts))) [:reporter reporter])
        (apply t/run-tests ns (mapcat identity (seq opts)))))))