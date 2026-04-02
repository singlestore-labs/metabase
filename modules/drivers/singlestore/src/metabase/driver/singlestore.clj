(ns metabase.driver.singlestore
  "SingleStore driver. Inherits from MySQL driver since SingleStore is MySQL-compatible,
   but uses the official SingleStore JDBC driver for better performance and feature support."
  (:require
   [clojure.string :as str]
   [metabase.driver :as driver]
   [metabase.driver-api.core :as driver-api]
   [metabase.driver.sql-jdbc.common :as sql-jdbc.common]
   [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]
   [metabase.driver.sql-jdbc.sync :as sql-jdbc.sync]
   [metabase.driver.sql.query-processor :as sql.qp]
   [metabase.driver.sql.query-processor.util :as sql.qp.u]
   [metabase.util :as u]
   [metabase.util.honey-sql-2 :as h2x]
   [metabase.util.performance :as perf]))

(set! *warn-on-reflection* true)

;; Register SingleStore driver with MySQL as parent
;; SingleStore is MySQL-compatible, so we inherit most behavior from MySQL
;; MySQL driver will be loaded via init steps in metabase-plugin.yaml
(driver/register! :singlestore, :parent :mysql)

(defmethod driver/display-name :singlestore [_] "SingleStore")

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                              Connection Details                                                 |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn- make-subname
  "Build the JDBC subname from host, port, and database name."
  [host port db]
  (str "//" host ":" port "/" (when-not (str/blank? db) db)))

(defmethod sql-jdbc.conn/connection-details->spec :singlestore
  [_ {ssl? :ssl, :keys [host port db dbname user password additional-options], :as details}]
  (let [db        (or dbname db "")
        host      (or host "localhost")
        port      (or port 3306)
        ssl?      (boolean ssl?)
        base-spec {:classname   "com.singlestore.jdbc.Driver"
                   :subprotocol "singlestore"
                   :subname     (make-subname host port db)
                   :user        user
                   :password    password
                   :useSSL      ssl?}]
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

;; SingleStore's week-related functions use Sunday as the start of week, matching MySQL.
;; YEARWEEK defaults to mode 0 (Sunday start) and DAYOFWEEK returns 1 for Sunday,
;; so this must be :sunday for adjust-start-of-week to compute correct offsets.
(defmethod driver/db-start-of-week :singlestore [_] :sunday)

;; Override current-datetime-honeysql-form because h2x/current-datetime-honeysql-form
;; has a hardcoded case statement that doesn't include :singlestore
;; SingleStore uses the same NOW(6) function as MySQL for current timestamp with microsecond precision
(defmethod sql.qp/current-datetime-honeysql-form :singlestore
  [_driver]
  (h2x/with-database-type-info [:now [:inline 6]] "timestamp"))

;; Override add-interval-honeysql-form because h2x/add-interval-honeysql-form
;; has a multimethod that doesn't include :singlestore
;; SingleStore uses the same DATE_ADD syntax as MySQL
(defmethod h2x/add-interval-honeysql-form :singlestore
  [db-type hsql-form amount unit]
  ;; SingleStore/MySQL doesn't support :millisecond as an option, but does support fractional seconds
  (if (= unit :millisecond)
    (recur db-type hsql-form (/ amount 1000.0) :second)
    [:date_add hsql-form [:raw (format "INTERVAL %s %s" amount (name unit))]]))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                         Type Cast Overrides                                                     |
;;; +----------------------------------------------------------------------------------------------------------------+

;; SingleStore doesn't support CONVERT(x, TEXT) - must use CHAR instead
;; This is used when Metabase needs to cast values to text (e.g., during fingerprinting)
(defmethod sql.qp/->honeysql [:singlestore :text]
  [driver [_ value]]
  (h2x/maybe-cast "CHAR" (sql.qp/->honeysql driver value)))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                         Date Truncation Overrides                                               |
;;; +----------------------------------------------------------------------------------------------------------------+

;; Helper functions for date truncation (similar to MySQL)
(defn- date-format [format-str expr]
  [:date_format expr (h2x/literal format-str)])

(defn- str-to-date [format-str expr]
  [:str_to_date expr (h2x/literal format-str)])

(defn- temporal-cast
  "Cast to the appropriate temporal type, handling timestamp -> datetime conversion."
  [type expr]
  (if (= "timestamp" (u/lower-case-en type))
    (h2x/maybe-cast "datetime" expr)
    (h2x/maybe-cast type expr)))

;; SingleStore doesn't support MAKEDATE, so we need to override :year truncation
;; MySQL uses: MAKEDATE(YEAR(col), 1)
;; SingleStore alternative: DATE(CONCAT(YEAR(col), '-01-01'))
(defmethod sql.qp/date [:singlestore :year]
  [_driver _unit expr]
  (->> (h2x/with-database-type-info
        [:date [:concat (h2x/year expr) (h2x/literal "-01-01")]]
        "date")
       (temporal-cast (h2x/database-type expr))))

;; Override :week because MySQL's implementation hardcodes :mysql in adjust-start-of-week.
;; We need to pass :singlestore so the offset is computed using our db-start-of-week.
(defmethod sql.qp/date [:singlestore :week]
  [_driver _unit expr]
  (let [extract-week-fn (fn [expr]
                          (str-to-date "%X%V %W"
                                       (h2x/concat [:yearweek expr]
                                                   (h2x/literal " Sunday"))))]
    (->> (sql.qp/adjust-start-of-week :singlestore extract-week-fn expr)
         (temporal-cast (h2x/database-type expr)))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                         JSON Query Processing                                                   |
;;; +----------------------------------------------------------------------------------------------------------------+

;; SingleStore uses JSON_EXTRACT_STRING instead of JSON_UNQUOTE(JSON_EXTRACT(...))
;; See: https://docs.singlestore.com/db/latest/reference/sql-reference/json-functions/json-extract-type-functions/

(defmethod sql.qp/json-query :singlestore
  [_driver unwrapped-identifier stored-field]
  {:pre [(h2x/identifier? unwrapped-identifier)]}
  (letfn [(handle-name [x] (str "\"" (if (number? x) (str x) (name x)) "\""))]
    (let [field-type        (:database-type stored-field)
          nfc-path          (:nfc-path stored-field)
          parent-identifier (sql.qp.u/nfc-field->parent-identifier unwrapped-identifier stored-field)
          jsonpath-query    (format "$.%s" (str/join "." (map handle-name (rest nfc-path))))
          json-extract-str  [:json_extract_string parent-identifier jsonpath-query]
          normalized-type   (u/lower-case-en (or field-type "text"))]
      (case normalized-type
        ;; For timestamps, extract as string then convert to datetime
        ;; Format: 2024-03-15T10:30:45.123Z (ISO 8601)
        ;; Note: %T is shorthand for %H:%i:%s, %f handles microseconds
        "timestamp" [:convert
                     [:str_to_date json-extract-str "%Y-%m-%dT%T.%fZ"]
                     [:raw "DATETIME"]]

        ;; For booleans, just extract as string (SingleStore handles this)
        "boolean" json-extract-str

        ;; For floating-point and decimal types, use JSON_EXTRACT_DOUBLE
        ;; Note: "decimal" is routed here because CONVERT(..., DECIMAL) without precision
        ;; defaults to DECIMAL(10,0) which truncates fractional parts silently
        ;; See: https://dev.mysql.com/doc/refman/8.4/en/fixed-point-types.html
        ("float" "double" "decimal" "numeric" "real") [:json_extract_double parent-identifier jsonpath-query]

        ;; For integers/bigints, use JSON_EXTRACT_BIGINT
        ("int" "integer" "bigint" "smallint" "tinyint" "mediumint") [:json_extract_bigint parent-identifier jsonpath-query]

        ;; For text/string types - JSON_EXTRACT_STRING already returns a string, no conversion needed
        ;; SingleStore doesn't support CONVERT(..., TEXT) - only CHAR is valid
        ("text" "tinytext" "mediumtext" "longtext" "varchar" "char" "string") json-extract-str

        ;; Default: extract as string and convert to the target type using CHAR (not TEXT)
        ;; because SingleStore doesn't support TEXT as a CONVERT target type
        [:convert json-extract-str [:raw (u/upper-case-en (or field-type "CHAR"))]]))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                         Field Clause Processing                                                 |
;;; +----------------------------------------------------------------------------------------------------------------+

;; Override ->honeysql for :field to ensure SingleStore's json-query is used instead of MySQL's
;; MySQL's implementation hardcodes :mysql in the json-query call, so we need to override it
(defmethod sql.qp/->honeysql [:singlestore :field]
  [driver [_ id-or-name opts :as mbql-clause]]
  (let [stored-field  (when (integer? id-or-name)
                        (driver-api/field (driver-api/metadata-provider) id-or-name))
        parent-method (get-method sql.qp/->honeysql [:sql :field])
        honeysql-expr (parent-method driver mbql-clause)]
    (cond
      (not (driver-api/json-field? stored-field))
      honeysql-expr

      (::sql.qp/forced-alias opts)
      (keyword (driver-api/qp.add.source-alias opts))

      :else
      ;; Use :singlestore here instead of :mysql (which MySQL hardcodes)
      (perf/postwalk #(if (h2x/identifier? %)
                        (sql.qp/json-query :singlestore % stored-field)
                        %)
                     honeysql-expr))))
