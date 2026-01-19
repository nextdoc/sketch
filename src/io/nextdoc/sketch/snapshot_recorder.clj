(ns io.nextdoc.sketch.snapshot-recorder
  "Component for recording state snapshots during test execution.
   Tracks state changes, messages, and assigns snapshot-ids for visualization.")

;; =============================================================================
;; Pure Functions
;; =============================================================================

(defn assign-snapshot-ids
  "Given messages (with :step-index) and snapshot-history vector,
   assign :snapshot-id using shifted logic: message N shows state
   just before message N+1 is emitted (accumulated state view).

   For the last message, uses the final snapshot."
  [messages snapshot-history]
  (vec (map-indexed
         (fn [msg-idx msg]
           (let [next-msg (get messages (inc msg-idx))
                 snapshot-id
                 (if next-msg
                   ;; Use snapshot from just before the step that emits next message
                   (let [next-step-idx (:step-index next-msg)]
                     (if (pos? next-step-idx)
                       (get snapshot-history (dec next-step-idx))
                       ;; Next message emitted at step 0, use its snapshot
                       (get snapshot-history 0)))
                   ;; Last message: use the final snapshot
                   (last snapshot-history))]
             (-> msg
                 (assoc :snapshot-id snapshot-id)
                 (dissoc :step-index))))
         messages)))

;; =============================================================================
;; Recorder Component
;; =============================================================================

(defn make-recorder
  "Creates a new snapshot recorder for tracking state changes during test execution.
   Returns an atom containing the recorder state."
  []
  (atom {:step-snapshots     {}    ;; step-id -> full state snapshot
         :messages           []    ;; messages with step-index (for post-processing)
         :snapshot-history   []    ;; latest-snapshot-id after each step
         :latest-snapshot-id nil   ;; most recent snapshot id
         :latest             {}    ;; most recent state (for change detection)
         :step-index         0}))  ;; current step index

(defn record-state-change!
  "Records a state snapshot if the state has changed from the previous snapshot.
   Updates latest-snapshot-id to step-id if state changed.
   Returns true if a snapshot was recorded, false otherwise."
  [recorder step-id current-state]
  (let [{:keys [latest]} @recorder
        state-changed? (not= current-state latest)
        should-record? (boolean (and step-id state-changed?))]
    (when should-record?
      (swap! recorder assoc
             :latest current-state
             :latest-snapshot-id step-id)
      (swap! recorder update :step-snapshots assoc step-id current-state))
    should-record?))

(defn record-step-complete!
  "Records that a step has completed. Updates snapshot-history with current
   latest-snapshot-id and increments step-index.
   Should be called after run-step! completes and state changes are recorded."
  [recorder]
  (swap! recorder update :snapshot-history conj (:latest-snapshot-id @recorder))
  (swap! recorder update :step-index inc))

(defn current-step-index
  "Returns the current step index (0-based)."
  [recorder]
  (:step-index @recorder))

(defn record-message!
  "Records a message emitted during a step.
   step-index should be the index of the step that emitted the message."
  [recorder step-index msg]
  (swap! recorder update :messages conj
         {:step-index step-index
          :data-flow  (:data-flow msg)
          :message    (:message msg)}))

(defn finalize
  "Finalizes the recorder state by:
   1. Assigning snapshot-ids to messages using shifted logic
   2. Removing internal tracking fields
   Returns the final state suitable for serialization."
  [recorder]
  (let [{:keys [messages snapshot-history step-snapshots]} @recorder
        messages-with-snapshots (assign-snapshot-ids messages snapshot-history)]
    {:step-snapshots step-snapshots
     :messages       messages-with-snapshots}))
