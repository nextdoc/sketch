(ns user
  (:require [shadow.cljs.devtools.api :as shadow]))

(comment
  ; start build water
  (do
    (require '[shadow.cljs.devtools.server :as server])
    (server/start!)
    (require '[shadow.cljs.devtools.api :as shadow])
    (shadow/watch :diagram))

  (shadow/release! :diagram)

  (shadow/stop-worker :diagram)

  ; connect browser repl
  (shadow/repl :diagram)
  )



