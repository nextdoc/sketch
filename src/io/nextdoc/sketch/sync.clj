(ns io.nextdoc.sketch.sync
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [rewrite-clj.node :as node]
            [rewrite-clj.zip :as z])
  (:import (clojure.lang ExceptionInfo)))

(defn rewrite-root
  [file-name]
  (let [f (io/file file-name)]
    (when-not (.exists f)
      (throw (ex-info "file not found" {:name file-name})))
    (-> f
        slurp
        z/of-string)))

(defn insert-sorted-registry-schema
  "insert a key-value pair into a sorted map.
   typically used to sync a registry with alpha sorted key entries."
  [map-zloc k v]
  (when-not (map? (z/sexpr map-zloc))
    (throw (ex-info "must start at registry map location" {:loc (z/sexpr map-zloc)})))
  (loop [current-key (z/down map-zloc)]                     ; start at first registry key
    (if (nil? current-key)
      ;; Insert at the end if no keys are greater
      (z/assoc map-zloc k v)
      (if (> (compare (name k) (name (z/sexpr current-key))) 0)
        (recur (-> current-key (z/right) (z/right)))        ; move to next key
        ;; insert before the current key if it is larger
        (-> current-key
            (z/insert-left k)
            (z/insert-left v)
            (z/insert-newline-left)
            (z/insert-space-left))))))

(defn registry-form-loc
  "starting anywhere inside a registry map, nav back up to the registry map location"
  [zloc]
  (loop [current zloc]
    (let [form (z/sexpr current)]
      (if (map? form)                                       ; registry is a map
        current
        (recur (z/up current))))))

(defn sync-model
  "sync the registry named 'generated' in a source file, ensuring all required keys are present.
   returns nil if no changes needed else the updated source and changes.
   throws if keys are present that are not required."
  [{:keys [file-name keys-required]}]
  (let [;; read or create source
        root (try (rewrite-root file-name)
                  (catch ExceptionInfo _
                    (println "File not found." file-name)
                    (z/of-string "(ns to-do)\n")))
        ;; find or insert the "generated" def form
        generated (or (some-> root
                              (z/find z/next (comp #{'generated} z/sexpr))
                              (z/up))
                      (-> root
                          (z/insert-right '(def generated {}))
                          (z/right)
                          (z/insert-newline-left 2)
                          (z/find 'ns z/prev)
                          (z/find 'generated z/next)))
        ;; nav down to the "generated" map
        generated-map (-> generated (z/down) (z/right) (z/right))
        existing-keys (-> generated-map (z/sexpr) keys set)
        adds (set/difference keys-required existing-keys)]
    (when-let [invalid (seq (remove keys-required existing-keys))]
      (throw (ex-info "extra keys found in generated registry" {:invalid invalid})))
    (when (seq adds)
      (let [;; insert new keys maintaining sort order
            with-adds (reduce (fn [zloc new-key]
                                (-> zloc
                                    (registry-form-loc)
                                    (insert-sorted-registry-schema new-key [:map])))
                              generated-map
                              adds)]
        {:adds   adds
         :source (z/root-string with-adds)}))))

(defn sort-registry
  "sort a zloc which is a map i.e. return a zloc which is a sorted map"
  [map-zloc]
  (->> (z/down map-zloc)                                    ; Go to the first child of the map (first key or val)
       (iterate z/right)                                    ; Create an infinite seq of siblings
       (take-while identity)                                ; Take until nil (end of children)
       (map z/node)                                         ; convert all zlocs into nodes
       (partition 2)                                        ; Pair keys with values
       (sort-by (comp node/sexpr first))                    ; Sort by keys
       (map #(interpose (node/spaces 2) %))
       (interpose [(node/newlines 1) (node/spaces 1)])
       (reduce into [])                                     ; Convert back to single seq
       (node/map-node)                                      ; load into a map node
       (z/replace map-zloc)                                 ; replace existing loc
       ))
