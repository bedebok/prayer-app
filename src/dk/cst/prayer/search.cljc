(ns dk.cst.prayer.search
  "Parsing of search query language into an AST that can be executed by the
  search functions in the 'dk.cst.prayer.db' namespace."
  (:require [clojure.string :as str]
            [clojure.zip :as zip]
            [instaparse.core :as insta]
            [taoensso.telemere :as t]
            [dk.cst.hiccup-tools.hiccup :as hiccup]
            [dk.cst.hiccup-tools.match :as match]
            [dk.cst.hiccup-tools.zip :as z]
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

(defn- apply-de-morgans-laws
  [tag child-loc]
  (into [tag] (for [child (zip/children child-loc)] [:NEGATION child])))

;; https://en.wikipedia.org/wiki/De_Morgan%27s_laws
(defn handle-de-morgans-laws
  "If this `negation-loc` negates either a union or an intersection, apply
  De Morgan's Laws such that the negated union/intersection is swapped with an
  intersection/union containing the negated parts of the former."
  [negation-loc]
  (if (= 1 (count (zip/children negation-loc)))
    (let [child-loc (zip/down negation-loc)]
      (cond
        ((match/match :INTERSECTION) child-loc)
        (zip/replace negation-loc (apply-de-morgans-laws :UNION child-loc))

        ((match/match :UNION) child-loc)
        (zip/replace negation-loc (apply-de-morgans-laws :INTERSECTION child-loc))

        :else negation-loc))
    negation-loc))


(def parse-tree-simplification
  {:multi [[#{:QUIRK intersection+} z/splice]
           [#{:IGNORED} zip/remove]
           [:NEGATION handle-double-negative]
           [:NEGATION handle-de-morgans-laws]]})

(defn simplify
  "Simplify a Hiccup `parse-tree`.

  Returns nil for a degenerate tree without any usable content, e.g. a query
  consisting solely of misplaced operators."
  [parse-tree]
  (when-let [inner (second (hiccup/reshape parse-tree parse-tree-simplification))]
    (if (not= :INTERSECTION (first inner))
      [:INTERSECTION inner]
      inner)))

(defn query->ast
  "Parse and simplify a search `query` into an executable AST.

  Returns nil when the query is blank, cannot be parsed, or contains no
  usable content."
  [query]
  (try
    (let [parse-tree (parse query)]
      (if (insta/failure? parse-tree)
        (t/log! {:level :warn
                 :data  {:query   query
                         :failure parse-tree}}
                "Failed to parse search query.")
        (when-let [ast (some-> parse-tree simplify)]
          (t/log! {:level :info
                   :data  {:query query
                           :ast   ast}}
                  "Transformed search query into AST.")
          ast)))
    (catch #?(:clj Exception :cljs js/Error) e
      (t/log! {:level :error
               :data {:query query
                      :error e}}
              "Failed to transform search query into AST."))))

(comment
  ;; Basic parsing and simplification.
  (parse "this that")
  (parse "this AND that")
  (parse "1 & 2 | 3")
  (simplify (parse "(1 & (2|3)) | 4 | 5"))
  (simplify (parse "1 & ( 2 & (3 & 4) )"))

  ;; Fields and phrases.
  (parse "field:value")
  (parse "field = value")
  (parse "\"glen:john\" | asdsd")
  (parse "\"aaabbbb AND sdd\" | asdsd")

  ;; Negations, incl. double negatives and De Morgan's laws.
  (query->ast "NOT corresp:AM08-0073")
  (simplify (parse "NOT (NOT NOT NOT NOT børge & glen)"))
  (simplify (parse "!!!!!!!(4 | !5)"))
  (simplify (parse "thing NOT (that this)"))

  ;; Degenerate input.
  (parse "   ")                                             ; => should return nil
  (parse "1|")                                              ; => quirks mode
  (simplify (parse "AND&|"))                                ; => quirks mode
  (parse "AND")

  ;; TODO: should be possible?
  (parse "aaabbbb (field:value OR asdsd OR 1 2 3)")
  #_.)
