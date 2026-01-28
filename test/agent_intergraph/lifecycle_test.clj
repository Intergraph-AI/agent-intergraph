(ns agent-intergraph.lifecycle-test
  (:require [clojure.test :refer :all]
            [agent-intergraph.core :refer [init-app]]
            [agent-intergraph.protocol :as protocol]
            [ring.mock.request :as mock]
            [cheshire.core :as json]))

(defn- setup-test-app []
  (let [state (atom (protocol/create-initial-state))
        config (protocol/load-config {})
        app (init-app state config)]
    {:app app :state state}))

(deftest test-lifecycle-endpoint
  (testing "start command"
    (let [{:keys [app state]} (setup-test-app)]
      (swap! state assoc :health :degraded) ;; Start as degraded
      (let [request (-> (mock/request :post "/v1/lifecycle")
                        (mock/json-body {:command "start"}))
            response (app request)
            body (json/parse-string (:body response) true)]
        (is (= (:status response) 200))
        (is (= (:status body) "healthy"))
        (is (= :healthy (:health @state))))))

  (testing "stop command"
    (let [{:keys [app state]} (setup-test-app)]
      (swap! state assoc :health :healthy) ;; Start as healthy
      (let [request (-> (mock/request :post "/v1/lifecycle")
                        (mock/json-body {:command "stop"}))
            response (app request)
            body (json/parse-string (:body response) true)]
        (is (= (:status response) 200))
        (is (= (:status body) "degraded"))
        (is (= :degraded (:health @state))))))

  (testing "unknown command"
    (let [{:keys [app state]} (setup-test-app)]
      (let [request (-> (mock/request :post "/v1/lifecycle")
                        (mock/json-body {:command "dance"}))
            response (app request)
            body (json/parse-string (:body response) true)]
        (is (= (:status response) 400))
        (is (= (:error body) "unknown-command"))))))
