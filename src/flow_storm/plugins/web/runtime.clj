(ns flow-storm.plugins.web.runtime
  (:require [flow-storm.runtime.indexes.api :as ia]
            [flow-storm.runtime.debuggers-api :as dbg-api]
            [clojure.string :as str]))

;;;;;;;;;;;
;; Utils ;;
;;;;;;;;;;;

(defn- find-entry-idx-for-pred [timeline start-idx next-idx-fn pred]
  (loop [i start-idx]
    (when (<= 0 i (dec (count timeline)))
      (let [entry (get timeline i)]
        (if (pred entry)
          i
          (recur (next-idx-fn i)))))))

(defn- fn-call-ns [timeline entry]
  (when-let [entry-fn-call (if (ia/fn-call-trace? entry)
                             entry
                             (get timeline (ia/fn-call-idx entry)))]
    (ia/get-fn-ns entry-fn-call)))

(defn- walk-backwards-to-entry-without-ns-prefix [timeline idx prefix]
  (find-entry-idx-for-pred
   timeline
   idx
   dec
   (fn [entry]
     (not (str/starts-with? (fn-call-ns timeline entry) prefix)))))

(defn- walk-forward-to-entry-without-ns-prefix [timeline idx prefix]
  (find-entry-idx-for-pred
   timeline
   idx
   inc
   (fn [entry]
     (not (str/starts-with? (fn-call-ns timeline entry) prefix)))))

;;;;;;;;;;;;;;;;;;;;
;; Entry matchers ;;
;;;;;;;;;;;;;;;;;;;;

;; Httpkit
(defn- http-kit-req-handling-entry? [timeline entry idx]
  (boolean
   (let [fn-call (ia/get-fn-call timeline idx)]
     (and (> idx 1)
          (ia/expr-trace? entry)
          (map? (ia/get-expr-val entry))
          (= 'rreq (ia/get-sub-form timeline idx))
          (str/starts-with? (ia/get-fn-name fn-call) "wrap-ring-websocket")
          (let [prev-idx (dec idx)
                prev-entry (get timeline prev-idx)]
            (and
             (ia/expr-trace? prev-entry)
             (ifn? (ia/get-expr-val prev-entry))
             (= 'ring-handler (ia/get-sub-form timeline prev-idx))))))))

(defn- http-kit-resp-handling-entry? [timeline entry idx]
  (boolean
   (let [fn-call (ia/get-fn-call timeline idx)]
     (and (ia/expr-trace? entry)
          (map? (ia/get-expr-val entry))
          (= '(ring-websocket-resp rreq (ring-handler rreq)) (ia/get-sub-form timeline idx))
          (str/starts-with? (ia/get-fn-name fn-call) "wrap-ring-websocket")))))

;; Jetty
(defn- jetty-req-handling-entry? [timeline entry idx]
  (boolean
   (let [fn-call (ia/get-fn-call timeline idx)]
     (and (ia/expr-trace? entry)
          (map? (ia/get-expr-val entry))
          (= 'request-map (ia/get-sub-form timeline idx))
          (str/starts-with? (ia/get-fn-name fn-call) "proxy-handler")))))

(defn- jetty-resp-handling-entry? [timeline entry idx]
  (boolean
   (let [fn-call (ia/get-fn-call timeline idx)]
     (and (ia/expr-trace? entry)
          (map? (ia/get-expr-val entry))
          (= '(handler request-map) (ia/get-sub-form timeline idx))
          (str/starts-with? (ia/get-fn-name fn-call) "proxy-handler")))))

;; Next-Jdbc
(defn- next-jdbc-sql-entry? [timeline entry idx]
  (boolean
   (and (ia/expr-trace? entry)
        (string? (ia/get-expr-val entry))
        (= '(first sql-params) (ia/get-sub-form timeline idx))
        (let [entry+2-idx (+ 2 idx)
              entry+2 (get timeline entry+2-idx)]
          (and
           (ia/expr-trace? entry+2)
           (= '(rest sql-params) (ia/get-sub-form timeline entry+2-idx)))))))

(defn- http-or-db-entry? [timeline _ idx]
  (boolean
   (let [entry-fn-call (ia/get-fn-call timeline idx)
         fn-ns (ia/get-fn-ns entry-fn-call)]
     (or (str/starts-with? fn-ns "next.jdbc")
         (str/starts-with? fn-ns "org.httpkit")
         (str/starts-with? fn-ns "ring.adapter.jetty")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Timelnie keeper (finder) ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn data-keeper [*threads-fns-level flow-id thread-id idx entry]

  (try
    (let [timeline (ia/get-timeline flow-id thread-id)]
     (cond

       (and (ia/fn-call-trace? entry)
            (not (http-or-db-entry? timeline entry idx)))
       (do
         (swap! *threads-fns-level (fn [levels] (update levels thread-id (fnil inc 0))))
         {:type :fn-call
          :fn-ns (ia/get-fn-ns entry)
          :fn-name (ia/get-fn-name entry)
          :idx idx
          :level (get @*threads-fns-level thread-id)
          :thread-id thread-id})

       (and (ia/fn-end-trace? entry)
            (not (http-or-db-entry? timeline entry idx)))
       (do
         (swap! *threads-fns-level (fn [levels] (update levels thread-id dec)))
         nil)


       (http-kit-req-handling-entry? timeline entry idx)
       (let [req (ia/get-expr-val entry)]
         {:type :http-request
          :req (dissoc req :body)
          :thread-id thread-id
          :idx (walk-forward-to-entry-without-ns-prefix timeline idx "org.httpkit")})

       (http-kit-resp-handling-entry? timeline entry idx)
       (let [resp (ia/get-expr-val entry)]
         {:type :http-response
          :response resp
          :thread-id thread-id
          :idx (walk-backwards-to-entry-without-ns-prefix timeline idx "org.httpkit")})

       (jetty-req-handling-entry? timeline entry idx)
       (let [req (ia/get-expr-val entry)]
         {:type :http-request
          :req (dissoc req :body)
          :thread-id thread-id
          :idx (walk-forward-to-entry-without-ns-prefix timeline idx "ring.adapter.jetty")})

       (jetty-resp-handling-entry? timeline entry idx)
       (let [resp (ia/get-expr-val entry)]
         {:type :http-response
          :response resp
          :thread-id thread-id
          :idx (walk-backwards-to-entry-without-ns-prefix timeline idx "ring.adapter.jetty")})

       (next-jdbc-sql-entry? timeline entry idx)
       (let [statement (ia/get-expr-val entry)
             params (ia/get-expr-val (get timeline (+ 2 idx)))]
         {:type :sql
          :statement statement
          :params params
          :thread-id thread-id
          :idx (walk-backwards-to-entry-without-ns-prefix timeline idx "next.jdbc")})))
    (catch Exception e
      (.printStackTrace e))))

(defn extract-data-task
  "Submit an interruptible task that will search for web-requests, functions calls and
  sql queries execution."
  [flow-id]
  (let [*threads-fns-level (atom {})]
    (dbg-api/submit-async-interruptible-batched-timelines-keep-task
     (ia/timelines-for {:flow-id flow-id})
     (fn [thread-id tl-idx tl-entry]
       (data-keeper *threads-fns-level flow-id thread-id tl-idx tl-entry)))))

(dbg-api/register-api-function :plugins.web/extract-data-task extract-data-task)
