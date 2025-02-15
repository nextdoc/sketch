(ns io.nextdoc.sketch.watcher
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [hawk.core :as hawk]
            [aero.core :as aero]
            [io.nextdoc.sketch.run :as run]
            [io.nextdoc.sketch.core :as core]
            [io.nextdoc.sketch.sync :as sync]
            [malli.core :as m]
            [malli.error :as me]
            [rewrite-clj.zip :as z]
            [taoensso.timbre :as log]))

(defonce watcher (atom nil))

(defn generate!
  [source target]
  (let [file (io/file source)
        domain-meta (when (.exists file)
                      (aero/read-config file))
        errors (when domain-meta
                 (me/humanize (m/explain :domain domain-meta
                                         {:registry core/model-registry})))
        data-flow-keys (->> [core/data-flow-events core/data-flow-pull-packets]
                            (mapcat (fn [source] (source domain-meta)))
                            (sort)
                            (vec))

        new-keys (->> [core/actor-keys
                       core/state-roots
                       core/state-root-entities
                       core/errors]
                      (mapcat (fn [source] (source domain-meta)))
                      (into data-flow-keys)
                      (sort)
                      (vec))]
    (when (nil? domain-meta)
      (log/error "no model found" {:path source}))
    (when errors
      (log/error "invalid actor model!")
      (clojure.pprint/pprint errors))

    ; add missing keys
    (when-let [changes (sync/sync-model {:file-name     target
                                         :keys-required (set new-keys)})]
      (spit target (:source changes))
      (log/info "added" (str/join ", " (sort (:adds changes))) "to" target))

    ; sort keys
    (let [root (sync/rewrite-root target)
          generated-map (some-> root
                                (z/find z/next (comp #{'generated} z/sexpr))
                                (z/right))
          sorted (sync/sort-registry generated-map)]
      (spit target (z/root-string sorted)))))

(defn start!
  [{:keys [model-path registry-path]}]
  (->> [{:paths   [model-path]
         :handler (fn [ctx e]
                    (log/with-merged-config
                      (run/log-config (run/this-ns))
                      (log/info "Sketch registry sync...")
                      (#'generate! model-path registry-path)
                      (log/info "Sketch registry sync complete!"))
                    ctx)}]
       (hawk/watch!)
       (reset! watcher))
  (println "Started!"))

(defn stop!
  []
  (when @watcher
    (hawk/stop! @watcher)
    (reset! watcher nil)
    (println "Stopped!")))

(comment
  (start! {:model-path    "examples/mobile_weather_app/weather-model.edn"
           :registry-path "examples/mobile_weather_app/weather_registry.cljc"})
  (stop!)
  )
