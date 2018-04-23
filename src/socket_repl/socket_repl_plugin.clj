(ns socket-repl.socket-repl-plugin
  "A plugin which connects to a running socket repl and sends output back to
  Neovim."
  (:require
    [clojure.core.async :as async]
    [clojure.java.io :as io]
    [clojure.string :as string]
    [clojure.tools.logging :as log]
    [neovim-client.1.api :as api]
    [neovim-client.1.api.buffer :as api.buffer]
    [neovim-client.1.api.buffer-ext :as api.buffer-ext]
    [neovim-client.1.api-ext :as api-ext]
    [neovim-client.message :as message]
    [neovim-client.nvim :as nvim]
    [socket-repl.nrepl :as nrepl]
    [socket-repl.parser :as parser]
    [socket-repl.repl-log :as repl-log]
    [socket-repl.socket-repl :as socket-repl]
    [socket-repl.util :refer [log-start log-stop]]))

(defn write-error
  "Write a throwable's stack trace to the repl log."
  [repl-log throwable]
  (async/>!! (repl-log/input-channel repl-log)
             (str "\n##### PLUGIN ERR #####\n"
                  (.getMessage throwable) "\n"
                  (string/join "\n" (map str (.getStackTrace throwable)))
                  "\n######################\n")))

(defn run-command
  [{:keys [nvim nrepl socket-repl]} f]
  (fn [msg]
    (if-not (or (socket-repl/connected? socket-repl)
                (nrepl/connected? nrepl))
      (async/thread
        (api/command
          nvim ":echo 'Use :Connect host:port to connect to a socket repl'"))
      (async/thread (f msg)))
    ;; Don't return an async channel, return something msg-pack can serialize.
    :done))

(defn get-rlog-buffer
  "Returns the buffer w/ b:rlog set, if one exists."
  [nvim]
  (some->> (api/list-bufs nvim)
           (filter #(api.buffer/get-var nvim % "rlog"))
           first))

(defn get-rlog-buffer-name
  "Returns the name of the buffer w/ b:rlog set, if one exists."
  [nvim]
  (let [buffer (get-rlog-buffer nvim)]
    (when buffer (api.buffer/get-name nvim buffer))))

(defn get-rlog-buffer-number
  "Returns the number of the buffer w/ b:rlog set, if one exists."
  [nvim]
  (let [buffer (get-rlog-buffer nvim)]
    (when buffer (api.buffer/get-number nvim buffer))))

(defn code-channel
  [plugin]
  (:code-channel plugin))

(defn start
  [{:keys [nvim nrepl repl-log socket-repl code-channel] :as plugin}]

  ;; Wire sub-component io.
  (log-start
    "plugin"
    (let [mult (async/mult code-channel)]
      (async/tap mult (socket-repl/input-channel socket-repl))
      (async/tap mult (repl-log/input-channel repl-log))
      (async/tap mult (nrepl/input-channel nrepl)))

    ;; Setup plugin functions.
    (nvim/register-method!
      nvim
      "connect"
      (fn [msg]
        (let [[host port] (-> msg
                              message/params
                              first
                              (string/split #":"))]
          (try
            (socket-repl/connect socket-repl host port)
            (catch Throwable t
              (log/error t "Error connecting to socket repl")
              (async/thread (api/command
                              nvim
                              ":echo 'Unable to connect to socket repl.'"))))
          :done)))

    (nvim/register-method!
      nvim
      "connect-nrepl"
      (fn [msg]
        ;; TODO - reuse this
        (let [[host port] (-> msg
                              message/params
                              first
                              (string/split #":"))]
          (try
            (nrepl/connect nrepl host port)
            (catch Throwable t
              (log/error t "Error connecting to nrepl")
              (async/thread (api/command
                              nvim
                              ":echo 'Unable to connect to nrepl.'"))))
          :done)))

    (nvim/register-method!
      nvim
      "eval"
      (run-command
        plugin
        (fn [msg]
          (try
            (async/>!! code-channel (parser/read-next
                                      (-> msg
                                          message/params
                                          ffirst)
                                      0 0))
            (catch Throwable t
              (log/error t "Error evaluating form")
              (write-error repl-log t))))))

    (nvim/register-method!
      nvim
      "eval-form"
      (run-command
        plugin
        (fn [msg]
          (let [[row col] (api-ext/get-cursor-location nvim)
                buffer-text (api-ext/get-current-buffer-text nvim)]
            (try
              (async/>!! code-channel (parser/read-next buffer-text row (inc col)))
              (catch Throwable t
                (log/error t "Error evaluating a form")
                (write-error repl-log t)))))))

    (nvim/register-method!
      nvim
      "eval-buffer"
      (run-command
        plugin
        (fn [msg]
          (let [buffer (api/get-current-buf nvim)]
            (let [code (string/join "\n" (api.buffer-ext/get-lines
                                           nvim buffer 0 -1))]
              (async/>!! code-channel (format "(eval '(do %s))" code)))))))

    (nvim/register-method!
      nvim
      "doc"
      (run-command
        plugin
        (fn [msg]
          (api-ext/get-current-word-async
            nvim
            (fn [word]
              (let [code (format "(clojure.repl/doc  %s)" word)]
                (async/>!! code-channel code)))))))

    (nvim/register-method!
      nvim
      "show-log"
      (run-command
        plugin
        (fn [msg]
          (let [file (-> repl-log repl-log/file .getAbsolutePath)]
            (let [original-window (api/get-current-win nvim)
                  buffer-cmd (first (message/params msg))
                  rlog-buffer (get-rlog-buffer-name nvim)
                  rlog-buffer-visible? (when rlog-buffer
                                         (async/<!!
                                           (api-ext/buffer-visible?-async
                                             nvim rlog-buffer)))]
              (when-not rlog-buffer-visible?
                (api/command
                  nvim
                  (format "%s | nnoremap <buffer> q :q<cr> | :let b:rlog=1 | :call termopen('tail -f %s') | :set ft=clojurerepl"
                          buffer-cmd file))
                (api/set-current-win nvim original-window)))))))

    (nvim/register-method!
      nvim
      "dismiss-log"
      (run-command
        plugin
        (fn [msg]
          (api/command
            nvim (format "bd! %s" (get-rlog-buffer-number nvim))))))

    (async/thread
      (api/command nvim "let g:socket_repl_plugin_ready = 1")
      (api/command nvim "echo 'SocketREPL plugin ready'"))

    plugin))

(defn stop
  [{:keys [nvim] :as plugin}]
  (log-stop
    "plugin"

    ;; Close the repl log buffer
    (api/command
      nvim (format "bd! %s" (get-rlog-buffer-number nvim)))

    (async/close! (:code-channel plugin))
    plugin))

(defn new
  [nvim nrepl repl-log socket-repl]
  {:nvim nvim
   :nrepl nrepl
   :repl-log repl-log
   :socket-repl socket-repl
   :code-channel (async/chan 1024)})
