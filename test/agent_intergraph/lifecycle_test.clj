(ns agent-intergraph.lifecycle-test
  (:require [clojure.test :refer :all]
            [agent-intergraph.core :refer [app agent-state]]
            [ring.mock.request :as mock]
            [cheshire.core :as json]))

(deftest test-lifecycle-endpoint
  (testing "start command"
    (reset! agent-state {:health :degraded}) ;; Start as degraded
    (let [request (-> (mock/request :post "/v1/lifecycle")
                      (mock/json-body {:command "start"}))
          response (app request)
          body (json/parse-string (:body response) true)]
      (is (= (:status response) 200))
      (is (= (:status body) "healthy"))
      (is (= :healthy (:health @agent-state)))))

  (testing "stop command"
    (reset! agent-state {:health :healthy}) ;; Start as healthy
    (let [request (-> (mock/request :post "/v1/lifecycle")
                      (mock/json-body {:command "stop"}))
          response (app request)
          body (json/parse-string (:body response) true)]
      (is (= (:status response) 200))
      (is (= (:status body) "degraded"))
      (is (= :degraded (:health @agent-state)))))

  (testing "unknown command"
    (let [request (-> (mock/request :post "/v1/lifecycle")
                      (mock/json-body {:command "dance"}))
          response (app request)
          body (json/parse-string (:body response) true)]
      (is (= (:status response) 400))
      (is (= (:error body) "unknown-command")))))
