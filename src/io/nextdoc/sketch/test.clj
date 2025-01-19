(ns io.nextdoc.sketch.test)

(defn matching-requests
  [k messages]
  (->> messages
       (map :message)
       (filter (comp #{k} :request))))

(defn most-recent-request-payload
  "Return the most recent request payload that matches a key.
   Useful when needing previously received state in a multi-step handler process
   e.g. a form was posted and then a few steps were executed and afterwards a step needs to combine the original post request with extra data.
   Note: does not work for :event payloads, only :request payloads"
  [k messages]
  (let [matches (matching-requests k messages)]
    (if (zero? (count matches))
      (throw (ex-info "no matches found" {:k k}))
      (-> matches last :payload))))
