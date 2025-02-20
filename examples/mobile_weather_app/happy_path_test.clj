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
  (sketch-watcher/start! {:model-path    "billing-sketch-meta.edn"
                          :registry-path "src/cursive/actor_model.cljc"})
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
              (let [id (random-uuid)
                    new-user {:id id
                              :user-name (:user-name fixtures)}]
                ; write to local storage
                (sketch-run/put-record! (:core-data state) :users id new-user)
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
                    id (random-uuid)
                    user (-> user-name
                             (domain/user-with-status matches)
                             (assoc :id id))]
                (sketch-run/put-record! (:ddb state) :users id user)
                {:emit [{:to        :aws/lambda
                         :request   :user-info
                         :direction :response
                         :payload   user}]}))})

(defn app-weather-request []
  {:actor   :iphone/weather-app
   :action  "app uses CLocationManager to get lat/long and requests weather"
   :handler (fn [{:keys [state fixtures]}]
              (let [{:keys [user-name]} (first (sketch-run/query (:core-data state) :users (constantly true)))]
                {:emit [{:to      :aws/lambda
                         :request :weather-info
                         :payload {:user-name user-name
                                   :latitude  (:lat fixtures)
                                   :longitude (:long fixtures)}}]}))})

(defn lambda-weather-start []
  {:actor   :aws/lambda
   :action  "AWS tracks (evil) user location and requests weather from provider"
   :handler (fn [{:keys [state messages]}]
              (let [{:keys [user-name latitude longitude]} (-> messages last :message :payload)
                    users-found (sketch-run/query (:ddb state) :users (comp #{user-name} :user-name))]
                (is (= 1 (count users-found)))
                (sketch-run/put-record! (:ddb state) :users
                                        (:id (first users-found))
                                        (merge (first users-found)
                                               {:latitude  latitude
                                                :longitude longitude}))
                {:emit [{:to      :open-weather/api
                         :request :one-call
                         :payload {:app-id (sketch-run/get-value (:env state) "API_KEY")
                                   :lat    latitude
                                   :lon    longitude}}]}))})

(defn api-weather-response []
  {:actor   :open-weather/api
   :action  "OpenWeather API returns current weather and forecast"
   :handler (fn [{:keys [fixtures]}]
              ; Ignoring app id validation and input lat/long
              {:emit [{:to        :aws/lambda
                       :request   :one-call
                       :direction :response
                       :payload   (-> fixtures :locations :sydney)}]})})

(defn lambda-weather-response []
  {:actor   :aws/lambda
   :action  "AWS Lambda forwards weather data to mobile app"
   :handler (fn [{:keys [messages]}]
              (let [weather-data (-> messages last :message :payload)]
                {:emit [{:to        :iphone/weather-app
                         :request   :weather-info
                         :direction :response
                         :payload   {:name       (:timezone weather-data)
                                     :temp       (-> weather-data :current :temp)
                                     :feels-like (-> weather-data :current :feels-like)}}]}))})

(defn app-weather-response []
  {:actor   :iphone/weather-app
   :action  "Mobile app stores weather data in Core Data"
   :handler (fn [{:keys [state messages]}]
              (let [weather-data (-> messages last :message :payload)]
                (sketch-run/put-record! (:core-data state) :citys (random-uuid) weather-data)
                {:emit []}))})

(defn lambda-poll-weather []
  {:actor   :aws/lambda
   :action  "AWS Lambda polls weather for tracked locations"
   :handler (fn [{:keys [state]}]
              (let [users (sketch-run/query (:ddb state) :users (comp some? :latitude))]
                {:emit (for [user users]
                         {:to      :open-weather/api
                          :request :one-call
                          :payload {:app-id (sketch-run/get-value (:env state) "API_KEY")
                                    :lat    (:latitude user)
                                    :lon    (:longitude user)}})}))})

(defn api-weather-alert-response []
  {:actor   :open-weather/api
   :action  "OpenWeather API returns weather with storm alert"
   :handler (fn [{:keys [fixtures]}]
              (let [alert {:sender-name "Bureau of Meteorology"
                           :event       "Severe Thunderstorm Warning"
                           :start       1684929490
                           :end         1684977332
                           :description "Severe thunderstorms likely to produce damaging winds and heavy rainfall"
                           :tags        ["Thunderstorm" "Rain" "Wind"]}]
                {:emit [{:to        :aws/lambda
                         :request   :one-call
                         :direction :response
                         :payload   (-> fixtures :locations :sydney
                                        (assoc :alerts [alert]))}]}))})

(defn lambda-push-weather-alert []
  {:actor   :aws/lambda
   :action  "AWS Lambda pushes weather alert to mobile app"
   :handler (fn [{:keys [messages]}]
              (let [weather-data (-> messages last :message :payload)]
                {:emit [{:to      :iphone/weather-app
                         :event   :weather-change
                         :payload {:name       (:timezone weather-data)
                                   :temp       (-> weather-data :current :temp)
                                   :feels-like (-> weather-data :current :feels-like)
                                   :alerts     (mapv :event (:alerts weather-data))}}]}))})

(defn app-weather-change []
  {:actor   :iphone/weather-app
   :action  "Mobile app updates weather data with alerts in Core Data"
   :handler (fn [{:keys [state messages]}]
              (let [weather-data (-> messages last :message :payload)
                    cities (sketch-run/query (:core-data state) :citys #(= (:name %) (:name weather-data)))
                    city (first cities)]
                (when city
                  (sketch-run/put-record! (:core-data state) :citys (:id city) weather-data))
                {:emit []}))})

;;;; TEST UTILS ;;;;

(defn initial-fixtures
  "provide text data to specific agents"
  []
  {:iphone       {:user-name "Rich"
                  :lat       -33.890897
                  :long      151.276801}
   :open-weather {:locations {:sydney {:lat             -33.890897
                                       :lon             151.276801
                                       :timezone        "Australia/Sydney"
                                       :timezone-offset 36000
                                       :current         {:dt         1684929490
                                                         :sunrise    1684926645
                                                         :sunset     1684977332
                                                         :temp       295.15
                                                         :feels-like 295.87
                                                         :pressure   1012
                                                         :humidity   72
                                                         :dew-point  289.82
                                                         :uvi        5.2
                                                         :clouds     20
                                                         :visibility 10000
                                                         :wind-speed 5.7
                                                         :wind-deg   135
                                                         :wind-gust  8.2
                                                         :weather    [{:id          800
                                                                       :main        "Clear"
                                                                       :description "clear sky"
                                                                       :icon        "01d"}]}}}}})

(defn reset-system! []
  {:reset (fn [system] (swap! system assoc :fixtures (initial-fixtures)))})

(defn with-config
  [m]
  (merge m {:model                 "mobile_weather_app/weather-model.edn"
            :state-store           (fn [] (sketch-run/atom-state-store))
            :registry              (model/registry)
            :state-schemas-ignored #{}}))

(def app-start-chapter '[app-user-info-request
                         lambda-cold-start
                         api-user-info-response
                         app-weather-request
                         lambda-weather-start
                         api-weather-response
                         lambda-weather-response
                         app-weather-response
                         lambda-poll-weather
                         api-weather-alert-response
                         lambda-push-weather-alert
                         app-weather-change])

; step thunks with indirection so exceptions can provide location
(def test-steps (mapv #(ns-resolve *ns* %)
                      (concat ['reset-system!] app-start-chapter)))

(deftest happy-path
  (->> {:steps                     test-steps
        :diagram-name              (str (sketch-run/this-ns))
        :diagram-config            {#_#_:actor-order []}
        :closed-data-flow-schemas? true
        :closed-state-schemas?     true
        :dev?                      false}
       (with-config)
       (sketch-run/run-steps!)
       (log/with-merged-config (sketch-run/log-config (sketch-run/this-ns)))))
