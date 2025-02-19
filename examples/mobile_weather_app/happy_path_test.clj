(ns mobile-weather-app.happy-path-test
  (:require [clojure.test :refer :all]
            [mobile-weather-app.weather-domain :as domain]
            [io.nextdoc.sketch.run :as sketch-run]
            [io.nextdoc.sketch.watcher :as sketch-watcher]
            [malli.dev :as md]
            [malli.dev.pretty :as pretty]
            [mobile-weather-app.weather-registry :as model]
            [taoensso.timbre :as log]))

(comment
  (sketch-watcher/start! {:model-path    "mobile_weather_app/weather-model.edn"
                          :registry-path "examples/mobile_weather_app/weather_registry.cljc"})
  (sketch-watcher/stop!))

(md/start! {:reporter (pretty/thrower)})

(defn app-user-info-request []
  {:actor   :iphone/weather-app
   :action  "user enters username into mobile app"
   :before  (fn [{:keys [state]}]
              (let [initial-data (sketch-run/as-map (:core-data state))]
                (is (every? empty? (vals initial-data))
                    "no data when app starts")))
   :handler (fn [{:keys [state fixtures]}]
              (let [new-user {:user-name (:user-name fixtures)}]
                ; write to local storage
                (sketch-run/put-record! (:core-data state) :users (random-uuid) new-user)
                ; send request to api
                {:emit [{:to      :aws/lambda
                         :request :user-info
                         :payload {:type      :user-info
                                   :user-name (:user-name new-user)}}]}))})

(defn lambda-cold-start []
  {:actor   :aws/lambda
   :action  "AWS loads environment settings for Lambda"
   :handler (fn [{:keys [state]}]
              (sketch-run/put-value! (:env state) "API_KEY" "secretkey")
              {:emit []})})

(defn api-user-info-response []
  {:actor   :aws/lambda
   :action  "API upserts user and responds with status"
   :handler (fn [{:keys [state messages]}]
              (let [user-name (-> messages last :message :payload :user-name)
                    matches (sketch-run/query (:ddb state) :user (comp #{user-name} :user-name))
                    user (domain/user-with-status user-name matches)]
                (sketch-run/put-record! (:ddb state) :users (random-uuid) user)
                {:emit [{:to        :aws/lambda
                         :request   :user-info
                         :direction :response
                         :payload   user}]}))})

; TODO app loads location, requests weather
; TODO api tracks user location. evil!
; TODO lambda calls openweatherapi
; TODO lambda pushes update

;;;; TEST UTILS ;;;;

(defn initial-fixtures
  "provide text data to specific agents"
  []
  {:iphone {:user-name "Rich"}})

(defn reset-system! []
  {:reset (fn [system] (swap! system assoc :fixtures (initial-fixtures)))})

(defn with-config
  [m]
  (merge m {:model                 "mobile_weather_app/weather-model.edn"
            :state-store           (fn [] (sketch-run/atom-state-store))
            :registry              (model/registry)
            :state-schemas-ignored #{}}))

(def app-start-steps '[app-user-info-request
                       lambda-cold-start
                       api-user-info-response])

; step thunks with indirection so exceptions can provide location
(def test-steps (mapv #(ns-resolve *ns* %)
                      (concat ['reset-system!] app-start-steps)))

(deftest happy-path
  (->> {:steps          test-steps
        :diagram-name   (str (sketch-run/this-ns))
        :diagram-config {;:actor-order []
                         }
        :dev?           true}
       (with-config)
       (sketch-run/run-steps!)
       (log/with-merged-config (sketch-run/log-config (sketch-run/this-ns)))))
