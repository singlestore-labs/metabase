(ns metabase.test.data.singlestore
  "Code for creating / destroying a SingleStore database from a `DatabaseDefinition`.
   SingleStore is MySQL-compatible, so we inherit behavior from MySQL."
  (:require
   [metabase.test :as mt]
   [metabase.test.data.interface :as tx]
   [metabase.test.data.sql :as sql.tx]
   [metabase.test.data.sql-jdbc :as sql-jdbc.tx]))

(set! *warn-on-reflection* true)

;; Add test extensions for SingleStore
(sql-jdbc.tx/add-test-extensions! :singlestore)

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
     {:db database-name})))

;;; ------------------------------------------ Field Type Mappings ------------------------------------------

;; SingleStore supports the same data types as MySQL
;; Inherit all field type mappings from MySQL by dispatching to parent
(doseq [base-type [:type/BigInteger :type/Boolean :type/Date :type/DateTime
                   :type/DateTimeWithTZ :type/Decimal :type/Float :type/Integer
                   :type/JSON :type/Text :type/Time]]
  (defmethod sql.tx/field-base-type->sql-type [:singlestore base-type]
    [driver base-type-kw]
    ((get-method sql.tx/field-base-type->sql-type [:mysql base-type-kw]) driver base-type-kw)))
