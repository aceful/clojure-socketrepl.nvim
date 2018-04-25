(ns socket-repl.socket-repl
  "Provides a channel interface to socket repl input and output."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.core.async :as async]
    [clojure.tools.logging :as log]
    [socket-repl.util :refer [log-start log-stop]])
  (:import
    (java.net Socket)
    (java.io PrintStream)))

(defn- write-code
  "Writes a string of code to the socket repl connection."
  [{:keys [connection]} code-string]
  (let [{:keys [print-stream]} @connection]
    (.println print-stream code-string)
    (.flush print-stream)))

(defn subscribe-output
  "Pipes the socket repl output to `chan`"
  [{:keys [output-channel]} chan]
  (async/pipe output-channel chan))

(defn connect
  "Create a connection to a socket repl."
  [{:keys [connection output-channel]} host port]
  (let [socket (java.net.Socket. host (Integer/parseInt port))
        reader (io/reader socket)]
    (reset! connection {:host host
                        :port port
                        :socket socket
                        :print-stream (-> socket io/output-stream PrintStream.)
                        :reader reader})
    (future
      (loop []
        (when-let [line (.readLine reader)]
          (async/>!! output-channel line)
          (recur))))))

(defn output-channel
  [socket-repl]
  (:output-channel socket-repl))

(defn input-channel
  [socket-repl]
  (:input-channel socket-repl))

(defn connected?
  [{:keys [connection]}]
  (:host @connection))

(defn start
  [{:keys [input-channel] :as socket-repl}]
  (log-start
    "socket-repl"
    (async/thread
      (loop []
        (when-let [input (async/<!! input-channel)]
          (when (connected? socket-repl)
            (write-code socket-repl input))
          (recur))))
    socket-repl))

(defn stop
  [{:keys [connection output-channel input-channel] :as socket-repl}]
  (log-stop
    "socket-repl"
    (let [{:keys [reader print-stream socket]} @connection]
      ;(when reader (.close reader))
      ;(when print-stream (.close print-stream))
      (when socket
        (.shutdownInput socket)
        (.shutdownOutput socket)))
    (async/close! output-channel)
    (async/close! input-channel)
    socket-repl))

(defn deep-edn-read-string
  [edn]
  (if (string? edn)
    (let [data (edn/read-string edn)]
      (cond
        (map? data) (into {}
                          (map (juxt (comp deep-edn-read-string first)
                                     (comp deep-edn-read-string second))
                               data))
        (vector? data) (into [] (map deep-edn-read-string data))
        (list? data) (apply list (map deep-edn-read-string data))
        (set? data) (into #{} (map deep-edn-read-string data))
        :else data))
    edn))

(defn new
  []
  {:input-channel (async/chan 1024)
   :output-channel (async/chan 1024 (map edn/read-string))
   :connection (atom {:host nil
                      :port nil
                      :socket nil
                      :reader nil
                      :print-stream nil})})
