(ns io.nextdoc.sketch.browser.diagram-app
  (:require [cljs.reader :as reader]
            [editscript.core :as edit]
            [goog.string :as gstring]
            [goog.string.format]
            [reagent.core :as r]
            [reagent.dom.client :as rdc]))

(defonce app-state (r/atom {:step   nil
                            :states nil}))

(defn create-tables-diagram
  "Creates a Graphviz diagram string showing multiple tables side by side
   Input: vector of maps, where each map has :name and :data keys
   Example: [{:name \"table1\" :data {...}} {:name \"table2\" :data {...}}]"
  [tables]
  (letfn [(make-html-table [data table-name]
            (when (seq data)
              (let [keys (keys (first (vals data)))
                    headers (str "<TR>"
                                 (apply str (map #(str "<TD><B>" (name %) "</B></TD>") keys))
                                 "</TR>")]
                (str "<TABLE BGCOLOR='white' BORDER='0' CELLBORDER='1' CELLSPACING='0' CELLPADDING='4'>"
                     "<TR><TD ALIGN='LEFT' COLSPAN='" (count keys) "'><B>" table-name "</B></TD></TR>"
                     ;headers
                     (apply str
                            (for [row-data (vals data)]
                              (str "<TR>"
                                   (->> keys
                                        (sort-by #(if (= :id %) [0 ""] [1 %]))
                                        (mapv #(str "<TD>" (str (get row-data %)) "</TD>"))
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

(defn app
  []
  (let [{:keys [step states actors actors-visible]} @app-state
        states-at-step (when states
                         (->> (:diffs states)
                              (take (inc (or step -1)))
                              (reduce edit/patch (:initial states))))
        visible-state-stores (->> actors-visible
                                  (mapv (fn [actor]
                                          (let [{:keys [store]} (get actors actor)]
                                            {:actor  actor
                                             :stores (mapv (fn [store-key]
                                                             (let [single-store (states-at-step store-key)
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
     ;[:div (pr-str (dissoc @app-state :states))]
     (for [{:keys [actor stores]} visible-state-stores]
       [:div {:key   (name actor)
              :style {:border  "2px solid #1740FF"
                      :padding "1rem"
                      :margin  "1rem"}}
        [:div {:style {:margin-bottom "1rem"}} [:b actor]]
        [:div (for [{:keys [store data]} stores]
                (let [diagram-data (reduce-kv (fn [acc k v]
                                                (conj acc {:name (name k)
                                                           :data (reduce-kv (fn [acc id attrs]
                                                                              (assoc acc id (merge attrs {:id id})))
                                                                            {}
                                                                            v)}))
                                              []
                                              data)
                      diagram (create-tables-diagram diagram-data)]
                  [:div {:key   (str (name actor) "-" (name store))
                         :style {:border        "2px solid #7D8FF9"
                                 :padding       "1rem"
                                 :margin-bottom "1rem"}}
                   [:div store]
                   [graphviz-component diagram]]))]])]))

(defn toggle-actor!
  [actor-name]
  (let [k (keyword actor-name)]
    (if (contains? (:actors-visible @app-state) k)
      (swap! app-state update :actors-visible disj k)
      (swap! app-state update :actors-visible (fnil conj #{}) k))))

(defn set-step!
  [step-number]
  (swap! app-state assoc :step step-number))

(defn on-mouse-move [event]
  (when (:resizing? @app-state)
    (let [container-width (.-offsetWidth (.querySelector js/document ".container"))
          new-left-width (* (/ (.-clientX event) container-width) 100)
          clamped-width (max 10 (min 90 new-left-width))]   ;; Clamping between 10% and 90%
      (swap! app-state assoc :left-width clamped-width)
      (set! (.-width
              (.-style (first (.getElementsByClassName js/document "left"))))
            (str (:left-width @app-state) "%")))))

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

                   (.addEventListener (first (.getElementsByClassName js/document "divider"))
                                      "mousedown"
                                      start-resizing)

                   (doseq [actor (.getElementsByClassName js/document "actor")]
                     (when-let [n (.getAttribute actor "name")] ; only rect has name
                       (.addEventListener (.-nextElementSibling actor) ; text in rect is clickable
                                          "click"
                                          (fn [_] (toggle-actor! n)))))
                   (let [counter (atom 0)]
                     (doseq [stepMessage (.getElementsByClassName js/document "messageText")]
                       (let [step-number @counter]
                         (.addEventListener stepMessage
                                            "click"
                                            (fn [_] (set-step! step-number)))
                         (swap! counter inc)))))
                 100))

(defn ^:export loadTest
  [states actors]
  (-> states
      (reader/read-string)
      (update :diffs #(mapv edit/edits->script %))          ; convert back to EditScript
      (->> (swap! app-state assoc :states)))
  (swap! app-state assoc :actors (reader/read-string actors)))
