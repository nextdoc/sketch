(ns io.nextdoc.sketch.browser.diagram-app
  (:require [cljs.reader :as reader]
            [com.rpl.specter :refer [ALL MAP-KEYS MAP-VALS collect collect-one multi-path select select-first transform]]
            [editscript.core :as edit]
            [goog.string :as gstring]
            [goog.string.format]
            [reagent.core :as r]
            [reagent.dom.client :as rdc]))

(defonce app-state (r/atom {:emit-count nil
                            :states     nil}))

(defn create-tables-diagram
  "Creates a Graphviz diagram string showing multiple tables side by side
   Input: vector of maps, where each map has :name and :data keys
   Example: [{:name \"table1\" :data {...}} {:name \"table2\" :data {...}}]"
  [tables]
  (letfn [(make-html-table [data table-name]
            (when (seq data)
              (let [keys (keys (first (vals data)))
                    headers (str "<TR>"
                                 (apply str (map #(str "<TD ALIGN='LEFT'><B>" (name %) "</B></TD>") keys))
                                 "</TR>")]
                (str "<TABLE BGCOLOR='white' BORDER='0' CELLBORDER='1' CELLSPACING='0' CELLPADDING='4'>"
                     "<TR><TD ALIGN='LEFT' COLSPAN='" (count keys) "'><B>" table-name "</B></TD></TR>"
                     ;headers
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
                (str "      <TR><TD>" (name k) "</TD><TD>" (str v) "</TD></TR>\n")))
       "    </TABLE>\n"
       "  >];\n"
       "}"))

(defn graphviz-component [dot-string]
  (let [container-ref (atom nil)]
    (r/create-class
      {:display-name "GraphvizComponent"
       :component-did-mount
       (fn [_]
         (when @container-ref
           (-> js/d3
               (.select @container-ref)
               (.graphviz)
               (.renderDot dot-string))))
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
       (fn [_] [:div {:ref (fn [el] (reset! container-ref el))}])})))

#_(defn render-pre [data]
    (let [formatted (with-out-str (cljs.pprint/pprint data))
          escaped (clojure.string/replace formatted #"<" "&lt;")] ; Escape HTML
      [:pre escaped]))

(defn app
  []
  (try
    (let [{:keys [emit-count states actors actors-visible store-types]} @app-state
          {:keys [diffs emits]} states
          states-at-step (when (and emit-count states)
                           (->> diffs
                                (take (get emits emit-count))
                                (reduce edit/patch {})))
          visible-state-stores (->> actors-visible
                                    (mapv (fn [actor]
                                            (let [{:keys [store]} (get actors actor)]
                                              {:actor  actor
                                               :stores (mapv (fn [store-key]
                                                               (let [single-store (or (get states-at-step store-key)
                                                                                      (throw (ex-info "store not found"
                                                                                                      {:key  store-key
                                                                                                       :keys (keys states-at-step)})))
                                                                     data (reduce-kv (fn [acc k v]
                                                                                       (if (empty? v)
                                                                                         acc
                                                                                         (assoc acc k v)))
                                                                                     {}
                                                                                     single-store)]
                                                                 {:store store-key
                                                                  :data  data}))
                                                             store)}))))]
      [:div
       ;[render-pre @app-state]
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
                      (->> data
                           (reduce-kv (fn store-data [acc k v]
                                        (conj acc {:name (name k)
                                                   :data (reduce-kv (fn [acc id attrs]
                                                                      (assoc acc id (merge attrs {:id id})))
                                                                    {}
                                                                    v)}))
                                      [])
                           (create-tables-diagram))
                      :associative
                      (create-map-table data))]])]])])
    (catch :default e
      (js/console.error e)
      [:div "Render failed! See console for more info."])))

(defn toggle-actor!
  [actor-name]
  (let [k (keyword actor-name)]
    (if (contains? (:actors-visible @app-state) k)
      (swap! app-state update :actors-visible disj k)
      (swap! app-state update :actors-visible (fnil conj #{}) k))))

(defn set-emitted-msg-count!
  [emit-count]
  (swap! app-state assoc :emit-count emit-count))

(defn on-mouse-move [event]
  (when (:resizing? @app-state)
    (let [container-width (.-offsetWidth (.querySelector js/document ".container"))
          new-left-width (* (/ (.-clientX event) container-width) 100)
          clamped-width (max 10 (min 90 new-left-width))]   ;; Clamping between 10% and 90%
      (swap! app-state assoc :left-width clamped-width)
      (set! (.-width
              (.-style (first (.getElementsByClassName js/document "left"))))
            (str (:left-width @app-state) "%"))
      (set! (.-width
              (.-style (first (.getElementsByClassName js/document "right"))))
            (str (- 100 (:left-width @app-state)) "%")))))

(defn stop-resizing []
  (swap! app-state assoc :resizing? false)
  (.removeEventListener js/document "mousemove" on-mouse-move)
  (.removeEventListener js/document "mouseup" stop-resizing))

(defn start-resizing [event]
  (swap! app-state assoc :resizing? true)
  (.addEventListener js/document "mousemove" on-mouse-move)
  (.addEventListener js/document "mouseup" stop-resizing))

(defonce root (rdc/create-root (.getElementById js/document "app")))

(defn ^:dev/after-load mount! []

  (rdc/render root [app])

  (js/setTimeout (fn []
                   ; Divider drag.
                   (.addEventListener (first (.getElementsByClassName js/document "divider"))
                                      "mousedown"
                                      start-resizing)
                   ; actor click.
                   (doseq [actor (.getElementsByClassName js/document "actor")]
                     (when-let [n (.getAttribute actor "name")] ; only rect has name
                       (.addEventListener (.-nextElementSibling actor) ; text in rect is clickable
                                          "click"
                                          (fn [_] (toggle-actor! n)))))
                   ; Event click.
                   (let [counter (atom 0)]
                     (doseq [stepMessage (.getElementsByClassName js/document "messageText")]
                       (let [emit-number @counter]
                         (.addEventListener stepMessage
                                            "click"
                                            (fn [_] (set-emitted-msg-count! emit-number)))
                         (swap! counter inc)))))
                 100))

(defn ^:export load
  [states model]
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
                         (into {}))]
    (-> states
        (reader/read-string)
        (update :diffs #(mapv edit/edits->script %))        ; convert back to EditScript
        (->> (swap! app-state assoc :states)))
    (swap! app-state merge {:actors      actors
                            :store-types store-types})))
