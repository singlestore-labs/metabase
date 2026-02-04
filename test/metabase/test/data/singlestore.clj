(ns metabase.test.data.singlestore
  "Code for creating / destroying a SingleStore database from a `DatabaseDefinition`.
   SingleStore is MySQL-compatible, so we inherit most behavior from MySQL."
  (:require
   [clojure.java.jdbc :as jdbc]
   [clojure.string :as str]
   [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]
   [metabase.test.data.interface :as tx]
   [metabase.test.data.sql :as sql.tx]
   [metabase.test.data.sql-jdbc :as sql-jdbc.tx]
   [metabase.test.data.sql-jdbc.load-data :as load-data]))

(sql-jdbc.tx/add-test-extensions! :singlestore)

;;; ------------------------------------------ Field Type Mappings ------------------------------------------

;; SingleStore supports the same data types as MySQL
(doseq [[base-type database-type] {:type/BigInteger     "BIGINT"
                                   :type/Boolean        "BOOLEAN"
                                   :type/Date           "DATE"
                                   :type/DateTime       "DATETIME(3)"
                                   :type/DateTimeWithTZ "TIMESTAMP(3) DEFAULT '1970-01-01 00:00:01'"
                                   :type/Decimal        "DECIMAL"
                                   :type/Float          "DOUBLE"
                                   :type/Integer        "INTEGER"
                                   :type/JSON           "JSON"
                                   :type/Text           "TEXT"
                                   :type/Time           "TIME(3)"}]
  (defmethod sql.tx/field-base-type->sql-type [:singlestore base-type] [_ _] database-type))

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

;;; ------------------------------------------ Database Creation ------------------------------------------

(defmethod sql.tx/create-db-sql :singlestore
  [_ {:keys [database-name]}]
  ;; SingleStore specific: You can optionally specify PARTITIONS for the database
  ;; For now, we'll use default partitioning
  (format "CREATE DATABASE IF NOT EXISTS %s;" database-name))

(defmethod sql.tx/drop-db-if-exists-sql :singlestore
  [_ {:keys [database-name]}]
  (format "DROP DATABASE IF EXISTS %s;" database-name))

;;; ------------------------------------------ Table Creation ------------------------------------------

;; SingleStore supports both ROWSTORE and COLUMNSTORE tables
;; By default, we'll create COLUMNSTORE tables for analytics workloads
;; This can be overridden by adding table-level options

(defmethod sql.tx/create-table-sql :singlestore
  [driver {:keys [database-name], :as dbdef} {:keys [table-name field-definitions table-options]}]
  (let [field-sql (map (fn [{:keys [field-name base-type field-options]}]
                         (str (sql.tx/qualify-and-quote driver field-name)
                              " "
                              (sql.tx/field-base-type->sql-type driver base-type)
                              (when-let [default (:default field-options)]
                                (str " DEFAULT " default))))
                       field-definitions)
        ;; SingleStore specific: Add COLUMNSTORE by default unless specified otherwise
        storage-type (get table-options :storage-type "COLUMNSTORE")
        create-sql (format "CREATE %s TABLE %s.%s (%s)"
                           storage-type
                           (sql.tx/qualify-and-quote driver database-name)
                           (sql.tx/qualify-and-quote driver table-name)
                           (str/join ", " field-sql))]
    create-sql))

;;; ------------------------------------------ Data Loading ------------------------------------------

;; Inherit data loading from SQL-JDBC
;; SingleStore supports standard INSERT statements like MySQL

(defmethod load-data/load-data! :singlestore
  [& args]
  (apply load-data/load-data-all-at-once! args))

(defmethod load-data/do-insert! :singlestore
  [driver spec table-identifier row-or-rows]
  (load-data/do-insert-with-jdbc! driver spec table-identifier row-or-rows))

;;; ------------------------------------------ Aggregate Column Info ------------------------------------------

(defmethod tx/aggregate-column-info :singlestore
  ([driver ag-type]
   ((get-method tx/aggregate-column-info ::tx/test-extensions) driver ag-type))

  ([driver ag-type field]
   ((get-method tx/aggregate-column-info ::tx/test-extensions) driver ag-type field)))

;;; ------------------------------------------ Role Management (for testing impersonation) ------------------------------------------

;; SingleStore supports MySQL-compatible role management
;; This is similar to MySQL's implementation

(defn grant-table-perms-to-roles!
  [driver details roles]
  (let [spec (sql-jdbc.conn/connection-details->spec driver details)]
    (doseq [[role-name table-perms] roles]
      (let [role-name (sql.tx/qualify-and-quote driver role-name)]
        (doseq [[table-name perms] table-perms]
          (let [columns (:columns perms)
                select-cols (str/join ", " (map #(sql.tx/qualify-and-quote driver %) columns))
                grant-stmt (if (not= select-cols "")
                             (format "GRANT SELECT (%s) ON %s TO %s" select-cols table-name role-name)
                             (format "GRANT SELECT ON %s TO %s" table-name role-name))]
            (jdbc/execute! spec [grant-stmt] {:transaction? false})))))))

(defmethod tx/create-and-grant-roles! :singlestore
  [driver details roles user-name default-role]
  (let [spec (sql-jdbc.conn/connection-details->spec driver details)]
    (doseq [statement [(format "DROP USER IF EXISTS '%s'@'%%';" user-name)
                       (format "CREATE USER '%s'@'%%' IDENTIFIED BY '';" user-name)
                       (format "DROP ROLE IF EXISTS %s;" default-role)
                       (format "CREATE ROLE %s;" default-role)
                       (format "GRANT SELECT ON *.* TO %s;" default-role)
                       (format "GRANT %s TO %s;" default-role user-name)
                       (format "SET DEFAULT ROLE %s TO %s;" default-role user-name)]]
      (jdbc/execute! spec [statement]))
    (sql-jdbc.tx/drop-if-exists-and-create-roles! driver details roles)
    (grant-table-perms-to-roles! driver details roles)
    (sql-jdbc.tx/grant-roles-to-user! driver details roles user-name)))

;;; ------------------------------------------ Test Data Specific ------------------------------------------

;; Any SingleStore-specific test data adjustments can go here
;; For example, if certain test datasets need special handling for SingleStore's
;; distributed architecture or storage types
