(ns ^{:doc "Emulation of persisted state for tests"}
  io.nextdoc.sketch.state
  (:require
    [com.rpl.specter :refer [ALL MAP-VALS collect-one select must select select-first]]))

(defprotocol StateDatabase
  "operations that a typical database can perform"
  (create-table [this entity-type]
    "Creates a new table for the given entity type if it doesn't exist")
  (get-record [this entity-type id]
    "Retrieves a record by its ID from the specified entity type table")
  (query [this entity-type predicate]
    "Returns all records from entity type table that match the predicate. Records returned include :id.")
  (put-record! [this entity-type value]
    "Stores a record in the entity type table")
  (delete-record! [this entity-type id]
    "Removes the record with the given ID from the entity type table")
  (clear! [this]
    "Removes all records from all tables")
  (as-map [this]
    "Returns the entire database state as a nested map"))

(defprotocol StateAssociative
  "operations that a typical lookup data-structure can perform"
  (get-value [this id]
    "Retrieves a value by its ID from the lookup store")
  (put-value! [this id value]
    "Stores a value with the given ID in the lookup store")
  (delete-value! [this id]
    "Removes the value with the given ID from the lookup store")
  (as-lookup [this]
    "Returns the entire lookup store state as a map"))

; TODO Split into two. https://github.com/nextdoc/sketch/issues/29
;  Provide factory function which receives past model and state key to dispatch to correct implementation.
(defn atom-state-store
  ([] (atom-state-store {}))
  ([{:keys [state-id primary-keys]}]
   (let [database (atom {})
         database-primary-keyword (fn [entity-type] (get-in primary-keys [state-id entity-type] :id))
         lookup (atom {})]
     (reify
       StateAssociative
       (get-value [_ id]
         (get @lookup id))
       (put-value! [_ id value]
         (swap! lookup assoc id value))
       (delete-value! [_ id]
         (swap! lookup dissoc id))
       (as-lookup [_]
         @lookup)
       StateDatabase
       (create-table [_ entity-type]
         (when (nil? (get @database entity-type))
           (swap! database assoc entity-type #{})))
       (get-record [_ entity-type primary-key]
         (let [primary-keyword (database-primary-keyword entity-type)]
           (select-first [entity-type ALL (comp #{primary-key} primary-keyword)] @database)))
       (query [_ entity-type predicate]
         (select [entity-type ALL predicate] @database))
       (put-record! [_ entity-type record]
         ;; Check if table exists
         (when-not (contains? @database entity-type)
           (throw (ex-info "Table does not exist" {:entity-type entity-type})))
         ;; Check if record has primary key
         (let [primary-keyword (database-primary-keyword entity-type)]
           (when-not (contains? record primary-keyword)
             (throw (ex-info "Record must have a primary key" {:key    primary-keyword
                                                               :record record})))
           (swap! database update entity-type
                  (fn upsert-by-id [db]
                    (conj
                      (->> db
                           (remove (comp #{(get record primary-keyword)} primary-keyword))
                           (set))
                      record)))))
       (delete-record! [_ entity-type primary-key]
         (let [primary-keyword (database-primary-keyword entity-type)]
           (swap! database update entity-type (fn [db]
                                                (->> db (remove (comp #{primary-key} primary-keyword)) set)))))
       (clear! [_] (reset! database {}))
       (as-map [_] @database)))))

(defn state-store-with-context
  "return the atom state store but with extra features such as:
   - Primary key for database records can be customised in the model
  "
  [{:keys [id model-parsed]}]
  (let [pks (->> model-parsed
                 (select [:locations MAP-VALS :state MAP-VALS (collect-one :id) (must :primary-keys)])
                 (into (sorted-map)))]
    (atom-state-store {:state-id     id
                       :primary-keys pks})))
