(ns dk.cst.prayer.search
  (:require [clojure.string :as str]
            [dk.cst.hiccup-tools.elem :as elem]
            [instaparse.core :as insta]
            #?(:cljs [shadow.resource :as resource])))

(insta/defparser parse*
  #?(:clj  (slurp "resources/search.ebnf")
     :cljs (resource/inline "lucene.ebnf")))

(defn tag
  [kset]
  (fn [x]
    (when (vector? x)
      (get kset (first x)))))

(defn value-type
  [v]
  (prn 'value-type v)
  (if (vector? v)
    (first v)
    :TEXT))

(defn parse
  [query]
  (some-> (str/trim query)
          (not-empty)
          (parse*)))

(def parse'
  (comp second parse))

;; https://stackoverflow.com/questions/16805630/and-or-order-of-operations
(defn expression-union
  "Reduce the `vs` of an expression to groups of values separated by OR."
  [vs]
  (->> (partition-by (comp boolean #{[:OR]}) vs)
       (take-nth 2)
       (map #(remove #{[:AND]} %))))

(defn simplify
  [x]
  (cond
    (vector? x)
    (let [[k & vs] x]
      (case k
        :QUIRK (->> (remove (tag #{:IGNORED}) vs)
                    (map simplify))
        :FIELD {(first vs) (second vs)}
        :NEGATION (into #{} (map simplify vs))
        :EXPRESSION (map simplify (expression-union vs))
        :VALUES (map simplify vs)
        ;; else
        (simplify vs)))

    (string? x)
    x

    :else
    (map simplify x)))


(comment
  (simplify (parse' "this that"))
  (second (simplify (parse' "that OR this AND f:v AND NOT (1 2 3)")))
  (simplify (parse' "that OR this"))
  (simplify (parse' "!(this that)"))
  (parse "this that")

  (parse "NOT that this")
  (parse "that OR this AND that")

  (parse "thing NOT (that this)")
  (parse "NOT AND this that")                               ; TODO: NOT should be preserved
  (parse "AND this AND that")                               ; TODO
  (parse "AND&|")
  (parse "NOT NOT NOT")
  (parse "NOT AND")
  (parse "NOT (that this)")
  (parse "this !that")
  (parse "   ")                                             ; => should return nil
  (parse "1|")                                              ; => quirks mode
  (parse "1|&")                                             ; => quirks mode
  (parse "|1|&")                                            ; => quirks mode
  (parse "| 1 |")                                           ; => quirks mode
  (parse "AND")
  (parse "field:value")
  (parse "field:value")
  (parse "field = value")
  (parse "aaabbbb field:value OR asdsd")
  (parse "aaabbbb (field:value OR asdsd)")                  ; TODO: should be possible
  (parse "1 2 3")
  (parse "aaabbbb sdds AND asdsd")
  (parse "aaabbbb sdds AND asdsd")
  (parse "aaabbbb sdds AND ( asdsd OR glen)")
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
