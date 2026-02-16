(ns metabase.test.data.singlestore
  "Code for creating / destroying a SingleStore database from a `DatabaseDefinition`.
   SingleStore is MySQL-compatible, so we inherit all behavior from MySQL."
  (:require
   [metabase.test.data.interface :as tx]
   [metabase.test.data.sql :as sql.tx]))

;; SingleStore inherits test extensions from MySQL (its parent), so no need to add them again

;;; ------------------------------------------ Connection Details ------------------------------------------

(defmethod tx/dbdef->connection-details :singlestore
  [_ context {:keys [database-name]}]
  (merge
   {:host (tx/db-test-env-var-or-throw :singlestore :host "localhost")
    :port (tx/db-test-env-var-or-throw :singlestore :port 3306)
    :user (tx/db-test-env-var :singlestore :user "root")}
   (when-let [password (tx/db-test-env-var :singlestore :password)]
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

;;; All other test functionality is inherited from MySQL through parent driver
;;; Override specific methods here when SingleStore-specific test behavior is needed
