(ns metabase.driver.singlestore
  "SingleStore driver. Builds off of the MySQL driver since SingleStore is MySQL-compatible."
  (:refer-clojure :exclude [some not-empty])
  (:require
   [metabase.driver :as driver]
   [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]
   [metabase.driver.sql-jdbc.sync :as sql-jdbc.sync]
   [metabase.driver.sql.query-processor :as sql.qp]
   [metabase.util.log :as log]
   [metabase.util.performance :as perf :refer [some not-empty]])
  (:import
   (java.sql DatabaseMetaData ResultSet)))

(set! *warn-on-reflection* true)

;; Register SingleStore driver with MySQL as parent since SingleStore is MySQL-compatible
(driver/register! :singlestore, :parent :mysql)

(def ^:private ^:const min-supported-singlestore-version 7.3)

(defmethod driver/display-name :singlestore [_] "SingleStore")

;; Feature support - inherits most from MySQL, but may need adjustments
(doseq [[feature supported?] {;; SingleStore-specific features
                              :window-functions/offset true  ;; SingleStore supports this unlike MySQL
                              :full-join              true   ;; SingleStore supports full joins
                              }]
  (defmethod driver/database-supports? [:singlestore feature] [_driver _feature _db] supported?))

;;; ------------------------------------------ Connection Details ------------------------------------------

(defmethod sql-jdbc.conn/connection-details->spec :singlestore
  [_ {:keys [host port db]
      :or   {host "localhost", port 3306, db ""}
      :as   details}]
  (-> (merge {:classname                     "com.mysql.cj.jdbc.Driver"
              :subprotocol                   "mysql"
              :subname                       (str "//" host ":" port "/" db)
              :useUnicode                    true
              :characterEncoding             "UTF8"
              :characterSetResults           "UTF8"
              ;; SingleStore-specific optimizations
              :connectionAttributes          "program_name:Metabase"
              ;; Timezone handling
              :connectionTimeZone            "UTC"
              :forceConnectionTimeZoneToSession true}
             (dissoc details :host :port :db :ssl))
      (sql-jdbc.conn/handle-additional-options details)))

;;; ------------------------------------------ Database Info ------------------------------------------

(defn- singlestore-version
  "Fetch the version of the SingleStore database."
  [database]
  (let [spec (sql-jdbc.conn/db->pooled-connection-spec database)]
    (jdbc.spec/with-db-connection [conn spec]
      (-> (.getConnection conn)
          .getMetaData
          .getDatabaseProductVersion))))

(defmethod driver/dbms-version :singlestore
  [driver database]
  (let [version-str (singlestore-version database)]
    ;; Parse version string like "8.0.123" or "memsql-8.0.123"
    (when-let [version-match (re-find #"(\d+\.\d+)" version-str)]
      {:version version-str
       :semantic-version (second version-match)})))

;;; ------------------------------------------ Sync ------------------------------------------

;; SingleStore has both columnstore and rowstore tables
;; We might want to capture this metadata for optimization hints
(defmethod sql-jdbc.sync/describe-table :singlestore
  [driver database table]
  (let [table-metadata (sql-jdbc.sync/describe-table-from-jdbc-metadata driver database table)]
    ;; TODO: Add SingleStore-specific table metadata like storage type (columnstore/rowstore)
    ;; This would require a query like:
    ;; SELECT STORAGE_TYPE FROM information_schema.TABLES WHERE TABLE_NAME = ?
    table-metadata))

;;; ------------------------------------------ Query Processor ------------------------------------------

;; SingleStore-specific SQL generation adjustments if needed
;; Most SQL generation is inherited from MySQL

;; Example: Override if SingleStore has different function syntax
#_(defmethod sql.qp/->honeysql [:singlestore :some-function]
    [driver [_ arg]]
    (hsql/call :singlestore_function arg))

;;; ------------------------------------------ Query Optimization Hints ------------------------------------------

;; SingleStore supports optimizer hints that might be useful for query optimization
;; Example: /*+ USE_HASH_JOIN */ 
;; This can be implemented when needed

(comment
  ;; Example of adding optimizer hints
  (defmethod sql.qp/apply-top-level-clause [:singlestore :singlestore/optimizer-hint]
    [driver _ honeysql-query [_ hint]]
    (update honeysql-query :modifiers (fnil conj []) hint)))

;;; ------------------------------------------ Database Creation (for testing) ------------------------------------------

;; Note: Database creation/management methods are typically defined in test namespaces
;; See metabase.test.data.singlestore for test-specific implementations
