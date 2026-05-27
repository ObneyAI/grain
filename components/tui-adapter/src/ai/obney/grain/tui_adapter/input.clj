(ns ai.obney.grain.tui-adapter.input
  "ANSI byte → logical key event parser.

   Logical event shape:
     {:type :key   :key   \"a\" | \"<enter>\" | \"C-a\" | ...}
     {:type :paste :string \"...\"}
     {:type :resize :size [w h]}
     {:type :focus  :in? bool}

   The parser is stateful (it holds bytes until a complete escape sequence
   arrives) and is driven by a `feed` function that the transport layer
   pumps. It does not own the byte stream, only the parser state.

   We don't depend on JLine here — JLine owns the raw `Terminal` and
   pumps bytes into us via `feed`. That keeps this namespace pure-ish
   (parser state is the only state) and lets tests drive bytes directly."
  (:require [clojure.string :as str]))

;; ─────────────────────────────────────────────────────────────────────
;; Constants
;; ─────────────────────────────────────────────────────────────────────

(def ^:private ESC 0x1B)
(def ^:private CR  0x0D)
(def ^:private LF  0x0A)
(def ^:private TAB 0x09)
(def ^:private BS  0x7F) ; DEL — terminals send DEL for backspace by default
(def ^:private BS2 0x08)

(def ^:private bracketed-paste-start "200~")
(def ^:private bracketed-paste-end   "201~")

;; ─────────────────────────────────────────────────────────────────────
;; Parser state
;; ─────────────────────────────────────────────────────────────────────

(defn make-parser
  "Construct an empty parser state. Pump bytes into it via `feed`."
  []
  {:buf       []          ; vec of pending bytes (ints 0-255)
   :paste-buf nil         ; collecting string when in bracketed-paste mode
   })

;; ─────────────────────────────────────────────────────────────────────
;; Helpers
;; ─────────────────────────────────────────────────────────────────────

(defn- ascii-key
  "Map a single non-ESC byte to a logical key event, or nil if it's part
   of a multi-byte UTF-8 sequence (handled separately)."
  [b]
  (cond
    (= b CR)        {:type :key :key "<enter>"}
    (= b LF)        {:type :key :key "<enter>"}
    (= b TAB)       {:type :key :key "<tab>"}
    (or (= b BS) (= b BS2)) {:type :key :key "<backspace>"}
    (and (>= b 1) (<= b 26) (not (#{CR LF TAB} b)))
    {:type :key :key (str "C-" (char (+ (dec b) (int \a))))}
    (and (>= b 32) (<= b 126))
    {:type :key :key (str (char b))}
    :else nil))

(defn- utf8-leading?
  "Number of continuation bytes following this leading byte, or nil if
   not a UTF-8 leading byte."
  [b]
  (cond
    (zero? (bit-and b 0x80)) nil       ; ASCII, handled by ascii-key
    (= 0xC0 (bit-and b 0xE0)) 1
    (= 0xE0 (bit-and b 0xF0)) 2
    (= 0xF0 (bit-and b 0xF8)) 3
    :else nil))

(defn- bytes->string
  "Decode a vec of unsigned ints (0-255) as UTF-8."
  [bytes]
  (String. (byte-array (map #(unchecked-byte (bit-and % 0xFF)) bytes)) "UTF-8"))

;; ─────────────────────────────────────────────────────────────────────
;; CSI sequence dispatch
;; ─────────────────────────────────────────────────────────────────────

(def ^:private csi-arrow
  {0x41 "<up>" 0x42 "<down>" 0x43 "<right>" 0x44 "<left>"
   0x46 "<end>" 0x48 "<home>"})

(defn- parse-csi
  "Parse a CSI sequence starting after ESC[. Returns
   {:event ev :consumed n} when complete, or {:incomplete? true} when more
   bytes are needed, or {:event nil :consumed n} when unknown (drop)."
  [buf start]
  ;; CSI body = optional params (0-9 ; ?), then a final byte (0x40-0x7E).
  ;; Special case bracketed paste: ESC [ 200 ~ ... ESC [ 201 ~
  (let [n (count buf)]
    (loop [i start]
      (cond
        (>= i n)
        {:incomplete? true}

        :else
        (let [b (nth buf i)]
          (cond
            ;; Final byte ends the sequence.
            (and (>= b 0x40) (<= b 0x7E))
            (let [body (apply str (map char (subvec buf start i)))]
              (cond
                ;; Bracketed paste start
                (and (= b (int \~)) (= "200" body))
                {:event {:type :paste-start} :consumed (- (inc i) start)}
                (and (= b (int \~)) (= "201" body))
                {:event {:type :paste-end}   :consumed (- (inc i) start)}
                ;; Arrow / nav
                (csi-arrow b)
                {:event {:type :key :key (csi-arrow b)} :consumed (- (inc i) start)}
                ;; Backtab / Shift-Tab: most terminals emit ESC [ Z.
                (and (= b (int \Z)) (= "" body))
                {:event {:type :key :key "<backtab>"} :consumed (- (inc i) start)}
                ;; Function keys F1-F4 via O-prefix variant handled separately
                :else
                {:event nil :consumed (- (inc i) start)}))

            :else
            (recur (inc i))))))))

;; ─────────────────────────────────────────────────────────────────────
;; SS3 sequence (ESC O X) — F1-F4 on some terminals
;; ─────────────────────────────────────────────────────────────────────

(def ^:private ss3-fn
  {0x50 "<f1>" 0x51 "<f2>" 0x52 "<f3>" 0x53 "<f4>"})

;; ─────────────────────────────────────────────────────────────────────
;; Top-level feed loop
;; ─────────────────────────────────────────────────────────────────────

(defn parse-buf
  "Walk the parser state's buffer, emitting events until either the
   buffer is empty or the next byte sequence is incomplete. Returns
   `[events new-parser-state]`."
  [parser]
  (loop [buf    (:buf parser)
         paste  (:paste-buf parser)
         events (transient [])]
    (let [n (count buf)]
      (cond
        (zero? n)
        [(persistent! events) (assoc parser :buf [] :paste-buf paste)]

        ;; Inside bracketed paste — collect characters until ESC[201~
        (some? paste)
        (let [b (first buf)]
          (cond
            (and (= b ESC) (>= n 6)
                 (= [0x5B 0x32 0x30 0x31 0x7E] (subvec buf 1 6)))
            ;; Paste end — emit accumulated paste event.
            (recur (subvec buf 6) nil
                   (conj! events {:type :paste :string paste}))

            (and (= b ESC) (< n 6))
            ;; Possibly start of paste-end; wait for more bytes.
            [(persistent! events) (assoc parser :buf buf :paste-buf paste)]

            :else
            (recur (subvec buf 1)
                   (str paste (char b))
                   events)))

        :else
        (let [b (first buf)]
          (cond
            ;; ESC introduces a sequence
            (= b ESC)
            (cond
              ;; Lone ESC — wait for more (possibly Alt-modified key)
              (= n 1)
              [(persistent! events) (assoc parser :buf buf :paste-buf paste)]

              ;; ESC [ ...
              (= 0x5B (nth buf 1))
              (let [{:keys [event consumed incomplete?]} (parse-csi buf 2)]
                (cond
                  incomplete?
                  [(persistent! events) (assoc parser :buf buf :paste-buf paste)]

                  ;; Bracketed paste start — switch into paste mode.
                  (= :paste-start (:type event))
                  (recur (subvec buf (+ 2 consumed)) "" events)

                  :else
                  (recur (subvec buf (+ 2 consumed))
                         paste
                         (if event (conj! events event) events))))

              ;; ESC O X — SS3 function keys
              (and (= 0x4F (nth buf 1)) (>= n 3))
              (if-let [k (ss3-fn (nth buf 2))]
                (recur (subvec buf 3) paste
                       (conj! events {:type :key :key k}))
                (recur (subvec buf 3) paste events))

              ;; ESC <printable> — Meta/Alt modifier
              (and (>= (nth buf 1) 32) (<= (nth buf 1) 126))
              (recur (subvec buf 2) paste
                     (conj! events {:type :key :key (str "M-" (char (nth buf 1)))}))

              ;; Lone ESC followed by something we don't recognise — emit ESC.
              :else
              (recur (subvec buf 2) paste
                     (conj! events {:type :key :key "<esc>"})))

            ;; UTF-8 multi-byte
            (utf8-leading? b)
            (let [need (utf8-leading? b)]
              (if (>= (count buf) (inc need))
                (let [bytes (subvec buf 0 (inc need))
                      s     (bytes->string bytes)]
                  (recur (subvec buf (inc need)) paste
                         (conj! events {:type :key :key s})))
                ;; Incomplete UTF-8 sequence — wait.
                [(persistent! events) (assoc parser :buf buf :paste-buf paste)]))

            ;; ASCII / control
            :else
            (let [ev (ascii-key b)]
              (recur (subvec buf 1) paste
                     (if ev (conj! events ev) events)))))))))

(defn feed
  "Feed `bytes` (a sequence of unsigned ints 0-255) into the parser.
   Returns `[events new-parser-state]`."
  [parser bytes]
  (-> parser
      (update :buf into bytes)
      parse-buf))

;; ─────────────────────────────────────────────────────────────────────
;; Convenience: feed a Java byte array (signed bytes in -128..127)
;; ─────────────────────────────────────────────────────────────────────

(defn feed-bytes
  "Feed a Java byte[] (or seq of signed bytes). Converts to unsigned ints first."
  [parser ^bytes ba]
  (feed parser (map (fn [^Byte b] (bit-and (int b) 0xFF)) ba)))

;; ─────────────────────────────────────────────────────────────────────
;; Resize / focus events — synthesized by the transport, not from the byte stream.
;; ─────────────────────────────────────────────────────────────────────

(defn resize-event [w h] {:type :resize :size [w h]})
(defn focus-event  [in?] {:type :focus  :in? in?})
