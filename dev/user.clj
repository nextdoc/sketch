(ns user)

(comment
  ; start build water
  (do
    (require '[shadow.cljs.devtools.server :as server])
    (server/start!)
    (require '[shadow.cljs.devtools.api :as shadow])
    (shadow/watch :diagram {:verbose false}))

  ; connect browser repl
  (shadow/repl :diagram)
  )



