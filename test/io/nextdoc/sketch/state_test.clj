(ns io.nextdoc.sketch.state-test
  (:require [clojure.test :refer :all]
            [io.nextdoc.sketch.state :as state]))

(deftest record-id-uniqueness-test
  (testing "Only one record per ID can exist in a database state store"
    (let [store (state/atom-state-store)]
      ;; Create a table for testing
      (state/create-table store :users)
      
      ;; Add initial record with ID 1
      (state/put-record! store :users {:id 1 :name "Original" :data "Initial"})
      
      ;; Verify record exists
      (let [initial-records (state/query store :users (constantly true))]
        (is (= 1 (count initial-records)) "Should have exactly one record")
        (is (= 1 (:id (first initial-records))) "Record should have ID 1")
        (is (= "Original" (:name (first initial-records)))))
      
      ;; Add another record with the same ID but different data
      (state/put-record! store :users {:id 1 :name "Updated" :data "New"})
      
      ;; Verify we still have just one record
      (let [updated-records (state/query store :users (constantly true))]
        (is (= 1 (count updated-records)) "Should still have exactly one record")
        
        ;; Verify the record was updated, not duplicated
        (let [record (first updated-records)]
          (is (= 1 (:id record)) "Record should have ID 1")
          (is (= "Updated" (:name record)) "Name should be updated")
          (is (= "New" (:data record)) "Data should be updated"))))))

(deftest state-associative-test
  (testing "StateAssociative protocol implementation"
    (let [store (state/atom-state-store)]
      
      (testing "put-value! and get-value"
        (state/put-value! store :key1 "value1")
        (is (= "value1" (state/get-value store :key1)))
        (state/put-value! store :key2 {:nested "data"})
        (is (= {:nested "data"} (state/get-value store :key2))))
      
      (testing "overwrite existing value"
        (state/put-value! store :key1 "updated-value")
        (is (= "updated-value" (state/get-value store :key1))))
      
      (testing "delete-value!"
        (state/delete-value! store :key1)
        (is (nil? (state/get-value store :key1)))
        (is (= {:nested "data"} (state/get-value store :key2))))
      
      (testing "as-lookup returns all key-value pairs"
        (state/put-value! store :key3 "value3")
        (is (= {:key2 {:nested "data"}
                :key3 "value3"}
               (state/as-lookup store)))))))

(deftest state-database-test
  (testing "StateDatabase protocol implementation"
    (let [store (state/atom-state-store)]
      
      (testing "create-table"
        (state/create-table store :users)
        (is (= {:users #{}} (state/as-map store)))
        
        ;; Creating an existing table doesn't overwrite it
        (state/put-record! store :users {:id 1 :name "Alice"})
        (state/create-table store :users)
        (is (= {:users #{{:id 1 :name "Alice"}}} (state/as-map store))))
      
      (testing "put-record! and get-record"
        (state/create-table store :items)
        
        ;; Insert new records
        (state/put-record! store :users {:id 2 :name "Bob"})
        (state/put-record! store :items {:id "item-1" :name "Laptop"})
        
        ;; Retrieve records by ID
        (is (= {:id 1 :name "Alice"} 
               (state/get-record store :users 1)))
        (is (= {:id 2 :name "Bob"} 
               (state/get-record store :users 2)))
        (is (= {:id "item-1" :name "Laptop"} 
               (state/get-record store :items "item-1"))))
      
      (testing "upsert behavior of put-record!"
        ;; Update existing record
        (state/put-record! store :users {:id 1 :name "Alice Updated" :role "Admin"})
        (is (= {:id 1 :name "Alice Updated" :role "Admin"} 
               (state/get-record store :users 1))))
      
      (testing "query"
        ;; Add more records for testing queries
        (state/put-record! store :users {:id 3 :name "Charlie" :role "User"})
        (state/put-record! store :users {:id 4 :name "Dave" :role "Admin"})
        
        ;; Query for all admins
        (let [admins (state/query store :users #(= "Admin" (:role %)))]
          (is (= 2 (count admins)))
          (is (= #{1 4} (set (map :id admins)))))
        
        ;; Query with a different predicate
        (let [d-names (state/query store :users #(.startsWith (:name %) "D"))]
          (is (= 1 (count d-names)))
          (is (= "Dave" (:name (first d-names))))))
      
      (testing "delete-record!"
        (state/delete-record! store :users 3)
        (is (nil? (state/get-record store :users 3)))
        
        ;; Other records remain
        (is (= 3 (count (state/query store :users (constantly true))))))
      
      (testing "clear!"
        (state/clear! store)
        (is (= {} (state/as-map store)))))))

(deftest combined-operations-test
  (testing "Combined operations between associative and database interfaces"
    (let [store (state/atom-state-store)]
      
      ;; Set up some state in both interfaces
      (state/put-value! store :config {:api-key "secret123"})
      (state/create-table store :users)
      (state/put-record! store :users {:id 1 :name "Alice"})
      
      ;; Verify both interfaces work independently
      (is (= {:api-key "secret123"} (state/get-value store :config)))
      (is (= {:id 1 :name "Alice"} (state/get-record store :users 1)))
      
      ;; Clear should affect database but not associative
      (state/clear! store)
      (is (= {} (state/as-map store)))
      (is (= {:api-key "secret123"} (state/get-value store :config)))
      
      ;; Operations after clear
      (state/create-table store :items)
      (state/put-record! store :items {:id "item-1" :name "Laptop"})
      (is (= {:items #{{:id "item-1" :name "Laptop"}}} (state/as-map store))))))

(deftest edge-cases-test
  (testing "Edge cases"
    (let [store (state/atom-state-store)]
      
      (testing "Empty database operations"
        (is (nil? (state/get-record store :non-existent-table 1)))
        (is (empty? (state/query store :non-existent-table (constantly true))))
        
        ;; Put to non-existent table should fail gracefully
        (is (thrown? Exception (state/put-record! store :non-existent-table {:id 1}))))
      
      (testing "Nil and empty values"
        (state/put-value! store :nil-key nil)
        (is (contains? (state/as-lookup store) :nil-key))
        (is (nil? (state/get-value store :nil-key)))
        
        (state/create-table store :empty-table)
        (is (= {:empty-table #{}} (state/as-map store))))
      
      (testing "Records without :id field"
        (state/create-table store :users)
        (is (thrown? Exception (state/put-record! store :users {:name "No ID"})))))))
