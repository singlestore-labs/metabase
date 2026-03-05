(ns metabase.driver.singlestore
  "SingleStore driver. Inherits from MySQL driver since SingleStore is MySQL-compatible,
   but uses the official SingleStore JDBC driver for better performance and feature support."
  (:require
   [clojure.string :as str]
   [metabase.driver :as driver]
   [metabase.driver.sql-jdbc.common :as sql-jdbc.common]
   [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]
   [metabase.driver.sql-jdbc.sync :as sql-jdbc.sync]))

(set! *warn-on-reflection* true)

;; Register SingleStore driver with MySQL as parent
;; SingleStore is MySQL-compatible, so we inherit most behavior from MySQL
;; MySQL driver will be loaded via init steps in metabase-plugin.yaml
(driver/register! :singlestore, :parent :mysql)

(defmethod driver/display-name :singlestore [_] "SingleStore")

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                              Connection Details                                                 |
;;; +----------------------------------------------------------------------------------------------------------------+

(def ^:private default-connection-args
  "Default connection args for SingleStore JDBC driver.
   These are similar to MySQL but tailored for SingleStore."
  {;; 0000-00-00 dates are valid in SingleStore; convert to null for Java compatibility
   :zeroDateTimeBehavior "convertToNull"
   ;; Force UTF-8 encoding
   :useUnicode           true
   :characterEncoding    "UTF8"
   :characterSetResults  "UTF8"
   ;; Enable compression for better performance
   :useCompression       true
   ;; Optimize transaction isolation handling
   :useLocalSessionState true})

(defn- make-subname
  "Build the JDBC subname from host, port, and database name."
  [host port db]
  (str "//" host ":" port "/" (when-not (str/blank? db) db)))

(defmethod sql-jdbc.conn/connection-details->spec :singlestore
  [_ {ssl? :ssl, :keys [host port db dbname user password additional-options], :as details}]
  (let [db         (or dbname db "")
        host       (or host "localhost")
        port       (or port 3306)
        ssl?       (boolean ssl?)
        base-spec  (merge
                    default-connection-args
                    {:classname   "com.singlestore.jdbc.Driver"
                     :subprotocol "singlestore"
                     :subname     (make-subname host port db)
                     :user        user
                     :password    password
                     :useSSL      ssl?})]
    (sql-jdbc.common/handle-additional-options base-spec details)))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                         SingleStore-Specific Type Mappings                                     |
;;; +----------------------------------------------------------------------------------------------------------------+

;; SingleStore extends MySQL with additional types for modern workloads:
;; - BSON: Binary JSON for efficient JSON storage
;; - GEOGRAPHY/GEOGRAPHYPOINT: Geospatial data types
;; - VECTOR: ML/AI vector embeddings
;; See: https://docs.singlestore.com/db/latest/reference/sql-reference/data-types/

(defmethod sql-jdbc.sync/database-type->base-type :singlestore
  [driver database-type]
  (or
   ({:BSON           :type/SerializedJSON
     :GEOGRAPHY      :type/SerializedJSON
     :GEOGRAPHYPOINT :type/SerializedJSON
     :VECTOR         :type/*
     :JSON           :type/JSON}
    (keyword (name database-type)))
   ;; Fall back to parent MySQL type mapping
   ((get-method sql-jdbc.sync/database-type->base-type :mysql) driver database-type)))

(defmethod sql-jdbc.sync/column->semantic-type :singlestore
  [_ database-type _]
  (case database-type
    "JSON"           :type/SerializedJSON
    "BSON"           :type/SerializedJSON
    "GEOGRAPHY"      :type/SerializedJSON
    "GEOGRAPHYPOINT" :type/SerializedJSON
    nil))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                         SingleStore-Specific Overrides                                         |
;;; +----------------------------------------------------------------------------------------------------------------+

;; SingleStore does not support schemas in the same way as MySQL
(defmethod sql-jdbc.sync/excluded-schemas :singlestore
  [_]
  #{"information_schema" "memsql" "cluster"})

;; SingleStore uses Monday as start of week by default
(defmethod driver/db-start-of-week :singlestore [_] :monday)
