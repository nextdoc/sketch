(ns io.nextdoc.sketch.diagrams
  (:require [clojure.java.io]
            [clojure.string]
            [clojure.string :as str]
            [malli.dot]))

(defn flow-sequence
  "return a Mermaid sequence diagram for a sequence of network calls"
  [{:keys [calls]}]
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
       (concat ["sequenceDiagram"])
       (str/join "\n")))

