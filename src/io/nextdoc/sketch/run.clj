(ns io.nextdoc.sketch.run
  (:require [aero.core :refer [read-config]]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [com.rpl.specter :refer [ALL MAP-VALS collect-one multi-path select select-first]]
            [hiccup.core :refer [html]]
            [io.nextdoc.sketch.core :as core]
            [io.nextdoc.sketch.diagrams :refer [flow-sequence]]
            [io.nextdoc.sketch.snapshot-recorder :as recorder]
            [io.nextdoc.sketch.state :as state]
            [malli.core :as m]
            [malli.dev.pretty :as mp]
            [malli.error :as me]
            [malli.util :as mu]
            [taoensso.timbre :as log])
  (:import (clojure.lang ExceptionInfo)))

(defn state-store-wrapped
  "A delegate state store implementation that creates tables and TODO validates types for all entities persisted in the store"
  [impl {:keys [entities]}]
  (doseq [entity entities]
    (state/create-table impl (keyword (str (name entity) "s"))))
  (reify
    state/StateAssociative
    (get-value [this id] (state/get-value impl id))
    (put-value! [this id value] (state/put-value! impl id value))
    (delete-value! [this id] (state/delete-value! impl id))
    (as-lookup [this] (state/as-lookup impl))
    state/StateDatabase
    (get-record [_ entity-type id]
      (state/get-record impl entity-type id))
    (query [_ entity-type predicate]
      (state/query impl entity-type predicate))
    (put-record! [_ entity-type record]
      ; TODO check entity type allowed in meta
      (state/put-record! impl entity-type record))
    (delete-record! [_ entity-type id]
      (state/delete-record! impl entity-type id))
    (clear! [_]
      (state/clear! impl))
    (as-map [_]
      (state/as-map impl))))

(defn tracking-state-store
  "Wraps a state store to track actor access. Actor-key is bound at creation time (functional).
   Records usage in system atom under :actor-state-usage as {actor-key #{store-ids}}."
  [impl store-id actor-key system]
  (let [record-usage! #(swap! system update-in [:actor-state-usage actor-key] (fnil conj #{}) store-id)]
    (reify
      state/StateAssociative
      (get-value [_ id]
        (record-usage!)
        (state/get-value impl id))
      (put-value! [_ id value]
        (record-usage!)
        (state/put-value! impl id value))
      (delete-value! [_ id]
        (record-usage!)
        (state/delete-value! impl id))
      (as-lookup [_]
        (state/as-lookup impl))
      state/StateDatabase
      (get-record [_ entity-type id]
        (record-usage!)
        (state/get-record impl entity-type id))
      (query [_ entity-type predicate]
        (record-usage!)
        (state/query impl entity-type predicate))
      (put-record! [_ entity-type record]
        (record-usage!)
        (state/put-record! impl entity-type record))
      (delete-record! [_ entity-type id]
        (record-usage!)
        (state/delete-record! impl entity-type id))
      (clear! [_]
        (state/clear! impl))
      (as-map [_]
        (state/as-map impl)))))

(def empty-system {:state-stores            {}
                   :actor-state-usage       {}              ;; {actor-key #{store-ids...}} tracks which actors accessed which stores
                   :messages                {}
                   :entry-step              nil
                   :missing-message-schemas #{}
                   :invalid-message-schemas #{}
                   :missing-state-schemas   #{}})

(defn actor-meta
  "return the actor and location from the model meta matching using an actor keyword"
  [model-parsed actor-key]
  (let [location-key (some-> actor-key (namespace) (keyword))
        actor-key (some-> actor-key (name) (keyword))
        location (or (-> model-parsed :locations (get location-key))
                     (throw (ex-info "location not found" {:key location-key})))
        {:keys [actors]} location
        actor (or (get actors actor-key)
                  (throw (ex-info "actor not found" {:key actor-key})))]
    {:actor    actor
     :location location}))

(defn most-recent-message
  [{:keys [messages]}]
  (or (:message (last messages))
      (throw (ex-info "no messages received" {}))))

(defmacro this-ns
  "return the namespace symbol for a compiled source"
  []
  `~*ns*)

(defn sketch-diagram-call
  "convert an emitted sketch message into a vector used by sketch sequence diagrams"
  [model-parsed]
  (fn [{:keys [data-flow message]}]
    (let [port-path [:locations MAP-VALS (collect-one :id)
                     :actors MAP-VALS (collect-one :id)
                     :ports MAP-VALS]
          [from-loc from-actor] (select-first [port-path #{(:from data-flow)}] model-parsed)
          [to-loc to-actor] (select-first [port-path #{(:to data-flow)}] model-parsed)]
      ; shape needed for sequence diagram
      [(merge (select-keys data-flow [:description])
              (select-keys message [:request :event])
              {:from (keyword (str (name from-loc) "-" (name from-actor)))
               :to   (keyword (str (name to-loc) "-" (name to-actor)))})
       {:directions (or (some->> (:direction message) (conj #{})) #{:request})
        :async?     false}])))

(def success-string "SUCCESS!")

(defn message-data-flow
  "return a single data-flow from meta that matches an emitted message"
  [{:keys [model-parsed message from-ports]}]
  (let [to-actor (:actor (actor-meta model-parsed (:to message)))
        to-ports (-> to-actor :ports keys set)
        response? (= :response (:direction message))
        matching-data-flows (select [:data-flow ALL
                                     (if response?
                                       ; responses go opposite direction
                                       ; i.e. -> actor request originated :from
                                       (comp from-ports :id :to)
                                       ; requests and events -> actor message sent :to
                                       (comp to-ports :id :to))
                                     (multi-path (comp #{(:request message)} :request)
                                                 (comp #{(:response message)} :request)
                                                 (comp #{(:event message)} :event))]
                              model-parsed)]
    (when (not= 1 (count matching-data-flows))
      (throw (ex-info (if (zero? (count matching-data-flows))
                        "no data flow found"
                        "more than one data flow found")
                      {:actor   to-actor
                       :message message
                       :count   (count matching-data-flows)
                       :matches matching-data-flows})))
    (first matching-data-flows)))

(defn validate-payload!
  "validate an emitted message using a schema"
  [{:keys [system actor-key model-parsed registry message step step-info closed-data-flow-schemas?]}]
  (let [{from-actor    :actor
         from-location :location} (actor-meta model-parsed actor-key)
        {to-actor    :actor
         to-location :location} (actor-meta model-parsed (:to message))
        response? (= :response (:direction message))
        schema-namespace (if response?
                           (str (name (:id from-location)) "-" (name (:id from-actor)))
                           (str (name (:id to-location)) "-" (name (:id to-actor))))
        schema-name (cond
                      response? (str (name (:request message)) "-response")
                      (:request message) (str (name (:request message)) "-request")
                      (:event message) (name (:event message)) ; no suffix for events
                      :else (throw (ex-info "unhandled message type" message)))
        schema-keyword (keyword schema-namespace schema-name)
        schema (cond-> (m/deref-recursive (m/schema schema-keyword {:registry registry}))
                       closed-data-flow-schemas? (mu/closed-schema))]
    (try
      (when-let [ex (m/explain schema (:payload message))]
        (let [error (ex-info "invalid message payload" {:explain ex})]
          (log/warn (ex-message error))
          ;(pprint/pprint message)
          ;(pprint/pprint (me/humanize ex))
          (mp/explain schema (:payload message))
          (throw error)))
      (catch ExceptionInfo ei
        ; if schema is not in registry, record for warning at the end of all steps
        (if (= ::m/invalid-schema (:type (ex-data ei)))
          (if (-> (ex-data ei) :data :form)
            (swap! system update :invalid-message-schemas conj schema-keyword)
            (swap! system update :missing-message-schemas conj schema-keyword))
          (throw ei))))))

(defn validate-state-stores!
  "validate the state stores for the actor because handler may have changed them"
  [{:keys [system model-parsed actor-key registry state-schemas-ignored handler-context closed-state-schemas?]}]
  (let [{from-actor    :actor
         from-location :location} (actor-meta model-parsed actor-key)]
    (doseq [[k store] (:state handler-context)]
      (let [store-type (select-first [:locations (:id from-location) :state k :type] model-parsed)
            state (case store-type
                    :associative (state/as-lookup store)
                    :database (state/as-map store))]
        (when-not (zero? (count state))
          (let [schema-namespace (str (name (:id from-location)) "-" (name (:id from-actor)))
                schema-name (name k)
                schema-keyword (keyword schema-namespace schema-name)]
            (when-not (contains? state-schemas-ignored schema-keyword)
              (try
                (let [schema (cond-> (m/deref-recursive (m/schema schema-keyword {:registry registry}))
                                     closed-state-schemas? (mu/closed-schema))]

                  ; Check and warn that schemas for state stores match Sketch expectations.
                  (case store-type
                    :database
                    (when-let [invalid-database-schemas (->> (m/children schema)
                                                             (filter (fn [schema-entry]
                                                                       (not= :set (m/type (last schema-entry)))))
                                                             (map first)
                                                             (seq))]
                      (log/warn ":database schema in your registry must contain :set schemas" k invalid-database-schemas))
                    :associative
                    (when-not (contains? #{:map :map-of} (m/type schema))
                      (log/warn ":associative schema in your registry must be a :map or :map-of schema" k)))

                  (when-let [ex (m/explain schema state)]
                    (let [error (ex-info "invalid storage" {:explain ex})]
                      (log/error (ex-message error))
                      (clojure.pprint/pprint state)
                      (mp/explain schema state)
                      (throw error))))
                (catch ExceptionInfo ei
                  (if (= ::m/invalid-schema (:type (ex-data ei)))
                    (swap! system update :missing-state-schemas conj schema-keyword)
                    (throw ei)))))))))))

(defn log-todos!
  [system]
  (let [{:keys [missing-message-schemas invalid-message-schemas missing-state-schemas]} @system]
    (when (seq invalid-message-schemas)
      (log/warn "TODO fix invalid message schemas" invalid-message-schemas))
    (when (seq missing-message-schemas)
      (log/warn "TODO sync missing message schemas" missing-message-schemas))
    (when (seq missing-state-schemas)
      (log/warn "TODO sync missing state schemas" missing-state-schemas))
    (when (or (seq missing-message-schemas)
              (seq missing-state-schemas))
      (log/warn "  use nextdoc.fbp.stream3-sketch"))))

(defn sequence-diagram-page
  [{:keys [diagram title model states tag dev? step-actions actor-state-usage]}]
  [:html {:lang "en"}
   [:head
    [:meta {:charset "UTF-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
    [:title title]

    [:script {:src "https://cdn.jsdelivr.net/npm/mermaid/dist/mermaid.min.js"}]
    [:script {:src "https://cdn.jsdelivr.net/npm/d3@7/dist/d3.min.js"}]
    [:script {:src "https://cdn.jsdelivr.net/npm/d3-graphviz/build/d3-graphviz.min.js"}]

    [:script "mermaid.initialize({ startOnLoad: false, securityLevel: 'loose' });"]

    [:style (slurp (io/resource "io/nextdoc/sketch/browser/host-page.css"))]]

   [:body {:style "background-color:#BEC7FC;"}
    [:div#app]
    [:script {:type "text/javascript"}
     (str/join "\n" ["const load = () => {"
                     (format "io.nextdoc.sketch.browser.diagram_app.load('%s', %s, %s, %s, %s, %s, %s);"
                             title
                             (json/write-str diagram)
                             (-> states
                                 (pr-str)
                                 (json/write-str))
                             (-> model
                                 (pr-str)
                                 (json/write-str))
                             (json/write-str tag)
                             (json/write-str step-actions)
                             (-> actor-state-usage
                                 (pr-str)
                                 (json/write-str)))
                     "}"])]
    [:script {:type   "text/javascript"
              :src    (if dev? "http://localhost:8000/diagram-js/main.js"
                               (format "https://cdn.jsdelivr.net/gh/nextdoc/sketch@%s/app/main.js" tag))
              :async  true
              :onload "load();"}]]])

(defn write-sequence-diagram!
  [{:keys [test-ns-str diagram-dir diagram-config system model-parsed states dev? step-actions]}]
  (let [test-name (as-> test-ns-str $
                        (str/split $ #"\.")
                        (last $))
        file-name (as-> test-ns-str $
                        (str/split $ #"\.")
                        (into [diagram-dir] $)
                        (str/join "/" $))
        calls (->> @system
                   :messages :all
                   (mapv (sketch-diagram-call model-parsed)))
        diagram (flow-sequence {:diagram-config diagram-config
                                :calls          calls})]
    (log/info "updating diagram artifacts:" file-name)
    (io/make-parents (io/file file-name))
    (spit (str file-name ".mmd") diagram)
    (spit (str file-name ".html") (-> {:diagram           diagram
                                       :title             test-name
                                       :states            states
                                       :model             model-parsed
                                       :step-actions      step-actions
                                       :actor-state-usage (:actor-state-usage @system)
                                       :dev?              dev?
                                       :tag               "r0.1.33"}
                                      (sequence-diagram-page)
                                      (html)))
    (log/info success-string)))

(defn custom-println-formatter
  "Timbre formatter that renders logs optimised for easy reading of Sketch test events"
  [{:keys [level vargs msg_ ?err] :as data}]
  (let [step-start? (and (map? (first vargs))
                         (= #{:location :actor :action} (set (keys (first vargs)))))
        msg (force msg_)
        success? (= success-string msg)]

    (cond

      step-start?
      (let [{:keys [location actor action]} (first vargs)]
        (str (:name location)
             "\n\t" (format "\033[38;2;23;64;255m%s\033[0m" (:name actor))
             "\n\t\t" (format "\033[38;2;125;143;249m%s\033[0m" action)))

      success?
      (format "\033[92m%s\033[0m" msg)

      (= :warn level)
      (format "\033[91m%s\033[0m" msg)

      (= :error level)
      (format "\033[91m%s\033[0m" (str msg " " ?err))

      :else
      (let [log-line (if-let [exception-msg (ex-message ?err)]
                       (str exception-msg " " (pr-str (ex-data ?err)))
                       msg)]
        (str (str/upper-case (name level)) " " log-line)))))

(defn log-config
  "return Timbre log config optimised for Sketch tests"
  ([] (log-config nil))
  ([ns-sym]
   {:appenders {:println (cond-> {:enabled?  true
                                  :async?    false
                                  :min-level :debug
                                  :output-fn custom-println-formatter}
                                 ns-sym
                                 (assoc :filter-fn (fn [{:keys [ns-str]}]
                                                     (= ns-str (str ns-sym)))))}}))

(defn run-steps!
  "the entry point to run a sketch test"
  [{:keys [steps model state-store state-store-context registry state-schemas-ignored middleware
           diagram-dir diagram-name diagram-config verbose?
           closed-data-flow-schemas? closed-state-schemas? dev?]
    :or   {diagram-dir               "target/sketch-diagrams"
           verbose?                  false
           closed-data-flow-schemas? false
           closed-state-schemas?     false
           dev?                      false}}]
  (when verbose? (clojure.pprint/pprint steps))
  (let [model-parsed (or (some-> (io/resource model)
                                 (read-config))
                         (throw (ex-info "model not found" {:source model})))
        ; Same validation as applied when using the watcher to sync the model to a generated registry.
        _ (when-let [model-errors (-> :domain
                                      (m/deref-recursive {:registry core/model-registry})
                                      (m/explain model-parsed)
                                      (me/humanize))]
            (log/warn "Invalid model. Errors follow...")
            (println model-errors))
        system (atom empty-system)
        step-actions (atom [])                              ; Added to collect step actions
        run-step! (fn run-step! [step]
                    (when (nil? step)
                      (throw (ex-info "empty step!" {})))
                    (let [step-result (step)                ; invoke step thunk to access it's config
                          {:keys     [reset action before handler after]
                           actor-key :actor} step-result]
                      (cond
                        ; reset the atoms back to the empty state and provide access to system for seeding data
                        reset (do
                                (reset! system empty-system)
                                (reset system))
                        handler
                        (let [{from-actor    :actor
                               from-location :location} (actor-meta model-parsed actor-key)
                              from-ports (-> from-actor :ports keys set)
                              from-keyword (keyword (name (:id from-location))
                                                    (name (:id from-actor)))
                              ; Expose state specific to the actors location to its handler
                              actor-state-stores
                              (reduce
                                (fn [acc state-meta]
                                  (let [store-id (:id state-meta)
                                        ;; Get or create the shared underlying store
                                        shared-store (or
                                                       (-> @system :state-stores (get store-id))
                                                       (let [new-store (cond
                                                                         state-store (-> store-id
                                                                                         (state-store)
                                                                                         (state-store-wrapped state-meta))
                                                                         state-store-context (-> {:id           store-id
                                                                                                  :model-parsed model-parsed}
                                                                                                 (state-store-context)
                                                                                                 (state-store-wrapped state-meta))
                                                                         :else (throw (ex-info "missing state store factory fn in config" {})))]
                                                         (when verbose?
                                                           (println "Creating state store" store-id))
                                                         (swap! system assoc-in [:state-stores store-id] new-store)
                                                         new-store))]
                                    ;; Wrap with actor-specific tracking (actor bound at creation time)
                                    (assoc acc store-id (tracking-state-store shared-store store-id from-keyword system))))
                                {}
                                ; location specific states
                                (vals (:state from-location)))
                              handler-context {:state    actor-state-stores
                                               :messages (get-in @system [:messages actor-key])
                                               :fixtures (get-in @system [:fixtures (:id from-location)])}
                              current-step {:location from-location
                                            :actor    from-actor
                                            :action   action}]

                          (log/info current-step)

                          (try
                            (when before (before handler-context))
                            (when handler
                              (let [{:keys [emit]} (handler handler-context)]
                                (when emit
                                  (doseq [emitted emit]
                                    (let [emit-middleware (or (:emit middleware) identity)
                                          message (emit-middleware emitted)
                                          data-flow (message-data-flow {:model-parsed model-parsed
                                                                        :message      message
                                                                        :from-ports   from-ports})]
                                      (validate-payload! {:system                    system
                                                          :actor-key                 actor-key
                                                          :model-parsed              model-parsed
                                                          :closed-data-flow-schemas? closed-data-flow-schemas?
                                                          :registry                  registry
                                                          :message                   message
                                                          :step                      step
                                                          :step-info                 current-step})
                                      ; record emitted messages and actions
                                      (let [network-message {:data-flow data-flow
                                                             :message   (assoc message :from from-keyword)}]
                                        ; Collect the action for this message
                                        (swap! step-actions conj action)
                                        ; for target
                                        (swap! system update-in [:messages (:to message)] (fnil conj []) network-message)
                                        ; also track all messages
                                        (swap! system update-in [:messages :all] (fnil conj []) network-message)))))))
                            (validate-state-stores! {:system                system
                                                     :model-parsed          model-parsed
                                                     :closed-state-schemas? closed-state-schemas?
                                                     :actor-key             actor-key
                                                     :registry              registry
                                                     :state-schemas-ignored state-schemas-ignored
                                                     :handler-context       handler-context})
                            (when after (after handler-context))
                            (catch ExceptionInfo ei
                              (let [cursive-link (when (meta step)
                                                   (tagged-literal 'cursive/node
                                                                   {:presentation [{:text  "Jump to failing step -> "
                                                                                    :color :link}
                                                                                   {:text  (str (:name (meta step)))
                                                                                    :color :link}]
                                                                    :action       :navigate
                                                                    :file         (:file (meta step))
                                                                    :line         (:line (meta step))
                                                                    :column       0}))]
                                (throw (ex-info "step failed" (if cursive-link {:cursive-jump-link cursive-link} {}) ei)))))))))
        lookup-state-store-type (->> model-parsed
                                     (select [:locations MAP-VALS :state MAP-VALS])
                                     (map (juxt :id :type))
                                     (into {}))
        snapshot-recorder (recorder/make-recorder)
        state-edn (fn [] (->> (:state-stores @system)
                              (reduce-kv (fn [acc k v]
                                           (assoc acc k
                                                      (case (lookup-state-store-type k)
                                                        :database (state/as-map v)
                                                        :associative (state/as-lookup v))))
                                         {})))
        step-id-fn (fn [step] (when-let [m (meta step)]
                                (str (:name m))))]

    ; Not using a reduce because the step handler functions can side effect for state changes
    (doseq [step steps]
      (let [all-messages #(get-in @system [:messages :all])
            message-count-before (count (all-messages))
            step-id (step-id-fn step)
            current-step-index (recorder/current-step-index snapshot-recorder)]
        (run-step! step)
        (let [state-after (state-edn)
              messages-after (all-messages)
              message-count-after (count messages-after)
              messages-emitted (- message-count-after message-count-before)
              new-messages (take-last messages-emitted messages-after)]

          ;; Record state change if any
          (recorder/record-state-change! snapshot-recorder step-id state-after)

          ;; Record step completion (updates snapshot-history and step-index)
          (recorder/record-step-complete! snapshot-recorder)

          ;; Record messages with their step-index
          (when (pos-int? messages-emitted)
            (doseq [msg new-messages]
              (recorder/record-message! snapshot-recorder current-step-index msg))))))

    ;; Finalize: assign snapshot-ids and prepare for serialization
    (let [states (recorder/finalize snapshot-recorder)]

      (log/info "post-test actions...")

      (log-todos! system)

      (write-sequence-diagram! {:test-ns-str    diagram-name
                                :diagram-dir    diagram-dir
                                :diagram-config diagram-config
                                :system         system
                                :model-parsed   model-parsed
                                :states         states
                                :step-actions   @step-actions
                                :dev?           dev?})

      @system)))
