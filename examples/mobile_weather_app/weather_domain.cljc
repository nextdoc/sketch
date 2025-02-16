(ns mobile-weather-app.weather-domain)

(defn user-with-status
  "Implement business rules about user activity based on matches from database"
  [user-name matches]
  (case (count matches)
    0 {:user-name user-name
       :status    :active}))
