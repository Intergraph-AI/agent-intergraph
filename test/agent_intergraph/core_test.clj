(ns agent-intergraph.core-test
  (:require [clojure.test :refer :all]
            [agent-intergraph.core :refer :all]
            [ring.mock.request :as mock]
            [cheshire.core :as json]))

(deftest test-app
  (testing "status endpoint"
    (let [response (app (mock/request :get "/v1/status"))
          body (json/parse-string (:body response) true)]
      (is (= (:status response) 200))
      (is (contains? body :status))
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
      (is (= (:status response) 404)))))