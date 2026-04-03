(ns ai.obney.grain.event-store-postgres-v3.datasource-test
  (:require [clojure.test :refer :all]
            [ai.obney.grain.event-store-postgres-v3.interface.datasource :as datasource]))

(deftest flat-config-dispatches-to-password
  (let [captured (atom nil)]
    (with-redefs [hikari-cp.core/make-datasource (fn [config] (reset! captured config) :mock-ds)]
      (let [config {:server-name "localhost"
                    :port-number "5432"
                    :username "postgres"
                    :password "secret"
                    :database-name "mydb"}
            result (datasource/make-datasource config)]
        (is (= :mock-ds result))
        (is (= "postgresql" (:adapter @captured)))
        (is (= "postgres" (:username @captured)))
        (is (= "secret" (:password @captured)))
        (is (= "localhost" (:server-name @captured)))))))

(deftest password-dispatch-promotes-auth-credentials
  (let [captured (atom nil)]
    (with-redefs [hikari-cp.core/make-datasource (fn [config] (reset! captured config) :mock-ds)]
      (let [config {:server-name "localhost"
                    :port-number "5432"
                    :database-name "mydb"
                    :auth {:type :password
                           :username "app_user"
                           :password "app_secret"}}
            result (datasource/make-datasource config)]
        (is (= :mock-ds result))
        (is (= "postgresql" (:adapter @captured)))
        (is (= "app_user" (:username @captured)))
        (is (= "app_secret" (:password @captured)))
        (is (= "localhost" (:server-name @captured)))
        (is (nil? (:auth @captured)))))))

(deftest unknown-auth-type-throws
  (is (thrown? IllegalArgumentException
              (datasource/make-datasource
                {:server-name "localhost"
                 :auth {:type :unknown}}))))

(deftest extensibility-custom-method
  (let [captured (atom nil)]
    (defmethod datasource/make-datasource :test-custom
      [config]
      (reset! captured config)
      :custom-ds)
    (try
      (let [config {:server-name "localhost"
                    :auth {:type :test-custom :token "abc"}}
            result (datasource/make-datasource config)]
        (is (= :custom-ds result))
        (is (= "abc" (get-in @captured [:auth :token]))))
      (finally
        (remove-method datasource/make-datasource :test-custom)))))
