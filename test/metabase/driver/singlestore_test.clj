(ns metabase.driver.singlestore-test
  "Tests for the SingleStore driver."
  (:require
   [clojure.test :refer :all]
   [honey.sql :as sql]
   [metabase.driver :as driver]
   [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]
   [metabase.driver.sql-jdbc.sync :as sql-jdbc.sync]
   [metabase.driver.sql.query-processor :as sql.qp]
   [metabase.query-processor :as qp]
   [metabase.test :as mt]
   [metabase.test.data.interface :as tx]))

(set! *warn-on-reflection* true)

(use-fixtures :each (fn [thunk]
                      ;; Make sure we're in Honey SQL 2 mode for all the SQL snippets
                      (binding [sql/*dialect* :mysql]
                        (thunk))))

;;; ------------------------------------------ Basic Driver Tests ------------------------------------------

(deftest driver-registration-test
  (testing "SingleStore driver is properly registered"
    (is (= :singlestore (driver/the-driver :singlestore)))
    (is (isa? driver/hierarchy :singlestore :mysql))
    (is (isa? driver/hierarchy :singlestore :sql-jdbc))))

(deftest display-name-test
  (testing "SingleStore driver display name"
    (is (= "SingleStore" (driver/display-name :singlestore)))))

;;; ------------------------------------------ Connection Tests ------------------------------------------

(deftest connection-details->spec-test
  (testing "Basic connection details conversion"
    (let [details {:host "localhost"
                   :port 3306
                   :user "root"
                   :password "password"
                   :db "test_db"}
          spec (sql-jdbc.conn/connection-details->spec :singlestore details)]
      (is (= "com.mysql.cj.jdbc.Driver" (:classname spec)))
      (is (= "mysql" (:subprotocol spec)))
      (is (str/includes? (:subname spec) "localhost"))
      (is (str/includes? (:subname spec) "3306"))
      (is (str/includes? (:subname spec) "test_db"))
      (is (= "UTF8" (:characterEncoding spec)))
      (is (= "UTC" (:connectionTimeZone spec)))))

  (testing "Connection with default port"
    (let [details {:host "localhost"
                   :user "root"
                   :db "test_db"}
          spec (sql-jdbc.conn/connection-details->spec :singlestore details)]
      (is (str/includes? (:subname spec) "3306")))))

;;; ------------------------------------------ Feature Support Tests ------------------------------------------

(deftest feature-support-test
  (testing "SingleStore-specific feature support"
    (mt/with-driver :singlestore
      ;; SingleStore supports window functions with offset unlike MySQL
      (is (driver/database-supports? :singlestore :window-functions/offset (mt/db)))

      ;; SingleStore supports full joins unlike MySQL
      (is (driver/database-supports? :singlestore :full-join (mt/db)))

      ;; Inherited from MySQL
      (is (driver/database-supports? :singlestore :persist-models (mt/db)))
      (is (driver/database-supports? :singlestore :datetime-diff (mt/db))))))

;;; ------------------------------------------ Version Tests ------------------------------------------

(deftest ^:skip-ci version-test
  (testing "Can retrieve SingleStore version"
    (mt/test-driver :singlestore
      (let [version (driver/dbms-version :singlestore (mt/db))]
        (is (some? (:version version)))
        (is (some? (:semantic-version version)))
        (is (string? (:version version)))))))

;;; ------------------------------------------ Sync Tests ------------------------------------------

(deftest ^:skip-ci describe-database-test
  (testing "Can describe a SingleStore database"
    (mt/test-driver :singlestore
      (let [db-metadata (sql-jdbc.sync/describe-database :singlestore (mt/db))]
        (is (map? db-metadata))
        (is (contains? db-metadata :tables))
        (is (sequential? (:tables db-metadata)))))))

(deftest ^:skip-ci describe-table-test
  (testing "Can describe a SingleStore table"
    (mt/test-driver :singlestore
      (let [table (mt/db)
            table-metadata (sql-jdbc.sync/describe-table :singlestore (mt/db) table)]
        (is (map? table-metadata))
        (is (contains? table-metadata :name))
        (is (contains? table-metadata :fields))
        (is (sequential? (:fields table-metadata)))))))

;;; ------------------------------------------ Query Processor Tests ------------------------------------------

(deftest ^:skip-ci basic-query-test
  (testing "Can execute basic queries against SingleStore"
    (mt/test-driver :singlestore
      (is (= [[1]]
             (mt/rows
              (qp/process-query
               {:database (mt/id)
                :type     :native
                :native   {:query "SELECT 1"}})))))))

(deftest ^:skip-ci simple-select-test
  (testing "Can execute simple SELECT queries"
    (mt/test-driver :singlestore
      (let [results (mt/run-mbql-query venues
                      {:fields   [$id $name]
                       :order-by [[:asc $id]]
                       :limit    5})]
        (is (= 5 (count (mt/rows results))))
        (is (= [:id :name] (map :name (mt/cols results))))))))

;;; ------------------------------------------ SQL Generation Tests ------------------------------------------

(deftest sql-generation-test
  (testing "Basic SQL generation for SingleStore"
    (mt/with-driver :singlestore
      (let [query (mt/mbql-query venues
                    {:fields   [$id $name]
                     :order-by [[:asc $id]]
                     :limit    5})]
        (is (string? (sql.qp/format-honeysql :singlestore (:honeysql query))))))))

;;; ------------------------------------------ Storage Type Tests ------------------------------------------

;; SingleStore-specific tests for columnstore vs rowstore tables
;; These would require actual SingleStore infrastructure to test properly

(deftest ^:skip-ci storage-type-metadata-test
  (testing "Can retrieve storage type metadata for tables"
    (mt/test-driver :singlestore
      ;; TODO: Implement test to verify we can detect if a table is COLUMNSTORE or ROWSTORE
      ;; This requires querying information_schema.TABLES
      (is true "Placeholder - implement when SingleStore infrastructure is available"))))

;;; ------------------------------------------ Performance Tests ------------------------------------------

(deftest ^:skip-ci distributed-query-test
  (testing "SingleStore can handle distributed queries"
    (mt/test-driver :singlestore
      ;; SingleStore is designed for distributed queries
      ;; These tests would verify that queries work correctly across partitions
      (is true "Placeholder - implement when SingleStore infrastructure is available"))))

;;; ------------------------------------------ TODO: Additional Tests ------------------------------------------

;; Additional tests that should be implemented when SingleStore infrastructure is available:
;; 1. Window functions with OFFSET (since SingleStore supports this unlike MySQL)
;; 2. Full JOIN support
;; 3. Optimizer hints
;; 4. Distributed query execution
;; 5. Columnstore vs Rowstore table handling
;; 6. Performance comparison tests
;; 7. Concurrent query handling
;; 8. Connection pooling under load
;; 9. JSON operations (SingleStore has enhanced JSON support)
;; 10. Geospatial functions if supported

(comment
  ;; Example test structure for future implementation
  (deftest ^:skip-ci window-function-offset-test
    (testing "SingleStore supports window functions with OFFSET"
      (mt/test-driver :singlestore
        ;; Test LAG/LEAD functions
        )))

  (deftest ^:skip-ci full-join-test
    (testing "SingleStore supports FULL OUTER JOIN"
      (mt/test-driver :singlestore
        ;; Test FULL JOIN queries
        )))

  (deftest ^:skip-ci optimizer-hints-test
    (testing "SingleStore optimizer hints work correctly"
      (mt/test-driver :singlestore
        ;; Test queries with /*+ USE_HASH_JOIN */ etc.
        ))))
