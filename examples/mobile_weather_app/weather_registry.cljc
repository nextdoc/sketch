(ns mobile-weather-app.weather-registry
  (:require [malli.core :as m]
            [malli.util :as mu]))

(def generated
  {:aws/lambda                         [:map]
   :aws/sqs-worker                     [:map]
   :aws-ddb/city                       [:map]
   :aws-ddb/user                       [:map]
   :aws-env/env                        [:map]
   :aws-lambda/ddb                     [:map
                                        [:users [:set :weather/user]]
                                        [:citys [:set :weather/city]]]
   :aws-lambda/env                     [:map-of :string :string]
   :aws-lambda/error                   [:map]
   :aws-lambda/user-info               [:map]
   :aws-lambda/user-info-request       [:map
                                        [:type [:= :user-info]]
                                        [:user-name :string]]
   :aws-lambda/user-info-response      :weather/user
   :aws-lambda/weather-info            [:map]
   :aws-lambda/weather-info-request    [:map
                                        [:user-name :string]
                                        [:latitude :float]
                                        [:longitude :float]]
   :aws-lambda/weather-info-response   :weather/city
   :aws-sqs-worker/ddb                 [:map
                                        [:users [:set :weather/user]]
                                        [:citys [:set :weather/city]]]
   :aws-sqs-worker/env                 [:map-of :string :string]
   :aws-sqs-worker/error               [:map]
   :aws-sqs-worker/process-alert       [:map
                                        [:city-name :string]
                                        [:alert-count :int]]
   :iphone/weather-app                 [:map]
   :iphone-core-data/city              :weather/city
   :iphone-core-data/user              [:map]
   :iphone-weather-app/core-data       [:map
                                        [:users [:set :weather/user]]
                                        [:citys [:set :weather/city]]]
   :iphone-weather-app/error           [:map]
   :iphone-weather-app/weather-change  :weather/city
   :open-weather/api                   [:map]
   :open-weather-api/error             [:map]
   :open-weather-api/one-call          [:map]
   :open-weather-api/one-call-request  :open-weather/onecall-request
   :open-weather-api/one-call-response :open-weather/onecall-response})

(def domain-schema
  {:weather/user [:map
                  [:user-id {:description "Primary key used as the partition key in DynamoDB"}
                   ; This primary key demonstrates how alternative primary keys can be specified for state in the model
                   ; The happy path test uses the more sophisticated state store to support this
                   :uuid]
                  [:user-name :string]
                  [:status {:optional true} [:enum :active :blocked]]
                  [:latitude {:optional true} :float]
                  [:longitude {:optional true} :float]]
   :weather/city [:map
                  [:id :uuid]
                  [:name :string]
                  [:temp :double]
                  [:feels-like :double]
                  [:alerts {:optional true} [:vector :string]]]})

(def ^{:doc "Rest API https://openweathermap.org/api/one-call-3"}
  open-weather-schema
  ; note: this schema could be generated from published swagger schemas
  ; created using AI from sample for demo purposes
  {:open-weather/weather-condition [:map
                                    [:id :int]
                                    [:main :string]
                                    [:description :string]
                                    [:icon :string]]

   :open-weather/precipitation     [:map
                                    [:1h :double]]

   :open-weather/wind-data         [:map
                                    [:wind-speed :double]
                                    [:wind-deg :int]
                                    [:wind-gust {:optional true} :double]]

   :open-weather/base-weather      [:map
                                    [:dt :int]
                                    [:temp :double]
                                    [:feels-like :double]
                                    [:pressure :int]
                                    [:humidity :int]
                                    [:dew-point :double]
                                    [:uvi :double]
                                    [:clouds :int]
                                    [:visibility :int]
                                    [:weather [:vector :open-weather/weather-condition]]
                                    [:rain {:optional true} :open-weather/precipitation]
                                    [:snow {:optional true} :open-weather/precipitation]]

   :open-weather/daily-temp        [:map
                                    [:day :double]
                                    [:min :double]
                                    [:max :double]
                                    [:night :double]
                                    [:eve :double]
                                    [:morn :double]]

   :open-weather/daily-feels-like  [:map
                                    [:day :double]
                                    [:night :double]
                                    [:eve :double]
                                    [:morn :double]]

   :open-weather/alert             [:map
                                    [:sender-name :string]
                                    [:event :string]
                                    [:start :int]
                                    [:end :int]
                                    [:description :string]
                                    [:tags [:vector :string]]]

   :open-weather/onecall-request   [:map
                                    [:app-id :string]
                                    [:lat :float]
                                    [:lon :float]]

   :open-weather/onecall-response  [:map
                                    [:lat :double]
                                    [:lon :double]
                                    [:timezone :string]
                                    [:timezone-offset :int]
                                    [:current [:merge
                                               :open-weather/base-weather
                                               :open-weather/wind-data
                                               [:map
                                                [:sunrise :int]
                                                [:sunset :int]]]]
                                    [:minutely {:optional true}
                                     [:vector [:map
                                               [:dt :int]
                                               [:precipitation :double]]]]
                                    [:hourly {:optional true}
                                     [:vector [:merge
                                               :open-weather/base-weather
                                               :open-weather/wind-data
                                               [:map
                                                [:pop :double]]]]]
                                    [:daily {:optional true}
                                     [:vector [:map
                                               [:dt :int]
                                               [:sunrise :int]
                                               [:sunset :int]
                                               [:moonrise :int]
                                               [:moonset :int]
                                               [:moon-phase :double]
                                               [:summary :string]
                                               [:temp :open-weather/daily-temp]
                                               [:feels-like :open-weather/daily-feels-like]
                                               [:pressure :int]
                                               [:humidity :int]
                                               [:dew-point :double]
                                               [:weather [:vector :open-weather/weather-condition]]
                                               [:clouds :int]
                                               [:pop :double]
                                               [:rain {:optional true} :double]
                                               [:snow {:optional true} :double]
                                               [:uvi :double]]]]
                                    [:alerts {:optional true}
                                     [:vector :open-weather/alert]]]

   })

(defn registry []
  (merge generated domain-schema open-weather-schema
         (m/default-schemas)
         (mu/schemas)))
