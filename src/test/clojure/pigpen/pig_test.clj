;;
;;
;;  Copyright 2013 Netflix, Inc.
;;
;;     Licensed under the Apache License, Version 2.0 (the "License");
;;     you may not use this file except in compliance with the License.
;;     You may obtain a copy of the License at
;;
;;         http://www.apache.org/licenses/LICENSE-2.0
;;
;;     Unless required by applicable law or agreed to in writing, software
;;     distributed under the License is distributed on an "AS IS" BASIS,
;;     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;;     See the License for the specific language governing permissions and
;;     limitations under the License.
;;
;;

(ns pigpen.pig-test
  (:use clojure.test
        pigpen.pig)
  (:require [pigpen.util :refer [test-diff pigsym-zero pigsym-inc]]
            [clj-time.format :as time]
            [taoensso.nippy :refer [freeze thaw]])
  (:import [org.apache.pig.data
            DataByteArray
            Tuple TupleFactory
            DataBag BagFactory]
           [java.util Map]))

;; TODO test-tuple
;; TODO test-bag

;; **********


(deftest test-string->bytes
  (is (= (vec (string->bytes "303132"))
         [48 49 50])))

(deftest test-string->DataByteArray
  (is (= (string->DataByteArray "303132")
         (DataByteArray. (byte-array [(byte 0x33) (byte 0x30) (byte 0x33) (byte 0x31) (byte 0x33) (byte 0x32)])))))

;; TODO test-bytes->int
;; TODO test-bytes->long
;; TODO test-bytes->string
;; TODO test-bytes->debug
;; TODO test-bytes->json

(deftest test-cast-bytes
  
  (testing "nil"
    (is (= nil (cast-bytes nil nil))))
  
  (let [b (byte-array (mapv byte [97 98 99]))]
    (testing "bytearray"
      (is (= b (cast-bytes "bytearray" b))))
    
    (testing "chararray"
      (is (= "abc" (cast-bytes "chararray" b))))))

; *****************

(deftest test-string->pig-boolean
  (test-diff (#'pigpen.pig/string->pig "true") '([:BOOLEAN "true"]))
  (test-diff (#'pigpen.pig/string->pig "false") '([:BOOLEAN "false"]))
  (test-diff (#'pigpen.pig/string->pig "TRUE") '([:BOOLEAN "TRUE"]))
  (test-diff (#'pigpen.pig/string->pig "FALSE") '([:BOOLEAN "FALSE"])))

(deftest test-string->pig-number
  (test-diff (#'pigpen.pig/string->pig "10") '([:NUMBER "10"]))
  (test-diff (#'pigpen.pig/string->pig "10L") '([:NUMBER "10"]))
  (test-diff (#'pigpen.pig/string->pig "10.5F") '([:NUMBER "10.5"]))
  (test-diff (#'pigpen.pig/string->pig "10.5e2f") '([:NUMBER "10.5e2"]))
  (test-diff (#'pigpen.pig/string->pig "10.5") '([:NUMBER "10.5"]))
  (test-diff (#'pigpen.pig/string->pig "10.5e2") '([:NUMBER "10.5e2"]))
  (test-diff (#'pigpen.pig/string->pig "-10") '([:NUMBER "-10"]))
  (test-diff (#'pigpen.pig/string->pig "-10L") '([:NUMBER "-10"]))
  (test-diff (#'pigpen.pig/string->pig "-10.5F") '([:NUMBER "-10.5"]))
  (test-diff (#'pigpen.pig/string->pig "-10.5e2f") '([:NUMBER "-10.5e2"]))
  (test-diff (#'pigpen.pig/string->pig "-10.5") '([:NUMBER "-10.5"]))
  (test-diff (#'pigpen.pig/string->pig "-10.5e2") '([:NUMBER "-10.5e2"]))
  ;; Clojure interprets these as octal, so we keep them as strings
  (test-diff (#'pigpen.pig/string->pig "000123") '([:STRING "000123"]))
  (test-diff (#'pigpen.pig/string->pig "0") '([:NUMBER "0"])))

(deftest test-string->pig-datetime
  (test-diff 
    (#'pigpen.pig/string->pig "1980-11-19T14:17:01.234+05:00")
    '([:DATETIME "1980-11-19T14:17:01.234+05:00"])))

(deftest test-string->pig-string
  (test-diff (#'pigpen.pig/string->pig "abc") '([:STRING "abc"]))
  (test-diff (#'pigpen.pig/string->pig "abc123") '([:STRING "abc123"]))
  (test-diff (#'pigpen.pig/string->pig "123abc") '([:STRING "123abc"]))

  (is (map? (#'pigpen.pig/string->pig "123,abc")))
  (is (map? (#'pigpen.pig/string->pig "abc,123"))))

(deftest test-string->pig-tuple
  (test-diff (#'pigpen.pig/string->pig "()") '([:TUPLE]))
  (test-diff (#'pigpen.pig/string->pig "(123.0)") '([:TUPLE [:NUMBER "123.0"]]))
  (test-diff (#'pigpen.pig/string->pig "(123,abc)") 
             '([:TUPLE [:NUMBER "123"] [:STRING "abc"]])))
  
(deftest test-string->pig-bag
  (test-diff (#'pigpen.pig/string->pig "{}") '([:BAG]))
  (test-diff (#'pigpen.pig/string->pig "{()}") '([:BAG [:TUPLE]]))
  (test-diff (#'pigpen.pig/string->pig "{(123)}") '([:BAG [:TUPLE [:NUMBER "123"]]]))
  (test-diff 
    (#'pigpen.pig/string->pig "{(123,abc),(def,456)}")
    '([:BAG
       [:TUPLE [:NUMBER "123"] [:STRING "abc"]]
       [:TUPLE [:STRING "def"] [:NUMBER "456"]]]))

  (is (map? (#'pigpen.pig/string->pig "{123}"))))

(deftest test-string->pig-map
  (test-diff (#'pigpen.pig/string->pig "[]") '([:MAP]))
  (test-diff (#'pigpen.pig/string->pig "[abc#123]")
             '([:MAP [:MAP-ENTRY [:STRING "abc"] [:NUMBER "123"]]]))
  (test-diff (#'pigpen.pig/string->pig "[123#abc]")
             '([:MAP [:MAP-ENTRY [:STRING "123"] [:STRING "abc"]]]))
  (test-diff (#'pigpen.pig/string->pig "[123#abc,def#456]")
             '([:MAP
                [:MAP-ENTRY [:STRING "123"] [:STRING "abc"]]
                [:MAP-ENTRY [:STRING "def"] [:NUMBER "456"]]])))

(deftest test-string->pig-composite
  (test-diff 
    (#'pigpen.pig/string->pig "({([a#1],(1.2),{}),()},foo)")
    '([:TUPLE
       [:BAG
        [:TUPLE
         [:MAP [:MAP-ENTRY [:STRING "a"] [:NUMBER "1"]]]
         [:TUPLE [:NUMBER "1.2"]]
         [:BAG]]
        [:TUPLE]]
       [:STRING "foo"]])))

(deftest test-pig->clojure-boolean
  (test-diff (#'pigpen.pig/pig->clojure [:BOOLEAN "true"]) true)
  (test-diff (#'pigpen.pig/pig->clojure [:BOOLEAN "TRUE"]) true)
  (test-diff (#'pigpen.pig/pig->clojure [:BOOLEAN "false"]) false)
  (test-diff (#'pigpen.pig/pig->clojure [:BOOLEAN "FALSE"]) false))
    
(deftest test-pig->clojure-number
  (test-diff (#'pigpen.pig/pig->clojure [:NUMBER "10"]) 10)
  (test-diff (#'pigpen.pig/pig->clojure [:NUMBER "10.5"]) 10.5)
  (test-diff (#'pigpen.pig/pig->clojure [:NUMBER "10.5e2"]) 1050.0)
  (test-diff (#'pigpen.pig/pig->clojure [:NUMBER "-10"]) -10)
  (test-diff (#'pigpen.pig/pig->clojure [:NUMBER "-10.5"]) -10.5)
  (test-diff (#'pigpen.pig/pig->clojure [:NUMBER "-10.5e2"]) -1050.0))

(deftest test-pig->clojure-datetime
  (test-diff (#'pigpen.pig/pig->clojure [:DATETIME "1980-11-19T14:17:01.234+05:00"])
             (time/parse "1980-11-19T14:17:01.234+05:00")))

(deftest test-pig->clojure-string
  (test-diff (#'pigpen.pig/pig->clojure [:STRING "foo"]) "foo"))

(deftest test-pig->clojure-tuple
  (test-diff (#'pigpen.pig/pig->clojure [:TUPLE]) [])
  (test-diff (#'pigpen.pig/pig->clojure [:TUPLE [:NUMBER "123"]]) [123]))

(deftest test-pig->clojure-bag
  (test-diff (#'pigpen.pig/pig->clojure [:BAG]) [])
  (test-diff (#'pigpen.pig/pig->clojure [:BAG [:TUPLE]]) [[]])
  (test-diff (#'pigpen.pig/pig->clojure [:BAG [:TUPLE [:NUMBER "123"]]]) [[123]]))

(deftest test-pig->clojure-map-entry
  (test-diff (#'pigpen.pig/pig->clojure [:MAP-ENTRY [:STRING "a"] [:NUMBER "1"]])
             ["a" 1]))

(deftest test-pig->clojure-map
  (test-diff (#'pigpen.pig/pig->clojure [:MAP]) {})
  (test-diff
    (#'pigpen.pig/pig->clojure [:MAP
                   [:MAP-ENTRY [:STRING "123"] [:STRING "abc"]]
                   [:MAP-ENTRY [:STRING "def"] [:NUMBER "456"]]])
    {"123" "abc", "def" 456}))

(deftest test-pig->clojure-composite
  (test-diff
    (#'pigpen.pig/pig->clojure
      [:TUPLE
       [:BAG
        [:TUPLE
         [:MAP [:MAP-ENTRY [:STRING "a"] [:NUMBER "1"]]]
         [:TUPLE [:NUMBER "1.2"]]
         [:BAG]]
        [:TUPLE]]
       [:STRING "foo"]])
    [[[{"a" 1} [1.2] []] []] "foo"]))

(deftest test-parse-pig-composite
  (test-diff
    (parse-pig "({([a#1],(1.2),{}),()},foo)")
    [[[{"a" 1} [1.2] []] []] "foo"]))

; *****************

(deftest test-hybrid->pig
  
  (testing "String"
    (is (= (hybrid->pig "foo")
           "foo")))
    
  (testing "Number"
    (is (= (hybrid->pig 37)
           37)))
    
  (testing "Keyword"
    (is (= (hybrid->pig :foo)
           "foo")))
    
  (testing "List"
    (is (= (hybrid->pig '(:a 1 "3"))
           (tuple "a" 1 "3"))))
    
  (testing "Vector"
    (is (= (hybrid->pig [:a 1 "3"])
           (tuple "a" 1 "3"))))
    
  (testing "Map"
    (is (= (hybrid->pig {:a 1, :b 2})
           {"a" 1, "b" 2})))
    
  (testing "DataByteArray"
    (is (= (hybrid->pig (DataByteArray. (freeze {:a [1 2 3]})))
           {"a" (tuple 1 2 3)})))
    
  (testing "Tuple"
    (is (= (str (hybrid->pig (tuple 1 "a")))
           "(1,a)")))
    
  (testing "DataBag"
    (is (= (str (hybrid->pig (bag (tuple 1 "a") (tuple {:a [3]}))))
           "{(1,a),([a#(3)])}"))))

; *****************

(deftest test-pig->string
  
  (testing "String"
    (is (= (pig->string "foo")
           "foo")))
  
  (testing "Number"
    (is (= (pig->string 37)
           "37")))
  
  (testing "Map"
    (is (= (pig->string {"a" 1, "b" 2})
           "[a#1,b#2]")))
  
  (testing "DataByteArray"
    (is (= (pig->string (DataByteArray. (byte-array (mapv byte [0x30 0x31 0x32]))))
           "303132")))
  
  (testing "Tuple"
    (is (= (pig->string (tuple 1 2 3))
           "(1,2,3)")))
  
  (testing "DataBag"
    (is (= (pig->string (bag (tuple 1 2 3)))
           "{(1,2,3)}"))))

; *****************

(deftest test-hybrid->clojure
  
  (testing "DataByteArray"
    (is (= (hybrid->clojure (DataByteArray. (freeze {:a [1 2 3]})))
           {:a [1 2 3]})))
  
  (testing "Tuple"
    (is (= (hybrid->clojure (tuple (DataByteArray. (freeze "foo"))
                                   (DataByteArray. (freeze "bar"))))
           ["foo" "bar"])))
  
  (testing "DataBag"
    (is (= (hybrid->clojure (bag (tuple (DataByteArray. (freeze "foo")))))
           ["foo"]))))

; *****************

;; TODO test-native->clojure

; *****************

;; TODO test-freeze-vals
;; TODO test-thaw-anything
;; TODO test-thaw-values

; *****************

(deftest test-map->bind
  (let [f (map->bind +)]
    (is (= (f 1 2 3) [6]))
    (is (= (f 2 4 6) [12]))))

(deftest test-filter->bind
  (let [f (filter->bind even?)]
    (is (= (f 1) []))
    (is (= (f 2) [2]))
    (is (= (f 3) []))
    (is (= (f 4) [4]))))

(deftest test-args->map
  (let [f (args->map #(* 2 %))]
    (is (= (f "a" 2 "b" 3)
           {:a 4 :b 6}))))

(deftest test-exec
  (let [f (exec :frozen :frozen (fn [x y] (* x y)))]
    (is (= '(freeze 6)
           (thaw-anything
             (f [(DataByteArray. (freeze 2))
                 (DataByteArray. (freeze 3))])))))
  
  (let [f (exec :frozen :frozen (fn [x] (clojure.edn/read-string x)))]
    (is (= '(freeze {:a [1 2], :b nil})
            (thaw-anything
              (f [(DataByteArray. (freeze "{:a [1 2] :b nil}"))])))))
  
  (let [f (exec :frozen :frozen identity)]
    (is (= '(freeze nil)
           (thaw-anything
             (f [(DataByteArray. (freeze nil))])))))

  (let [f (exec :frozen :frozen-with-nils identity)]
    (is (= nil
           (thaw-anything
             (f [(DataByteArray. (freeze nil))])))))
  
  (let [f (exec :frozen :frozen-with-nils identity)]
    (is (= '(freeze 2)
           (thaw-anything
             (f [(DataByteArray. (freeze 2))]))))))

(deftest test-exec-multi
  (let [command (pigpen.pig/exec-multi :native :native 
                  [(pigpen.pig/map->bind clojure.edn/read-string)
                   (pigpen.pig/map->bind identity)
                   (pigpen.pig/filter->bind (constantly true))
                   vector
                   (pigpen.pig/map->bind clojure.core/pr-str)])]
  
    (is (= (thaw-anything (command ["1"]))
           '(bag (tuple "1")))))
  
  (let [command (pigpen.pig/exec-multi :native :native 
                  [(pigpen.pig/map->bind clojure.edn/read-string)
                   (fn [x] [x (+ x 1) (+ x 2)])
                   (pigpen.pig/map->bind clojure.core/pr-str)])]
  
    (is (= (thaw-anything (command ["1"]))
           '(bag (tuple "1") (tuple "2") (tuple "3")))))
  
  (let [command (pigpen.pig/exec-multi :native :native 
                   [(pigpen.pig/map->bind clojure.edn/read-string)
                    (fn [x] [x (* x 2)])
                    (fn [x] [x (* x 2)])
                    (fn [x] [x (* x 2)])
                    (pigpen.pig/map->bind clojure.core/pr-str)])]
  
     (is (= (thaw-anything (command ["1"]))
            '(bag (tuple "1") (tuple "2") (tuple "2") (tuple "4") (tuple "2") (tuple "4") (tuple "4") (tuple "8"))))))

(deftest test-debug
  (is (= "class java.lang.Long\t2\tclass org.apache.pig.data.DefaultDataBag\t{(foo,bar)}"
         (debug 2 (bag (tuple "foo" "bar"))))))
