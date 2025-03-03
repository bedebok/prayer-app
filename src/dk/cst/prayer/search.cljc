(ns dk.cst.prayer.search
  (:require [clojure.string :as str]
            [clojure.zip :as zip]
            [dk.cst.hiccup-tools.hiccup :as hiccup]
            [dk.cst.hiccup-tools.match :as match]
            [dk.cst.hiccup-tools.zip :as z]
            [instaparse.core :as insta]
            #?(:cljs [shadow.resource :as resource])))

(insta/defparser parse*
  #?(:clj  (slurp "resources/search.ebnf")
     :cljs (resource/inline "search.ebnf")))

(defn parse
  "Parse a search `query` to get a Hiccup parse tree."
  [query]
  (some-> (str/trim query)
          (not-empty)
          (parse*)))

(def intersection+
  (match/match
    :INTERSECTION
    (match/has-parent :INTERSECTION)))

(defn handle-double-negative
  "If this `negation-loc` contains another negation loc as its sole child,
  collapse both and splicing the inner negation's children into the tree."
  [negation-loc]
  (if (and (= 1 (count (zip/children negation-loc)))
           ((match/match :NEGATION) (zip/down negation-loc)))
    (-> negation-loc zip/down z/splice z/splice)
    negation-loc))

(def parse-tree-simplification
  {:multi {#{:QUIRK intersection+} z/splice
           #{:IGNORED}             zip/remove
           :NEGATION               handle-double-negative}})

(defn simplify
  "Simplify a Hiccup `parse-tree`."
  [parse-tree]
  (let [inner (second (hiccup/reshape parse-tree parse-tree-simplification))]
    (if (not= :INTERSECTION (first inner))
      [:INTERSECTION inner]
      inner)))

;; TODO: log the error? Or return some kind of debugging output?
(defn query->ast
  [query]
  (try
    (simplify (parse query))
    (catch #?(:clj Exception :cljs js/Error) _ nil)))

(comment
  (query->ast "NOT corresp:AM08-0073")
  (simplify (parse "NOT corresp:AM08-0073"))
  (simplify (parse "NOT corresp:AM08-0073 glen"))

  (parse "!(this that)")
  (parse "1 2 OR (3 AND 4)")
  [:QUERY
   [:UNION
    [:INTERSECTION [:FIELD "field" "value"] "1" "2" "3"]
    [:INTERSECTION
     [:NEGATION [:INTERSECTION "4" "5"]]
     [:UNION "6" "7"]]]]

  [:QUERY
   [:EXPRESSION
    [:EXPRESSION "1" [:EXPRESSION "2" [:OR] "3"]]
    [:OR]
    "4"
    [:OR]
    "5"]]

  (let [parts (->> [[:VALUES "1"] [:AND] [:VALUES "2"] [:OR] [:VALUES "3"]]
                   (remove #{[:AND]})
                   (partition-by #{[:OR]})
                   (take-nth 2))]
    (map (partial into #{}) parts))

  [:QUERY
   [:OPT
    [:OPT "1" [:OPT "2"] [:OPT "3"]]
    [:OPT "4"]
    [:OPT "5"]]]

  (->> [[:VALUES "1"] [:AND] [:VALUES "2"]]
       (remove #{[:AND]})
       (partition-by #{[:OR]})
       (take-nth 2)
       (count))

  (simplify (parse "!!!!!!!(4 | !5)"))
  (simplify (parse "!!!!!!(4 !5)"))
  (simplify (parse "!!!!!!(4 !5)"))
  (parse "1 & 2 | 3")
  (parse "1 & 2")
  (parse "1 | 2 3")
  (simplify (parse "1 & ( 2 & (3 & 4) )"))
  (simplify (parse "(field:value 1 2 3) | !!!(4 5) & (6 | 7)"))
  (simplify (parse "1 | (2 3)"))
  (simplify (parse "(1 & (2|3)) | 4 | 5"))
  (simplify (parse "this that AND (1 2 3)"))
  (parse "that OR this AND f:v AND NOT (1 2 3)")
  (parse "that OR (1 2 3)")
  (parse "!(this & that)")
  (parse "this that")

  (parse "NOT that this")
  (simplify (parse "that OR this AND that"))

  (simplify (parse "thing NOT (that this)"))
  (parse "NOT AND this that")
  (parse "AND this AND that")
  (simplify (parse "AND&|"))
  (parse "NOT NOT NOT")
  (parse "NOT AND")
  (parse "NOT (that this)")
  (parse "this !that")
  (parse "   ")                                             ; => should return nil
  (parse "1|")                                              ; => quirks mode
  (simplify (parse "1|&"))                                  ; => quirks mode
  (parse "|1|&")                                            ; => quirks mode
  (parse "| 1 |")                                           ; => quirks mode
  (parse "AND")
  (parse "field:value")
  (parse "field:value")
  (parse "field = value")
  (parse "aaabbbb field:value OR asdsd")
  (parse "aaabbbb (field:value OR asdsd OR 1 2 3)")         ; TODO: should be possible?
  (parse "aaabbbb (field:value AND asdsd AND 1 2 3)")       ;  TODO: should be possible?
  (parse "1 2 3")
  (parse "aaabbbb sdds AND asdsd")
  (parse "aaabbbb sdds AND asdsd")
  (parse "aaabbbb sdds AND ( asdsd OR glen john)")
  (parse "this AND that")
  (parse "\"aaabbbb sdd\" AND sdds AND asdsd")
  (parse "(aaabbbb sdds AND) OR asdsd")                     ; quirks mode
  (parse "aaabbbb|asdsd|\"glen er john\"")
  (parse "\"aaabbbb AND sdd\" | asdsd")
  (parse "\"glen:john\" | asdsd")
  (parse "\"aaabbbb sdd\" OR asdsd OR glen")
  (parse "\"aaabbbb sdd\" | asdsd | glen")
  (parse "\"aaabbbb sdd\" | asdsd")
  #_.
  #_.)
