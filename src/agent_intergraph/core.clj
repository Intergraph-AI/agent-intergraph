(ns agent-intergraph.core
  (:require [agent-intergraph.protocol :as protocol]
            [ring.adapter.jetty :as jetty]
            [clojure.tools.logging :as log]
            [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.java.io :as io])
  (:import [java.util UUID])
  (:gen-class))

;; --- Test Agent Logic ---

(defn- submit-action! [state action mom-url]
  (let [expiry (:lease-expires-at @state)
        now (System/currentTimeMillis)]
    (if (> expiry now)
      (try
        (log/info "Submitting action to MoM:" action)
        (http/post (str mom-url "/api/v1/action")
                   {:content-type :json
                    :body (json/generate-string action)
                    :throw-exceptions false})
        (catch Exception e
          (log/error e "Failed to submit action")))
      (log/error "Refusing to submit action: No valid lease held."))))

(defn- handle-event [state request mom-url]
  (let [event (:body request)
        event-type (:event/type event)]
    (log/info "TestAgent received event:" event-type)
    
    (when (:current-task @state)
      (swap! state update-in [:progress :step] inc))
    
    (if (= (str event-type) "system/hello")
      (do
        (log/info ">>> HelloWorld Agent says: Hello, Intergraph! <<<")
        (submit-action! state 
                       {:action/type :ledger/transfer
                        :action/actor-id (:id @state)
                        :action/payload {:reason "Responded to hello"}}
                       mom-url))
      (log/info "Ignored event type:" event-type))
    
    {:status 200 :body {:status "received" :agent-id (:id @state)}}))

(defn- handle-task-assignment [state request]
  (let [task (:body request)]
    (log/info "Task assigned to TestAgent:" (:task/id task))
    (swap! state update :task-queue conj
           {:task-id (:task/id task)
            :spec (:task-spec task)
            :assigned-at (:assigned-at task)
            :resume-from (:resume-from task)
            :progress-token (:progress-token task)})
    {:status 200 :body {:status "queued"}}))

;; --- Task Processor ---

(defn- generate-monotonic-progress-token [current-token]
  (if (nil? current-token)
    (UUID/randomUUID)
    (loop []
      (let [new-token (UUID/randomUUID)]
        (if (>= (compare (str new-token) (str current-token)) 0)
          new-token
          (recur))))))

(defn- process-next-task! [state]
  (let [curr-state @state]
    (when (and (nil? (:current-task curr-state))
               (not (empty? (:task-queue curr-state)))
               (= :healthy (:health curr-state)))
      (let [task (peek (:task-queue curr-state))
            new-token (generate-monotonic-progress-token (:progress-token task))]
        (log/info "TestAgent starting task:" (:task-id task))
        (swap! state assoc
               :task-queue (pop (:task-queue curr-state))
               :current-task task
               :mode :working
               :idle-reason :none
               :progress {:token new-token :step (or (:resume-from task) 0)}
               :last-progress-token-change-time (System/currentTimeMillis)
               :heartbeats-without-progress 0)))))

(defn start-task-processor! [state]
  (future
    (while true
      (try
        (process-next-task! state)
        (catch Exception e (log/error e "Error in task processor")))
      (Thread/sleep 1000))))

;; --- App Initialization ---

(defn init-app [state config]
  (let [mom-url (:mom-url config)]
    (protocol/create-app state 
      {:on-event (fn [req] (handle-event state req mom-url))
       :on-task-assigned (fn [req] (handle-task-assignment state req))})))

;; --- Main ---

(defn -main [& args]
  (let [config (protocol/load-config 
                 (when (first args) {:agent-port (Integer/parseInt (first args))}))
        state (atom (protocol/create-initial-state))]
    
    (if (protocol/bootstrap-identity! state config)
      (do
        (log/info "Identity bootstrapped:" (:id @state))
        (protocol/start-heartbeat-loop! state config)
        
        (let [app (init-app state config)]
          (jetty/run-jetty app {:port (:agent-port config) :join? false})
          (log/info "Test Agent fully initialized on port" (:agent-port config))
          (start-task-processor! state)))
      (do
        (log/error "Bootstrap failed. Identity is mandatory.")
        (System/exit 1)))
    
    ;; Keep the main thread alive if jetty is not joining
    @(promise)))