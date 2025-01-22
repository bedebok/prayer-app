(ns src.dk.cst.prayer.search-test
  (:require [dk.cst.prayer.search :refer [parse]]
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
             [:QUERY [:QUIRK [:VALUES "1"] [:IGNORED [:AND]]]])))
    (testing "misplaced operators should trigger it"
      (is (= (parse "& 1 &")
             [:QUERY [:QUIRK [:IGNORED [:AND]] [:VALUES "1"] [:IGNORED [:AND]]]]))))

  (testing "values"
    (testing "whitespace at bounds should be ignored"
      (is (= (parse " this     ")
             (parse "this")
             [:QUERY [:VALUES "this"]])))
    (testing "multiple tokens (i.e. no operators) should trigger it"
      (is (= (parse "this that")
             [:QUERY [:VALUES "this" "that"]]))
      (is (= (parse "this that other")
             [:QUERY [:VALUES "this" "that" "other"]])))
    (testing "groups should return same output as values"
      (is (= (parse "(this that)")
             [:QUERY [:VALUES "this" "that"]])))
    (testing "fields should be available"
      (is (= (parse "field:value")
             (parse "field = value")
             [:QUERY [:VALUES [:FIELD "field" "value"]]]))
      (is (= (parse "field:\"a phrase\"")
             [:QUERY [:VALUES [:FIELD "field" "a phrase"]]]))
      (testing "fields and tokens should be able to mix"
        (is (= (parse "field = value token")
               [:QUERY [:VALUES [:FIELD "field" "value"] "token"]]))))
    (testing "phrases should be available"
      (is (= (parse "\"this is a phrase\"")
             [:QUERY [:VALUES "this is a phrase"]]))
      (testing "phrases and tokens should be able to mix"
        (is (= (parse "\"this is a phrase\" token")
               [:QUERY [:VALUES "this is a phrase" "token"]])))))

  (testing "expressions"
    (testing "implicit grouping should occur"
      (is (= (parse "1 2 OR 3")
             [:QUERY
              [:EXPRESSION
               [:VALUES "1" "2"]
               [:OR]
               [:VALUES "3"]]])))
    (testing "multiple operators are possible"
      (is (= (parse "1 2 OR 3 AND 4")
             [:QUERY
              [:EXPRESSION
               [:VALUES "1" "2"]
               [:OR]
               [:VALUES "3"]
               [:AND]
               [:VALUES "4"]]])))
    (testing "expressions can contain other expressions"
      (is (= (parse "1 2 OR (3 AND 4)")
             [:QUERY
              [:EXPRESSION
               [:VALUES "1" "2"]
               [:OR]
               [:EXPRESSION
                [:VALUES "3"]
                [:AND]
                [:VALUES "4"]]]]))))

  (testing "negations"
    (testing "should be able to negate a single value"
      (is (= (parse "NOT this")
             [:QUERY [:NEGATION "this"]])))
    (testing "should be able to apply multiple negations"
      (is (= (parse "NOT NOT this")
             [:QUERY [:NEGATION [:NEGATION "this"]]])))
    (testing "should be able to infix negation among multiple values"
      (is (= (parse "that NOT this")
             [:QUERY [:VALUES "that" [:NEGATION "this"]]])))
    (testing "should work with a group"
      (is (= (parse "!(this that)")
             [:QUERY [:NEGATION [:VALUES "this" "that"]]])))
    (testing "should fit into an expression"
      (is (= (parse "that NOT this OR that")
             [:QUERY [:EXPRESSION [:VALUES "that" [:NEGATION "this"]] [:OR] [:VALUES "that"]]])))
    (testing "should be able to negate an expression"
      (is (= (parse "NOT (this OR that)")
             [:QUERY [:NEGATION [:EXPRESSION [:VALUES "this"] [:OR] [:VALUES "that"]]]])))))
