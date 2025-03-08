(ns user)

(comment
  ; start build watcher
  (do
    (require '[shadow.cljs.devtools.server :as server])
    (server/start!)
    (require '[shadow.cljs.devtools.api :as shadow])
    (shadow/watch :diagram))

  ; connect browser repl
  (shadow/repl :diagram)

  (do (shadow/release! :diagram) :done)

  (shadow/stop-worker :diagram)
  )
