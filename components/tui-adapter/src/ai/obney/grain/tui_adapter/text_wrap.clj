(ns ai.obney.grain.tui-adapter.text-wrap
  "Terminal text wrapping helpers shared by preferred-size and render paths."
  (:require [clojure.string :as string]))

(defn- hard-wrap
  [width s]
  (if (or (zero? width) (empty? s))
    [""]
    (mapv #(apply str %) (partition-all width s))))

(defn- wrap-words
  [width s]
  (loop [words (seq (string/split s #"\s+"))
         line  ""
         out   []]
    (cond
      (nil? words)
      (if (empty? line) out (conj out line))

      (empty? line)
      (let [word (first words)]
        (if (> (count word) width)
          (recur (next words) "" (into out (hard-wrap width word)))
          (recur (next words) word out)))

      :else
      (let [word (first words)
            candidate (str line " " word)]
        (if (<= (count candidate) width)
          (recur (next words) candidate out)
          (if (> (count word) width)
            (recur (next words) "" (into (conj out line) (hard-wrap width word)))
            (recur (next words) word (conj out line))))))))

(defn visual-lines
  "Return terminal visual lines for `s` at `width`.

   Wraps on word boundaries where possible. Tokens longer than `width`
   fall back to hard wrapping so every rendered row fits the terminal."
  [width s]
  (->> (string/split (or s "") #"\n" -1)
       (mapcat (fn [line]
                 (if (string/blank? line)
                   [""]
                   (wrap-words width line))))
       vec))

(defn visual-line-count
  [width s]
  (max 1 (count (visual-lines width s))))
