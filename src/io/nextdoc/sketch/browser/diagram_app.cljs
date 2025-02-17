(ns io.nextdoc.sketch.browser.diagram-app
  (:require [cljs.reader :as reader]
            [editscript.core :as edit]
            [reagent.core :as r]
            [reagent.dom :as dom]
            [reagent.dom.client :as rdc]
            [goog.string :as gstring]
            [goog.string.format]
            [sc.api]))

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
                (str "<TABLE BORDER='0' CELLBORDER='1' CELLSPACING='0' CELLPADDING='4'>"
                     "<TR><TD ALIGN='LEFT' COLSPAN='" (count keys) "'><B>" table-name "</B></TD></TR>"
                     ;headers
                     (apply str
                            (for [row-data (vals data)]
                              (str "<TR>"
                                   (apply str (map #(str "<TD>" (str (get row-data %)) "</TD>") keys))
                                   "</TR>")))
                     "</TABLE>"))))

          (render-table [{:keys [name data]}]
            (when (seq data)
              (gstring/format "  %s [label=<%s>];\n"
                              name
                              (make-html-table data name))))]

    (str "digraph {\n"
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
     (for [{:keys [actor stores]} visible-state-stores]
       [:div {:key   (name actor)
              :style {:background-color "lightgrey"
                      :padding          "1rem"
                      :margin-bottom    "1rem"}}
        [:h3 actor]
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
                         :style {}}
                   [:div store]
                   [graphviz-component diagram]]))]])]))

(defn toggle-actor!
  [actor-name]
  (let [k (keyword actor-name)]
    (if (contains? (:actors-visible @app-state) actor-name)
      (swap! app-state update :actors-visible disj k)
      (swap! app-state update :actors-visible (fnil conj #{}) k))))

(defn set-step!
  [step-number]
  (swap! app-state assoc :step step-number))

(defonce root (rdc/create-root (.getElementById js/document "app")))

(defn ^:dev/after-load mount! []

  (rdc/render root [app])

  (js/setTimeout (fn []
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
