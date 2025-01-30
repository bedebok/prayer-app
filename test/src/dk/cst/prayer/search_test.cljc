(ns src.dk.cst.prayer.search-test
  (:require [dk.cst.prayer.search :refer [parse simplify]]
            [clojure.test :refer [deftest is testing]]))

(deftest test-parse
  (testing "invalid input"
    (testing "empty queries should result in nil"
      (is (= (parse "") nil))
      (is (= (parse "     ") nil)))
    (testing "invalid input should result in exceptions"
      (is (thrown? #?(:clj Exception :cljs js/Error) (parse nil)))
      (is (thrown? #?(:clj Exception :cljs js/Error) (parse 123)))
      (is (thrown? #?(:clj Exception :cljs js/Error) (parse :kw)))
      (is (thrown? #?(:clj Exception :cljs js/Error) (parse [])))))

  (testing "quirks mode"
    (testing "operator-only input should trigger it"
      (is (= (parse "AND")
             [:QUERY [:QUIRK [:IGNORED [:AND]]]])))
    (testing "multiple misplaced operators should trigger it"
      (is (= (parse "AND&|")
             [:QUERY [:QUIRK [:IGNORED [:AND] [:AND] [:OR]]]])))
    (testing "NOT should not prevent quirks mode"
      (is (= (parse "NOT AND")
             [:QUERY [:QUIRK [:IGNORED [:NOT] [:AND]]]])))
    (testing "missing operands should trigger it"
      (is (= (parse "1 &")
             [:QUERY [:QUIRK [:INTERSECTION "1"] [:IGNORED [:AND]]]])))
    (testing "misplaced operators should trigger it"
      (is (= (parse "| 1 | 2 & 3 &")
             [:QUERY
              [:QUIRK
               [:IGNORED [:OR]]
               [:UNION "1" [:INTERSECTION "2" "3"]]
               [:IGNORED [:AND]]]]))))

  (testing "values"
    (testing "whitespace at bounds should be ignored"
      (is (= (parse " this     ")
             (parse "this")
             [:QUERY [:INTERSECTION "this"]])))
    (testing "multiple tokens (i.e. no operators) should trigger it"
      (is (= (parse "this that")
             [:QUERY [:INTERSECTION "this" "that"]]))
      (is (= (parse "this that other")
             [:QUERY [:INTERSECTION "this" "that" "other"]])))
    (testing "groups should return same output as values"
      (is (= (parse "(this that)")
             [:QUERY [:INTERSECTION "this" "that"]])))
    (testing "fields should be available"
      (is (= (parse "field:value")
             (parse "field = value")
             [:QUERY [:INTERSECTION [:FIELD "field" "value"]]]))
      (is (= (parse "field:\"a phrase\"")
             [:QUERY [:INTERSECTION [:FIELD "field" "a phrase"]]]))
      (testing "fields and tokens should be able to mix"
        (is (= (parse "field = value token")
               [:QUERY [:INTERSECTION [:FIELD "field" "value"] "token"]]))))
    (testing "phrases should be available"
      (is (= (parse "\"this is a phrase\"")
             [:QUERY [:INTERSECTION "this is a phrase"]]))
      (testing "phrases and tokens should be able to mix"
        (is (= (parse "\"this is a phrase\" token")
               [:QUERY [:INTERSECTION "this is a phrase" "token"]])))))

  (testing "expressions"
    (testing "implicit grouping should occur"
      (is (= (parse "1 2 OR 3")
             [:QUERY [:UNION [:INTERSECTION "1" "2"] "3"]])))
    (testing "multiple operators are possible"
      (is (= (parse "1 2 OR 3 AND 4")
             [:QUERY
              [:UNION
               [:INTERSECTION "1" "2"]
               [:INTERSECTION "3" "4"]]])))
    (testing "expressions can contain other expressions"
      (is (= (parse "1 2 OR (3 AND 4)")
             [:QUERY
              [:UNION
               [:INTERSECTION "1" "2"]
               [:INTERSECTION "3" "4"]]]))))

  (testing "negations"
    (testing "should be able to negate a single value"
      (is (= (parse "NOT this")
             [:QUERY [:NEGATION "this"]])))
    (testing "should be able to apply multiple negations"
      (is (= (parse "NOT NOT this")
             [:QUERY [:NEGATION [:NEGATION "this"]]])))
    (testing "should be able to infix negation among multiple values"
      (is (= (parse "that NOT this")
             [:QUERY [:INTERSECTION "that" [:NEGATION "this"]]])))
    (testing "should work with a group"
      (is (= (parse "!(this that)")
             [:QUERY [:NEGATION [:INTERSECTION "this" "that"]]])))
    (testing "should fit into an expression"
      (is (= (parse "that NOT this OR that")
             [:QUERY [:UNION [:INTERSECTION "that" [:NEGATION "this"]] "that"]])))
    (testing "should be able to negate an expression"
      (is (= (parse "NOT (this OR that)")
             [:QUERY [:NEGATION [:UNION "this" "that"]]])))))

(deftest test-simplify
  (testing "intersections in intersections should be collapsed"
    (is (= (simplify (parse "1 & ( 2 & (3 & 4) )"))
           [:INTERSECTION "1" "2" "3" "4"])))
  (testing "double/multiple negations should be collapsed appropriately"
    (is (= (simplify (parse "(1 2 3)"))
           (simplify (parse "!!(1 2 3)"))
           (simplify (parse "!!!!(1 2 3)"))
           [:INTERSECTION "1" "2" "3"]))
    (is (= (simplify (parse "!(1 2 3)"))
           (simplify (parse "!!!(1 2 3)"))
           (simplify (parse "!!!!!(1 2 3)"))
           [:INTERSECTION [:NEGATION [:INTERSECTION "1" "2" "3"]]]))
    (testing "including complex expressions")
    (is (= (simplify (parse "!!(1 | (!2 3))"))
           [:INTERSECTION [:UNION "1" [:INTERSECTION [:NEGATION "2"] "3"]]])))
  (testing "quirks should be collapsed and misplaced content removed"
    (is (= (simplify (parse "| 1 | 2 & 3 &"))
           [:INTERSECTION [:UNION "1" [:INTERSECTION "2" "3"]]]))))
