(ns agent-intergraph.core
  (:require [ring.adapter.jetty :as jetty]
            [compojure.core :refer :all]
            [compojure.route :as route]
            [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
            [clj-http.client :as http]
            [cheshire.core :as json]
            [clojure.tools.logging :as log])
  (:gen-class))

(def agent-state (atom {:status :stopped}))

(defn- submit-action! [mom-url action]
  (log/info "Submitting action to MoM:" action)
  (try
    (http/post (str mom-url "/api/v1/action")
               {:content-type :json
                :body (json/generate-string action)
                :throw-exceptions false})
    (catch Exception e
      (log/error e "Failed to submit action"))))

(defn handle-event [request]
  (let [event (:body request)
        event-type (:event/type event)]
    (log/info "Received event:" event-type "Payload:" event)
    (if (= (str event-type) "system/hello")
      (do
        (log/info ">>> HelloWorld Agent says: Hello, Intergraph! <<<")
        ;; Example: submit a settlement action back to MoM
        (submit-action! "http://localhost:8080"
                        {:action/type :ledger/transfer
                         :action/actor-id "distributed-agent-id"
                         :action/payload {:reason "Responded to hello"}}))
      (log/info "Ignored event type:" event-type))
    {:status 200 :body {:status "received"}}))

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
    (jetty/run-jetty app {:port (Integer/parseInt port) :join? false})))