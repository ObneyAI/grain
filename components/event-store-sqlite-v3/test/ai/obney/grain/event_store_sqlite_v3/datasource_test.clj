(ns ai.obney.grain.event-store-sqlite-v3.datasource-test
  (:require [clojure.test :refer :all]
            [ai.obney.grain.event-store-sqlite-v3.interface.datasource :as datasource]))

(deftest file-config-builds-jdbc-url
  (let [captured (atom nil)]
    (with-redefs [hikari-cp.core/make-datasource (fn [config] (reset! captured config) :mock-ds)]
      (let [result (datasource/make-datasource {:database-file "/tmp/foo.sqlite"})]
        (is (= :mock-ds result))
        (is (= "jdbc:sqlite:/tmp/foo.sqlite" (:jdbc-url @captured)))
        (is (= "org.sqlite.JDBC" (:driver-class-name @captured)))
        (is (= "PRAGMA foreign_keys = ON" (:connection-init-sql @captured)))))))

(deftest memory-config-builds-jdbc-url
  (let [captured (atom nil)]
    (with-redefs [hikari-cp.core/make-datasource (fn [config] (reset! captured config) :mock-ds)]
      (datasource/make-datasource {:database-file ":memory:"})
      (is (= "jdbc:sqlite::memory:" (:jdbc-url @captured))))))

(deftest missing-database-file-throws
  (is (thrown? IllegalArgumentException
               (datasource/make-datasource {}))))

(deftest extensibility-custom-method
  (let [captured (atom nil)]
    (defmethod datasource/make-datasource :test-custom
      [config]
      (reset! captured config)
      :custom-ds)
    (try
      (let [result (datasource/make-datasource {:type :test-custom :pragma "foo"})]
        (is (= :custom-ds result))
        (is (= "foo" (:pragma @captured))))
      (finally
        (remove-method datasource/make-datasource :test-custom)))))
