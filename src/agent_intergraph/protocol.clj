(ns agent-intergraph.protocol
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
           [org.slf4j MDC]))

;; --- Configuration Defaults ---

(def default-config
  {:mom-url "http://localhost:8080"
   :heartbeat-interval-ms 5000
   :agent-host "localhost"
   :agent-port 9090
   :wedge-heartbeat-threshold 10})

(defn load-config [overrides]
  (let [resource-config (try
                          (let [config-file (io/resource "config.edn")]
                            (if config-file
                              (edn/read-string (slurp config-file))
                              {}))
                          (catch Exception _ {}))]
    (merge default-config resource-config overrides)))

;; --- Identity Persistence ---

(def agent-id-file (io/file (System/getProperty "user.home") ".agent-id"))

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
      nil)
    (catch Exception e
      (log/error e "Failed to load persisted agent ID")
      nil)))

(defn- delete-persisted-agent-id! []
  (try
    (if (.exists agent-id-file)
      (io/delete-file agent-id-file))
    (catch Exception _ nil)))

;; --- Shared State Structure ---

(defn create-initial-state []
  {:id nil
   :incarnation (str (UUID/randomUUID))
   :mode :idle
   :idle-reason :no-task
   :health :degraded ;; Start degraded until lifecycle 'start'
   :lease-expires-at 0
   :progress {:token nil :step 0}
   :current-task nil
   :task-queue PersistentQueue/EMPTY
   :last-progress-token-change-time (System/currentTimeMillis)
   :heartbeats-without-progress 0
   :recovery-count 0
   :load 0.0})

;; --- Heartbeat & Lease ---

(declare bootstrap-identity!)

(defn- validate-heartbeat-state [state]
  (let [valid-modes #{:idle :working :waiting}
        valid-health #{:healthy :degraded :wedged}]
    (cond
      (not (contains? valid-modes (:mode state))) "invalid-mode"
      (not (contains? valid-health (:health state))) "invalid-health"
      :else nil)))

(defn- recover-agent! [agent-state config]
  (let [agent-id (:id @agent-state)
        url (str (:mom-url config) "/admin/agents/" agent-id "/recover")]
    (log/info "Attempting Agent Recovery for ID:" agent-id)
    (try
      (let [resp (http/post url {:accept :json :content-type :json :throw-exceptions false})
            status (:status resp)
            body (json/parse-string (:body resp) true)]
        (log/info "Recovery response status:" status "body:" body)
        (if (or (:success body) (= status 200))
          (do
            (log/info "Recovery successful! Agent re-authorized.")
            ;; If recovery returns a lease, use it
            (if-let [lease-until (:lease/granted-until body)]
              (let [expiry-ms (.toEpochMilli (Instant/parse lease-until))]
                (swap! agent-state assoc :lease-expires-at expiry-ms :health :healthy :recovery-count 0))
              (swap! agent-state assoc :health :healthy :recovery-count 0))
            true)
          (do
            (log/error "Recovery failed. Status:" status "Response:" (:body resp))
            false)))
      (catch Exception e
        (log/error e "Recovery connection failed")
        false))))

(defn- send-heartbeat! [agent-state config]
  (let [state @agent-state
        validation-error (validate-heartbeat-state state)]
    (if validation-error
      (do
        (log/error "Heartbeat validation failed:" validation-error)
        (swap! agent-state assoc :mode :idle :idle-reason :no-task))
      
      (let [payload {:agent/id (:id state)
                     :agent/incarnation (:incarnation state)
                     :agent/health (name (:health state))
                     :agent/load (:load state)
                     :agent/mode (name (:mode state))
                     :agent/idle-reason (name (:idle-reason state))
                     :agent/progress {:token (some-> (:token (:progress state)) str)
                                      :step (:step (:progress state))}}
            opts {:accept :json
                  :content-type :json
                  :form-params payload
                  :throw-exceptions false}]
        (try
          (let [response (http/post (str (:mom-url config) "/api/v1/heartbeat") opts)
                status (:status response)
                raw-body (:body response)]
            (cond
              (= status 200)
              (let [body (json/parse-string raw-body true)
                    lease-until (:lease/granted-until body)
                    commands (:commands body)]
                (if lease-until
                  (let [expiry-ms (.toEpochMilli (Instant/parse lease-until))]
                    (swap! agent-state assoc :lease-expires-at expiry-ms :recovery-count 0)
                    (log/debug "Heartbeat success. Lease renewed until:" lease-until)
                    
                    (when commands
                      (doseq [cmd commands]
                        (let [cmd-type (:type cmd)]
                          (case cmd-type
                            "stop" (do (log/warn "Stop command received") (System/exit 0))
                            "pause" (swap! agent-state assoc :mode :idle :idle-reason :blocked)
                            "continue" (log/debug "Continue command received")
                            (log/warn "Unknown orchestrator command:" cmd-type))))))
                  (log/warn "Heartbeat response missing lease/granted-until")))
              
              (= status 400)
              (let [body (json/parse-string raw-body true)]
                (if (= (:error body) "lease-expired-no-restart")
                  (do
                    (log/warn "MoM rejected heartbeat: Lease expired and locked. Triggering recovery...")
                    (swap! agent-state update :recovery-count inc)
                    (if (>= (:recovery-count @agent-state) 3)
                      (do
                        (log/error "Circuit breaker triggered: Multiple recovery failures. Re-bootstrapping identity.")
                        (delete-persisted-agent-id!)
                        (swap! agent-state assoc :id nil :health :degraded :recovery-count 0)
                        (future (Thread/sleep 1000) (bootstrap-identity! agent-state config)))
                      (recover-agent! agent-state config)))
                  (log/error "Heartbeat validation failed by MoM:" raw-body)))

              (= status 404)
              (do
                (log/error "Agent identity lost (404). Clearing local ID and re-bootstrapping...")
                (delete-persisted-agent-id!)
                (swap! agent-state assoc :id nil :health :degraded)
                ;; Trigger bootstrap in a future to avoid blocking the heartbeat loop
                (future 
                  (Thread/sleep 1000)
                  (bootstrap-identity! agent-state config)))
              
              :else
              (log/error "Heartbeat failed! Status:" status "Response:" raw-body)))
          (catch Exception e
            (log/error e "Heartbeat connection failed")
            (swap! agent-state assoc :health :degraded)))))))

(defn start-heartbeat-loop! [agent-state config]
  (future
    (while true
      (try
        (if-let [id (:id @agent-state)]
          (do 
            (MDC/put "agent-id" (str id))
            (send-heartbeat! agent-state config))
          (do
            ;; No ID? Maybe we are in the middle of re-bootstrapping
            (log/debug "No Agent ID, skipping heartbeat pulse.")))
        (catch Exception e
          (log/error e "Error in heartbeat loop")))
      (Thread/sleep (:heartbeat-interval-ms config)))))

;; --- Registration ---

(defn- get-or-create-owner! [config]
  (let [user-data {:name "Agent Owner" 
                   :email "agent-owner@intergraph.ai" 
                   :password "secure-agent-password"}]
    (try
      (let [opts {:accept :json :content-type :json :form-params user-data :throw-exceptions false}
            reg-resp (http/post (str (:mom-url config) "/auth/register") opts)
            reg-body (json/parse-string (:body reg-resp) true)]
        (if (:success reg-body)
          (get-in reg-body [:user :user/id])
          (let [login-resp (http/post (str (:mom-url config) "/auth/login") 
                                      (assoc opts :form-params (select-keys user-data [:email :password])))
                login-body (json/parse-string (:body login-resp) true)]
            (if (:success login-body)
              (get-in login-body [:user :user/id])
              nil))))
      (catch Exception _ nil))))

(defn- register-agent! [owner-id config]
  (let [agent-endpoint (str "http://" (:agent-host config) ":" (:agent-port config))
        agent-data {:owner-id owner-id
                    :name "Distributed Agent"
                    :type "ai"
                    :endpoint agent-endpoint}]
    (try
      (let [opts {:accept :json :content-type :json :form-params agent-data :throw-exceptions false}
            resp (http/post (str (:mom-url config) "/admin/agents/register") opts)
            body (json/parse-string (:body resp) true)]
        (or (:agent/id body) (:id body)))
      (catch Exception _ nil))))

(defn bootstrap-identity! [agent-state config]
  (let [id (if-let [persisted-id (load-persisted-agent-id)]
             (do
               (log/info "Using persisted agent ID:" persisted-id)
               persisted-id)
             (if-let [owner-id (get-or-create-owner! config)]
               (if-let [new-id (register-agent! owner-id config)]
                 (do
                   (log/info "Successfully registered new agent ID:" new-id)
                   (save-agent-id! new-id)
                   new-id)
                 nil)
               nil))]
    (if id
      (do
        (swap! agent-state assoc :id id)
        (MDC/put "agent-id" (str id))
        true)
      false)))

;; --- Storage API (KV & Reload) ---

(defn storage-push-kv! [agent-state config namespace data]
  (let [agent-id (:id @agent-state)
        url (str (:mom-url config) "/api/v1/storage/" namespace "/" agent-id "/kv")]
    (try
      (http/post url {:accept :json
                      :content-type :json
                      :body (json/generate-string data)
                      :throw-exceptions false})
      (catch Exception e
        (log/error e "Storage push failed")))))

(defn storage-reload! [agent-state config namespace]
  (let [agent-id (:id @agent-state)
        url (str (:mom-url config) "/api/v1/storage/" namespace "/" agent-id "/reload")]
    (try
      (let [resp (http/get url {:accept :json :throw-exceptions false})]
        (if (= (:status resp) 200)
          (json/parse-string (:body resp) true)
          nil))
      (catch Exception e
        (log/error e "Storage reload failed")
        nil))))

;; --- HTTP Server Framework ---

(defn- wrap-manual-json-fallback [handler]
  (fn [request]
    (let [raw-body (:body request)
          parsed-body (if (map? raw-body)
                        raw-body
                        (try
                          (when (or (instance? java.io.InputStream raw-body)
                                    (instance? java.io.Reader raw-body))
                            (json/parse-stream (io/reader raw-body) true))
                          (catch Exception _ nil)))]
      (handler (assoc request :body parsed-body)))))

(defn- create-app-routes [agent-state callbacks]
  (routes
    (POST "/v1/event" request
      (if-let [f (:on-event callbacks)]
        (f request)
        {:status 501 :body {:error "not-implemented"}}))
    
    (GET "/v1/status" []
      {:status 200 :body @agent-state})
    
    (POST "/v1/lifecycle" request
      (let [cmd (get-in request [:body :command])]
        (log/info "Lifecycle command received:" cmd)
        (cond
          (= cmd "start")
          (do (swap! agent-state assoc :health :healthy)
              {:status 200 :body {:status "healthy"}})
          (= cmd "stop")
          (do (swap! agent-state assoc :health :degraded)
              {:status 200 :body {:status "degraded"}})
          :else
          {:status 400 :body {:error "unknown-command"}})))
    
    (POST "/admin/agents/:id/assign-task" [id :as request]
      (if-let [f (:on-task-assigned callbacks)]
        (f request)
        {:status 501 :body {:error "not-implemented"}}))

    (route/not-found "Not Found")))

(defn start-agent! [agent-state config callbacks]
  (log/info "Starting Intergraph Agent Protocol...")
  
  (if (bootstrap-identity! agent-state config)
    (do
      (log/info "Identity bootstrapped:" (:id @agent-state))
      (start-heartbeat-loop! agent-state config)
      
      (let [app (-> (create-app-routes agent-state callbacks)
                    (wrap-manual-json-fallback)
                    (wrap-json-body {:keywords? true})
                    (wrap-json-response))]
        
        (jetty/run-jetty app {:port (:agent-port config) :join? false})
        (log/info "Agent listening on port" (:agent-port config))
        
        agent-state))
    (do
      (log/error "Bootstrap failed. Identity is mandatory.")
      (System/exit 1))))

(defn create-app [agent-state callbacks]
  (-> (create-app-routes agent-state callbacks)
      (wrap-manual-json-fallback)
      (wrap-json-body {:keywords? true})
      (wrap-json-response)
      ((fn [handler]
         (fn [request]
           (if-let [id (:id @agent-state)]
             (MDC/put "agent-id" (str id)))
           (handler request))))))