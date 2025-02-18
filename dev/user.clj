(ns user)

(comment
  ; start build watcher
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





