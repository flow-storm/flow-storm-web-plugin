(ns flow-storm.plugins.web.runtime
  (:require [flow-storm.runtime.indexes.api :as ia]
            [flow-storm.runtime.debuggers-api :as dbg-api]
            [clojure.string :as str]))

(defn- find-entry-idx-for-pred [timeline start-idx next-idx-fn pred]
  (loop [i start-idx]
    (when (<= 0 i (dec (count timeline)))
      (let [entry (get timeline i)]
        (if (pred entry)
          (ia/entry-idx entry)
          (recur (next-idx-fn i)))))))

(defn- fn-call-ns [timeline entry]
  (when-let [entry-fn-call (if (ia/fn-call-trace? entry)
                             entry
                             (get timeline (ia/fn-call-idx entry)))]
    (ia/get-fn-ns entry-fn-call)))

(defn- http-kit-req-handling-entry? [timeline entry]
  (boolean
   (let [idx (ia/entry-idx entry)]
     (and (> idx 1)
          (ia/expr-trace? entry)
          (map? (ia/get-expr-val entry))
          (= 'rreq (ia/get-sub-form timeline entry))
          (let [prev-entry (get timeline (dec idx))]
            (and
             (ia/expr-trace? prev-entry)
             (ifn? (ia/get-expr-val prev-entry))
             (= 'ring-handler (ia/get-sub-form timeline prev-entry))))))))

(defn- http-kit-resp-handling-entry? [timeline entry]
  (boolean
   (and (ia/expr-trace? entry)
        (map? (ia/get-expr-val entry))
        (= '(ring-websocket-resp rreq (ring-handler rreq)) (ia/get-sub-form timeline entry)))))

(defn- http-or-db-entry? [timeline entry]
  (boolean
   (let [entry-fn-call (if (ia/fn-call-trace? entry)
                         entry
                         (get timeline (ia/fn-call-idx entry)))]
     (or (str/starts-with? (ia/get-fn-ns entry-fn-call) "next.jdbc")
         (str/starts-with? (ia/get-fn-ns entry-fn-call) "org.httpkit")))))

(defn- next-jdbc-sql-entry? [timeline entry]
  (boolean
   (let [idx (ia/entry-idx entry)]
     (and (ia/expr-trace? entry)
          (string? (ia/get-expr-val entry))
          (= '(first sql-params) (ia/get-sub-form timeline entry))
          (let [entry+2 (get timeline (+ 2 idx))]
            (and
             (ia/expr-trace? entry+2)
             (= '(rest sql-params) (ia/get-sub-form timeline entry+2))))))))

(defn data-keeper [*threads-fns-level flow-id thread-id entry]

  (try
    (let [timeline (ia/get-timeline flow-id thread-id)
         idx (ia/entry-idx entry)]
     (cond

       (and (ia/fn-call-trace? entry)
            (not (http-or-db-entry? timeline entry)))
       (do
         (swap! *threads-fns-level (fn [levels] (update levels thread-id (fnil inc 0))))
         {:type :fn-call
          :fn-ns (ia/get-fn-ns entry)
          :fn-name (ia/get-fn-name entry)
          :idx idx
          :level (get @*threads-fns-level thread-id)
          :thread-id thread-id})

       (and (ia/fn-end-trace? entry)
            (not (http-or-db-entry? timeline entry)))
       (do
         (swap! *threads-fns-level (fn [levels] (update levels thread-id dec)))
         nil)


       (http-kit-req-handling-entry? timeline entry)
       (let [req (ia/get-expr-val entry)]
         {:type :http-request
          :req (dissoc req :body)
          :thread-id thread-id
          :idx (find-entry-idx-for-pred ;; walk forward
                timeline
                idx
                inc
                (fn [entry]
                  (not (str/starts-with? (fn-call-ns timeline entry) "org.httpkit"))))})

       (http-kit-resp-handling-entry? timeline entry)
       (let [resp (ia/get-expr-val entry)]
         {:type :http-response
          :response resp
          :thread-id thread-id
          :idx (find-entry-idx-for-pred ;; walk back to before being on httpkit response code
                timeline
                idx
                dec
                (fn [entry]
                  (not (str/starts-with? (fn-call-ns timeline entry) "org.httpkit"))))})

       (next-jdbc-sql-entry? timeline entry)
       (let [statement (ia/get-expr-val entry)
             params (ia/get-expr-val (get timeline (+ 2 idx)))]
         {:type :sql
          :statement statement
          :params params
          :thread-id thread-id
          :idx (find-entry-idx-for-pred ;; walk back to whoever called next.jdbc
                timeline
                idx
                dec
                (fn [entry]
                  (not (str/starts-with? (fn-call-ns timeline entry) "next.jdbc"))))})))
    (catch Exception e
      (.printStackTrace e))))

(defn extract-data-task [flow-id]
  (let [*threads-fns-level (atom {})]
    (dbg-api/submit-async-interruptible-batched-timelines-keep-task
     (ia/timelines-for {:flow-id flow-id})
     (fn [thread-id tl-entry]
       (data-keeper *threads-fns-level flow-id thread-id tl-entry)))))

(dbg-api/register-api-function :plugins.web/extract-data-task extract-data-task)
