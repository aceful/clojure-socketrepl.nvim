(ns socket-repl.repl-log
  "Writes (presumably socket output) to the repl log."
  (:require
    [clojure.edn :as edn]
    [clojure.core.async :as async]
    [clojure.java.io :as io]
    [clojure.string :as string]
    [clojure.tools.logging :as log]
    [socket-repl.nrepl :as nrepl]
    [socket-repl.socket-repl :as socket-repl]
    [socket-repl.util :refer [log-start log-stop]])
  (:import
    (java.io PrintStream File)))

(defn file
  [repl-log]
  (:file repl-log))

(defn input-channel
  "A channel containing strings to be written to the repl log. Each element
  is expected to represent a line, and are println'd to the repl log."
  [repl-log]
  (:input-channel repl-log))

(defn format-input
  [s]
  s)

(defn start
  [{:keys [file input-channel socket-repl nrepl] :as repl-log}]

  (log-start
    "repl-log"
    ;; Subscribe to socket-repl output.
    (socket-repl/subscribe-output socket-repl input-channel)

    ;; Subscribe to nrepl output.
    (nrepl/subscribe-output nrepl input-channel)

    ;; Write input to file.
    (let [writer (io/writer file)]
      (async/thread
        (loop []
          (when-let [input (async/<!! input-channel)]
            (binding [*out* writer]
              (println input))
            (recur))))
      (assoc repl-log :writer writer))))

(defn stop
  [{:keys [writer input-channel] :as repl-log}]
  (log-stop
    "repl-log"
    (.close writer)
    (async/close! input-channel)
    (dissoc repl-log :writer :input-channel)))

(defn format-stuff
  [v]
  (log/info v)
  (case (:tag v)
    (:out) (string/trim-newline (:val v))
    (:ret) (str (:form v) " => " (:val v))
    v))

(defn new
  [socket-repl nrepl]
  {:socket-repl socket-repl
   :nrepl nrepl
   :file (File/createTempFile "socket-repl" ".txt")
   :print-stream nil
   :input-channel (async/chan 1024 (comp
                                     (filter #(not= (:tag %) :tap))
                                     (map format-stuff)))})
