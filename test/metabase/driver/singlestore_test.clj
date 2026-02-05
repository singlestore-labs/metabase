(ns metabase.driver.singlestore-test
  "Tests for the SingleStore driver. Most tests inherited from MySQL."
  (:require
   [clojure.test :refer :all]
   [metabase.driver :as driver]))

(set! *warn-on-reflection* true)

;;; ------------------------------------------ Basic Driver Tests ------------------------------------------

(deftest driver-registration-test
  (testing "SingleStore driver is properly registered"
    (is (= :singlestore (driver/the-driver :singlestore)))
    (is (isa? driver/hierarchy :singlestore :mysql))
    (is (isa? driver/hierarchy :singlestore :sql-jdbc))))

(deftest display-name-test
  (testing "SingleStore driver display name"
    (is (= "SingleStore" (driver/display-name :singlestore)))))

;;; Most test functionality is inherited from MySQL
;;; Add SingleStore-specific tests here as needed
