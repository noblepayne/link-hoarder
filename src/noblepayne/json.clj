(ns noblepayne.json
  "Minimal strict JSON reader.

  Vendored deliberately: this environment cannot resolve new Maven deps
  (any deps.edn change NPEs inside tools.deps, including via the clj-nix
  lock path `nix run .#deps-lock`), and regex-parsing JSON payloads is
  exactly the silently-empty failure this codebase keeps hitting. This
  reader throws ex-info on any malformed input instead of returning
  partial data.

  Supports what Fireside and S3 hand back: objects with string keys,
  arrays, strings (with escapes including \\uXXXX), numbers, booleans,
  null. Anything else is a throw, not a guess.")

(defn- fail [s pos msg]
  (throw (ex-info (str "Invalid JSON: " msg)
                  {:at pos
                   :around (subs s (max 0 (- pos 20)) (min (count s) (+ pos 20)))})))

(defn- skip-ws [^String s pos]
  (let [n (count s)]
    (loop [i pos]
      (if (and (< i n) (Character/isWhitespace (.charAt s i)))
        (recur (inc i))
        i))))

(declare read-value)

(defn- read-string-literal [^String s pos]
  ;; pos points at the opening quote
  (let [n (count s)
        sb (StringBuilder.)]
    (loop [i (inc pos)]
      (when (>= i n)
        (fail s i "unterminated string"))
      (let [c (.charAt s i)]
        (cond
          (= c \")
          [(.toString sb) (inc i)]

          (= c \\)
          (do (when (>= (inc i) n)
                (fail s i "dangling backslash"))
              (let [e (.charAt s (inc i))]
                (case e
                  \" (.append sb \")
                  \\ (.append sb \\)
                  \/ (.append sb \/)
                  \b (.append sb \backspace)
                  \f (.append sb \formfeed)
                  \n (.append sb \newline)
                  \r (.append sb \return)
                  \t (.append sb \tab)
                  \u (do (when (> (+ i 6) n)
                             (fail s i "truncated \\u escape"))
                           (let [hex (subs s (+ i 2) (+ i 6))]
                             (try (.append sb (char (Integer/parseInt hex 16)))
                                  (catch NumberFormatException _
                                    (fail s i (str "bad \\u escape: " hex))))))
                  (fail s i (str "bad escape: \\" e)))
                (recur (if (= e \u) (+ i 6) (+ i 2)))))

          :else
          (do (.append sb c)
              (recur (inc i))))))))

(defn- read-literal [^String s pos literal value]
  (let [end (+ pos (count literal))]
    (if (= literal (subs s pos (min end (count s))))
      [value end]
      (fail s pos (str "expected " literal)))))

(defn- read-number [^String s pos]
  (let [m (re-matcher #"-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?" (subs s pos))]
    (if (.lookingAt m)
      (let [tok (.group m)
            end (+ pos (count tok))]
        [(if (re-find #"[\.eE]" tok)
           (try (Double/parseDouble tok)
                (catch NumberFormatException _ (fail s pos (str "bad number: " tok))))
           (try (Long/parseLong tok)
                (catch NumberFormatException _ (fail s pos (str "bad number: " tok)))))
         end])
      (fail s pos "expected value"))))

(defn- read-array [^String s pos]
  ;; pos points at [
  (loop [i (skip-ws s (inc pos)) acc []]
    (if (and (< i (count s)) (= (.charAt s i) \]))
      [acc (inc i)]
      (let [[v i2] (read-value s i)
            i3 (skip-ws s i2)]
        (when (>= i3 (count s))
          (fail s i3 "unterminated array"))
        (let [c (.charAt s i3)]
          (cond
            (= c \,) (recur (skip-ws s (inc i3)) (conj acc v))
            (= c \]) [(conj acc v) (inc i3)]
            :else (fail s i3 "expected , or ] in array")))))))

(defn- read-object [^String s pos]
  ;; pos points at {
  (loop [i (skip-ws s (inc pos)) acc {}]
    (if (and (< i (count s)) (= (.charAt s i) \}))
      [acc (inc i)]
      (let [i (skip-ws s i)]
        (when (or (>= i (count s)) (not= (.charAt s i) \"))
          (fail s i "expected string key in object"))
        (let [[k i2] (read-string-literal s i)
              i3 (skip-ws s i2)]
          (when (or (>= i3 (count s)) (not= (.charAt s i3) \:))
            (fail s i3 "expected : in object"))
          (let [[v i4] (read-value s (skip-ws s (inc i3)))
                i5 (skip-ws s i4)]
            (when (>= i5 (count s))
              (fail s i5 "unterminated object"))
            (let [c (.charAt s i5)]
              (cond
                (= c \,) (recur (skip-ws s (inc i5)) (assoc acc k v))
                (= c \}) [(assoc acc k v) (inc i5)]
                :else (fail s i5 "expected , or } in object")))))))))

(defn- read-value [^String s pos]
  (let [i (skip-ws s pos)]
    (when (>= i (count s))
      (fail s i "unexpected end of input"))
    (let [c (.charAt s i)]
      (cond
        (= c \") (read-string-literal s i)
        (= c \{) (read-object s i)
        (= c \[) (read-array s i)
        (= c \t) (read-literal s i "true" true)
        (= c \f) (read-literal s i "false" false)
        (= c \n) (read-literal s i "null" nil)
        (or (= c \-) (Character/isDigit c)) (read-number s i)
        :else (fail s i (str "unexpected character: " c))))))

(defn read-str
  "Parse a JSON string. Returns Clojure data (maps with string keys).
  Throws ex-info on malformed input or trailing garbage."
  [s]
  (when-not (string? s)
    (throw (ex-info "read-str needs a string" {:got (type s)})))
  (let [[v pos] (read-value s 0)
        rest-pos (skip-ws s pos)]
    (when (< rest-pos (count s))
      (fail s rest-pos "trailing garbage after JSON value"))
    v))
