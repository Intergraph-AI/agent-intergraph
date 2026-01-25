(defproject agent-intergraph "0.1.0-SNAPSHOT"
  :description "The Intergraph Distributed Agent - A standalone brain node for MoM"
  :url "https://mom.intergraph.ai"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [clj-http "3.12.3"]
                 [ring/ring-core "1.9.6"]
                 [ring/ring-jetty-adapter "1.9.6"]
                 [ring/ring-json "0.5.1"]
                 [compojure "1.7.0"]
                 [cheshire "5.11.0"]
                 [org.clojure/tools.logging "1.2.4"]
                 [ch.qos.logback/logback-classic "1.2.11"]]
  :main ^:skip-aot agent-intergraph.core
  :target-path "target/%s"
  :aliases {"start-agent" ["run" "9090"]}
  :profiles {:uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}})
