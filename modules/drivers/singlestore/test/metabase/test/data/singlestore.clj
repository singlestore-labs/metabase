(ns metabase.test.data.singlestore
  "Code for creating / destroying a SingleStore database from a `DatabaseDefinition`.
   SingleStore is MySQL-compatible, so we inherit behavior from MySQL.
   Test extensions are inherited from :mysql via the driver hierarchy
   (:singlestore -> :mysql -> :sql-jdbc/test-extensions)."
  (:require
   [clojure.string :as str]
   [metabase.test :as mt]
   [metabase.test.data.interface :as tx]
   [metabase.test.data.sql :as sql.tx]))

(set! *warn-on-reflection* true)

(defn- sanitize-db-name
  "SingleStore does not allow hyphens or other non-alphanumeric/underscore characters in database names."
  [db-name]
  (when db-name
    (str/replace db-name #"-" "_")))

;;; ------------------------------------------ Database Name Handling ------------------------------------------

;; SingleStore rejects database names containing hyphens (e.g. `test-data`), even when backtick-quoted.
;; Override qualified-name-components to sanitize the database name in all SQL DDL statements.
(defmethod sql.tx/qualified-name-components :singlestore
  ([_ db-name]                        [(sanitize-db-name db-name)])
  ([_ db-name table-name]             [table-name])
  ([_ db-name table-name field-name]  [table-name field-name]))

;;; ------------------------------------------ Connection Details ------------------------------------------

(defmethod tx/dbdef->connection-details :singlestore
  [_ context {:keys [database-name]}]
  (merge
   {:host (or (mt/db-test-env-var :singlestore :host) "localhost")
    :port (Integer/parseInt (or (mt/db-test-env-var :singlestore :port) "3306"))
    :user (or (mt/db-test-env-var :singlestore :user) "root")}
   (when-let [password (mt/db-test-env-var :singlestore :password)]
     {:password password})
   (when (= context :db)
     {:db (sanitize-db-name database-name)})))

;;; ------------------------------------------ Field Type Mappings ------------------------------------------

;; SingleStore only supports DATETIME/TIMESTAMP with precision 0 or 6 (not 3 like MySQL).
;; Override the types that MySQL defines with precision (3).
(doseq [[base-type database-type] {:type/DateTime       "DATETIME(6)"
                                   :type/DateTimeWithTZ "TIMESTAMP(6) DEFAULT '1970-01-01 00:00:01'"
                                   :type/Time           "TIME(6)"
                                   :type/DateTimeWithZoneOffset "TIMESTAMP(6) DEFAULT '1970-01-01 00:00:01'"}]
  (defmethod sql.tx/field-base-type->sql-type [:singlestore base-type] [_ _] database-type))

;;; ------------------------------------------ Foreign Keys ------------------------------------------

;; SingleStore does not support foreign key constraints.
(defmethod sql.tx/add-fk-sql :singlestore [& _] nil)
