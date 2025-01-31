(ns io.nextdoc.sketch.run
  (:require [aero.core :refer [read-config]]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [com.rpl.specter :refer [ALL MAP-VALS collect-one multi-path select select-first]]
            [hiccup.core :refer [html]]
            [malli.core :as m]
            [malli.dev.pretty :as mp]
            [malli.util :as mu]
            [io.nextdoc.sketch.diagrams :refer [flow-sequence]]
            [taoensso.timbre :as log])
  (:import (clojure.lang ExceptionInfo)))

(defprotocol StateDatabase
  "operations that a typical database can perform"
  (create-table [this entity-type])
  (get-record [this entity-type id])
  (query [this entity-type predicate])
  (put-record! [this entity-type id value])
  (delete-record! [this entity-type id])
  (clear! [this])
  (as-map [this]))

(defprotocol StateAssociative
  "operations that a typical lookup data-structure can perform"
  (get-value [this id])
  (put-value! [this id value])
  (delete-value! [this id])
  (as-lookup [this]))

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
          (swap! database assoc entity-type {})))
      (get-record [_ entity-type id]
        (get-in @database [entity-type id]))
      (query [_ entity-type predicate]
        (->> (get @database entity-type)
             (vals)
             (filterv predicate)))
      (put-record! [_ entity-type id record]
        (swap! database assoc-in [entity-type id] record))
      (delete-record! [_ entity-type id]
        (swap! database update entity-type dissoc id))
      (clear! [_] (reset! database {}))
      (as-map [_] @database))))

(defn state-store-wrapped
  [impl {:keys [id entities]}]
  (doseq [entity entities]
    (create-table impl (keyword (str (name entity) "s"))))
  (reify
    StateAssociative
    (get-value [this id] (get-value impl id))
    (put-value! [this id value] (put-value! impl id value))
    (delete-value! [this id] (delete-value! impl id))
    (as-lookup [this] (as-lookup impl))
    StateDatabase
    (get-record [_ entity-type id]
      ; TODO check entity type allowed in meta
      (get-record impl entity-type id)
      )
    (query [_ entity-type predicate]
      ; TODO check entity type allowed in meta
      (query impl entity-type predicate))
    (put-record! [_ entity-type id record]
      ; TODO check....
      (put-record! impl entity-type id record)
      )
    (delete-record! [_ entity-type id]
      ; TODO check....
      (delete-record! impl entity-type id)
      )
    (clear! [_]
      (clear! impl))
    (as-map [_]
      (as-map impl))))

(def empty-system {:state-stores            {}
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
  [{:keys [system actor-key model-parsed registry message closed-data-flow-schemas?]}]
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
        schema (cond-> (m/schema schema-keyword {:registry registry})
                       closed-data-flow-schemas? (mu/closed-schema))]
    (try
      (when-not (m/validate schema (:payload message))
        (let [error (ex-info "invalid message payload" {})]
          (log/warn (ex-message error))
          (pprint/pprint message)
          (pprint/pprint (m/form (m/deref schema)))
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
  [{:keys [system model-parsed actor-key registry lookup-state-schemas state-schemas-ignored handler-context closed-state-schemas?]}]
  (let [{from-actor    :actor
         from-location :location} (actor-meta model-parsed actor-key)]
    (doseq [[k store] (:state handler-context)]
      (when-not (zero? (count (as-map store)))
        (let [schema-namespace (str (name (:id from-location)) "-" (name (:id from-actor)))
              schema-name (name k)
              schema-keyword (keyword schema-namespace schema-name)]
          (when-not (contains? state-schemas-ignored schema-keyword)
            (try
              (let [schema (cond-> (m/schema schema-keyword {:registry registry})
                                   closed-state-schemas? (mu/closed-schema))
                    state (if (contains? lookup-state-schemas schema-keyword)
                            (as-lookup store)
                            (as-map store))]
                (when-let [ex (m/explain schema state)]
                  (let [error (ex-info "invalid storage" {:explain ex})]
                    (log/error (ex-message error))
                    (clojure.pprint/pprint state)
                    (mp/explain schema state)
                    (throw error))))
              (catch ExceptionInfo ei
                (if (= ::m/invalid-schema (:type (ex-data ei)))
                  (swap! system update :missing-state-schemas conj schema-keyword)
                  (throw ei))))))))))

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
  [{:keys [diagram title]}]
  [:html {:lang "en"}
   [:head
    [:meta {:charset "UTF-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
    [:title title]
    [:script {:src "https://cdn.jsdelivr.net/npm/mermaid/dist/mermaid.min.js"}]
    [:script "mermaid.initialize({ startOnLoad: true });"]]
   [:body
    [:div.mermaid diagram]]])

(defn write-sequence-diagram!
  [{:keys [test-ns-str diagram-dir diagram-config system model-parsed]}]
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
    (spit (str file-name ".html") (-> {:diagram diagram
                                       :title   test-name}
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
  [{:keys [steps model state-store registry lookup-state-schemas state-schemas-ignored middleware
           diagram-dir diagram-name diagram-config verbose?
           closed-data-flow-schemas? closed-state-schemas?]
    :or   {diagram-dir               "target/sketch-diagrams"
           verbose?                  false
           closed-data-flow-schemas? false
           closed-state-schemas?     false}}]
  (when verbose? (clojure.pprint/pprint steps))
  (let [model-parsed (or (some-> (io/resource model)
                                 (read-config))
                         (throw (ex-info "model not found" {:source model})))
        system (atom empty-system)
        run! (fn [step]
               (let [step-result (step)                     ; invoke step thunk to access it's config
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
                         state-stores (reduce (fn [acc state-meta]
                                                (assoc acc (:id state-meta)
                                                           (or (-> @system :state-stores (get (:id state-meta)))
                                                               (let [new-store (state-store-wrapped (state-store) state-meta)]
                                                                 (swap! system assoc-in [:state-stores (:id state-meta)] new-store)
                                                                 new-store))))
                                              {}
                                              (vals (:state from-location)))
                         handler-context {:state    state-stores
                                          :messages (get-in @system [:messages actor-key])
                                          :fixtures (get-in @system [:fixtures (:id from-location)])}
                         current-step {:location from-location
                                       :actor    from-actor
                                       :action   action}]

                     (log/info current-step)

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
                                                   :message                   message})
                               ; record emitted messages
                               (let [network-message {:data-flow data-flow
                                                      :message   (assoc message :from from-keyword)}]
                                 ; for target
                                 (swap! system update-in [:messages (:to message)] (fnil conj []) network-message)
                                 ; also track all messages
                                 (swap! system update-in [:messages :all] (fnil conj []) network-message)))))))
                     (validate-state-stores! {:system                system
                                              :model-parsed          model-parsed
                                              :closed-state-schemas? closed-state-schemas?
                                              :actor-key             actor-key
                                              :registry              registry
                                              :lookup-state-schemas  lookup-state-schemas
                                              :state-schemas-ignored state-schemas-ignored
                                              :handler-context       handler-context})
                     (when after (after handler-context))))))]

    ; TODO snapshots the state stores after each step for diagrams

    (doseq [step steps]
      (run! step))

    (log/info "post-test actions...")

    (log-todos! system)

    (write-sequence-diagram! {:test-ns-str    diagram-name
                              :diagram-dir    diagram-dir
                              :diagram-config diagram-config
                              :system         system
                              :model-parsed   model-parsed})

    @system))
