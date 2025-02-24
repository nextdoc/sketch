(ns ^{:doc "Emulation of persisted state for tests"}
  io.nextdoc.sketch.state
  (:require [com.rpl.specter :refer [ALL select select-first]]))

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

(defn atom-state-store
  []
  (let [database (atom {})
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
      (get-record [_ entity-type id]
        (select-first [entity-type ALL (comp #{id} :id)] @database))
      (query [_ entity-type predicate]
        (select [entity-type ALL predicate] @database))
      (put-record! [_ entity-type record]
        (swap! database update entity-type (fnil conj #{}) record))
      (delete-record! [_ entity-type id]
        (swap! database update entity-type (fn [db]
                                             (->> db (remove (comp #{id} :id)) set))))
      (clear! [_] (reset! database {}))
      (as-map [_] @database))))
