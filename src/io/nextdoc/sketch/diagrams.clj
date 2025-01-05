(ns io.nextdoc.sketch.diagrams
  (:require [clojure.java.io]
            [clojure.set :as set]
            [clojure.string]
            [clojure.string :as str]
            [malli.dot]))

(defn flow-sequence
  "return a Mermaid sequence diagram for a sequence of network calls"
  [{:keys [calls diagram-config]}]
  (->> calls
       (mapcat (fn [[{:keys [from to request event description]}
                     {:keys [directions async?]}]]
                 (let [async-icon "#128339;"
                       note (str "note over " (name from) ": "
                                 (when async? (str async-icon " "))
                                 description)]
                   (cond
                     request
                     (cond-> []
                             (or (empty? directions)
                                 (directions :request))
                             (conj (str (name from) " ->> " (name to) ": " (name request) " request"))
                             (or (empty? directions)
                                 (directions :response))
                             (conj (str (name to) " ->> " (name from) ": " (name request) " response"))
                             (or (empty? directions)
                                 (directions :request))
                             (conj note))
                     event
                     [(str (name from) " -->> " (name to) ": " (name event))
                      note]))))
       (concat (if-let [custom-actor-order (:actor-order diagram-config)]
                 (let [actors-from-calls (->> calls
                                              (map first)
                                              (mapcat (juxt :from :to))
                                              (set))]
                   (when-let [diff (seq (set/difference actors-from-calls (set custom-actor-order)))]
                     (throw (ex-info "not all actors in custom :actor-order" {:from-calls actors-from-calls
                                                                              :custom     custom-actor-order
                                                                              :diff       diff})))
                   (mapv (fn [actor]
                           (str "participant " (name actor)))
                         custom-actor-order))
                 []))
       (concat ["sequenceDiagram"])
       (str/join "\n")))

