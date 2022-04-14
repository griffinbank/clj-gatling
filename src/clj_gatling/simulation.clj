(ns clj-gatling.simulation
  (:require [clj-gatling.simulation-runners :as runners]
            [clj-gatling.schema :as schema]
            [clj-gatling.progress-tracker :as progress-tracker]
            [clj-gatling.simulation-util :refer [arg-count
                                                 choose-runner
                                                 clean-result
                                                 log-exception
                                                 weighted-scenarios]]
            [schema.core :refer [validate]]
            [clj-gatling.timers :as timers]
            [clojure.core.async :as async :refer [go go-loop close! alts! <! >! poll!]])
  (:import (java.time Duration LocalDateTime)))

(set! *warn-on-reflection* true)

(defn- now [] (System/currentTimeMillis))

(defn asynchronize [f ctx]
  (let [parse-result   (fn [result]
                         (if (instance? Throwable result)
                           {:result false :exception result}
                           {:result result}))
        parse-response (fn [response]
                         (if (vector? response)
                           (assoc (parse-result (first response)) :end-time (now) :context (second response))
                           (assoc (parse-result response) :end-time (now) :context ctx)))]
    (go
      (try
        (let [response (f ctx)]
          (if (instance? clojure.core.async.impl.channels.ManyToManyChannel response)
            (parse-response (<! response))
            (parse-response response)))
        (catch Exception e
          {:result false :end-time (now) :context ctx :exception e})
        (catch AssertionError e
          {:result false :end-time (now) :context ctx :exception e})))))

(defn async-function-with-timeout [step timeout sent-requests user-id original-context]
  (swap! sent-requests inc)
  (go
    (when-let [sleep-before (:sleep-before step)]
      (<! (timers/timeout (sleep-before original-context))))
    (let [original-context-with-user (assoc original-context :user-id user-id)
          start (now)
          return {:name (:name step)
                  :id user-id
                  :start start
                  :context-before original-context-with-user}
          response (asynchronize (:request step) original-context-with-user)
          [{:keys [result end-time context exception]} c] (alts! [response (timers/timeout timeout)])]
      (if (= c response)
        [(assoc return :end end-time
                       :exception exception
                       :result result
                       :context-after context) context]
        [(assoc return :end (now)
                       :exception (ex-info "clj-gatling: request timed out" {:timeout-in-ms timeout})
                       :result false
                       :context-after original-context-with-user)
         original-context-with-user]))))

(defn- response->result [scenario result]
  {:name (:name scenario)
   :id (:id (first result))
   :start (:start (first result))
   :end (:end (last result))
   :requests result})

(defn- next-step [[steps step-fn] context]
  (cond
    (seq steps)
    [(first steps) context [(rest steps) nil]]

    (ifn? step-fn)
    (let [result (step-fn context)
          ret (if (vector? result)
                result
                [result context])]
      (conj ret [nil step-fn]))

    :else
    [nil context [nil nil]]))

(defn- continue-run? [{:keys [runner sent-requests simulation-start next-run-at force-stop-ch]}]
  (if (poll! force-stop-ch)
    (do
      (println "Force stop requested. Not starting new scenarios anymore")
      false)
    (runners/continue-run? runner sent-requests simulation-start next-run-at)))

(defn- run-scenario-once [{:keys [runner force-stop-ch simulation-start] :as options}
                          {:keys [pre-hook post-hook] :as scenario} user-id]
  (let [timeout (:timeout-in-ms options)
        sent-requests (:sent-requests options)
        result-channel (async/chan)
        skip-next-after-failure? (if (nil? (:skip-next-after-failure? scenario))
                                   true
                                   (:skip-next-after-failure? scenario))
        request-failed? #(not (:result %))
        merged-context (or (merge (:context options) (:context scenario)) {})
        final-context (if pre-hook
                        (pre-hook merged-context)
                        merged-context)
        should-terminate? #(and (:allow-early-termination? scenario)
                                (not (continue-run? {:runner runner
                                                     :force-stop-ch force-stop-ch
                                                     :sent-requests @sent-requests
                                                     :simulation-start simulation-start
                                                     :next-run-at (LocalDateTime/now)})))
        step-ctx [(:steps scenario) (:step-fn scenario)]]
    (go-loop [[step context step-ctx] (next-step step-ctx final-context)
              results []]
      (let [[result new-ctx] (<! (async-function-with-timeout step
                                                              timeout
                                                              sent-requests
                                                              user-id
                                                              context))
            [step' _ _ :as next-steps] (next-step step-ctx new-ctx)]
        (when-let [e (:exception result)]
          (log-exception (:error-file options) e))
        (if (or (should-terminate?)
                (nil? step')
                (and skip-next-after-failure?
                     (request-failed? result)))
          (do
            (when post-hook
              (post-hook context))
            (>! result-channel (conj results (clean-result result))))
          (recur next-steps (conj results (clean-result result))))))
    result-channel))

(defn- run-concurrent-scenario-constantly
  [{:keys [concurrent-scenarios
           runner
           force-stop-ch
           sent-requests
           simulation-start
           concurrency
           concurrency-distribution
           context] :as options}
   scenario
   user-id]
  (let [c (async/chan)
        should-run-now? (if concurrency-distribution
                          (let [modifier-fn (if (= 2 (arg-count concurrency-distribution))
                                              (fn [{:keys [progress context]}]
                                                (concurrency-distribution progress context))
                                              concurrency-distribution)]
                            #(let [[progress duration] (runners/calculate-progress runner
                                                                                   @sent-requests
                                                                                   simulation-start
                                                                                   (LocalDateTime/now))
                                   target-concurrency (* concurrency (modifier-fn {:progress progress
                                                                                   :duration duration
                                                                                   :context context}))]
                               (> target-concurrency @concurrent-scenarios)))
                          (constantly true))]
    (go-loop []
      (if (should-run-now?)
        (do
          (swap! concurrent-scenarios inc)
          (let [result (<! (run-scenario-once options scenario user-id))]
            (swap! concurrent-scenarios dec)
            (>! c result)))
        (<! (timers/timeout 200)))
      (if (continue-run? {:runner runner
                          :sent-requests @sent-requests
                          :simulation-start simulation-start
                          :next-run-at (LocalDateTime/now)
                          :force-stop-ch force-stop-ch})
        (recur)
        (close! c)))
    c))

(defn- run-at
  "Tells the thread when it should next trigger"
  [run-tracker interval-ns]
  ;;Randomise the run times by +- 0.25*interval, to prevent sync-ups when distributed
  (let [jitter (int (- (rand-int (/ interval-ns 2)) (/ interval-ns 4)))]
    (swap! run-tracker #(.plusNanos ^LocalDateTime % (+ interval-ns jitter)))))

(defn- rate->interval-ns
  "Converts a rate-per-second of requests to a nanosecond interval between requests"
  [rate]
  (->> rate
       (/ 1000)
       (* 1000000)))

(defn- run-rate-scenario-constantly
  [{:keys [concurrent-scenarios
           run-tracker
           runner
           force-stop-ch
           rate-distribution
           prepared-requests
           sent-requests
           simulation-start
           context] :as options}
   scenario
   user-id]
  (let [c           (async/chan)
        rate        (:rate scenario)
        interval-ns (if rate-distribution
                      (let [modifier-fn (if (= 2 (arg-count rate-distribution))
                                          (fn [{:keys [progress context]}]
                                            (rate-distribution progress context))
                                          rate-distribution)]
                        #(let [[progress duration] (runners/calculate-progress runner
                                                                               @sent-requests
                                                                               simulation-start
                                                                               @run-tracker)
                               target-rate (* rate (modifier-fn {:progress progress
                                                                 :duration duration
                                                                 :context context}))]
                           (rate->interval-ns target-rate)))
                      (constantly (rate->interval-ns rate)))]
    (go-loop []
      (let [next-run (run-at run-tracker (interval-ns))
            pending  (swap! prepared-requests inc)]
        ;;This means we only wait if there are not already enough waiting
        ;;requests to complete the scenario
        (if (continue-run? {:runner runner
                            :sent-requests pending
                            :simulation-start simulation-start
                            :next-run-at next-run
                            :force-stop-ch force-stop-ch})
          (let [t (LocalDateTime/now)]
            (when (.isBefore t next-run)
              (<! (timers/timeout (.toMillis (Duration/between t next-run)))))
            (swap! concurrent-scenarios inc)
            (let [result (<! (run-scenario-once options scenario user-id))]
              (swap! concurrent-scenarios dec)
              (>! c result))
            (recur))
          (close! c))))
    c))

(defn- print-scenario-info [scenario]
  (let [rate (:rate scenario)]
    (println "Running scenario" (:name scenario)
             (if rate
               (str "with rate " rate " users/sec")
               (str "with concurrency " (count (:users scenario)))))))

(defn- run-scenario [run-constantly-fn options scenario]
  (print-scenario-info scenario)
  (let [responses (async/merge (map #(run-constantly-fn options scenario %) (:users scenario)))
        results (async/chan)]
    (go-loop []
      (if-let [result (<! responses)]
        (do
          (>! results (response->result scenario result))
          (recur))
        (close! results)))
    results))

(defn run-scenarios [{:keys [post-hook
                             context
                             runner
                             concurrency
                             concurrency-distribution
                             rate
                             rate-distribution
                             progress-tracker
                             default-progress-tracker] :as options}
                     scenarios]
  (println "Running simulation with"
           (runners/runner-info runner)
           (if rate
             (if rate-distribution
               (str "adding " rate " users/sec, with rate distribution function")
               (str "adding " rate " users/sec"))
             (if concurrency-distribution
               (str "using request concurrency " concurrency " with concurrency distribution function")
               (str "using request concurrency " concurrency))))
  (validate [schema/RunnableScenario] scenarios)
  (let [simulation-start (LocalDateTime/now)
        prepared-requests (atom 0)
        sent-requests (atom 0)
        force-stop-ch (async/chan)
        force-stop-fn #(async/put! force-stop-ch true)
        scenario-concurrency-trackers (reduce (fn [m k] (assoc m k (atom 0))) {} (map :name scenarios))
        rate-run-trackers (when rate
                            (reduce (fn [m k] (assoc m k (atom simulation-start))) {} (map :name scenarios)))
        ;;TODO Maybe use try-finally for stopping
        stop-progress-tracker (progress-tracker/start {:runner runner
                                                       :force-stop-fn force-stop-fn
                                                       :sent-requests sent-requests
                                                       :start-time simulation-start
                                                       :scenario-concurrency-trackers scenario-concurrency-trackers
                                                       :default-progress-tracker default-progress-tracker
                                                       :progress-tracker progress-tracker})
        run-scenario-with-opts (fn [{:keys [name] :as scenario}]
                                 (run-scenario (if rate
                                                 run-rate-scenario-constantly
                                                 run-concurrent-scenario-constantly)
                                               (assoc options
                                                 :concurrent-scenarios (get scenario-concurrency-trackers name)
                                                 :run-tracker (get rate-run-trackers name)
                                                 :force-stop-ch force-stop-ch
                                                 :simulation-start simulation-start
                                                 :prepared-requests prepared-requests
                                                 :sent-requests sent-requests)
                                               scenario))
        responses (async/merge (map run-scenario-with-opts scenarios))
        results (async/chan)]
    (go-loop []
      (if-let [result (<! responses)]
        (do
          (>! results result)
          (recur))
        (do
          (close! results)
          (stop-progress-tracker)
          (when post-hook (post-hook context)))))
    {:results results :force-stop-fn force-stop-fn}))

(defn run [{:keys [scenarios pre-hook post-hook] :as simulation}
           {:keys [concurrency rate users context] :as options}]
  (validate schema/Simulation simulation)
  (let [user-ids (or users (range concurrency))
        final-ctx (merge context (when pre-hook (pre-hook context)))]
    (run-scenarios (assoc options
                          :context final-ctx
                          :post-hook post-hook
                          :runner (choose-runner scenarios
                                                 (count user-ids)
                                                 options))
                   (weighted-scenarios user-ids rate scenarios))))
