(ns io.nextdoc.sketch.browser.state-tables
  "Pure state-table rendering logic shared by the diagram browser app.
   Kept host-neutral so it can be exercised from JVM tests."
  (:require [clojure.set :as set]))

(defn detect-changes
  "Compare previous and current state to identify:
   - Added records (not present in previous state)
   - Deleted records (present in previous but not in current)
   - Modified fields (fields with different values)"
  [prev-state current-state]
  (cond
    ;; Both nil or empty - no changes
    (and (or (nil? prev-state) (empty? prev-state))
         (or (nil? current-state) (empty? current-state)))
    {}

    ;; Previous state is nil/empty - all current are adds
    (or (nil? prev-state) (empty? prev-state))
    {:added (set (keys current-state))}

    ;; Current state is nil/empty - all previous are deletes
    (or (nil? current-state) (empty? current-state))
    {:deleted (set (keys prev-state))}

    ;; Both have content - compare them
    :else
    (let [all-prev-keys (set (keys prev-state))
          all-curr-keys (set (keys current-state))
          added-records (set/difference all-curr-keys all-prev-keys)
          deleted-records (set/difference all-prev-keys all-curr-keys)
          common-records (set/intersection all-prev-keys all-curr-keys)

          ;; For each common record, find modified fields
          modified (reduce (fn [acc record-id]
                             (let [prev-record (get prev-state record-id)
                                   curr-record (get current-state record-id)
                                   ;; Handle records that might not be maps (for associative stores)
                                   modified-fields (when (and (map? curr-record) (map? prev-record))
                                                     (reduce-kv
                                                       (fn [field-acc field-key field-val]
                                                         (if (not= field-val (get prev-record field-key))
                                                           (conj field-acc field-key)
                                                           field-acc))
                                                       #{}
                                                       curr-record))
                                   modified-fields (or modified-fields #{})]
                               (if (seq modified-fields)
                                 (assoc acc record-id modified-fields)
                                 acc)))
                           {}
                           common-records)]
      {:added    added-records
       :deleted  deleted-records
       :modified modified})))

(defn create-tables-diagram
  "Creates a Graphviz diagram string showing multiple tables side by side
   Input: vector of maps, where each map has :name and :data keys
   Example: [{:name \"table1\" :data {...}} {:name \"table2\" :data {...}}]"
  [tables {:keys [column-headers? max-columns]}]
  (letfn [(make-html-table [data table-name changes primary-key]
            (when (seq data)
              (let [sorted-keys (->> (vals data)
                                     (first)
                                     (keys)
                                     (sort-by #(if (= primary-key %) [0 ""] [1 %])))
                    headers (str "<TR>"
                                 (->> sorted-keys
                                      (take max-columns)
                                      (map #(str "<TD ALIGN='LEFT'><B>" (name %) "</B></TD>"))
                                      (apply str))
                                 "</TR>")]
                (str "<TABLE BGCOLOR='white' BORDER='0' CELLBORDER='1' CELLSPACING='0' CELLPADDING='4'>"
                     "<TR><TD ALIGN='LEFT' COLSPAN='" (count sorted-keys) "'><B>" table-name "</B></TD></TR>"
                     (when column-headers? headers)
                     (apply str
                            (for [record-id (keys data)
                                  :let [row-data (get data record-id)
                                        added? (contains? (:added changes) record-id)
                                        deleted? (contains? (:deleted changes) record-id)
                                        modified-fields (get (:modified changes) record-id #{})]]
                              (str "<TR>"
                                   (->> sorted-keys
                                        (take max-columns)
                                        (mapv (fn [field-key]
                                                (let [value (get row-data field-key)
                                                      bg-color (cond
                                                                 added? "#A3E4D7" ; Green for added records
                                                                 deleted? "#F5B7B1" ; Red for deleted records
                                                                 (contains? modified-fields field-key) "#FAD7A0" ; Orange for changed fields
                                                                 :else "white")]
                                                  (str "<TD ALIGN='LEFT' BGCOLOR='" bg-color "'>"
                                                       (str value)
                                                       "</TD>"))))
                                        (apply str))
                                   "</TR>")))
                     "</TABLE>"))))

          (render-table [{:keys [name data changes primary-key]}]
            (when (seq data)
              (str "  \"" name "\" [label=<"
                   (make-html-table data name (or changes {}) (or primary-key :id))
                   ">];\n")))]

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
                (str "      <TR><TD ALIGN='LEFT'>" (name k) "</TD><TD ALIGN='LEFT'>" (str v) "</TD></TR>\n")))
       "    </TABLE>\n"
       "  >];\n"
       "}"))

(defn process-database-store
  "Process data for a database type store, handling empty tables and detecting changes"
  [single-store prev-store store-primary-keys]
  (reduce-kv (fn [acc entity-type records]
               (if (empty? records)
                 acc
                 (let [prev-records (get-in prev-store [entity-type])
                       primary-key (get store-primary-keys entity-type :id)
                       record-map (reduce #(assoc %1 (primary-key %2) %2) {} records)
                       prev-record-map (reduce #(assoc %1 (primary-key %2) %2) {} prev-records)
                       changes (detect-changes prev-record-map record-map)]
                   (assoc acc entity-type
                              {:records     records
                               :primary-key primary-key
                               :changes     changes}))))
             {}
             single-store))

(defn process-associative-store
  "Process data for an associative type store, detecting changes between states"
  [single-store prev-store]
  {:data    single-store
   :changes (detect-changes prev-store single-store)})

(defn format-database-data-for-rendering
  "Convert database data into format needed for table rendering"
  [data]
  (reduce-kv (fn [acc entity-type entity-data]
               (let [primary-key (:primary-key entity-data :id)]
                 (conj acc {:name        (name entity-type)
                            :data        (reduce (fn [acc record]
                                                   (assoc acc (primary-key record) record))
                                                 {}
                                                 (:records entity-data))
                            :primary-key primary-key
                            :changes     (:changes entity-data)})))
             []
             data))

(defn process-store
  "Process a single store for an actor, handling different store types"
  [store-key store-types store-primary-keys states-at-step prev-states-at-step]
  (let [single-store (get states-at-step store-key)
        prev-store (get prev-states-at-step store-key)
        data (when single-store
               (case (store-types store-key)
                 :database (process-database-store single-store prev-store
                                                   (get store-primary-keys store-key))
                 :associative (process-associative-store single-store prev-store)
                 {}))
        processed-data (case (store-types store-key)
                         :database (format-database-data-for-rendering data)
                         ;; For associative stores, just pass the data through
                         data)]
    {:store store-key
     :data  processed-data}))
