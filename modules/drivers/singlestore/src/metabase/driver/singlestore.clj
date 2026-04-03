(ns metabase.driver.singlestore
  "SingleStore driver. Inherits from MySQL driver since SingleStore is MySQL-compatible,
   but uses the official SingleStore JDBC driver for better performance and feature support."
  (:require
   [clojure.java.jdbc :as jdbc]
   [clojure.string :as str]
   [honey.sql :as sql]
   [java-time.api :as t]
   [metabase.driver :as driver]
   [metabase.driver-api.core :as driver-api]
   [metabase.driver.sql :as driver.sql]
   [metabase.driver.sql-jdbc.common :as sql-jdbc.common]
   [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]
   [metabase.driver.sql-jdbc.execute :as sql-jdbc.execute]
   [metabase.driver.sql-jdbc.execute.old-impl :as sql-jdbc.execute.old]
   [metabase.driver.sql-jdbc.sync :as sql-jdbc.sync]
   [metabase.driver.sql-jdbc.sync.common :as sql-jdbc.sync.common]
   [metabase.driver.sql-jdbc.sync.describe-table :as sql-jdbc.describe-table]
   [metabase.driver.sql.query-processor :as sql.qp]
   [metabase.driver.sql.query-processor.util :as sql.qp.u]
   [metabase.driver.sql.util :as sql.u]
   [metabase.util :as u]
   [metabase.util.date-2 :as u.date]
   [metabase.util.honey-sql-2 :as h2x]
   [metabase.util.performance :as perf])
  (:import
   (java.sql Connection DatabaseMetaData ResultSet ResultSetMetaData Statement)
   (java.time LocalDate LocalDateTime LocalTime OffsetDateTime OffsetTime ZonedDateTime)))

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
        base-spec {:classname        "com.singlestore.jdbc.Driver"
                   :subprotocol      "singlestore"
                   :subname          (make-subname host port db)
                   :user             user
                   :password         password
                   :useSSL           ssl?
                   :allowLocalInfile true}]
    (sql-jdbc.common/handle-additional-options base-spec details)))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                         Session Timezone                                                       |
;;; +----------------------------------------------------------------------------------------------------------------+

;; SingleStore silently ignores SET @@session.time_zone — the value always stays at SYSTEM.
;; Return nil to tell Metabase that SingleStore does not support session-level timezone,
;; so results-timezone-id will correctly fall back to UTC rather than the report timezone.
;; Note: CONVERT_TZ() still works for explicit timezone conversions in queries.
(defmethod sql-jdbc.execute.old/set-timezone-sql :singlestore [_] nil)

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

;; SingleStore does not support foreign key constraints, so disable FK-related features.
;; SingleStore does not support MySQL's atomic multi-table `RENAME TABLE t1 TO t2, t3 TO t4` syntax,
;; so disable :atomic-renames. Single-table rename is supported via ALTER TABLE ... RENAME TO.
(doseq [feature [:describe-fks :metadata/key-constraints :atomic-renames]]
  (defmethod driver/database-supports? [:singlestore feature] [_driver _feature _db] false))

;; SingleStore doesn't support MySQL's `RENAME TABLE t1 TO t2` syntax.
;; Use `ALTER TABLE t1 RENAME TO t2` instead, which is the standard SingleStore approach.
(defmethod driver/rename-tables!* :singlestore
  [driver db-id sorted-rename-map]
  (let [sqls (mapv (fn [[from-table to-table]]
                     (first (sql/format {:alter-table (keyword from-table)
                                         :rename-table (keyword (name to-table))}
                                        :quoted true
                                        :dialect (sql.qp/quote-style driver))))
                   sorted-rename-map)]
    (jdbc/with-db-transaction [t-conn (sql-jdbc.conn/db->pooled-connection-spec db-id)]
      (with-open [stmt (.createStatement ^Connection (:connection t-conn))]
        (doseq [sql sqls]
          (.addBatch ^Statement stmt ^String sql))
        (.executeBatch ^Statement stmt)))))

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

;; SingleStore doesn't support CAST(x AS DOUBLE) — only FLOAT, DECIMAL, SIGNED, etc. are valid.
;; Use the (x + 0.0) trick to implicitly convert to double precision.
(defmethod sql.qp/->float :singlestore
  [_driver value]
  (h2x/with-database-type-info [:+ value [:inline 0.0]] "double"))

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
  (let [contains-date-parts? (some #(str/includes? format-str %)
                                   ["%a" "%b" "%c" "%D" "%d" "%e" "%j" "%M" "%m" "%U"
                                    "%u" "%V" "%v" "%W" "%w" "%X" "%x" "%Y" "%y"])
        contains-time-parts? (some #(str/includes? format-str %)
                                   ["%f" "%H" "%h" "%I" "%i" "%k" "%l" "%p" "%r" "%S" "%s" "%T"])
        database-type        (cond
                               (and contains-date-parts? (not contains-time-parts?)) "date"
                               (and contains-time-parts? (not contains-date-parts?)) "time"
                               :else                                                 "datetime")]
    (-> [:str_to_date expr (h2x/literal format-str)]
        (h2x/with-database-type-info database-type))))

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
;; SingleStore doesn't have YEARWEEK(), so we use DATE_FORMAT(expr, '%X%V') instead.
;; %X = year-for-week (Sunday start), %V = week number (01-53, Sunday start).
(defmethod sql.qp/date [:singlestore :week]
  [_driver _unit expr]
  (let [extract-week-fn (fn [expr]
                          (str-to-date "%X%V %W"
                                       (h2x/concat (date-format "%X%V" expr)
                                                   (h2x/literal " Sunday"))))]
    (->> (sql.qp/adjust-start-of-week :singlestore extract-week-fn expr)
         (temporal-cast (h2x/database-type expr)))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                         JSON Query Processing                                                   |
;;; +----------------------------------------------------------------------------------------------------------------+

;; SingleStore uses JSON_EXTRACT_STRING instead of JSON_UNQUOTE(JSON_EXTRACT(...))
;; See: https://docs.singlestore.com/db/latest/reference/sql-reference/json-functions/json-extract-type-functions/
;;
;; IMPORTANT: SingleStore's JSON_EXTRACT_* functions use separate string arguments for each
;; key in the path, NOT JSONPath syntax. For example:
;;   JSON_EXTRACT_STRING(col, 'key')              -- top-level key
;;   JSON_EXTRACT_STRING(col, 'nested', 'key')    -- nested key
;; NOT: JSON_EXTRACT_STRING(col, '$."key"')        -- this returns NULL!

(defmethod sql.qp/json-query :singlestore
  [_driver unwrapped-identifier stored-field]
  {:pre [(h2x/identifier? unwrapped-identifier)]}
  (let [field-type        (:database-type stored-field)
        nfc-path          (:nfc-path stored-field)
        parent-identifier (sql.qp.u/nfc-field->parent-identifier unwrapped-identifier stored-field)
        key-args          (mapv (fn [x] (if (number? x) (str x) (name x))) (rest nfc-path))
        json-extract-str  (into [:json_extract_string parent-identifier] key-args)
        normalized-type   (u/lower-case-en (or field-type "text"))]
    (case normalized-type
      "timestamp" [:convert
                   [:str_to_date json-extract-str "%Y-%m-%dT%T.%fZ"]
                   [:raw "DATETIME"]]

      "boolean" json-extract-str

      ("float" "double" "decimal" "numeric" "real")
      (into [:json_extract_double parent-identifier] key-args)

      ("int" "integer" "bigint" "smallint" "tinyint" "mediumint")
      (into [:json_extract_bigint parent-identifier] key-args)

      ("text" "tinytext" "mediumtext" "longtext" "varchar" "char" "string")
      json-extract-str

      [:convert json-extract-str [:raw (u/upper-case-en (or field-type "CHAR"))]])))

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

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                         Inline Value Overrides                                                  |
;;; +----------------------------------------------------------------------------------------------------------------+

;; SingleStore doesn't support SQL standard typed literals (date '...', time '...', timestamp '...').
;; Use function-call syntax instead.

(defmethod sql.qp/inline-value [:singlestore LocalDate]
  [_ t]
  (format "DATE('%s')" (u.date/format t)))

(defmethod sql.qp/inline-value [:singlestore LocalTime]
  [_ t]
  (format "CAST('%s' AS TIME)" (u.date/format "HH:mm:ss.SSS" t)))

(defmethod sql.qp/inline-value [:singlestore LocalDateTime]
  [_ t]
  (format "TIMESTAMP('%s')" (u.date/format "yyyy-MM-dd HH:mm:ss.SSS" t)))

(defn- format-offset [t]
  (let [offset (t/format "ZZZZZ" (t/zone-offset t))]
    (if (= offset "Z")
      "UTC"
      offset)))

;; SingleStore doesn't support session timezone, so use CONVERT_TZ with explicit UTC target
;; instead of @@session.time_zone
(defmethod sql.qp/inline-value [:singlestore OffsetTime]
  [_ t]
  (format "convert_tz('%s', '%s', 'UTC')"
          (t/format "HH:mm:ss.SSS" t)
          (format-offset t)))

(defmethod sql.qp/inline-value [:singlestore OffsetDateTime]
  [_ t]
  (format "convert_tz('%s', '%s', 'UTC')"
          (t/format "yyyy-MM-dd HH:mm:ss.SSS" t)
          (format-offset t)))

(defmethod sql.qp/inline-value [:singlestore ZonedDateTime]
  [_ t]
  (format "convert_tz('%s', '%s', 'UTC')"
          (t/format "yyyy-MM-dd HH:mm:ss.SSS" t)
          (str (t/zone-id t))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                         prettify-native-form                                                    |
;;; +----------------------------------------------------------------------------------------------------------------+

;; SingleStore is MySQL-compatible, so use the :mysql SQL formatter dialect.
;; The default :sql dispatch uses StandardSql which adds spaces inside backtick-quoted
;; identifiers (e.g. ` orders ` instead of `orders`), causing "table not found" errors.
(defmethod driver/prettify-native-form :singlestore
  [_ native-form]
  (sql.u/format-sql-and-fix-params :mysql native-form))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                         Primary Key Detection                                                   |
;;; +----------------------------------------------------------------------------------------------------------------+

;; SingleStore's JDBC driver may not return results from DatabaseMetaData.getPrimaryKeys
;; for all table types. Query information_schema directly for reliable PK detection.
(defmethod sql-jdbc.describe-table/get-table-pks :singlestore
  [_driver ^Connection conn _db-name-or-nil table]
  (let [^DatabaseMetaData metadata (.getMetaData conn)
        catalog                    (.getCatalog conn)]
    (into []
          (sql-jdbc.sync.common/reducible-results
           #(.getPrimaryKeys metadata catalog (:schema table) (:name table))
           (fn [^ResultSet rs] #(.getString rs "COLUMN_NAME"))))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                         TINYINT(1) → Boolean Mapping                                           |
;;; +----------------------------------------------------------------------------------------------------------------+

;; MySQL maps TINYINT(1) to BIT (boolean) during query execution so that boolean columns
;; return true/false instead of 1/0. SingleStore uses the same convention, so apply
;; the same override. Without this, upload tests that check boolean values fail.
(defmethod sql-jdbc.execute/db-type-name :singlestore
  [_driver ^ResultSetMetaData rsmeta column-index]
  (let [db               (try
                           (driver-api/database (driver-api/metadata-provider))
                           (catch Throwable _ nil))
        tiny-int-1-is-bit? (not (some-> db :details :additional-options (str/includes? "tinyInt1isBit=false")))
        db-type-name     (.getColumnTypeName rsmeta column-index)
        precision        (try
                           (.getPrecision rsmeta column-index)
                           (catch Throwable _ nil))]
    (if (and (= "TINYINT" db-type-name)
             (= precision 1)
             tiny-int-1-is-bit?)
      "BIT"
      db-type-name)))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                         JSON Field Length                                                        |
;;; +----------------------------------------------------------------------------------------------------------------+

;; Used by JSON nested field column sampling to determine if a JSON value is too long.
;; SingleStore supports the same LENGTH(CAST(x AS CHAR)) pattern as MySQL.
(defmethod driver.sql/json-field-length :singlestore
  [_ json-field-identifier]
  [:length [:cast json-field-identifier :char]])
