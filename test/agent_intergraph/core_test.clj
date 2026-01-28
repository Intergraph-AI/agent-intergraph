(ns agent-intergraph.core-test
  (:require [clojure.test :refer :all]
            [agent-intergraph.core :refer :all]
            [agent-intergraph.protocol :as protocol]
            [ring.mock.request :as mock]
            [cheshire.core :as json]))

(defn- setup-test-app []
  (let [state (atom (protocol/create-initial-state))
        config (protocol/load-config {})
        app (init-app state config)]
    {:app app :state state}))

(deftest test-app
  (let [{:keys [app state]} (setup-test-app)]
    (testing "status endpoint"
      (let [response (app (mock/request :get "/v1/status"))
            body (json/parse-string (:body response) true)]
        (is (= (:status response) 200))
        (is (contains? body :health))
        (is (contains? body :incarnation))))

    (testing "event endpoint"
      (let [event {:event/type "system/hello" :event/data {}}
            response (app (-> (mock/request :post "/v1/event")
                              (mock/json-body event)))
            body (json/parse-string (:body response) true)]
        (is (= (:status response) 200))
        (is (= (:status body) "received"))))

    (testing "not-found route"
      (let [response (app (mock/request :get "/invalid"))]
        (is (= (:status response) 404))))))