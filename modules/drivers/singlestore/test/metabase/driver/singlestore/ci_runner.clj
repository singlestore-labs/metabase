(ns metabase.driver.singlestore.ci-runner
  "CI entrypoint for SingleStore `:mb/driver-tests` with alpha exclusions.

  Lives under `test` rather than `src` because it depends on the test runner, which is not on the classpath when the
  driver JAR is built."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [mb.hawk.core :as hawk]
   [metabase.test-runner :as test-runner]))

(set! *warn-on-reflection* true)

(defn- load-exclusions []
  (let [resource (io/resource "singlestore-ci-exclusions.edn")]
    (when-not resource
      (throw (ex-info "Missing singlestore-ci-exclusions.edn on classpath" {})))
    (edn/read-string (slurp resource))))

(defn- var-name ^String [v]
  (format "%s/%s" (-> v meta :ns ns-name) (-> v meta :name name)))

(defn- ignored-var-names
  [{:keys [excluded-namespaces excluded-vars]} base-options]
  (into (set excluded-vars)
        (for [ns-sym excluded-namespaces
              v     (hawk/find-tests ns-sym base-options)]
          (var-name v))))

(defn run
  "Run driver tests for CI, excluding known alpha gaps (see `singlestore-ci-exclusions.edn`)."
  [options]
  (let [base-options (merge {:only-tags    [:mb/driver-tests]
                             :exclude-tags [:mb/upload-tests :mb/transforms-python-test]}
                            options)
        ignored-vars (ignored-var-names (load-exclusions) base-options)]
    (test-runner/find-and-run-tests-cli
     (assoc base-options :ignored {:vars ignored-vars}))))
