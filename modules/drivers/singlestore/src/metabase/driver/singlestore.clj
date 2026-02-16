(ns metabase.driver.singlestore
  "SingleStore driver. Inherits from MySQL driver since SingleStore is MySQL-compatible."
  (:require
   [metabase.driver :as driver]))

(set! *warn-on-reflection* true)

;; Register SingleStore driver with MySQL as parent
;; SingleStore is fully MySQL-compatible, so we inherit all behavior from MySQL
;; MySQL driver will be loaded via init steps in metabase-plugin.yaml
(driver/register! :singlestore, :parent :mysql)

(defmethod driver/display-name :singlestore [_] "SingleStore")

;; All other functionality is inherited from MySQL driver
;; Override specific methods here when SingleStore-specific behavior is needed
