(ns clojure-miniprofiler
  (:require [clojure.string :as string]
            [ring.util.response :as response]
            [ring.middleware.content-type :refer [content-type-response]]
            [ring.middleware.file-info :refer [file-info-response]]
            [cheshire.core :as json]
            [clojure-miniprofiler.types :refer :all]
            [clojure-miniprofiler.store :refer :all]))

;; storage here
(deftype InMemoryStore [store]
  Storage
  (save [_ profile]
    (swap! store assoc (get profile "Id") profile))
  (fetch [_ id]
    (get @store id)))

(defn in-memory-store []
  (InMemoryStore.
    (atom {})))

(defn uuid [] (str  (java.util.UUID/randomUUID)))

(defn distance-of-ns-time [ns0 ns1]
  (/ (float (- ns1 ns0)) 1000000))

(def ^:dynamic *current-miniprofiler* nil)

(defn current-ms []
  (/ (float (System/nanoTime)) 1000000))

(defn ms-since-start []
  (distance-of-ns-time (get @*current-miniprofiler* :start-ns) (System/nanoTime)))

(defn add-child [parent-timer section-name]
  (->Timing (uuid) section-name (ms-since-start) nil [] {}))

(defmacro trace [section-name & body]
  `(if *current-miniprofiler*
     (let [parent-timer# (:current-timer @*current-miniprofiler*)
           new-timer# (add-child parent-timer# ~section-name)
           t0# (System/nanoTime)]
       (swap! *current-miniprofiler* assoc :current-timer new-timer#)
       (try
         (do ~@body)
         (finally
           (let [t1# (System/nanoTime)
                 duration# (distance-of-ns-time t0# t1#)]
             (swap! *current-miniprofiler*
                    (fn [current-miniprofiler#]
                      (assoc current-miniprofiler#
                             :current-timer
                             (assoc parent-timer#
                                    :children
                                    (conj (get parent-timer# :children)
                                          (assoc (:current-timer current-miniprofiler#)
                                                 :duration-ms duration#))))))))))
     (do ~@body)))

(defn create-custom-timing [execute-type command-string stacktrace-info]
  (->CustomTiming
    (uuid)
    execute-type
    command-string
    stacktrace-info
    (ms-since-start)
    nil))

(defn get-stacktrace-info [duration]
  (if (< duration 5)
    ""
    (let [stacktrace-elems (.getStackTrace (Throwable.))]
      (->> stacktrace-elems
        (filter (fn [^StackTraceElement e]
                  (and
                    (not (re-matches #".*clojure_miniprofiler.*" (.getClassName e)))
                    (not (re-matches #".*clojure.lang.*" (.getClassName e)))
                    (not (re-matches #".*clojure.core.*" (.getClassName e))))))
        (drop 1)
        (take 5)
        (map (fn [^StackTraceElement e]
               (str (.getClassName e) "." (.getMethodName e) " " (.getFileName e) ":" (.getLineNumber e))))
        (string/join "\n")))))

(defmacro custom-timing [call-type execute-type command-string & body]
  `(if *current-miniprofiler*
    (let [custom-timing# (create-custom-timing ~execute-type ~command-string nil)
          t0# (System/nanoTime)]
      (try
        (do ~@body)
        (finally
          (let [t1# (System/nanoTime)
                duration# (distance-of-ns-time t0# t1#)
                stacktrace-info# (get-stacktrace-info duration#)]
            (swap! *current-miniprofiler*
                   (fn [current-miniprofiler#]
                     (assoc current-miniprofiler#
                            :current-timer
                            (assoc (:current-timer current-miniprofiler#)
                                   :custom-timings
                                   (assoc (get (:current-timer current-miniprofiler#) :custom-timings {})
                                          ~call-type
                                          (conj (get-in current-miniprofiler# [:current-timer :custom-timings ~call-type] [])
                                                (assoc custom-timing#
                                                       :duration-ms duration#
                                                       :stacktrace-info stacktrace-info#)))))))))))
    (do ~@body)))

(defn create-miniprofiler [req]
  (->Profiler
    (->Timing
      (uuid)
      "ring handler"
      0
      nil
      []
      {})
    (->Profile
      (uuid)
      (str (.toUpperCase (name (:request-method req))) " " (:uri req))
      (current-ms)
      0
      (.getHostName (java.net.InetAddress/getLocalHost))
      nil
      [])
    (System/nanoTime)))

(defn reconstruct-profile [profiler duration]
  (assoc (:root profiler)
         :root
         (assoc
           (:current-timer profiler)
           :duration-ms
           duration)
         :duration-ms duration))

(defmacro with-recording [options req & body]
  `(let [miniprofiler# (create-miniprofiler ~req)]
     (binding [*current-miniprofiler* (atom miniprofiler#)]
     (let [t0# (System/nanoTime)
           result# (do ~@body)
           t1# (System/nanoTime)
           duration# (distance-of-ns-time t0# t1#)]
       (save (:store ~options)
             (to-miniprofiler-map
               (reconstruct-profile @*current-miniprofiler* duration#)))
       [(get-in @*current-miniprofiler* [:root :id]) result#]))))

(def miniprofiler-script-tag
  (slurp (:body (response/resource-response "include.partial.html"))))

(defn build-miniprofiler-script-tag [duration-ms profiler-id options]
  (reduce
    (fn [result [k v]]
      (string/replace result (re-pattern (str "\\{" k "\\}")) (str v)))
    miniprofiler-script-tag
    {"path" (str (:base-path options) "/")
     "version" "0.0.1"
     "currentId" profiler-id
     "ids" profiler-id
     "showControls" "true"
     "authorized" "true"
     "startHidden" "false"
     "position" "right"
     "showTrivial" "true"
     "showChildren" "true"
     "maxTracesToShow" "100"
     "trivialMilliseconds" (:trivial-ms options 2)}))

(defn build-miniprofiler-response [response duration-ms profiler-id options]
  (assoc response
         :body
         (string/replace
           (:body response)
           #"</body>"
           (build-miniprofiler-script-tag duration-ms profiler-id options))))

(defn miniprofiler-resource-path [req options]
  (let [^String uri (:uri req)]
    (if (.contains uri (:base-path options))
      (do
        (if (.endsWith uri (str (:base-path options) "/includes.js"))
          "includes.js"
          (if (.endsWith uri (str (:base-path options) "/includes.tmpl"))
            "includes.tmpl"
            (if (.endsWith uri (str (:base-path options) "/includes.css"))
              "includes.css")))))))

(defn miniprofiler-results-request? [req options]
  (= (str (:base-path options) "/results") (:uri req)))

(defn get-id-from-req [req]
  (if (= (:request-method req) :post)
    (let [body (slurp (:body req))
          [_ id] (re-matches #".*id=([a-zA-Z\-0-9]*)&.*" body)]
      (if id
        id
        (get-in req [:params :id])))
    (string/replace (:query-string req) "id=" "")))

(defn render-share [id options]
  (let [resource (response/resource-response "share.html")
        result (fetch (:store options) id)]
    {:body
     (reduce
       (fn [result [k v]]
         (string/replace result (re-pattern (str "\\{" k "\\}")) (str v)))
       (slurp (:body resource))
       {"name" (get result "Name")
        "duration" (get result "DurationMilliseconds")
        "json" (json/generate-string result)
        "includes" (build-miniprofiler-script-tag (get result "DurationMilliseconds") (get result "Id") options)})}))

(defn miniprofiler-results-response [req options]
  (let [id (get-id-from-req req)]
    (if (= (:request-method req) :post)
      {:body (json/generate-string (fetch (:store options) id))}
      (render-share id options))))

(def default-options
  {:base-path "/miniprofiler"
   :authorized? (fn [req] (= (:server-name req) "localhost"))
   :trivial-ms 2})

(defn wrap-miniprofiler
  [handler opts]
  (let [options (map->Options (merge default-options opts))
        authorized? (:authorized? options)]
    (fn [req]
      (if (authorized? req)
        (if-let [miniprofiler-resource-path (miniprofiler-resource-path req options)]
          (->
            (response/resource-response miniprofiler-resource-path)
            (file-info-response req)
            (content-type-response req))
          (if (miniprofiler-results-request? req options)
            (miniprofiler-results-response req options)
            (let [t0 (System/nanoTime)
                  [profile-id response] (with-recording options req (handler req))
                  t1 (System/nanoTime)
                  duration-ms (float (/ (- t1 t0) 1000000))]
              (if (and (get-in response [:headers "Content-Type"])
                       (re-matches #".*text/html.*" (get-in response [:headers "Content-Type"])))
                (build-miniprofiler-response response duration-ms profile-id options)
                response))))
        (handler req)))))
