(ns metabase.driver.singlestore
  "SingleStore driver. Inherits from MySQL driver since SingleStore is MySQL-compatible."
  (:require
   [metabase.driver :as driver]
   [metabase.driver.sql-jdbc.sync :as sql-jdbc.sync]))

(set! *warn-on-reflection* true)

;; Register SingleStore driver with MySQL as parent
;; SingleStore is fully MySQL-compatible, so we inherit all behavior from MySQL
;; MySQL driver will be loaded via init steps in metabase-plugin.yaml
(driver/register! :singlestore, :parent :mysql)

(defmethod driver/display-name :singlestore [_] "SingleStore")

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
  ;; First try SingleStore-specific types, then fall back to MySQL
  (or
   ({:BSON           :type/SerializedJSON  ; Binary JSON format
     :GEOGRAPHY      :type/SerializedJSON  ; GeoJSON representation
     :GEOGRAPHYPOINT :type/SerializedJSON  ; GeoJSON point
     :VECTOR         :type/*               ; ML/AI vector type
     ;; SingleStore's JSON is more advanced than MySQL's
     :JSON           :type/JSON}
    (keyword (name database-type)))
   ;; Fall back to parent MySQL type mapping
   ((get-method sql-jdbc.sync/database-type->base-type :mysql) driver database-type)))

(defmethod sql-jdbc.sync/column->semantic-type :singlestore
  [_ database-type _]
  ;; SingleStore-specific semantic types for better UI representation
  (case database-type
    "JSON"           :type/SerializedJSON
    "BSON"           :type/SerializedJSON
    "GEOGRAPHY"      :type/SerializedJSON
    "GEOGRAPHYPOINT" :type/SerializedJSON
    nil))

;; All other functionality is inherited from MySQL driver
;; Override specific methods here when SingleStore-specific behavior is needed
