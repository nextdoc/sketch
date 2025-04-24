(ns mobile-weather-app.sync-test
  (:require [clojure.test :refer :all]
            [io.nextdoc.sketch.watcher :as sketch-watcher]
            [taoensso.timbre :as log]
            [mobile-weather-app.happy-path-test :as happy]))

(deftest generates-without-errors
  (testing "generation warnings are not emitted for the weather model in the happy path test"
    (let [logs (atom [])
          {:keys [model-path registry-path]} happy/watcher-args]
      (log/with-merged-config
        {:appenders {:println {:enabled?  true
                               :async?    false
                               :min-level :debug
                               :fn        (fn [data]
                                            (let [{:keys [level vargs]} data]
                                              (swap! logs conj {:first-arg (first vargs)
                                                                :level     level})))}}}
        (sketch-watcher/generate! model-path registry-path)
        (is (= [{:first-arg "updated"
                 :level     :debug}] @logs)
            "no warnings or errors from weather model generation")))))


