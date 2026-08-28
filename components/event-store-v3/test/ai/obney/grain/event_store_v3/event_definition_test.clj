(ns ai.obney.grain.event-store-v3.event-definition-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-v3.interface.event-definition :as definitions]
            [malli.core :as m]))

(use-fixtures :each
  (fn [test-fn]
    (definitions/reset-event-definitions!)
    (test-fn)
    (definitions/reset-event-definitions!)))

(deftest grain-built-in-events-are-defined
  ;; The fixture clears the registry, so reload the registration namespace.
  (require 'ai.obney.grain.event-store-v3.interface.schemas :reload)
  (doseq [event-type [:grain/tx
                      :grain/todo-processor-checkpoint
                      :grain/todo-processor-effect-failure]]
    (let [definition (es/event-definition event-type)]
      (is (= event-type (:event/type definition)))
      (is (string? (:description definition)))
      (is (some? (:schema definition)))))
  (is (= {:retain-at-least {:seconds 3600 :nanos 0}
          :keep-latest-per {:tags #{:processor}}}
         (:history/normalized
          (es/event-definition :grain/todo-processor-checkpoint))))
  (is (nil? (:history (es/event-definition :grain/tx))))
  (is (nil? (:history
             (es/event-definition :grain/todo-processor-effect-failure)))))

(deftest registration-is-data-only
  (is (= :test/heartbeat
         (definitions/register-event-definition!
          :test/heartbeat "Heartbeat"
          {:schema [:map [:node/id :uuid]]
           :history {:retain-at-least "P7D"
                     :keep-latest-per {:tags #{:node :region}}}}
          {:ns "test" :file "test.clj" :line 1})))
  (let [definition (es/event-definition :test/heartbeat)]
    (is (= "Heartbeat" (:description definition)))
    (is (= {:retain-at-least {:seconds 604800 :nanos 0}
            :keep-latest-per {:tags #{:node :region}}}
           (:history/normalized definition)))
    (is (m/validate :test/heartbeat {:node/id (random-uuid)}))))

(deftest macro-registers-without-constructing
  (es/defevent :test/macro-event "Macro event" {:schema [:map [:value :int]]})
  (is (= :test/macro-event (:event/type (es/event-definition :test/macro-event))))
  (is (nil? (:history/normalized (es/event-definition :test/macro-event))))
  (is (= {:event/type :test/macro-event :event/tags #{} :value 1}
         (es/->event {:type :test/macro-event :body {:value 1}}))))

(deftest identical-registration-is-idempotent-and-conflict-is-rejected
  (let [options {:schema [:map [:value :int]]}]
    (definitions/register-event-definition! :test/stable "Stable" options {:line 1})
    (is (= :test/stable
           (definitions/register-event-definition! :test/stable "Stable" options {:line 2})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Conflicting"
          (definitions/register-event-definition!
           :test/stable "Changed" options {:line 3})))))

(deftest definitions-require-name-description-and-schema
  (is (thrown? clojure.lang.ExceptionInfo
        (definitions/register-event-definition! :unqualified "Description"
                                                  {:schema [:map]} {})))
  (is (thrown? clojure.lang.ExceptionInfo
        (definitions/register-event-definition! :test/missing-description ""
                                                  {:schema [:map]} {})))
  (is (thrown? clojure.lang.ExceptionInfo
        (definitions/register-event-definition! :test/missing-schema "Description"
                                                  {} {}))))

(deftest duration-normalization-and-rejection
  (is (= (definitions/normalize-duration "P1D")
         (definitions/normalize-duration "PT24H")))
  (is (= {:seconds 183600 :nanos 0}
         (definitions/normalize-duration "P2DT3H")))
  (doseq [invalid ["P0D" "P1Y" "P1M" "P2W" "-P1D" "PT0S" "7 days" nil]]
    (testing (str "rejects " (pr-str invalid))
      (is (thrown? clojure.lang.ExceptionInfo
            (definitions/normalize-duration invalid))))))

(deftest retention-key-policy-is-structural-data
  (doseq [invalid [{:retain-at-least "P1D" :keep-latest-per {:tags #{}}}
                   {:retain-at-least "P1D" :keep-latest-per {:tags [:node]}}
                   {:retain-at-least "P1D" :keep-latest-per {:tags #{"node"}}}
                   {:retain-at-least "P1D" :unknown true}
                   {}]]
    (is (thrown? clojure.lang.ExceptionInfo
          (definitions/normalize-history invalid)))))
