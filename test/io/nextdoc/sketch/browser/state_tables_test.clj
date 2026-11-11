(ns io.nextdoc.sketch.browser.state-tables-test
  (:require [clojure.test :refer :all]
            [io.nextdoc.sketch.browser.state-tables :as tables]))

(def custom-pk-store-types {:ddb :database})

(def custom-pk-primary-keys
  {:ddb {:subscriptions :topic-scope-id
         :connections   :connection-request-id}})

(def two-subscriptions
  {:ddb {:subscriptions #{{:topic-scope-id "topic-a" :status "pending"}
                          {:topic-scope-id "topic-b" :status "pending"}}}})

(defn- entity-table
  [processed entity-type]
  (->> processed
       :data
       (filter (comp #{(name entity-type)} :name))
       (first)))

(defn- row-keys
  "Row identity keys the renderer uses for a single entity type."
  [processed entity-type]
  (-> (entity-table processed entity-type) :data keys set))

(deftest custom-primary-key-rows-do-not-collapse-test
  (testing "two records in a store with a non-:id primary key render as two rows"
    (let [processed (tables/process-store :ddb custom-pk-store-types custom-pk-primary-keys
                                          two-subscriptions nil)
          ks (row-keys processed :subscriptions)]
      (is (= 2 (count ks))
          "two distinct records must produce two rows")
      (is (not (contains? ks nil))
          "no record may key by nil")
      (is (= #{"topic-a" "topic-b"} ks)
          "each record must key by its declared primary key"))))

(deftest custom-primary-key-change-highlighting-test
  (testing "modifying one record of a custom-pk store highlights the changed field only"
    (let [curr {:ddb {:subscriptions #{{:topic-scope-id "topic-a" :status "active"}
                                       {:topic-scope-id "topic-b" :status "pending"}}}}
          processed (tables/process-store :ddb custom-pk-store-types custom-pk-primary-keys
                                          curr two-subscriptions)
          changes (:changes (entity-table processed :subscriptions))]
      (is (empty? (:added changes)) "an edit is not an add")
      (is (empty? (:deleted changes)) "an edit is not a delete")
      (is (= {"topic-a" #{:status}} (:modified changes))
          "only the edited record's edited field is modified"))))

(deftest deleted-record-detected-by-primary-key-test
  (testing "removing one record of a custom-pk store reports exactly that key deleted"
    (let [curr {:ddb {:subscriptions #{{:topic-scope-id "topic-a" :status "pending"}}}}
          processed (tables/process-store :ddb custom-pk-store-types custom-pk-primary-keys
                                          curr two-subscriptions)
          changes (:changes (entity-table processed :subscriptions))]
      (is (= #{"topic-b"} (:deleted changes)))
      (is (empty? (:added changes))))))

(deftest default-id-primary-key-still-works-test
  (testing "stores without declared primary keys keep :id semantics"
    (let [states {:app-db {:users #{{:id 1 :name "a"} {:id 2 :name "b"}}}}
          processed (tables/process-store :app-db {:app-db :database} {} states nil)]
      (is (= #{1 2} (row-keys processed :users))))))

(deftest primary-key-column-sorts-first-test
  (testing "the declared primary key is the leading column, not :id"
    (let [rendered (tables/create-tables-diagram
                     [{:name        "subscriptions"
                       :data        {"topic-a" {:status "pending" :topic-scope-id "topic-a"}}
                       :primary-key :topic-scope-id
                       :changes     {}}]
                     {:column-headers? true :max-columns 5})
          first-header (second (re-find #"<B>subscriptions</B></TD></TR><TR><TD ALIGN='LEFT'><B>(.*?)</B>"
                                        rendered))]
      (is (= "topic-scope-id" first-header)
          "primary key column must render first"))))

(def composite-pk-primary-keys
  {:ddb {:subscriptions [:connection-request-id :topic-scope-id]}})

(def two-connections-same-topic
  {:ddb {:subscriptions #{{:connection-request-id "conn-1" :topic-scope-id "topic-a" :status "pending"}
                          {:connection-request-id "conn-2" :topic-scope-id "topic-a" :status "pending"}}}})

(deftest composite-primary-key-rows-do-not-collapse-test
  (let [processed (tables/process-store :ddb custom-pk-store-types composite-pk-primary-keys
                                        two-connections-same-topic nil)]
    (is (= #{["conn-1" "topic-a"] ["conn-2" "topic-a"]} (row-keys processed :subscriptions)))))

(deftest composite-primary-key-change-detection-test
  (let [curr {:ddb {:subscriptions #{{:connection-request-id "conn-1" :topic-scope-id "topic-a" :status "active"}}}}
        processed (tables/process-store :ddb custom-pk-store-types composite-pk-primary-keys
                                        curr two-connections-same-topic)
        changes (:changes (entity-table processed :subscriptions))]
    (is (= #{["conn-2" "topic-a"]} (:deleted changes)))
    (is (empty? (:added changes)))
    (is (= {["conn-1" "topic-a"] #{:status}} (:modified changes)))))

(deftest composite-primary-key-columns-sort-first-in-key-order-test
  (let [rendered (tables/create-tables-diagram
                   [{:name        "subscriptions"
                     :data        {["conn-1" "topic-a"] {:status                "pending"
                                                         :topic-scope-id        "topic-a"
                                                         :connection-request-id "conn-1"}}
                     :primary-key [:topic-scope-id :connection-request-id]
                     :changes     {}}]
                   {:column-headers? true :max-columns 5})
        headers (->> (re-seq #"<TD ALIGN='LEFT'><B>(.*?)</B></TD>" rendered)
                     (map second)
                     (remove #{"subscriptions"}))]
    (is (= ["topic-scope-id" "connection-request-id" "status"] headers))))
