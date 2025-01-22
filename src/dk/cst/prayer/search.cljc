(ns dk.cst.prayer.search
  (:require [clojure.string :as str]
            [instaparse.core :as insta]
            #?(:cljs [shadow.resource :as resource])))

(insta/defparser parse*
  #?(:clj  (slurp "resources/search.ebnf")
     :cljs (resource/inline "lucene.ebnf")))

(defn parse
  [query]
  (some-> (str/trim query)
          (not-empty)
          (parse*)))

(comment
  (parse "!(this that)")
  (parse "this that")
  (parse "NOT that this")
  (parse "thing NOT (that this)")
  (parse "NOT AND this that")
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
