(ns io.nextdoc.sketch.browser.diagram-app
  (:require [cljs.reader :as reader]
            [com.rpl.specter :refer [ALL MAP-KEYS MAP-VALS collect collect-one multi-path select select-first transform]]
            [editscript.core :as edit]
            [goog.string :as gstring]
            [goog.string.format]
            [reagent.core :as r]
            [reagent.dom.client :as rdc]
            [sc.api]))

(defonce app-state (r/atom {:left-width 75
                            :emit-count nil
                            :states     nil}))

(defn create-tables-diagram
  "Creates a Graphviz diagram string showing multiple tables side by side
   Input: vector of maps, where each map has :name and :data keys
   Example: [{:name \"table1\" :data {...}} {:name \"table2\" :data {...}}]"
  [tables {:keys [headers?]
           :or   {headers? false}}]
  (letfn [(make-html-table [data table-name]
            (when (seq data)
              (let [keys (keys (first (vals data)))
                    headers (str "<TR>"
                                 (apply str (map #(str "<TD ALIGN='LEFT'><B>" (name %) "</B></TD>") keys))
                                 "</TR>")]
                (str "<TABLE BGCOLOR='white' BORDER='0' CELLBORDER='1' CELLSPACING='0' CELLPADDING='4'>"
                     "<TR><TD ALIGN='LEFT' COLSPAN='" (count keys) "'><B>" table-name "</B></TD></TR>"
                     (when headers? headers)
                     (apply str
                            (for [row-data (vals data)]
                              (str "<TR>"
                                   (->> keys
                                        (sort-by #(if (= :id %) [0 ""] [1 %]))
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

(defn graphviz-component [dot-string]
  (let [container-ref (atom nil)
        with-size-control (fn []
                            (-> js/d3
                                (.select @container-ref)
                                (.select "svg")
                                (.attr "viewBox" "0 0 100 100")
                                (.attr "preserveAspectRatio" "xMidYMid meet")))]
    (r/create-class
      {:display-name "Graphviz"
       :component-did-mount
       (fn [_]
         (when @container-ref
           (-> js/d3
               (.select @container-ref)
               (.graphviz)
               (.renderDot dot-string with-size-control))))
       :component-did-update
       (fn [this _]
         (when @container-ref
           (let [new-dot-string (second (r/argv this))]
             (-> js/d3
                 (.select @container-ref)
                 (.graphviz)
                 (.transition (fn [] (-> js/d3 (.transition) (.duration 500))))
                 (.renderDot new-dot-string)))))
       :reagent-render
       (fn [_] [:div.graphviz {:ref (fn [el] (reset! container-ref el))}])})))

#_(defn render-pre [data]
    (let [formatted (with-out-str (cljs.pprint/pprint data))
          escaped (clojure.string/replace formatted #"<" "&lt;")] ; Escape HTML
      [:pre escaped]))

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
       :component-did-update
       (fn [this _ _]
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

(defn app
  []
  (try
    (let [{:keys [title left-width mermaid emit-count states actors actors-visible store-types]} @app-state
          {:keys [diffs message-diffs]} states
          states-at-step (when (and emit-count states)
                           (->> diffs
                                (take (nth message-diffs emit-count))
                                (reduce edit/patch {})))
          visible-state-stores (mapv (fn actor-storages [actor]
                                       (let [stores-in-actor (:store (get actors actor))]
                                         {:actor  actor
                                          :stores (->> stores-in-actor
                                                       (mapv (fn actor-store [store-key]
                                                               (let [single-store (get states-at-step store-key)
                                                                     data (if single-store
                                                                            (if (= :database (store-types store-key))
                                                                              ; remove empty records
                                                                              (reduce-kv (fn [acc k v]
                                                                                           (if (empty? v)
                                                                                             acc
                                                                                             (assoc acc k v)))
                                                                                         {}
                                                                                         single-store)
                                                                              single-store)
                                                                            {})]
                                                                 {:store store-key
                                                                  :data  data}))))}))
                                     actors-visible)]
      [:div#diagram-app {:onMouseMove change-divider-location!
                         :onMouseUp   stop-resizing!}

       [:div.title
        [:h3 title]
        ;[render-pre @app-state]
        ]

       [:div.container
        [:div.mermaid.left {:style {:width (str left-width "%")}}
         [mermaid-sequence {:svg-string mermaid}]]

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
                     [graphviz-component
                      (case (store-types store)
                        :database
                        (-> (reduce-kv (fn store-data [acc k v]
                                         (conj acc {:name (name k)
                                                    :data (reduce-kv (fn [acc id attrs]
                                                                       (assoc acc id (merge attrs {:id id})))
                                                                     {}
                                                                     v)}))
                                       []
                                       data)
                            (create-tables-diagram {}))
                        :associative
                        (create-map-table data))]])]])]]])
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
  [title mermaid-diagram states model]
  (let [model* (reader/read-string model)
        actors (->> model*
                    (select [:locations MAP-VALS (collect-one :id)
                             (collect [:actors MAP-KEYS])
                             :state MAP-KEYS])
                    (reduce (fn [acc [location [actor] state]]
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
    (swap! app-state merge {:title       title
                            :actors      actors
                            :store-types store-types
                            :states      states-decoded})
    (-> (js/mermaid.render (str (random-uuid)) mermaid-diagram)
        (.then (fn [result]
                 (when-let [rendered ^String (.-svg result)]
                   (swap! app-state assoc :mermaid rendered))))
        (.catch js/console.error))))
