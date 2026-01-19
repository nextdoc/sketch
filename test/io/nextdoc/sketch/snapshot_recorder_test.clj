(ns io.nextdoc.sketch.snapshot-recorder-test
  (:require [clojure.test :refer [deftest is testing]]
            [io.nextdoc.sketch.snapshot-recorder :as recorder]))

;; =============================================================================
;; Tests for assign-snapshot-ids (pure function)
;; =============================================================================

(deftest assign-snapshot-ids-test
  (testing "empty messages returns empty vector"
    (is (= [] (recorder/assign-snapshot-ids [] ["snap-0"]))))

  (testing "single message uses final snapshot"
    (let [messages [{:step-index 0 :data-flow {:desc "msg1"}}]
          history ["snap-0"]
          result (recorder/assign-snapshot-ids messages history)]
      (is (= 1 (count result)))
      (is (= "snap-0" (:snapshot-id (first result))))
      (is (nil? (:step-index (first result))) "step-index should be removed")))

  (testing "two messages - first uses snapshot before second's step"
    ;; Message 0 emitted at step 0, message 1 emitted at step 2
    ;; Message 0 should show snapshot from step 1 (just before step 2)
    (let [messages [{:step-index 0 :data-flow {:desc "msg0"}}
                    {:step-index 2 :data-flow {:desc "msg1"}}]
          ;; History: after step 0 = "snap-0", after step 1 = "snap-1", after step 2 = "snap-2"
          history ["snap-0" "snap-1" "snap-2"]
          result (recorder/assign-snapshot-ids messages history)]
      (is (= "snap-1" (:snapshot-id (first result))) "msg0 should use snapshot before msg1's step")
      (is (= "snap-2" (:snapshot-id (second result))) "msg1 (last) should use final snapshot")))

  (testing "state from non-emitting step visible in subsequent message"
    ;; This tests the original bug: step 0 emits message, step 1 changes state but no message,
    ;; step 2 emits message. Message from step 0 should show state including step 1's changes.
    (let [messages [{:step-index 0 :data-flow {:desc "msg0"}}
                    {:step-index 2 :data-flow {:desc "msg1"}}]
          ;; step 0: emits msg0, snapshot = "step-0"
          ;; step 1: no message, changes state, snapshot = "step-1"
          ;; step 2: emits msg1, snapshot = "step-2"
          history ["step-0" "step-1" "step-2"]
          result (recorder/assign-snapshot-ids messages history)]
      ;; msg0 should show state from step-1 (before step 2 runs)
      (is (= "step-1" (:snapshot-id (first result)))
          "message should show accumulated state including non-emitting step")))

  (testing "multiple messages from same step"
    (let [messages [{:step-index 0 :data-flow {:desc "msg0"}}
                    {:step-index 0 :data-flow {:desc "msg1"}}
                    {:step-index 1 :data-flow {:desc "msg2"}}]
          history ["snap-0" "snap-1"]
          result (recorder/assign-snapshot-ids messages history)]
      ;; msg0: next is msg1 at step 0, so use snapshot before step 0 = snap at index -1, clamped to 0
      (is (= "snap-0" (:snapshot-id (nth result 0))))
      ;; msg1: next is msg2 at step 1, so use snapshot before step 1 = snap-0
      (is (= "snap-0" (:snapshot-id (nth result 1))))
      ;; msg2: last message, use final snapshot
      (is (= "snap-1" (:snapshot-id (nth result 2))))))

  (testing "next message at step 0 uses step 0's snapshot"
    ;; Edge case: message N+1 is emitted at step 0
    (let [messages [{:step-index 0 :data-flow {:desc "msg0"}}
                    {:step-index 0 :data-flow {:desc "msg1"}}]
          history ["snap-0"]
          result (recorder/assign-snapshot-ids messages history)]
      (is (= "snap-0" (:snapshot-id (first result))))
      (is (= "snap-0" (:snapshot-id (second result)))))))

;; =============================================================================
;; Tests for SnapshotRecorder component
;; =============================================================================

(deftest make-recorder-test
  (testing "creates recorder with initial state"
    (let [recorder (recorder/make-recorder)]
      (is (= {} (:step-snapshots @recorder)))
      (is (= [] (:messages @recorder)))
      (is (= [] (:snapshot-history @recorder)))
      (is (nil? (:latest-snapshot-id @recorder)))
      (is (= {} (:latest @recorder)))
      (is (= 0 (:step-index @recorder))))))

(deftest record-state-change-test
  (testing "records state change when state differs"
    (let [recorder (recorder/make-recorder)
          changed? (recorder/record-state-change! recorder "step-1" {:foo "bar"})]
      (is (true? changed?))
      (is (= {:foo "bar"} (get-in @recorder [:step-snapshots "step-1"])))
      (is (= "step-1" (:latest-snapshot-id @recorder)))
      (is (= {:foo "bar"} (:latest @recorder)))))

  (testing "does not record when state unchanged"
    (let [recorder (recorder/make-recorder)]
      (recorder/record-state-change! recorder "step-1" {:foo "bar"})
      (let [changed? (recorder/record-state-change! recorder "step-2" {:foo "bar"})]
        (is (false? changed?))
        (is (nil? (get-in @recorder [:step-snapshots "step-2"])))
        (is (= "step-1" (:latest-snapshot-id @recorder))))))

  (testing "does not record when step-id is nil"
    (let [recorder (recorder/make-recorder)
          changed? (recorder/record-state-change! recorder nil {:foo "bar"})]
      (is (false? changed?))
      (is (= {} (:step-snapshots @recorder))))))

(deftest record-step-complete-test
  (testing "appends to snapshot-history and increments step-index"
    (let [recorder (recorder/make-recorder)]
      (recorder/record-state-change! recorder "step-0" {:state 0})
      (recorder/record-step-complete! recorder)
      (is (= ["step-0"] (:snapshot-history @recorder)))
      (is (= 1 (:step-index @recorder)))

      (recorder/record-state-change! recorder "step-1" {:state 1})
      (recorder/record-step-complete! recorder)
      (is (= ["step-0" "step-1"] (:snapshot-history @recorder)))
      (is (= 2 (:step-index @recorder)))))

  (testing "records nil in history when no state change"
    (let [recorder (recorder/make-recorder)]
      (recorder/record-step-complete! recorder)
      (is (= [nil] (:snapshot-history @recorder))))))

(deftest current-step-index-test
  (testing "returns current step index"
    (let [recorder (recorder/make-recorder)]
      (is (= 0 (recorder/current-step-index recorder)))
      (recorder/record-step-complete! recorder)
      (is (= 1 (recorder/current-step-index recorder))))))

(deftest record-message-test
  (testing "records message with step-index"
    (let [recorder (recorder/make-recorder)
          msg {:data-flow {:desc "test"} :message {:payload "data"}}]
      (recorder/record-message! recorder 0 msg)
      (is (= 1 (count (:messages @recorder))))
      (is (= 0 (:step-index (first (:messages @recorder)))))
      (is (= {:desc "test"} (:data-flow (first (:messages @recorder))))))))

(deftest finalize-test
  (testing "produces final state with snapshot-ids assigned"
    (let [recorder (recorder/make-recorder)]
      ;; Step 0: changes state, emits message
      (recorder/record-state-change! recorder "step-0" {:data "v0"})
      (recorder/record-message! recorder 0 {:data-flow {:d 0} :message {:m 0}})
      (recorder/record-step-complete! recorder)

      ;; Step 1: changes state, no message (like lambda-cold-start)
      (recorder/record-state-change! recorder "step-1" {:data "v1"})
      (recorder/record-step-complete! recorder)

      ;; Step 2: emits message
      (recorder/record-message! recorder 2 {:data-flow {:d 2} :message {:m 2}})
      (recorder/record-step-complete! recorder)

      (let [result (recorder/finalize recorder)]
        (is (contains? result :step-snapshots))
        (is (contains? result :messages))
        (is (= 2 (count (:messages result))))
        ;; First message should show state from step-1 (before step 2)
        (is (= "step-1" (:snapshot-id (first (:messages result)))))
        ;; Last message uses final snapshot
        (is (= "step-1" (:snapshot-id (second (:messages result)))))
        ;; No step-index in final messages
        (is (nil? (:step-index (first (:messages result)))))))))

(deftest integration-scenario-test
  (testing "lambda-cold-start scenario - non-emitting step state visible"
    ;; Simulates: app-request -> lambda-cold-start (no msg) -> api-response
    (let [recorder (recorder/make-recorder)]
      ;; Step 0: app-user-info-request - changes core-data, emits message
      (recorder/record-state-change! recorder "app-user-info-request" {:core-data {:user "test"}})
      (recorder/record-message! recorder 0 {:data-flow {:desc "user-info req"} :message {}})
      (recorder/record-step-complete! recorder)

      ;; Step 1: lambda-cold-start - changes env/ddb, NO message
      (recorder/record-state-change! recorder "lambda-cold-start"
                                     {:core-data {:user "test"} :env {"API_KEY" "secret"} :ddb {}})
      (recorder/record-step-complete! recorder)

      ;; Step 2: api-user-info-response - changes state, emits message
      (recorder/record-state-change! recorder "api-user-info-response"
                                     {:core-data {:user "test" :status :active} :env {"API_KEY" "secret"} :ddb {}})
      (recorder/record-message! recorder 2 {:data-flow {:desc "user-info resp"} :message {}})
      (recorder/record-step-complete! recorder)

      (let [result (recorder/finalize recorder)
            msg0 (first (:messages result))
            msg1 (second (:messages result))]
        ;; Message 0 (user-info request) should show state from lambda-cold-start
        ;; which includes the env data
        (is (= "lambda-cold-start" (:snapshot-id msg0))
            "First message should show state including lambda-cold-start changes")
        (is (contains? (get-in result [:step-snapshots "lambda-cold-start"]) :env)
            "lambda-cold-start snapshot should include :env")
        ;; Message 1 uses final snapshot
        (is (= "api-user-info-response" (:snapshot-id msg1)))))))
