(ns socket-repl.socket-repl-plugin
  "A plugin which connects to a running socket repl and sends output back to
  Neovim."
  (:require
    [clojure.core.async :as async]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as string]
    [clojure.tools.namespace.find :as namespace.find]
    [clojure.tools.logging :as log]
    [neovim-client.1.api :as api]
    [neovim-client.1.api.window :as api.window]
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

(defn run-command-async
  [{:keys [nvim nrepl socket-repl]} f]
  (fn [msg]
    (if-not (or (socket-repl/connected? socket-repl)
                (nrepl/connected? nrepl))
      (async/thread
        (api/command
          nvim ":echo 'Use :Connect host:port to connect to a socket repl'"))
      (async/thread (f msg)))
    ;; Don't return an async channel, return something msg-pack can serialize.
    "done"))

(defn run-command
  [{:keys [nvim nrepl socket-repl]} f]
  (fn [msg]
    (if-not (or (socket-repl/connected? socket-repl)
                (nrepl/connected? nrepl))
      (async/thread
        (api/command
          nvim ":echo 'Use :Connect host:port to connect to a socket repl'"))
      (f msg))))

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

(comment
  (def lines ["  (let [my-fo \"foo\"]"  "    ))"  ""])
  (def line-delta 1)
  (def cursor-col (byte 4))

  (edn/read-string (build-context lines line-delta (int cursor-col)))

  (def lines ["(defn fo"
              "  [foo]"
              "  (let [f ]))"])
  (def line-delta 2)
  (def cursor-col 10)
  )

(defn build-context
  [lines line-delta cursor-col]
  (let [updated-lines (update lines line-delta (fn [line]
                                                 (-> (into [] line)
                                                     (update cursor-col #(into [] (str "__prefix__" %)))
                                                     (flatten)
                                                     (string/join))))
        ctx-form (string/join " " updated-lines)]
    (pr-str ctx-form)))

(defn get-completion-context
  [nvim word]
  (let [[cursor-line cursor-col :as cursor-position] (api-ext/get-cursor-location nvim)
        skip-exp "synIDattr(synID(line(\".\"),col(\".\"),1),\"name\") =~? \"comment\\|string\\|char\\|regexp\""
        [ctx-start-line ctx-start-col :as ctx-start] (api/call-function nvim "searchpairpos" ["(" "" ")" "Wrnb" skip-exp])
        [ctx-end-line ctx-end-col :as ctx-end] (api/call-function nvim "searchpairpos" ["(" "" ")" "Wrnc" skip-exp])]
    (if (or
          (= [0 0] ctx-start)
          (= [0 0] ctx-end))
      (do
        (log/info "Ctx not found")
        "")
      (let [buffer (api/get-current-buf nvim)
            lines (into [] (api.buffer-ext/get-lines nvim buffer (dec ctx-start-line) (inc ctx-end-line)))
            line-delta (max 0 (- cursor-line ctx-start-line))]
        (try
          (build-context lines line-delta (int cursor-col))
          (catch Exception e
            (log/info e)
            nil))))))

(defn code-channel
  [plugin]
  (:code-channel plugin))

(defn start
  [{:keys [nvim nrepl repl-log socket-repl internal-socket-repl code-channel] :as plugin}]

  ;; Wire sub-component io.
  (log-start
    "plugin"
    (let [mult (async/mult code-channel)]
      (async/tap mult (socket-repl/input-channel socket-repl))
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
            (socket-repl/connect internal-socket-repl host port)
            "success"

            (catch Throwable t
              (log/error t "Error connecting to socket repl")
              (async/thread (api/command
                              nvim
                              ":echo 'Unable to connect to socket repl.'"))
              "failure")))))

    (nvim/register-method!
      nvim
    "inject"
      (fn [msg]
        (when-not (= 1 (api/get-var nvim "g:socket_repl_injected"))
          (let [code-form (pr-str '(do
                                     (ns srepl.injection
                                       (:require
                                         [compliment.core :as compliment]))

                                     (defn completions
                                       [prefix options]
                                       (compliment/completions prefix options))))
                res-chan (async/chan 1 (filter #(= (:form %) code-form)))]
            (try
              (socket-repl/subscribe-output internal-socket-repl res-chan)
              (async/>!! (socket-repl/input-channel internal-socket-repl)
                         code-form)
              (let [res (async/<!! res-chan)]
                (log/info (str "inject res: " res))
                (if (:cause res)
                  (api/command nvim ":echo 'Error during inject'")
                  (do
                    (api/set-var nvim "g:socket_repl_injected" 1)
                    (api/command nvim ":echo 'Socket REPL injected'"))))
              (finally
                (async/close! res-chan)))))))

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
      (run-command-async
        plugin
        (fn [msg]
          (try
            (async/>!! (socket-repl/input-channel socket-repl)
                       (parser/read-next
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
      (run-command-async
        plugin
        (fn [msg]
          (let [[row col] (api-ext/get-cursor-location nvim)
                buffer-text (api-ext/get-current-buffer-text nvim)]
            (try
              (async/>!! (socket-repl/input-channel socket-repl)
                         (parser/read-next buffer-text row (inc col)))
              (catch Throwable t
                (log/error t "Error evaluating a form")
                (write-error repl-log t)))))))

    (nvim/register-method!
      nvim
      "eval-buffer"
      (run-command-async
        plugin
        (fn [msg]
          (let [buffer (api/get-current-buf nvim)]
            (let [code (string/join "\n" (api.buffer-ext/get-lines
                                           nvim buffer 0 -1))]
              (async/>!! (socket-repl/input-channel socket-repl)
                         (format "(eval '(do %s))" code)))))))

    (nvim/register-method!
      nvim
      "doc"
      (run-command-async
        plugin
        (fn [msg]
          (let [code (format "(clojure.repl/doc %s)" (-> msg
                                                         message/params
                                                         ffirst))]
            (async/>!! (socket-repl/input-channel socket-repl) code)))))

    (nvim/register-method!
      nvim
      "doc-cursor"
      (run-command-async
        plugin
        (fn [msg]
          (api-ext/get-current-word-async
            nvim
            (fn [word]
              (let [code (format "(clojure.repl/doc %s)" word)]
                (async/>!! (socket-repl/input-channel socket-repl) code)))))))

    (nvim/register-method!
      nvim
      "source"
      (run-command-async
        plugin
        (fn [msg]
          (let [code (format "(clojure.repl/source %s)" (-> msg
                                                            message/params
                                                            ffirst))]
            (async/>!! (socket-repl/input-channel socket-repl) code)))))

    (nvim/register-method!
      nvim
      "source-cursor"
      (run-command-async
        plugin
        (fn [msg]
          (api-ext/get-current-word-async
            nvim
            (fn [word]
              (let [code (format "(clojure.repl/source %s)" word)]
                (async/>!! (socket-repl/input-channel socket-repl) code)))))))

    (nvim/register-method!
      nvim
      "complete-initial"
      (run-command
        plugin
        (fn [msg]
          (let [line (api/get-current-line nvim)
                [cursor-row cursor-col] (api-ext/get-cursor-location nvim)]
            (let [start-col (- cursor-col
                               (->> line
                                    (take cursor-col)
                                    (reverse)
                                    (take-while #(not (#{\ \(} %)))
                                    (count)))]
              start-col)))))

    (nvim/register-method!
      nvim
      "complete-matches"
      (run-command
        plugin
        (fn [msg]
          (let [word (first (message/params msg))
                context (get-completion-context nvim word)
                code-form (str "(srepl.injection/completions "
                               "\"" word "\" "
                               "{:ns *ns* "
                               ":context " context
                               "})")
                res-chan (async/chan 1 (filter #(= (:form %)
                                                   code-form)))]
            (try
              (socket-repl/subscribe-output internal-socket-repl res-chan)
              (async/>!! (socket-repl/input-channel internal-socket-repl) code-form)
              (let [[matches timeout] (async/alts!! [res-chan (async/timeout 10000)])
                    r (map (fn [{:keys [candidate type ns] :as match}]
                             {"word" candidate
                              "menu" ns
                              "kind" (case type
                                       :function "f"
                                       :special-form "d"
                                       :class "t"
                                       :local "v"
                                       :keyword "v"
                                       :resource "t"
                                       :namespace "t"
                                       :method "f"
                                       :static-field "m"
                                       "")})
                           (edn/read-string (:val matches)))]
                r)
              (finally
                (async/close! res-chan)))))))

    (nvim/register-method!
      nvim
      "cp"
      (run-command-async
        plugin
        (fn [msg]
          (let [code-form "(map #(.getAbsolutePath %) (clojure.java.classpath/classpath))"]
            (async/>!! (socket-repl/input-channel internal-socket-repl)
                       code-form)
            (async/thread
              (let [res-chan (async/chan 1 (filter #(= (:form %) code-form)))]
                (try
                  (socket-repl/subscribe-output internal-socket-repl res-chan)
                  (let [res (async/<!! res-chan)]
                    (log/info (:ns res))
                    (log/info (:ms res))
                    (log/info (:val res)))
                  (finally
                    (async/close! res-chan)))))))))

    (nvim/register-method!
      nvim
      "switch-buffer-ns"
      (run-command-async
        plugin
        (fn [msg]
          (let [buffer-name (api/get-current-buf nvim)
                file-name (api.buffer/get-name nvim buffer-name)
                file (io/file file-name)
                namespace-declaration (first (namespace.find/find-ns-decls-in-dir file))
                eval-entire-declaration? (= 1 (api/get-var nvim "eval_entire_ns_decl"))
                code-form (if eval-entire-declaration?
                            namespace-declaration
                            `(clojure.core/in-ns '~(second namespace-declaration)))]
            (async/>!! (socket-repl/input-channel socket-repl) code-form)
            (async/>!! (socket-repl/input-channel internal-socket-repl) code-form)))))

    (nvim/register-method!
      nvim
      "show-log"
      (run-command-async
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
      (run-command-async
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
  [nvim nrepl repl-log socket-repl internal-socket-repl]
  {:nvim nvim
   :nrepl nrepl
   :repl-log repl-log
   :socket-repl socket-repl
   :internal-socket-repl internal-socket-repl
   :code-channel (async/chan 1024)})
