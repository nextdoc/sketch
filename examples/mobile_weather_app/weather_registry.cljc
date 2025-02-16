(ns mobile-weather-app.weather-registry
  (:require [malli.core :as m]
            [malli.util :as mu]))

(def generated
  {:aws/lambda                         [:map]
   :aws-ddb/city                       [:map]
   :aws-ddb/user                       [:map]
   :aws-env/env                        [:map]
   :aws-lambda/ddb                     [:map]
   :aws-lambda/env                     [:map]
   :aws-lambda/error                   [:map]
   :aws-lambda/user-info               [:map]
   :aws-lambda/user-info-request       [:map
                                        [:type [:= :user-info]]
                                        [:user-name :string]]
   :aws-lambda/user-info-response      :weather/user
   :iphone/weather-app                 [:map]
   :iphone-core-data/city              :weather/city
   :iphone-core-data/user              [:map]
   :iphone-weather-app/core-data       [:map]
   :iphone-weather-app/error           [:map]
   :iphone-weather-app/forecast-change [:map]})

(def domain-schema
  {:weather/user [:map
                  [:user-name :string]
                  [:status [:enum :active :blocked]]]
   :weather/city [:map
                  [:name :string]
                  [:latitude :int]
                  [:longitude :int]]

   })

(defn registry []
  (merge generated domain-schema
         (m/default-schemas)
         (mu/schemas)))
