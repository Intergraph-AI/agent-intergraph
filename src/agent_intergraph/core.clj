(ns agent-intergraph.core
  (:require [ring.adapter.jetty :as jetty]
            [compojure.core :refer :all]
            [compojure.route :as route]
            [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
            [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.tools.logging :as log])
  (:import [java.time Instant]
           [java.util UUID])
  (:gen-class))

;; --- State ---

(def agent-state (atom {:id nil ;; Will be set during bootstrap
                        :incarnation (str (UUID/randomUUID))
                        :status :healthy ;; Must be :healthy, :degraded, or :wedged
                        :lease-expires-at 0
                        :current-task nil
                        :load 0.0
                        :progress {:step 0}}))

(def mom-url (or (System/getenv "MOM_URL") "http://localhost:8080"))

;; --- Helpers ---

(defn authorized? []
  (let [expiry (:lease-expires-at @agent-state)
        now (System/currentTimeMillis)]
    (if (> expiry now)
      true
      (do
        (log/warn "AUTHORITY EXPIRED! Lease expired at" (Instant/ofEpochMilli expiry) "Current time" (Instant/now))
        false))))

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
      ;; Try to Register
      (let [opts {:accept :json
                  :content-type :json
                  :form-params user-data
                  :throw-exceptions false}]
        (log/info "Bootstrap: Registering owner. Request opts:" opts)
        (let [reg-resp (http/post (str mom-url "/auth/register") opts)
              reg-body (json/parse-string (:body reg-resp) true)]
        
          (if (:success reg-body)
            (get-in reg-body [:user :user/id])
            
            ;; If register failed (likely exists), try Login
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

(defn- register-agent! [owner-id]
  (let [agent-data {:owner-id owner-id
                    :name "Distributed Agent 01"
                    :type "ai"
                    :config {:capabilities ["compute" "storage"]}}]
    (try
      (let [opts {:accept :json
                  :content-type :json
                  :form-params agent-data
                  :throw-exceptions false}]
        (log/info "Bootstrap: Registering agent. Request opts:" opts)
        (let [resp (http/post (str mom-url "/admin/agents") opts)
              body (json/parse-string (:body resp) true)]
        
          (if (or (:agent/id body) (:id body))
            (or (:agent/id body) (:id body))
            (do
              (log/error "Agent registration failed. Response body:" body)
              nil))))
      (catch Exception e
        (log/error e "Bootstrap: Agent registration failed")
        nil))))

(defn bootstrap! []
  (log/info "Bootstrapping Agent Identity...")
  (if-let [owner-id (get-or-create-owner!)]
    (if-let [agent-id (register-agent! owner-id)]
      (do
        (log/info "Bootstrap Success! Assigned Agent ID:" agent-id)
        (swap! agent-state assoc :id agent-id)
        true)
      (do (log/error "Could not create/find agent.") false))
    (do (log/error "Could not create/find owner.") false)))

;; --- Heartbeat Logic ---

(defn- send-heartbeat! []
  (let [state @agent-state
        ;; Use keywords that match MoM namespaced expectation
        payload {:agent/id (:id state)
                 :agent/incarnation (:incarnation state)
                 :heartbeat/timestamp (str (Instant/now))
                 :agent/health (name (:status state))
                 :agent/load (:load state)
                 :agent/can-accept-work (= (:status state) :healthy)
                 :agent/progress (:progress state)}]
    (try
      (let [opts {:accept :json
                  :content-type :json
                  :form-params payload
                  :throw-exceptions false}]
        (log/info "Sending heartbeat. Request opts:" opts)
        (let [response (http/post (str mom-url "/api/v1/heartbeat") opts)
              status (:status response)
              raw-body (:body response)]
        
          (if (= status 200)
            (try
              (let [body (json/parse-string raw-body true)
                    lease-until (:lease/granted-until body)]
                (if lease-until
                  (let [expiry-ms (.toEpochMilli (Instant/parse lease-until))]
                    (swap! agent-state assoc :lease-expires-at expiry-ms :status :healthy)
                    (log/debug "Lease renewed until:" lease-until))
                  (log/warn "Heartbeat response missing lease/granted-until:" body)))
              (catch Exception parse-e
                (log/error parse-e "Failed to parse heartbeat response as JSON. Body was:" raw-body)))
          
            (log/warn "Heartbeat failed with status:" status "Body:" raw-body))))
      
      (catch Exception e
        (log/error "Heartbeat connection failed:" (.getMessage e))
        (swap! agent-state assoc :status :degraded)))))

(defn start-heartbeat-loop! []
  (log/info "Starting heartbeat loop. MoM URL:" mom-url)
  (future
    (while true
      (send-heartbeat!)
      (Thread/sleep 5000))))

;; --- Handlers ---

(defn handle-event [request]
  (let [event (:body request)
        event-type (:event/type event)]
    (log/info "Received event:" event-type "Payload:" event)
    
    ;; Update progress to prove we are alive and processing
    (swap! agent-state update-in [:progress :step] inc)
    
    (if (= (str event-type) "system/hello")
      (do
        (log/info ">>> HelloWorld Agent says: Hello, Intergraph! <<<")
        (submit-action! {:action/type :ledger/transfer
                         :action/actor-id (:id @agent-state)
                         :action/payload {:reason "Responded to hello"}}))
      (log/info "Ignored event type:" event-type))
    {:status 200 :body {:status "received" :agent-id (:id @agent-state)}}))

(defroutes app-routes
  (POST "/v1/event" request (handle-event request))
  (GET "/v1/status" [] {:status 200 :body @agent-state})
  (POST "/v1/lifecycle" request 
    (let [cmd (get-in request [:body :command])]
      (swap! agent-state assoc :status (keyword cmd))
      (log/info "Lifecycle command received:" cmd "New status:" @agent-state)
      {:status 200 :body {:status cmd}}))
  (route/not-found "Not Found"))

(def app
  (-> app-routes
      (wrap-json-body {:keywords? true})
      (wrap-json-response)))

(defn -main [& args]
  (let [port (or (first args) "9090")]
    (log/info "Starting Distributed Agent on port" port)
    
    ;; Attempt bootstrap
    (if (bootstrap!)
      (do
        (start-heartbeat-loop!)
        (jetty/run-jetty app {:port (Integer/parseInt port) :join? false}))
      (do
        (log/error "BOOTSTRAP FAILED. Agent cannot start without Identity.")
        (System/exit 1)))))