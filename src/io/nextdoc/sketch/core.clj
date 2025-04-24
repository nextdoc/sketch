(ns io.nextdoc.sketch.core
  (:require [com.rpl.specter :refer [ALL FIRST MAP-VALS collect-one must select]]
            [malli.core :as m]
            [malli.registry :as mr]
            [malli.util :as mu]
            [rewrite-clj.zip :as z]))

; see https://github.com/clj-commons/rewrite-clj/blob/main/doc/01-user-guide.adoc

; TODO this tool can generate and validate :where #{} in schema i.e. filter schemas for typescript and apex generators
; TODO choose source or target naming: maybe plugin fns for generating namespace and name
; TODO only include keywords in schema if there's data-flow that uses them

(defn map-entry-location?
  "return true if a zloc is a vector containing a :keyword and more i.e. the shape of a schema map entry."
  [zloc]
  (let [form (z/sexpr zloc)]
    (and (vector? form)
         (keyword? (first form))
         (not= :map (first form))
         (> (count form) 1))))

(defn next-map-entry
  "from any location in a Malli :map schema, navigate to the 'next' map-entry-location? node.
   next means the (top level) map entry after the current entry.
   always use a z/subedit-> in code that uses this fn because that controls the 'top' :map node."
  [zloc]
  (let [current-entry (loop [zloc zloc
                             highest-entry nil]
                        (if (or (nil? zloc) (:end? zloc))
                          highest-entry
                          (recur (z/up zloc)
                                 (if (map-entry-location? zloc)
                                   zloc
                                   highest-entry))))]
    (z/right current-entry)))

(defn insert-sorted-map-entry
  "insert a key-value pair into a map schema.
   typically used to sync [:map] schemas with alpha sorted key entries.
   uses next-map-entry so should be passed a z/subedit-> zloc."
  [map-zloc k v]
  (loop [current-key (z/find map-zloc z/next map-entry-location?)] ; start at first entry
    (if (z/end? current-key)
      ;; Insert at the end if no keys are greater
      (-> map-zloc
          (z/down)
          (z/rightmost)
          ; insert at end of map in reverse order i.e. push new nodes down
          (z/insert-right [k v])
          (z/insert-newline-right))
      (if (> (compare (name k) (name (first (z/sexpr current-key)))) 0)
        (recur (next-map-entry current-key))                ; move to next key
        ;; insert before the current key if it is larger
        (-> current-key
            ;(z/skip-whitespace-left)
            (z/insert-left [k v])
            (z/insert-newline-left))))))

(defn duplicate-actor-ids
  [locations]
  (->> locations
       (select [MAP-VALS :actors MAP-VALS :id])
       (frequencies)
       (select [ALL (comp (complement #{1}) last) FIRST])))

(defn duplicate-port-ids
  [locations]
  (->> locations
       (select [MAP-VALS
                :actors MAP-VALS
                :ports MAP-VALS :id])
       (frequencies)
       (select [ALL (comp (complement #{1}) last) FIRST])))

(defn mismatched-ids
  [map-entries]
  (filterv (fn [[k v]] (not= k (:id v))) map-entries))

(def ^{:doc "Schemas for the model of network locations, actors and state"}
  model-registry
  (merge
    (m/default-schemas)
    (mu/schemas)
    {:domain    [:map {:description "Top level of model"}
                 [:locations [:and
                              [:map-of :keyword :location]
                              [:fn map?]
                              [:fn {:error/fn (fn [{:keys [value]} _]
                                                (str "duplicate actor ids found " (duplicate-actor-ids value)))}
                               (fn [locations]
                                 (empty? (duplicate-actor-ids locations)))]
                              [:fn {:error/fn (fn [{:keys [value]} _]
                                                (str "duplicate port ids found " (duplicate-port-ids value)))}
                               (fn [locations]
                                 (empty? (duplicate-port-ids locations)))]]]
                 [:domain-entities [:map-of :keyword :named]]
                 [:data-flow [:vector :flow]]]
     :location  [:and {:description "A host environment where an actor can execute"}
                 :named
                 [:map
                  [:state [:map-of :keyword :state]]
                  [:actors [:map-of :keyword :actor]]]]
     :state     [:and {:description "A persistent store of state e.g. database, cache, etc"}
                 :named
                 [:map
                  [:type [:enum :associative :database]]
                  [:entities {:optional true} [:set :keyword]]
                  [:primary-keys {:optional true} [:map-of :keyword :keyword]]]]
     :actor     [:and {:description "A process running at a location that can handle and emit data"}
                 :named
                 [:map
                  [:ports [:and {:description "A collection of communication ports"}
                           ; TODO port ids must be unique
                           [:map-of :keyword :named]
                           :id-map]]]]
     :flow-edge [:map {:description "A connection between two ports"}
                 [:description :string]
                 ; refs below validated by aero deref. return nil if not valid
                 [:from :named]
                 [:to :named]]
     :flow      [:or {:description "a one-direction or bi-directional connection between two ports"}
                 [:and :flow-edge
                  [:map
                   [:request :keyword]]]
                 [:and :flow-edge
                  [:map
                   [:event :keyword]]]]
     :id-map    [:fn {:error/fn (fn [{:keys [value]} _]
                                  (str "id in map doesn't match parent map" (mismatched-ids value)))}
                 (fn [map-entries]
                   (empty? (mismatched-ids map-entries)))]
     :named     [:map {:description "An entity in the model which needs identity"}
                 [:id :keyword]
                 [:name :string]]}))

(defn actor-keys
  "return schema keywords for all actors that communicate (using ports) in the system"
  [domain]
  (->> domain
       (select [:locations MAP-VALS (collect-one :id)
                :actors MAP-VALS :id])
       (mapv (fn [[loc act]]
               (keyword (str (name loc))
                        (name act))))))

(defn state-roots
  "return schema keywords for all state stores where records can be persisted"
  [domain]
  (->> domain
       (select [:locations MAP-VALS (collect-one :id)])
       (mapcat (fn [[location {:keys [state actors]}]]
                 (for [actor (map :id (vals actors))
                       state (map :id (vals state))]
                   (keyword (str (name location) "-" (name actor))
                            (name state)))))
       (vec)))

(defn state-root-entities
  "return schema keywords for top level records persisted in state across the system"
  [domain]
  (->> domain
       (select [:locations MAP-VALS (collect-one :id)
                :state MAP-VALS (collect-one :id)
                :entities ALL])
       (mapv (fn [[loc state entity]]
               (keyword (str (name loc) "-" (name state))
                        (name entity))))))

(defn port-lookup
  "return a lookup using port id as key where values are [location actor]"
  [domain]
  (->> domain
       (select [:locations MAP-VALS (collect-one :id)
                :actors MAP-VALS (collect-one :id)
                :ports MAP-VALS :id])
       (map (juxt last drop-last))
       (into {})))

(defn data-flow-events
  "return schema keywords for all communication events between actors in the system.
   actor-filter in opts can restrict to events involving specific actors.
   :request or :event variant used as name of key.
   returned in :data-flow order i.e. this could closely match the sequence diagram because flow events added as test grows."
  ([domain] (data-flow-events domain {}))
  ([domain {:keys [actor-filter]
            :or   {actor-filter (constantly true)}}]
   ; because ports are unique (enforced by schema) we can lookup path to a port
   ; to derive the location and actor
   (let [lookup-port (port-lookup domain)]
     (->> domain
          (select [:data-flow ALL (collect-one [:from :id]) (collect-one [:to :id])])
          (keep (fn [[from-port-id to-port-id {:keys [request event]}]]
                  (let [[_ from-actor] (lookup-port from-port-id)
                        [location to-actor] (lookup-port to-port-id)]
                    (when (some nil? [from-actor location to-actor])
                      (throw (ex-info "missing model ref for data flow event" {:request    request
                                                                               :event      event
                                                                               :from-actor from-actor
                                                                               :location   location
                                                                               :to-actor   to-actor})))
                    (when (actor-filter from-actor to-actor)
                      (keyword (str (name location) "-" (name to-actor))
                               (or (some-> request name)
                                   (some-> event name)))))))
          (vec)))))

(defn data-flow-pull-packets
  "return schema keywords for all request/response/event data transferred between actors in the system"
  [domain]
  (let [lookup-port (port-lookup domain)]
    (->> domain
         (select [:data-flow ALL (collect-one [:to :id]) (must :request)])
         (mapcat (fn [[port-id request]]
                   (let [[location actor] (lookup-port port-id)]
                     [(keyword (str (name location) "-" (name actor))
                               (-> request name (str "-request")))
                      (keyword (str (name location) "-" (name actor))
                               (-> request name (str "-response")))])))
         (sort)
         (vec))))


(defn errors
  "return schema keywords for all errors that can be returned by an actor that receives an event"
  ; these events can include N user-visible messages but contents are not handled here, only names
  [domain]
  (let [receiving-ports (set (select [:data-flow ALL :to :id] domain))]
    (->> domain
         (select [:locations MAP-VALS (collect-one :id)
                  :actors MAP-VALS (collect-one :id)
                  :ports MAP-VALS :id])
         (keep (fn [[location actor port-id]]
                 (when (contains? receiving-ports port-id)
                   [location actor])))
         (map (fn [[loc act]]
                (keyword (str (name loc) "-" (name act))
                         "error"))))))

(defn map-schema-keys
  "return a set of keywords containing all map schemas used at any level including and below a schema key"
  ; tried m/walk but it doesn't see the schema keyword/name and that's what is collected here
  [registry schema-key]
  (letfn [(map-descendant-keys [acc schema-keys]
            (mapv (fn [schema-key]
                    (if-let [schema (mr/schema registry schema-key)]
                      (let [; recursively find below
                            nested (cond
                                     (keyword? schema)
                                     (map-descendant-keys acc [schema])

                                     (vector? schema)       ; ignore IntoSchema etc
                                     (case (first schema)
                                       :vector (map-descendant-keys acc [(last schema)])
                                       :map (->> (rest schema)
                                                 (remove map?) ; ignore props
                                                 (mapcat (fn [child-map-entry]
                                                           (let [entry-schema (last child-map-entry)]
                                                             (cond
                                                               ; keyword might be a reference to another map schema
                                                               (keyword? entry-schema) [entry-schema]
                                                               ; vector is a nested schema e.g. [:map-of...], [:vector...] etc
                                                               (vector? entry-schema) (rest entry-schema)
                                                               :else []))))
                                                 (map-descendant-keys acc))
                                       :map-of (->> (rest schema)
                                                    (remove map?) ; ignore props
                                                    (map-descendant-keys acc))
                                       :multi               ; 2-tuple labelled alternatives
                                       (->> (rest schema)
                                            (remove map?)   ; ignore props
                                            (map last)      ; multi options are a name and a schema
                                            (map-descendant-keys acc))
                                       :orn                 ; 2-tuple labelled alternatives
                                       (->> (rest schema)
                                            (remove map?)   ; ignore props
                                            (map last)      ; multi options are a name and a schema
                                            (map-descendant-keys acc))
                                       (:merge :and :or)    ; schemas or keys for alternatives
                                       (->> (rest schema)
                                            (remove map?)   ; ignore props
                                            (map-descendant-keys acc))
                                       [])

                                     :else [])
                            map-schema? (and (vector? schema)
                                             (= :map (first schema)))]
                        (cond-> (reduce into acc nested)
                                map-schema? (conj schema-key)))
                      acc))
                  schema-keys))]
    (first (map-descendant-keys (sorted-set) [schema-key]))))

(defn map-multi-schemas
  "return a map of keyword -> keyword, containing all schemas that are part of a top level :multi schema"
  [registry]
  (->> (mr/schemas registry)
       (filter (fn [[_ v]] (and (vector? v) (= :multi (first v)))))
       (mapcat (fn [[multi-schema-key schema]]
                 (->> (rest schema)
                      (remove map?)                         ; ignore props
                      (map last)
                      (filter keyword)
                      (map #(vector % multi-schema-key)))))
       (into (sorted-map))))
