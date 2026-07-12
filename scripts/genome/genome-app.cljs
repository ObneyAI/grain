;; genome-app.cljs — the interactive layer, interpreted in-browser by Scittle (SCI).
;; No build step. Reads window.GENOME (injected by genome.bb) and renders the board.
(ns genome.app)

;; ---------- data ----------
(def doc js/document)
(defn gid [id] (.getElementById doc id))
(defn ce [t] (.createElement doc t))
(def svgns "http://www.w3.org/2000/svg")
(defn ces [t] (.createElementNS doc svgns t))

(def G          (js->clj (.-GENOME js/window) :keywordize-keys true))
(def clusters-js (.-clusters (.-GENOME js/window)))   ; raw, order-preserving (legend)
(def clusters   (:clusters G))
(def cols       (:cols G))
(def rows       (:rows G))
(def zones      (:zones G))
(def copy       (:copy G))
(def macro-groups (:macroGroups G))
(def protocols  (:protocols G))

(def all-ids (set (map :id (:nodes G))))
(def dependents
  (reduce (fn [m n]
            (reduce (fn [m d] (if (all-ids d) (update m d (fnil conj []) (:id n)) m))
                    m (:deps n)))
          {} (:nodes G)))
(def nodes
  (mapv (fn [n]
          (let [reald   (filterv all-ids (:deps n))
                deps-of (get dependents (:id n) [])]
            (assoc n :x (nth cols (:col n)) :y (nth rows (:row n))
                     :macros (or (:macros n) [])
                     :dependents deps-of :fanin (count deps-of) :fanout (count reald))))
        (:nodes G)))
(def by-id (into {} (map (juxt :id identity)) nodes))
(def edge-count (reduce + (map :fanout nodes)))

(defn cvar [c]
  (-> (js/getComputedStyle (.-documentElement doc))
      (.getPropertyValue (get-in clusters [(keyword c) :v]))
      (.trim)))

;; ---------- HUD ----------
(set! (.-textContent (gid "hud-kick"))  (:kick copy))
(set! (.-innerHTML   (gid "hud-title")) (:title copy))
(set! (.-innerHTML   (gid "hud-lede"))  (:lede copy))
(set! (.-textContent (gid "s-comp")) (count nodes))
(set! (.-textContent (gid "s-edge")) edge-count)
(set! (.-textContent (gid "s-mac"))  (count (filter #(seq (:macros %)) nodes)))
(set! (.-textContent (gid "s-pro"))  (count (filter :proto nodes)))

(declare highlight clear-highlight select deselect open-inspector build-catalog
         toggle-cluster frame-rect fit center-on lens-set paint-edge zoom-at render-tf
         set-lens comp-chip)

(def world (gid "world"))
(def insp  (gid "inspector"))
(def vp    (gid "viewport"))
(def node-els (atom {}))
(def selected (atom nil))
(def edge-els (atom []))

;; ---------- zones ----------
(doseq [z-spec zones]
  (let [[t x y] z-spec, z (ce "div")]
    (set! (.-className z) "zone")
    (set! (.-textContent z) t)
    (set! (.. z -style -left) (str x "px"))
    (set! (.. z -style -top)  (str y "px"))
    (.appendChild world z)))

;; ---------- nodes ----------
(doseq [n nodes]
  (let [el (ce "div")
        id-fs (.toFixed (+ 12.5 (* (min (:fanin n) 12) 0.34)) 1)
        glyph (cond (seq (:macros n)) "ƒ" (or (:proto n) (:impl n)) "◇" :else "")]
    (set! (.-className el) "node")
    (set! (.. el -dataset -id) (:id n))
    (set! (.. el -dataset -fanin) (str (:fanin n)))
    (cond (seq (:macros n)) (.add (.-classList el) "has-macro")
          (:proto n)        (.add (.-classList el) "has-proto")
          (:impl n)         (.add (.-classList el) "has-impl"))
    (set! (.. el -style -left) (str (:x n) "px"))
    (set! (.. el -style -top)  (str (:y n) "px"))
    (.setProperty (.-style el) "--accent" (cvar (:cluster n)))
    (.setProperty (.-style el) "--glow" (str (min 46 (+ 12 (* (:fanin n) 3.4))) "px"))
    (set! (.. el -style -zIndex) (+ 5 (:fanin n)))
    (set! (.-innerHTML el)
          (str "<div class='fanbadge'>" (:fanin n) "</div>"
               (when (seq glyph) (str "<div class='typeglyph'>" glyph "</div>"))
               "<div class='nid' style='font-size:" id-fs "px'>" (:id n) "</div>"
               "<div class='nmeta'><span class='dot'></span>" (:loc n)
               " loc<span class='role'>" (:role n) "</span></div>"))
    (.addEventListener el "click"      (fn [e] (.stopPropagation e) (select (:id n))))
    (.addEventListener el "mouseenter" (fn [_] (when-not @selected (highlight (:id n) true))))
    (.addEventListener el "mouseleave" (fn [_] (when-not @selected (clear-highlight))))
    (.appendChild world el)
    (swap! node-els assoc (:id n) el)))

;; ---------- edges (Manhattan PCB traces) ----------
(defn pcb-path [x1 y1 x2 y2 track]
  (let [adx (js/Math.abs (- x2 x1)) ady (js/Math.abs (- y2 y1)) ch 9]
    (cond
      (< adx 3) {:d (str "M" x1 "," y1 " L" x2 "," y2) :bends []}
      (< ady 3) {:d (str "M" x1 "," y1 " L" x2 "," y2) :bends []}
      :else
      (let [sx (js/Math.sign (- x2 x1)) sy (js/Math.sign (- y2 y1))
            mid0 (+ (/ (+ y1 y2) 2) (* (- (mod track 6) 3) 9))
            lo (+ (min y1 y2) ch 2) hi (- (max y1 y2) ch 2)
            m (max lo (min hi mid0))
            f (fn [v] (.toFixed v 1))]
        {:d (str "M" x1 "," y1
                 " L" x1 "," (f (- m (* sy ch)))
                 " L" (f (+ x1 (* sx ch))) "," (f m)
                 " L" (f (- x2 (* sx ch))) "," (f m)
                 " L" x2 "," (f (+ m (* sy ch)))
                 " L" x2 "," y2)
         :bends [[x1 m] [x2 m]]}))))

(def svg (gid "edges"))
(def via-g (ces "g"))
(doseq [n nodes, d (:deps n) :let [t (by-id d)] :when t]
  (let [res (pcb-path (:x n) (:y n) (:x t) (:y t) (count @edge-els))
        p (ces "path")
        vias (mapv (fn [b]
                     (let [c (ces "circle")]
                       (.setAttribute c "cx" (.toFixed (nth b 0) 1))
                       (.setAttribute c "cy" (.toFixed (nth b 1) 1))
                       (.setAttribute c "r" "3")
                       (.setAttribute c "class" "via")
                       (.appendChild via-g c) c))
                   (:bends res))]
    (.setAttribute p "d" (:d res))
    (set! (.. p -dataset -from) (:id n))
    (set! (.. p -dataset -to) (:id t))
    (set! (.-_vias p) (clj->js vias))
    (.appendChild svg p)
    (swap! edge-els conj p)))
(doseq [n nodes]
  (let [pad (ces "circle")]
    (.setAttribute pad "cx" (:x n)) (.setAttribute pad "cy" (:y n))
    (.setAttribute pad "r" "5") (.setAttribute pad "class" "pad")
    (.appendChild via-g pad)))
(.appendChild svg via-g)

(defn paint-edge [p state color]
  (let [st (.-style p) cl (.-classList p)]
    (case state
      "hot" (do (set! (.-opacity st) 1)     (.add cl "hot")               (set! (.-color st) (or color "")))
      "dim" (do (set! (.-opacity st) 0.045) (.remove cl "hot" "dashsel")  (set! (.-color st) ""))
      (do (set! (.-opacity st) "") (.remove cl "hot" "dashsel") (set! (.-color st) "")))
    (doseq [c (array-seq (or (.-_vias p) #js []))]
      (set! (.. c -style -opacity) (case state "dim" 0.05 "hot" 1 ""))
      (set! (.. c -style -fill) (if (= state "hot") (or color "") ""))
      (.toggle (.-classList c) "hot" (= state "hot")))))

;; ---------- legend ----------
(def legend (gid "legend"))
(set! (.-innerHTML legend) "<div class='lt'>clusters</div>")
(doseq [pair (js/Object.entries clusters-js)]
  (let [k (aget pair 0)
        c (js->clj (aget pair 1) :keywordize-keys true)
        cnt (count (filter #(= (:cluster %) k) nodes))
        row (ce "div")]
    (set! (.-className row) "leg")
    (set! (.. row -dataset -cluster) k)
    (set! (.-innerHTML row)
          (str "<span class='sw' style='--accent:var(" (:v c) ")'></span>"
               "<span>" (:name c) "</span><span class='cnt'>" cnt "</span>"))
    (set! (.-title row) (:desc c))
    (.addEventListener row "click" (fn [_] (toggle-cluster k row)))
    (.appendChild legend row)))

;; ---------- highlight / lens / select ----------
(defn neighbors-of [id]
  (let [n (by-id id)]
    (into #{id} (concat (filter all-ids (:deps n)) (:dependents n)))))
(defn highlight [id soft]
  (let [s (neighbors-of id) accent (cvar (:cluster (by-id id)))]
    (doseq [m nodes]
      (let [cl (.-classList (get @node-els (:id m)))]
        (.toggle cl "dim" (not (contains? s (:id m))))
        (.toggle cl "neighbor" (and (contains? s (:id m)) (not= (:id m) id)))))
    (doseq [p @edge-els]
      (let [hot (or (= (.. p -dataset -from) id) (= (.. p -dataset -to) id))]
        (paint-edge p (if hot "hot" "dim") accent)
        (when (and hot (not soft)) (.add (.-classList p) "dashsel"))))))

(def current-lens (atom "structure"))
(defn lens-set [mode]
  (set (for [n nodes :when (or (and (= mode "macros") (seq (:macros n)))
                               (and (= mode "protocols") (or (:proto n) (:impl n))))]
         (:id n))))
(defn clear-highlight []
  (if (= @current-lens "structure")
    (do (doseq [m nodes] (.remove (.-classList (get @node-els (:id m))) "dim" "neighbor"))
        (doseq [p @edge-els] (paint-edge p "normal" nil)))
    (let [lit (lens-set @current-lens)]
      (doseq [m nodes]
        (let [cl (.-classList (get @node-els (:id m)))]
          (.toggle cl "dim" (not (contains? lit (:id m))))
          (.remove cl "neighbor")))
      (doseq [p @edge-els]
        (let [on (and (= @current-lens "protocols")
                      (contains? lit (.. p -dataset -from))
                      (contains? lit (.. p -dataset -to)))]
          (paint-edge p (if on "hot" "dim")
                      (if on (cvar (:cluster (by-id (.. p -dataset -to)))) "")))))))
(defn set-lens [mode]
  (reset! current-lens mode)
  (doseq [b (js/Array.from (.querySelectorAll doc ".lensbtn"))]
    (.toggle (.-classList b) "active" (= (.. b -dataset -lens) mode)))
  (deselect)
  (set! (.. (gid "hint") -style -opacity) "0"))
(doseq [b (js/Array.from (.querySelectorAll doc ".lensbtn"))]
  (.addEventListener b "click" (fn [_] (set-lens (.. b -dataset -lens)))))

(defn select [id]
  (reset! selected id)
  (highlight id false)
  (doseq [pair @node-els] (.remove (.-classList (val pair)) "selected"))
  (.add (.-classList (get @node-els id)) "selected")
  (open-inspector (by-id id))
  (center-on (by-id id)))
(defn deselect []
  (reset! selected nil)
  (clear-highlight)
  (doseq [pair @node-els] (.remove (.-classList (val pair)) "selected"))
  (.remove (.-classList (gid "inspector")) "open"))

;; ---------- inspector ----------
(defn rel-chips [ids]
  (if (empty? ids)
    "<div class='empty'>none</div>"
    (str "<div class='chips'>"
         (apply str (for [id ids]
                      (let [c (if (by-id id) (cvar (:cluster (by-id id))) "var(--ink-faint)")]
                        (str "<span class='chip' data-goto='" id "' style='--chip-c:" c
                             "'><span class='cd'></span>" id "</span>"))))
         "</div>")))
(defn expose-chips [n]
  (let [parts (concat
                (for [m (:macros n)] (str "<span class='expo m'><span>ƒ</span>" m "</span>"))
                (when (:proto n) [(str "<span class='expo p'><span>◇</span>defprotocol " (:proto n) "</span>")])
                (when (:impl n)  [(str "<span class='expo p'><span>◇</span>implements " (:impl n) "</span>")]))]
    (if (seq parts) (str "<div class='exposes'>" (apply str parts) "</div>") "")))
(defn open-inspector [n]
  (let [accent (cvar (:cluster n))]
    (.setProperty (.-style insp) "--accent" accent)
    (set! (.-innerHTML insp)
          (str "<div class='insp-head'>"
               "<div class='insp-close' id='ic'>✕</div>"
               "<span class='lin'>" (get-in clusters [(keyword (:cluster n)) :name]) " · " (:role n) "</span>"
               "<h2>" (:id n) "</h2>"
               "<p class='desc'>" (:desc n) "</p>"
               (expose-chips n)
               "</div><div class='insp-body'>"
               "<div class='metrics'>"
               "<div class='m'><div class='v' style='color:" accent "'>" (:fanin n) "</div><div class='l'>depended&nbsp;on&nbsp;by</div></div>"
               "<div class='m'><div class='v'>" (:fanout n) "</div><div class='l'>depends&nbsp;on</div></div>"
               "<div class='m'><div class='v'>" (:loc n) "</div><div class='l'>lines</div></div>"
               "</div><div class='rel'>"
               "<h3>Depends on <span class='n'>" (:fanout n) "</span></h3>"
               (rel-chips (filter all-ids (:deps n)))
               "<h3>Depended on by <span class='n'>" (:fanin n) "</span></h3>"
               (rel-chips (:dependents n))
               "</div></div>"))
    (.add (.-classList insp) "open")
    (set! (.-onclick (gid "ic")) (fn [_] (deselect)))
    (doseq [ch (js/Array.from (.querySelectorAll insp "[data-goto]"))]
      (set! (.-onclick ch) (fn [_] (select (.. ch -dataset -goto)))))))

;; ---------- cluster toggle ----------
(def off-clusters (atom #{}))
(defn toggle-cluster [k row]
  (if (contains? @off-clusters k)
    (do (swap! off-clusters disj k) (.remove (.-classList row) "off"))
    (do (swap! off-clusters conj k) (.add (.-classList row) "off")))
  (doseq [n nodes]
    (set! (.. (get @node-els (:id n)) -style -display)
          (if (contains? @off-clusters (:cluster n)) "none" "")))
  (doseq [p @edge-els]
    (let [hide (or (contains? @off-clusters (:cluster (by-id (.. p -dataset -from))))
                   (contains? @off-clusters (:cluster (by-id (.. p -dataset -to)))))]
      (set! (.. p -style -visibility) (if hide "hidden" "")))))

;; ---------- catalog board ----------
(defn comp-chip [id]
  (let [c (if (by-id id) (cvar (:cluster (by-id id))) "var(--ink-faint)")]
    (str "<span class='en-chip' data-goto='" id "' style='--cc:" c "'><span class='cd'></span>" id "</span>")))
(defn build-catalog []
  (let [macro-count (reduce + (map #(count (:items %)) macro-groups))
        definers (count (filter #(seq (:macros %)) nodes))
        h (str
            "<p class='cat-kick'>grain · reference</p>"
            "<h2 class='cat-title'>DSL Catalog</h2>"
            "<p class='cat-lede'>The authoring surface of the current generation: <b class='mm'>" macro-count
            " macros</b> across " definers " components form the CQRS DSL, and just <b class='pp'>"
            (count protocols) " protocols</b> mark every swappable I/O seam. Each entry links back to the component that defines it.</p>"
            "<div class='cat-sec'><div class='cat-sec-h macro'><span class='sg'>ƒ</span><h3>Macros</h3><span class='ct'>"
            macro-count " macros · " definers " definers</span></div>"
            (apply str
              (for [grp macro-groups]
                (str "<div class='grp-title'>" (:g grp) "</div><div class='mgrid'>"
                     (apply str
                       (for [it (:items grp)]
                         (str "<div class='entry m'><div class='en-top'><span class='en-g'>ƒ</span>"
                              "<span class='en-name'>" (:m it) "</span></div>"
                              "<div class='en-sig'>" (:sig it) "</div>"
                              "<div class='en-p'>" (:p it) "</div>"
                              "<div class='en-foot'><span class='lbl-defs'>defined in</span>" (comp-chip (:comp it)) "</div></div>")))
                     "</div>")))
            "</div>"
            "<div class='cat-sec'><div class='cat-sec-h proto'><span class='sg'>◇</span><h3>Protocols</h3><span class='ct'>"
            (count protocols) " seams</span></div><div class='pgrid'>"
            (apply str
              (for [pr protocols]
                (str "<div class='entry p'><div class='en-top'><span class='en-g'>◇</span>"
                     "<span class='en-name'>defprotocol " (:name pr) "</span></div>"
                     "<div class='en-p'>" (:p pr) "</div><div class='en-row'>"
                     "<div class='pcol'><div class='pl'>defined in</div><div class='en-foot'>" (comp-chip (:comp pr)) "</div></div>"
                     "<div class='pcol'><div class='pl'>implemented by</div><div class='en-foot'>"
                     (if (seq (:impls pr)) (apply str (map comp-chip (:impls pr)))
                         "<span class='lbl-defs'>in-process — no external backend</span>")
                     "</div></div></div>")))
            "</div></div>")
        el (gid "catalog")]
    (set! (.-innerHTML el) h)
    (doseq [ch (js/Array.from (.querySelectorAll el "[data-goto]"))]
      (set! (.-onclick ch) (fn [e] (.stopPropagation e) (select (.. ch -dataset -goto)))))))
(build-catalog)

;; ---------- pan / zoom ----------
(def view (atom {:tx 0 :ty 0 :s 1}))
(def MIN 0.16) (def MAX 2.4)
(def genome-view {:x 170 :y 60 :w 2080 :h 1260})
(def genome-ins {:l 475 :r 30 :t 96 :b 80})
(defn clampv [v] (max MIN (min MAX v)))
(defn render-tf []
  (let [{:keys [tx ty s]} @view]
    (set! (.. world -style -transform) (str "translate(" tx "px," ty "px) scale(" s ")"))))
(defn frame-rect [r animate ins]
  (let [ins (or ins {:l 20 :r 20 :t 20 :b 20})
        vw (.-clientWidth vp) vh (.-clientHeight vp) pad 1.04
        availW (max 200 (- vw (:l ins) (:r ins)))
        availH (max 200 (- vh (:t ins) (:b ins)))
        s (clampv (min (/ availW (* (:w r) pad)) (/ availH (* (:h r) pad))))
        cx (+ (:x r) (/ (:w r) 2)) cy (+ (:y r) (/ (:h r) 2))
        tx (- (+ (:l ins) (/ availW 2)) (* cx s))
        ty (- (+ (:t ins) (/ availH 2)) (* cy s))]
    (reset! view {:tx tx :ty ty :s s})
    (if animate
      (do (set! (.. world -style -transition) "transform .55s cubic-bezier(.3,.9,.25,1)")
          (render-tf) (js/setTimeout #(set! (.. world -style -transition) "") 580))
      (render-tf))))
(defn catalog-rect []
  (let [b (gid "catalog")]
    {:x (- (.-offsetLeft b) 56) :y (- (.-offsetTop b) 56)
     :w (+ (.-offsetWidth b) 112) :h (+ (.-offsetHeight b) 112)}))
(defn fit [] (frame-rect genome-view false genome-ins))
(defn zoom-at [cx cy factor]
  (let [{:keys [tx ty s]} @view ns (clampv (* s factor))
        ntx (- cx (* (- cx tx) (/ ns s))) nty (- cy (* (- cy ty) (/ ns s)))]
    (reset! view {:tx ntx :ty nty :s ns})
    (render-tf)))
(defn center-on [n]
  (let [vw (.-clientWidth vp) vh (.-clientHeight vp)
        panelR (if (.contains (.-classList insp) "open") 360 0)
        s (:s @view)
        tx (- (/ (- vw panelR) 2) (* (:x n) s)) ty (- (/ vh 2) (* (:y n) s))]
    (swap! view assoc :tx tx :ty ty)
    (set! (.. world -style -transition) "transform .4s cubic-bezier(.3,.9,.25,1)")
    (render-tf) (js/setTimeout #(set! (.. world -style -transition) "") 420)))

(.addEventListener vp "wheel"
  (fn [e] (.preventDefault e)
    (let [r (.getBoundingClientRect vp)]
      (zoom-at (- (.-clientX e) (.-left r)) (- (.-clientY e) (.-top r))
               (if (neg? (.-deltaY e)) 1.12 0.893))))
  #js {:passive false})

(def pan (atom nil))
(.addEventListener vp "pointerdown"
  (fn [e]
    (when-not (.closest (.-target e) ".node")
      (reset! pan {:sx (.-clientX e) :sy (.-clientY e) :moved false})
      (.add (.-classList vp) "grabbing")
      (.setPointerCapture vp (.-pointerId e)))))
(.addEventListener vp "pointermove"
  (fn [e]
    (when-let [p @pan]
      (let [dx (- (.-clientX e) (:sx p)) dy (- (.-clientY e) (:sy p))]
        (when (> (+ (js/Math.abs dx) (js/Math.abs dy)) 3) (swap! pan assoc :moved true))
        (swap! view #(-> % (update :tx + dx) (update :ty + dy)))
        (swap! pan assoc :sx (.-clientX e) :sy (.-clientY e))
        (render-tf)
        (set! (.. (gid "hint") -style -opacity) "0")))))
(.addEventListener vp "pointerup"
  (fn [e]
    (let [p @pan]
      (reset! pan nil)
      (.remove (.-classList vp) "grabbing")
      (when (and p (not (:moved p)) (not (.closest (.-target e) ".node,#catalog")))
        (deselect)))))

(set! (.-onclick (gid "zin"))  (fn [_] (let [r (.getBoundingClientRect vp)] (zoom-at (/ (.-width r) 2) (/ (.-height r) 2) 1.2))))
(set! (.-onclick (gid "zout")) (fn [_] (let [r (.getBoundingClientRect vp)] (zoom-at (/ (.-width r) 2) (/ (.-height r) 2) 0.83))))
(set! (.-onclick (gid "gocat")) (fn [_] (frame-rect (catalog-rect) true nil) (set! (.. (gid "hint") -style -opacity) "0")))
(set! (.-onclick (gid "gogenome")) (fn [_] (frame-rect genome-view true genome-ins)))
(.addEventListener doc "keydown" (fn [e] (when (= (.-key e) "Escape") (deselect) (frame-rect genome-view true genome-ins))))

(set! (.-onclick (gid "theme"))
  (fn [_]
    (let [de (.-documentElement doc)
          cur (.getAttribute de "data-theme")
          sys-dark (.-matches (.matchMedia js/window "(prefers-color-scheme: dark)"))
          nxt (if cur (if (= cur "dark") "light" "dark") (if sys-dark "light" "dark"))]
      (.setAttribute de "data-theme" nxt)
      (doseq [n nodes] (.setProperty (.-style (get @node-els (:id n))) "--accent" (cvar (:cluster n))))
      (build-catalog)
      (when @selected (open-inspector (by-id @selected))))))

(fit)
(.addEventListener js/window "resize" (fn [_] (when-not @selected (fit))))
