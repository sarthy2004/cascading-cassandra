(ns com.ifesdjeen.cascading.cassandra.core-test
  (:require [clojurewerkz.cassaforte.embedded :as e])
  (:use cascalog.api
        clojure.test
        clojurewerkz.cassaforte.cql
        cascalog.playground
        clojurewerkz.cassaforte.query
        [clojurewerkz.cassaforte.bytes :as b]
        [midje sweet cascalog])
  (:require [cascalog.io :as io]
            [cascalog.ops :as c])
  (:import [cascading.tuple Fields]
           [cascading.scheme Scheme]
           [com.ifesdjeen.cascading.cassandra CassandraTap CassandraScheme]
           [org.apache.cassandra.utils ByteBufferUtil]
           [org.apache.cassandra.thrift Column]))

(bootstrap-emacs)

(declare connected?)
(defn create-test-column-family
  []
;;  (e/start-server!)
  (alter-var-root (var *debug-output* ) (constantly false))
  (when (not (bound? (var *client*)))
    (connect! ["127.0.0.1"]))
  (drop-keyspace :cascading_cassandra)
  (create-keyspace :cascading_cassandra
                   (with {:replication
                          {:class "SimpleStrategy"
                           :replication_factor 1}}))
  (use-keyspace :cascading_cassandra)
  (create-table :libraries
                (with {:compact-storage true})
                (column-definitions {:name :varchar
                                     :language :varchar
                                     :schmotes :int
                                     :votes :int
                                     :primary-key [:name]}))
  (create-table :libraries_wide
                (with {:compact-storage true})
                (column-definitions {:name :varchar
                                     :language :varchar
                                     :votes :int
                                     :primary-key [:name :language]}))

  (create-table :libraries_wide_composite
                (with {:compact-storage true})
                (column-definitions {:name :varchar
                                     :language :varchar
                                     :version :int
                                     :votes :int
                                     :primary-key [:name :language :version]}))
  )

(defn create-tap
  [conf]
  (let [defaults      {"db.host" "127.0.0.1"
                       "db.port" "9160"
                       "db.keyspace" "cascading_cassandra"
                       "db.inputPartitioner" "org.apache.cassandra.dht.Murmur3Partitioner"
                       "db.outputPartitioner" "org.apache.cassandra.dht.Murmur3Partitioner"}
        scheme        (CassandraScheme. (merge defaults conf))
        tap           (CassandraTap. scheme)]
    tap))


(deftest t-cassandra-tap-as-source
  (create-test-column-family)
  (dotimes [counter 100]
    (prepared
     (insert :libraries
             (values {:name (str "Cassaforte" counter)
                      :language (str "Clojure" counter)
                      :schmotes (int counter)
                      :votes (int (* 2 counter))}))))
  (let [tap (create-tap {"db.columnFamily" "libraries"
                         "types" {"name" "UTF8Type"
                                  "language"    "UTF8Type"
                                  "schmotes"    "Int32Type"
                                  "votes"       "Int32Type"}
                         "mappings.source" ["name" "language" "schmotes" "votes"]})
        query (<- [?count ?sum3 ?sum4]
                  (tap ?value1 ?value2 ?value3 ?value4)
                  (c/count ?count)
                  (c/sum ?value3 :> ?sum3)
                  (c/sum ?value4 :> ?sum4))]

    (fact?- (produces [[100 4950 9900]]))))

(deftest t-cassandra-tap-as-sink
  (create-test-column-family)
  (let [test-data [["Riak" "Erlang" (int 100) (int 200)]
                   ["Cassaforte" "Clojure" (int 300) (int 400)]]]

    (?<- (create-tap {"db.columnFamily" "libraries"
                      "types" {"name" "UTF8Type"
                               "language"    "UTF8Type"
                               "schmotes"    "Int32Type"
                               "votes"       "Int32Type"}
                      "mappings.sink.rowKeyField" "?value1"
                      "mappings.sink" {"name"     "?value1"
                                       "language" "?value2"
                                       "schmotes" "?value3"
                                       "votes"    "?value4"}})
         [?value1 ?value2 ?value3 ?value4]
         (test-data ?value1 ?value2 ?value3 ?value4))

    (let [res (select :libraries)]
      (is (= "Riak" (:name (first res))))
      (is (= "Erlang" (:language (first res))))
      (is (= 100 (:schmotes (first res))))
      (is (= 200 (:votes (first res))))

      (is (= "Cassaforte" (:name (second res))))
      (is (= "Clojure" (:language (second res))))
      (is (= 300 (:schmotes (second res))))
      (is (= 400 (:votes (second res)))))))

(deftest t-cassandra-tap-as-source-wide
  (create-test-column-family)
  (dotimes [counter 100]
    (prepared
     (insert :libraries_wide
             (values {:name (str "Cassaforte" counter)
                      :language (str "Clojure" counter)
                      :votes (int counter)}))))

  (let [tap (create-tap {"db.columnFamily" "libraries_wide"
                         "types.dynamic" {"rowKey"      "UTF8Type"
                                          "columnName"  "UTF8Type"
                                          "columnValue" "Int32Type"}
                         "mappings.dynamic" {"rowKey"      "?value1"
                                             "columnName"  "?value2"
                                             "columnValue" "?value3"}})]
    (fact "Handles simple calculations"
          (<-
           [?count ?sum]
           (tap ?value1 ?value2 ?value3)
           (c/count ?count)
           (c/sum ?value3 :> ?sum))
          => (produces [[100 4950]]))))

(deftest t-cassandra-tap-as-sink-wide
  (create-test-column-family)
  (let [test-data [["Riak" "Erlang" (int 100)]
                   ["Cassaforte" "Clojure" (int 150)]]]

    (?<- (create-tap {"db.columnFamily" "libraries_wide"
                      "types.dynamic" {"rowKey"      "UTF8Type"
                                       "columnName"  "UTF8Type"
                                       "columnValue" "Int32Type"}

                      "mappings.sink.rowKeyField" "?value1"
                      "mappings.dynamic" {"rowKey"      "?value1"
                                          "columnName"  "?value2"
                                          "columnValue" "?value3"}})
         [?value1 ?value2 ?value3]
         (test-data ?value1 ?value2 ?value3))

    (let [res (select :libraries_wide)]
      (is (= "Riak" (:name (first res))))
      (is (= "Erlang" (:language (first res))))
      (is (= "Cassaforte" (:name (second res))))
      (is (= "Clojure" (:language (second res)))))))

(deftest t-cassandra-tap-as-source-wide-composite
  (create-test-column-family)
  (dotimes [counter 100]
    (prepared
     (insert :libraries_wide_composite
             (values {:name (str "Cassaforte" counter)
                      :language (str "Clojure" counter)
                      :version (int 5)
                      :votes (int counter)}))))

  (let [tap (create-tap {"db.columnFamily" "libraries_wide"

                         "types.dynamic" {"rowKey"      "UTF8Type"
                                          "columnName"  "CompositeType(UTF8Type, Int32Type)"
                                          "columnValue" "Int32Type"}
                         "mappings.dynamic" {"rowKey"      "?value1"
                                             "columnName"  "?value2"
                                             "columnValue" "?value3"}})]
    (fact "Handles simple calculations"
          (<-
           [?count ?version-sum ?votes-sum]
           (tap ?value1 ?value2 ?value3 ?value4)
           (c/count ?count)
           (c/sum ?value3 :> ?version-sum)
           (c/sum ?value4 :> ?votes-sum))
          => (produces [[100 500 4950]]))))

(deftest t-cassandra-tap-as-sink-wide-composite
  (create-test-column-family)
  (let [test-data [["Riak" "Erlang" (int 5) (int 100)]
                   ["Cassaforte" "Clojure" (int 1) (int 150)]]]

    (?<- (create-tap {"db.columnFamily" "libraries_wide_composite"

                      "types.dynamic" {"rowKey"      "UTF8Type"
                                       "columnName"  "CompositeType(UTF8Type, Int32Type)"
                                       "columnValue" "Int32Type"}

                      "mappings.sink.rowKeyField" "?value1"
                      "mappings.dynamic" {"rowKey"      "?value1"
                                          "columnName"  ["?value2" "?value3"]
                                          "columnValue" "?value4"}})
         [?value1 ?value2 ?value3 ?value4]
         (test-data ?value1 ?value2 ?value3 ?value4))

    (let [res (select :libraries_composite)]
      (is (= "Riak" (:name (first res))))
      (is (= "Erlang" (:language (first res))))
      (is (= 5 (:version (first res))))

      (is (= "Cassaforte" (:name (second res))))
      (is (= "Clojure" (:language (second res))))
      (is (= 1 (:version (second res)))))))
