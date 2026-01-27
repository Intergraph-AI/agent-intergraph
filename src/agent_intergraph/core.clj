(ns agent-intergraph.core
  (:require [ring.adapter.jetty :as jetty]
            [compojure.core :refer :all]
            [compojure.route :as route]
            [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
            [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.java.io :as io])
  (:import [java.time Instant]
           [java.util UUID]
           [clojure.lang PersistentQueue]
           [org.slf4j MDC])
  (:gen-class))

;; --- Configuration ---

(defn load-config []
  (try
    (let [config-file (io/resource "config.edn")]
      (if config-file
        (edn/read-string (slurp config-file))
        (do
          (log/warn "config.edn not found in resources, using defaults")
          {})))
    (catch Exception e
      (log/error e "Error loading config.edn, using defaults")
      {})))

(def config (load-config))

(def mom-url (or (System/getenv "MOM_URL") 
                 (:mom-url config) 
                 "http://localhost:8080"))

(def heartbeat-interval-ms (or (:heartbeat-interval-ms config) 5000))

(def agent-host (or (System/getenv "AGENT_HOST")
                    (:agent-host config)
                    "localhost"))

(def agent-port (or (System/getenv "AGENT_PORT")
                    (:agent-port config)
                    9090))

(def wedge-heartbeat-threshold (or (:wedge-heartbeat-threshold config) 10))

(def agent-id-file (io/file (System/getProperty "user.home") ".agent-id"))

;; --- State ---

(def agent-state (atom {
  ;; Identity
  :id nil                                    ; agent/id UUID persisted
  :incarnation (str (UUID/randomUUID))      ; fresh each startup NOT persisted
  
  ;; v2 State Machine
  :mode :idle                               ; idle, working, waiting
  :idle-reason :no-task                     ; one of 8 valid reasons
  :health :healthy                          ; healthy, degraded, wedged
  
  ;; Leasing
  :lease-expires-at 0                       ; timestamp in ms
  
  ;; Progress Tracking
  :progress {:token nil                     ; UUID token lexicographically monotonic
             :step 0}                       ; step counter for UI
  
  ;; Task Management
  :current-task nil                         ; {:id :spec :assigned-at :resume-from :progress-token :checkpoint}
  :task-queue PersistentQueue/EMPTY   ; queue of pending task assignments
  
  ;; Timing & Detection
  :last-progress-token-change-time (System/currentTimeMillis)
  :heartbeats-without-progress 0            ; for wedge detection
  :status :degraded}))                      ; legacy field

;; --- Validation ---

(def valid-modes #{:idle :working :waiting})

(def valid-idle-reasons #{:no-task :dependency :cooldown :rate-limit :blocked :waiting-for-resource :max-retries-reached})

(def valid-health-statuses #{:healthy :degraded :wedged})

(defn- valid-mode-idle-reason-combo? [mode idle-reason]
  (case mode
    :idle (contains? valid-idle-reasons idle-reason)
    :working (= idle-reason :none)
    :waiting (contains? #{:dependency :cooldown :rate-limit :blocked :waiting-for-resource} idle-reason)
    false))

(defn- validate-heartbeat-state [state]
  "Validate agent state before sending heartbeat. Returns error map or nil."
  (let [mode (:mode state)
        idle-reason (:idle-reason state)
        health (:health state)]
    (cond
      (not (contains? valid-modes mode))
      {:error "invalid-mode" :details {:mode mode}}
      
      (not (contains? valid-health-statuses health))
      {:error "invalid-health" :details {:health health}}
      
      (not (valid-mode-idle-reason-combo? mode idle-reason))
      {:error "invalid-idle-reason" :details {:mode mode :idle-reason idle-reason}}
      
      :else nil)))

;; --- Persistence ---

(defn- save-agent-id! [agent-id]
  (try
    (spit agent-id-file (str agent-id))
    (log/info "Agent ID persisted to" (.getAbsolutePath agent-id-file))
    (catch Exception e
      (log/error e "Failed to persist agent ID"))))

(defn- load-persisted-agent-id []
  (try
    (if (.exists agent-id-file)
      (let [id (str/trim (slurp agent-id-file))]
        (log/info "Loaded persisted agent ID:" id)
        id)
      (do
        (log/info "No persisted agent ID found")
        nil))
    (catch Exception e
      (log/error e "Failed to load persisted agent ID")
      nil)))

(defn- get-checkpoint-file [agent-id]
  (io/file (System/getProperty "user.home") (str ".agent-checkpoint-" agent-id)))

(defn- save-checkpoint! [agent-id checkpoint-data]
  (try
    (let [file (get-checkpoint-file agent-id)]
      (spit file (pr-str checkpoint-data))
      (log/info "Checkpoint persisted for agent" agent-id))
    (catch Exception e
      (log/error e "Failed to persist checkpoint"))))

(defn- load-checkpoint! [agent-id]
  (try
    (let [file (get-checkpoint-file agent-id)]
      (if (.exists file)
        (let [data (edn/read-string (slurp file))]
          (log/info "Loaded checkpoint for agent" agent-id)
          data)
        nil))
    (catch Exception e
      (log/error e "Failed to load checkpoint")
      nil)))

(defn- delete-checkpoint! [agent-id]
  (try
    (let [file (get-checkpoint-file agent-id)]
      (if (.exists file)
        (io/delete-file file)
        true))
    (catch Exception e
      (log/error e "Failed to delete checkpoint"))))

;; --- Helpers ---

(defn authorized? []
  (let [expiry (:lease-expires-at @agent-state)
        now (System/currentTimeMillis)]
    (if (> expiry now)
      true
      (do
        (log/warn "AUTHORITY EXPIRED! Lease expired at" (Instant/ofEpochMilli expiry) "Current time" (Instant/now))
        false))))

(defn- generate-monotonic-progress-token [current-token]
  "Generate a new UUID that's lexicographically >= current-token."
  (if (nil? current-token)
    (UUID/randomUUID)
    (loop []
      (let [new-token (UUID/randomUUID)]
        (if (>= (compare (str new-token) (str current-token)) 0)
          new-token
          (recur))))))

(defn- submit-action! [action]
  (if (authorized?)
    (do
      (log/info "Submitting action to MoM:" action)
      (try
        (http/post (str mom-url "/api/v1/action")
                   {:content-type :json
                    :body (json/generate-string action)
                    :throw-exceptions false})
        (catch Exception e
          (log/error e "Failed to submit action"))))
    (log/error "Refusing to submit action: No valid lease held.")))

;; --- Bootstrap ---

(defn- get-or-create-owner! []
  (let [user-data {:name "Agent Owner" 
                   :email "agent-owner@intergraph.ai" 
                   :password "secure-agent-password"}]
    (try
      (let [opts {:accept :json
                  :content-type :json
                  :form-params user-data
                  :throw-exceptions false}]
        (log/info "Bootstrap: Registering owner. Request opts:" opts)
        (let [reg-resp (http/post (str mom-url "/auth/register") opts)
              reg-body (json/parse-string (:body reg-resp) true)]
        
          (if (:success reg-body)
            (get-in reg-body [:user :user/id])
            
            (do
              (log/info "Bootstrap: Registration skipped (exists or failed), attempting login...")
              (let [login-opts {:accept :json
                                :content-type :json
                                :form-params (select-keys user-data [:email :password])
                                :throw-exceptions false}
                    _ (log/info "Bootstrap: Login request opts:" login-opts)
                    login-resp (http/post (str mom-url "/auth/login") login-opts)
                    login-body (json/parse-string (:body login-resp) true)]
                (if (:success login-body)
                  (get-in login-body [:user :user/id])
                  (throw (ex-info "Failed to register or login owner" {:register reg-body :login login-body}))))))))
      (catch Exception e
        (log/error e "Bootstrap: Owner resolution failed")
        nil))))

(defn- register-agent! [owner-id agent-endpoint]
  (let [agent-data {:owner-id owner-id
                    :name "Distributed Agent 01"
                    :type "ai"
                    :endpoint agent-endpoint}]
    (try
      (let [opts {:accept :json
                  :content-type :json
                  :form-params agent-data
                  :throw-exceptions false}]
        (log/info "Bootstrap: Registering agent. Request opts:" opts)
        (let [resp (http/post (str mom-url "/admin/agents/register") opts)
              body (json/parse-string (:body resp) true)]
        
          (if (or (:agent/id body) (:id body))
            (or (:agent/id body) (:id body))
            (do
              (log/error "Agent registration failed. Response body:" body)
              nil))))
      (catch Exception e
        (log/error e "Bootstrap: Agent registration failed")
        nil))))

(defn bootstrap! [agent-port agent-host]
  (log/info "Bootstrapping Agent Identity...")
  (let [agent-endpoint (str "http://" agent-host ":" agent-port)]
    (if-let [owner-id (get-or-create-owner!)]
      (do
        (if-let [persisted-id (load-persisted-agent-id)]
          (do
            (log/info "Found persisted agent ID, using:" persisted-id)
            (swap! agent-state assoc :id persisted-id)
            (MDC/put "agent-id" (str persisted-id))
            true)
          (if-let [agent-id (register-agent! owner-id agent-endpoint)]
            (do
              (log/info "Bootstrap Success! Assigned new Agent ID:" agent-id)
              (swap! agent-state assoc :id agent-id)
              (MDC/put "agent-id" (str agent-id))
              (save-agent-id! agent-id)
              true)
            (do (log/error "Could not create/find agent.") false))))
      (do (log/error "Could not create/find owner.") false))))

;; --- Heartbeat Logic ---

(defn- send-heartbeat! []
  (let [state @agent-state
        validation-error (validate-heartbeat-state state)]
    (if validation-error
      (do
        (log/error "Heartbeat validation failed:" validation-error)
        (log/warn "Fixing state and will retry on next heartbeat")
        ;; Force safe state
        (swap! agent-state assoc :mode :idle :idle-reason :no-task))
      
      (let [payload {:agent/id (:id state)
                     :agent/incarnation (:incarnation state)
                     :agent/health (name (:health state))
                     :agent/load (:load state)
                     :agent/mode (name (:mode state))
                     :agent/idle-reason (name (:idle-reason state))
                     :agent/progress {:token (if-let [t (:token (:progress state))]
                                               (str t)
                                               nil)
                                      :step (:step (:progress state))}}
            opts {:accept :json
                  :content-type :json
                  :form-params payload
                  :throw-exceptions false}]
        (try
          (let [response (http/post (str mom-url "/api/v1/heartbeat") opts)
                status (:status response)
                raw-body (:body response)]
            (if (= status 200)
              (let [body (json/parse-string raw-body true)
                    lease-until (:lease/granted-until body)
                    commands (:commands body)]
                (if lease-until
                  (let [expiry-ms (.toEpochMilli (Instant/parse lease-until))]
                    (swap! agent-state assoc :lease-expires-at expiry-ms :health :healthy)
                    (log/info "Heartbeat success. Lease renewed until:" lease-until)
                    
                    ;; Process commands from orchestrator
                    (when commands
                      (doseq [cmd commands]
                        (let [cmd-type (:type cmd)]
                          (log/info "Processing command:" cmd-type)
                          (case cmd-type
                            "continue" (log/debug "Continue command received")
                            "pause" (do (log/info "Pause command received")
                                       (swap! agent-state assoc :mode :idle :idle-reason :blocked))
                            "stop" (do (log/warn "Stop command received")
                                      (System/exit 0))
                            (log/warn "Unknown command:" cmd-type))))))
                  (log/warn "Heartbeat response missing lease/granted-until. Response:" raw-body)))
              
              (if (= status 400)
                (let [body (json/parse-string raw-body true)
                      error (:error body)]
                  (case error
                    "validation-failed"
                    (do
                      (log/error "Heartbeat validation failed by MoM:" (:details body))
                      (swap! agent-state assoc :mode :idle :idle-reason :no-task))
                    
                    "lease-expired-no-restart"
                    (do
                      (log/error "CRITICAL: Lease expired, requires operator recovery. Waiting...")
                      (swap! agent-state assoc :mode :idle :idle-reason :waiting-for-resource :health :degraded))
                    
                    (log/error "Heartbeat error 400:" error)))
                
                (if (= status 404)
                  (let [body (json/parse-string raw-body true)
                        error-code (:error body)]
                    (if (= (str error-code) "agent-not-found")
                      (do
                        (log/error "Agent identity lost (404 agent-not-found). Attempting re-bootstrap...")
                        (swap! agent-state assoc :id nil :health :degraded)
                        (future
                          (Thread/sleep 1000)
                          (log/info "Re-bootstrapping agent after identity loss...")
                          (bootstrap! agent-port agent-host)))
                      (log/error "Heartbeat failed with 404. Response:" raw-body)))
                  
                  (log/error "Heartbeat failed! Status:" status "Response:" raw-body)))))

          (catch Exception e
            (log/error e "Heartbeat connection failed")
            (swap! agent-state assoc :health :degraded)))))))

(defn start-heartbeat-loop! []
  (let [agent-id (:id @agent-state)]
    (log/info "Starting heartbeat loop. MoM URL:" mom-url "Interval:" heartbeat-interval-ms "ms")
    (future
      (MDC/put "agent-id" (str agent-id))
      (while true
        (send-heartbeat!)
        
        ;; Wedge detection: check if progress token changed
        (let [state @agent-state]
          (if (and (= :working (:mode state))
                   (= :healthy (:health state)))
            (let [current-token (get-in state [:progress :token])
                  last-change (:last-progress-token-change-time state)]
              (if (= current-token (get-in state [:progress :token]))
                ;; No change, increment wedge counter
                (swap! agent-state update :heartbeats-without-progress inc)
                ;; Changed, reset counter
                (swap! agent-state assoc :heartbeats-without-progress 0
                       :last-progress-token-change-time (System/currentTimeMillis))))
            ;; Not working, reset counter
            (swap! agent-state assoc :heartbeats-without-progress 0)))
        
        ;; Check wedge condition
        (let [state @agent-state]
          (if (>= (:heartbeats-without-progress state) wedge-heartbeat-threshold)
            (do
              (log/error "Agent detected as wedged! No progress for" wedge-heartbeat-threshold "heartbeats")
              (swap! agent-state assoc :health :wedged :mode :idle :idle-reason :blocked))))
        
        (Thread/sleep heartbeat-interval-ms)))))

;; --- Task Management ---

(defn- handle-task-assignment [task-assignment]
  "Queue task assignment for processing."
  (let [task-id (:task/id task-assignment)
        task-spec (:task-spec task-assignment)
        assigned-at (:assigned-at task-assignment)
        resume-from (:resume-from task-assignment)
        progress-token (:progress-token task-assignment)]
    
    (log/info "Task assigned:" task-id)
    
    (swap! agent-state update :task-queue conj
      {:task-id task-id
       :spec task-spec
       :assigned-at assigned-at
       :resume-from resume-from
       :progress-token progress-token})
    
    (log/info "Task queued. Current queue size:" (count (:task-queue @agent-state)))))

(defn- process-next-task []
  "Process next task from queue if idle."
  (let [state @agent-state]
    (if (and (nil? (:current-task state))
             (not (empty? (:task-queue state))))
      (let [task (peek (:task-queue state))
            _ (swap! agent-state update :task-queue pop)
            task-id (:task-id task)
            spec (:spec task)]
        
        (log/info "Starting task:" task-id)
        
        ;; Load checkpoint if resuming
        (let [checkpoint-data (if (> (:resume-from task) 0)
                               (load-checkpoint! (str (:id state)))
                               nil)]
          
          ;; Initialize progress token
          (let [new-token (generate-monotonic-progress-token (:progress-token task))]
            (swap! agent-state assoc
              :current-task {:id task-id
                           :spec spec
                           :assigned-at (:assigned-at task)
                           :resume-from (:resume-from task)
                           :progress-token new-token
                           :checkpoint checkpoint-data}
              :mode :working
              :idle-reason :none
              :progress {:token new-token :step (:resume-from task)}
              :last-progress-token-change-time (System/currentTimeMillis)
              :heartbeats-without-progress 0)))))))

;; --- Event Handling ---

(defn handle-event [request]
  (let [event (:body request)
        event-type (:event/type event)]
    (log/info "Received event:" event-type "Payload:" event)
    
    ;; If working on task, update task progress
    (when (:current-task @agent-state)
      (swap! agent-state update-in [:progress :step] inc))
    
    (if (= (str event-type) "system/hello")
      (do
        (log/info ">>> HelloWorld Agent says: Hello, Intergraph! <<<")
        (submit-action! {:action/type :ledger/transfer
                        :action/actor-id (:id @agent-state)
                        :action/payload {:reason "Responded to hello"}}))
      (log/info "Ignored event type:" event-type))
    
    {:status 200 :body {:status "received" :agent-id (:id @agent-state)}}))

;; --- HTTP Handlers ---

(defroutes app-routes
  (POST "/v1/event" request (handle-event request))
  (GET "/v1/status" [] {:status 200 :body @agent-state})
  
  (POST "/v1/lifecycle" request 
    (let [cmd (get-in request [:body :command])
          new-status (if (= cmd "start") :healthy :degraded)]
      (swap! agent-state assoc :health new-status)
      (log/info "Lifecycle command received:" cmd "New health:" new-status)
      {:status 200 :body {:status (name new-status)}}))
  
  (POST "/v1/task/complete" request
    (let [body (:body request)
          agent-id (:agent-id body)
          task-id (:task-id body)
          final-token (:final-progress-token body)
          result-ref (:result-ref body)]
      
      (log/info "Task completion received:" task-id)
      
      ;; Verify task matches current
      (if (= task-id (get-in @agent-state [:current-task :id]))
        (do
          ;; Update progress token on completion
          (swap! agent-state assoc-in [:progress :token] (UUID/fromString final-token))
          
          ;; Clean up and transition to idle
          (swap! agent-state assoc
            :current-task nil
            :mode :idle
            :idle-reason :no-task
            :progress {:token (UUID/fromString final-token) :step 0})
          
          ;; Delete checkpoint
          (delete-checkpoint! (str agent-id))
          
          ;; Trigger processing of next queued task
          (process-next-task)
          
          {:status 200 :body {:status "completed" :task-id task-id}})
        
        (do
          (log/error "Task mismatch! Expected:" (get-in @agent-state [:current-task :id]) "Got:" task-id)
          {:status 400 :body {:error "task-mismatch" :expected (get-in @agent-state [:current-task :id]) :received task-id}}))))
  
  (POST "/admin/agents/:id/assign-task" [id :as request]
    (let [body (:body request)]
      (log/info "Task assignment request for agent:" id)
      (handle-task-assignment body)
      {:status 200 :body {:status "queued" :assignment body}}))
  
  (route/not-found "Not Found"))

(def app
  (-> app-routes
      (wrap-json-body {:keywords? true})
      (wrap-json-response)
      ((fn [handler]
         (fn [request]
           (if-let [agent-id (:id @agent-state)]
             (MDC/put "agent-id" (str agent-id)))
           (handler request))))))

;; --- Task Processor Loop ---

(defn start-task-processor! []
  (let [agent-id (:id @agent-state)]
    (log/info "Starting task processor loop")
    (future
      (MDC/put "agent-id" (str agent-id))
      (while true
        (process-next-task)
        (Thread/sleep 1000)))))

;; --- Main ---

(defn -main [& args]
  (let [port (or (first args) agent-port)
        host agent-host]
    (log/info "Starting Distributed Agent on port" port "host" host)
    
    ;; Attempt bootstrap
    (if (bootstrap! port host)
      (do
        (log/info "Bootstrap successful. Starting heartbeat and task processor...")
        (start-heartbeat-loop!)
        (start-task-processor!)
        (jetty/run-jetty app {:port (Integer/parseInt (str port)) :join? false}))
      (do
        (log/error "BOOTSTRAP FAILED. Agent cannot start without Identity.")
        (System/exit 1)))))
