(ns io.nextdoc.sketch.browser.diagram-app
  (:require [cljs.reader :as reader]
            [com.rpl.specter :refer [ALL MAP-KEYS MAP-VALS collect collect-one multi-path select select-first transform]]
            [editscript.core :as edit]
            [goog.string :as gstring]
            [goog.functions :as gfun]
            [goog.string.format]
            [reagent.core :as r]
            [reagent.dom.client :as rdc]))

(defonce app-state (r/atom {:settings   {:column-headers? false
                                         :max-columns     5}
                            :left-width 60
                            :emit-count nil
                            :states     nil}))

(defn create-tables-diagram
  "Creates a Graphviz diagram string showing multiple tables side by side
   Input: vector of maps, where each map has :name and :data keys
   Example: [{:name \"table1\" :data {...}} {:name \"table2\" :data {...}}]"
  [tables {:keys [column-headers? max-columns]}]
  (letfn [(make-html-table [data table-name]
            (when (seq data)
              (let [keys (->> (vals data)
                              (first)
                              (keys)
                              (sort-by #(if (= :id %) [0 ""] [1 %])))
                    headers (str "<TR>"
                                 (->> keys
                                      (take max-columns)
                                      (map #(str "<TD ALIGN='LEFT'><B>" (name %) "</B></TD>"))
                                      (apply str))
                                 "</TR>")]
                (str "<TABLE BGCOLOR='white' BORDER='0' CELLBORDER='1' CELLSPACING='0' CELLPADDING='4'>"
                     "<TR><TD ALIGN='LEFT' COLSPAN='" (count keys) "'><B>" table-name "</B></TD></TR>"
                     (when column-headers? headers)
                     (apply str
                            (for [row-data (vals data)]
                              (str "<TR>"
                                   (->> keys
                                        (take max-columns)
                                        (mapv #(str "<TD ALIGN='LEFT'>" (str (get row-data %)) "</TD>"))
                                        (apply str))
                                   "</TR>")))
                     "</TABLE>"))))

          (render-table [{:keys [name data]}]
            (when (seq data)
              (gstring/format "  %s [label=<%s>];\n"
                              name
                              (make-html-table data name))))]

    (str "digraph {\n"
         "  bgcolor=\"#BEC7FC\";\n"
         "  node [shape=none];\n"
         "  rankdir=LR;\n"
         (apply str (map render-table tables))
         "}")))

(defn create-map-table
  "Creates a Graphviz diagram string showing a map as a two column table
   with keys in the first column and values in the second column"
  [data]
  (str "digraph {\n"
       "  bgcolor=\"#BEC7FC\";\n"
       "  node [shape=none];\n"
       "  table [label=<\n"
       "    <TABLE BGCOLOR='white' BORDER='0' CELLBORDER='1' CELLSPACING='0' CELLPADDING='4'>\n"
       (apply str
              (for [[k v] data]
                (str "      <TR><TD ALIGN='LEFT'>" (name k) "</TD><TD ALIGN='LEFT'>" (str v) "</TD></TR>\n")))
       "    </TABLE>\n"
       "  >];\n"
       "}"))

(def animation-duration 500)

(defn graphviz-component [_]
  (let [container-ref (atom nil)
        d3-element (fn [selector] (-> js/d3
                                      (.select @container-ref)
                                      (.select selector)))
        with-aspect-ratio (fn []
                            ; set initial attrs to keep/scale svg inside parent
                            (-> (d3-element "svg")
                                (.attr "preserveAspectRatio" "xMidYMid meet")))
        with-container-height-transition (fn [n]
                                           (-> js/d3
                                               (.select @container-ref)
                                               (.select ".diagram")
                                               (.transition "container-resize")
                                               (.duration animation-duration)
                                               (.style "height" (str (* n 8) "vh"))))
        active-transitions (atom {})]
    (r/create-class
      {:display-name "Graphviz"
       :component-did-mount
       (fn [this]
         (let [{:keys [dot row-count]} (r/props this)]
           (when @container-ref
             (with-container-height-transition row-count)
             (-> (d3-element ".diagram")
                 (.graphviz)
                 (.renderDot dot with-aspect-ratio)))))
       :component-did-update
       (fn [this _ _]
         (when @container-ref
           ; https://github.com/magjac/d3-graphviz?tab=readme-ov-file#creating-transitions
           (let [{:keys [actor store dot row-count]} (r/props this)
                 ; Use a named transition to ensure that only a single one is running at a time
                 ; i.e. when quickly changing the slider, any existing transition will be replaced
                 transition-name (str "update-diagram-" (name actor) "-" (name store))
                 t (-> (js/d3.transition transition-name)
                       (.duration animation-duration)
                       (.ease js/d3.easeLinear)
                       (.on "start" (fn [] (swap! active-transitions assoc transition-name true)))
                       (.on "end" (fn [] (swap! active-transitions dissoc transition-name))))
                 ; Delay the transition if one is already running to avoid overlaps
                 ; becausee overlaps cause exceptions which break subsequent transitions
                 transition-delayed? (get @active-transitions transition-name)
                 transition-delay (if transition-delayed? (+ 50 animation-duration) 0)
                 start-diagram-transition (gfun/debounce
                                            (fn [] (-> (d3-element ".diagram")
                                                       (.graphviz)
                                                       (.transition t)
                                                       (.renderDot dot)))
                                            transition-delay)
                 start-container-transition (gfun/debounce
                                              (fn [] (with-container-height-transition row-count))
                                              transition-delay)]
             (start-diagram-transition)
             (start-container-transition))))
       :reagent-render
       (fn [_] [:div.graphviz {:ref (fn [el] (reset! container-ref el))}
                ; container div and diagram svg are d3 animated in parallel
                ; so rendered as separate nodes to avoid bugs
                [:div.diagram]])})))

(defn toggle-actor!
  [actor-name]
  (let [k (keyword actor-name)]
    (if (contains? (:actors-visible @app-state) k)
      (swap! app-state update :actors-visible disj k)
      (swap! app-state update :actors-visible (fnil conj #{}) k))))

(defn set-emitted-msg-count!
  [emit-count]
  (swap! app-state assoc :emit-count emit-count))

(defn mermaid-sequence [_]
  (let [container-ref (atom nil)]
    (r/create-class
      {:display-name "mermaid diagram"
       :reagent-render
       (fn [] [:div {:ref (fn [el]
                            (reset! container-ref el))}
               "Loading..."])
       :component-did-mount
       (fn [this]
         (when-let [svg-container @container-ref]
           ;; Clear any existing content before appending the new SVG.
           (set! (.-innerHTML svg-container) "")
           ;; parse and append new SVG
           (let [parser (js/DOMParser.)
                 svg-node (as-> (r/props this) $
                                (:svg-string $)
                                (.parseFromString parser $ "image/svg+xml")
                                (.-documentElement $))]
             (.appendChild svg-container svg-node)

             ; add actor click handlers
             (doseq [actor (.getElementsByClassName svg-node "actor")]
               (when-let [n (.getAttribute actor "name")]   ; only rect has name
                 (.addEventListener (.-nextElementSibling actor) ; text in rect is clickable
                                    "click"
                                    (fn [_] (toggle-actor! n)))))
             ; add event click handlers
             (let [counter (atom 0)]
               (doseq [stepMessage (.getElementsByClassName svg-node "messageText")]
                 (let [emit-number @counter]
                   (.addEventListener stepMessage
                                      "click"
                                      (fn [_] (set-emitted-msg-count! emit-number)))
                   (swap! counter inc)))))))})))

(defn change-divider-location! [event]
  (when (:resizing? @app-state)
    (let [container-width (.-offsetWidth (.querySelector js/document ".container"))
          new-left-width (* (/ (.-clientX event) container-width) 100)
          clamped-width (max 10 (min 90 new-left-width))]   ;; Clamping between 10% and 90%
      (swap! app-state assoc :left-width clamped-width))))

(defn stop-resizing! [_]
  (swap! app-state assoc :resizing? false))

(defn start-resizing! [_]
  (swap! app-state assoc :resizing? true))

(defn tooltip-component [props]
  (let [state (r/atom {:open?         false                 ;; Whether the tooltip is open
                       :click-handler nil})                 ;; To store the click handler
        tooltip-el (atom nil)]                              ;; Reference to the tooltip element
    (r/create-class
      {:component-did-mount
       (fn [this]
         (let [handler (fn [e]
                         (when (and (:open? @state)
                                    @tooltip-el
                                    (not (.contains @tooltip-el (.-target e))))
                           (swap! state assoc :open? false)))]
           (.addEventListener js/document "click" handler)
           (swap! state assoc :click-handler handler)))

       :component-will-unmount
       (fn [this]
         (when-let [handler (:click-handler @state)]
           (.removeEventListener js/document "click" handler)))

       :reagent-render
       (fn []
         ;; Wrap the icon and tooltip in a relative container to position the tooltip absolutely
         [:div.tooltip-container
          ;; Info icon toggles the tooltip on click
          [:span {:style    {:cursor "pointer"}
                  :on-click #(swap! state update :open? not)}
           "ℹ️"]
          ;; Render tooltip only if open
          (when (:open? @state)
            [:div {:class "tooltip"
                   :ref   (fn [el] (reset! tooltip-el el))}
             [:div.tooltip-content
              [:div.tooltip-version "Sketch version:" [:span.version-number props]]
              [:div.tooltip-creator
               [:div.creator-label "Created by"]
               [:div.creator-logo
                [:a {:href "https://nextdoc.io/" :target "_blank" :rel "noopener noreferrer"}
                 [:img {:src "https://nextdoc.io/images/Logo-NextDoc_Colour_Colour.svg"
                        :alt "Nextdoc Logo"}]]]]]])])})))

(defn app
  []
  (try
    (let [{:keys [title left-width mermaid emit-count states actors actors-visible store-types settings tag]} @app-state
          {:keys [diffs message-diffs]} states
          next-msg-num (inc emit-count)                     ; add 1 to show states after message received, up to next message emitted
          diffs-applied (nth message-diffs next-msg-num)
          states-at-step (when (and emit-count states)
                           (->> diffs
                                (take diffs-applied)
                                (reduce edit/patch {})))
          visible-state-stores (mapv (fn actor-storages [actor]
                                       (let [stores-in-actor (:store (get actors actor))]
                                         {:actor  actor
                                          :stores (->> stores-in-actor
                                                       (mapv (fn actor-store [store-key]
                                                               (let [single-store (get states-at-step store-key)
                                                                     data (if single-store
                                                                            (if (= :database (store-types store-key))
                                                                              ; hide empty tables
                                                                              (reduce-kv (fn [acc entity-type records]
                                                                                           (if (empty? records)
                                                                                             acc
                                                                                             (assoc acc entity-type records)))
                                                                                         {}
                                                                                         single-store)
                                                                              single-store)
                                                                            {})]
                                                                 {:store store-key
                                                                  :data  data}))))}))
                                     actors-visible)]
      [:div#diagram-app {:onMouseMove change-divider-location!
                         :onMouseUp   stop-resizing!}

       [:div.header

        [:div.title
         [:h3 title]]

        [:div.settings
         [:label
          [:input {:type      "checkbox"
                   :checked   (:column-headers? settings)
                   :on-change #(swap! app-state update-in [:settings :column-headers?] not)}]
          "Column Headers"]
         [:label "Max Columns"
          [:input {:type      "range"
                   :min       1
                   :max       20
                   :value     (:max-columns settings)
                   :style     {:width "8rem"}
                   :on-change #(swap! app-state assoc-in [:settings :max-columns]
                                      (js/parseInt (.. % -target -value)))}]
          [:span {:style {:margin-left "0.5rem"}}
           (:max-columns settings)]]
         [tooltip-component tag]
         ]]

       [:div.container
        [:div.mermaid.left {:style {:width (str left-width "%")}}
         [mermaid-sequence {:svg-string mermaid}]

         #_[:div.debug
            [:h3 "debug"]
            [:table {:border 0
                     :style  {:width        "100%"
                              :table-layout "fixed"}}
             (let [formatted (fn [v] [:pre (-> (with-out-str (cljs.pprint/pprint v))
                                               (clojure.string/replace #"<" "&lt;"))])]
               (into
                 [:tbody
                  [:tr
                   [:td {:style {:width "100px"}} "msg diffs"]
                   [:td (formatted message-diffs)]]
                  [:tr [:td "msg#"] [:td emit-count]]
                  [:tr [:td "next"] [:td next-msg-num]]
                  [:tr [:td "diffs"] [:td diffs-applied]]]
                 (map-indexed (fn [i diff]
                                [:tr [:td (str "diff-" (inc i))]
                                 [:td (formatted diff)]])
                              diffs)))]]]

        [:div.divider {:onMouseDown start-resizing!}]

        [:div.right {:style {:width (str (- 100 left-width) "%")}}
         (for [{:keys [actor stores]} visible-state-stores]
           [:div {:key   (name actor)
                  :style {:border  "2px solid #1740FF"
                          :padding "1rem"
                          :margin  "1rem"}}
            [:div {:style {:margin-bottom "1rem"}} [:b actor]]
            [:div (for [{:keys [store data]} stores]
                    [:div {:key   (str (name actor) "-" (name store))
                           :style {:border        "2px solid #7D8FF9"
                                   :padding       "1rem"
                                   :margin-bottom "1rem"}}
                     [:div store]
                     (let [diagram-string (case (store-types store)
                                            :database
                                            (-> (reduce-kv (fn store-data [acc entity-type records]
                                                             (conj acc {:name (name entity-type)
                                                                        :data (reduce (fn [acc record]
                                                                                        (assoc acc (:id record) record))
                                                                                      {}
                                                                                      records)}))
                                                           []
                                                           data)
                                                (create-tables-diagram settings))
                                            :associative
                                            (create-map-table data))]
                       [graphviz-component
                        {:actor       actor
                         :store       store
                         :data        data
                         :row-count   (case (store-types store)
                                        :database (->> (vals data) (map count) (reduce +))
                                        :associative (count data))
                         :table-count (case (store-types store)
                                        :database (count data)
                                        :associative (count (keys data)))
                         :dot         diagram-string}])])]])]]])
    (catch :default e
      (js/console.error (ex-message e) (some-> e (ex-data) (clj->js)))
      [:div {:style {:padding "1rem"
                     :color   "RED"}}
       "Render failed! See console for more info."])))

(defn ^:dev/after-load mount! []
  (let [diagram-app (.getElementById js/document "diagram-app")]
    (if diagram-app
      (rdc/render (.getElementById js/document "app") [app])
      (rdc/render (rdc/create-root (.getElementById js/document "app")) [app]))))

(defn ^:export load
  [title mermaid-diagram states model tag]
  (try
    (-> (js/mermaid.render "sequence" mermaid-diagram)
        (.then (fn [result]
                 (let [model* (reader/read-string model)
                       actors (->> model*
                                   (select [:locations MAP-VALS (collect-one :id)
                                            (collect [:actors MAP-KEYS])
                                            :state MAP-KEYS])
                                   (reduce (fn [acc [location [actor] state]]
                                             ; FIXME https://github.com/nextdoc/sketch/issues/8
                                             (update-in acc
                                                        [(keyword (str (name location) "-" (name actor))) :store]
                                                        (fnil conj #{}) state))
                                           {}))
                       store-types (->> model*
                                        (select [:locations MAP-VALS :state MAP-VALS])
                                        (map (juxt :id :type))
                                        (into {}))
                       states-decoded (-> states
                                          (reader/read-string)
                                          ; convert back to EditScript
                                          (update :diffs #(mapv edit/edits->script %)))]
                   (swap! app-state merge {:tag         tag
                                           :title       title
                                           :actors      actors
                                           :store-types store-types
                                           :states      states-decoded
                                           :mermaid     (-> result
                                                            (js->clj :keywordize-keys true)
                                                            :svg)}))))
        (.catch js/console.error))
    (catch :default e
      (js/console.log e))))
