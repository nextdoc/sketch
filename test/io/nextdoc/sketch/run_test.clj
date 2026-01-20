(ns io.nextdoc.sketch.run-test
  (:require [clojure.test :refer :all]
            [io.nextdoc.sketch.run :as run]
            [io.nextdoc.sketch.state :as state]))

(deftest tracking-state-store-test
  (testing "tracking-state-store records actor access to state stores"
    (let [system (atom run/empty-system)
          ;; Create underlying store
          underlying-store (state/atom-state-store)
          _ (state/create-table underlying-store :users)

          ;; Create tracking wrapper for actor :aws/lambda
          lambda-store (run/tracking-state-store underlying-store :ddb :aws/lambda system)

          ;; Create tracking wrapper for actor :aws/sqs-worker (same underlying store)
          sqs-store (run/tracking-state-store underlying-store :ddb :aws/sqs-worker system)]

      ;; Initially no usage recorded
      (is (= {} (:actor-state-usage @system)))

      ;; Lambda writes a record
      (state/put-record! lambda-store :users {:id 1 :name "Alice"})
      (is (= {:aws/lambda #{:ddb}} (:actor-state-usage @system))
          "Lambda access should be recorded")

      ;; SQS worker queries the same store
      (state/query sqs-store :users (constantly true))
      (is (= {:aws/lambda #{:ddb}
              :aws/sqs-worker #{:ddb}}
             (:actor-state-usage @system))
          "SQS worker access should also be recorded")

      ;; Underlying data is shared
      (is (= [{:id 1 :name "Alice"}] (state/query sqs-store :users (constantly true)))
          "SQS worker should see data written by Lambda"))))

(deftest tracking-multiple-stores-test
  (testing "tracking correctly records access to different state stores"
    (let [system (atom run/empty-system)
          ;; Create two underlying stores
          ddb-store (state/atom-state-store)
          env-store (state/atom-state-store)
          _ (state/create-table ddb-store :users)

          ;; Lambda gets tracking wrappers for both stores
          lambda-ddb (run/tracking-state-store ddb-store :ddb :aws/lambda system)
          lambda-env (run/tracking-state-store env-store :env :aws/lambda system)

          ;; SQS worker only gets wrapper for ddb
          sqs-ddb (run/tracking-state-store ddb-store :ddb :aws/sqs-worker system)]

      ;; Lambda accesses both stores
      (state/put-record! lambda-ddb :users {:id 1 :name "Alice"})
      (state/put-value! lambda-env "API_KEY" "secret")

      ;; SQS worker only accesses ddb
      (state/query sqs-ddb :users (constantly true))

      ;; Verify tracking
      (is (= {:aws/lambda #{:ddb :env}
              :aws/sqs-worker #{:ddb}}
             (:actor-state-usage @system))
          "Each actor should only show stores they actually accessed"))))

(deftest tracking-no-duplicate-recording-test
  (testing "multiple accesses to same store don't create duplicates"
    (let [system (atom run/empty-system)
          underlying-store (state/atom-state-store)
          _ (state/create-table underlying-store :users)
          tracked-store (run/tracking-state-store underlying-store :ddb :aws/lambda system)]

      ;; Multiple operations on same store
      (state/put-record! tracked-store :users {:id 1 :name "Alice"})
      (state/put-record! tracked-store :users {:id 2 :name "Bob"})
      (state/query tracked-store :users (constantly true))
      (state/get-record tracked-store :users 1)

      ;; Should still just have one entry (set semantics)
      (is (= {:aws/lambda #{:ddb}} (:actor-state-usage @system))))))
