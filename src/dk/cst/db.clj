(ns dk.cst.db
  (:require [clojure.string :as str]
            [clojure.zip :as zip]
            [dk.cst.xml-hiccup :as xh]
            [dk.cst.hiccup-tools.hiccup :as h]
            [dk.cst.hiccup-tools.zip :as z]
            [dk.cst.hiccup-tools.match :as match :refer [matcher]]
            [clojure.java.io :as io]))

(defn tei-ref
  [tag]
  (let [url  (str "https://tei-c.org/release/doc/tei-p5-doc/en/html/ref-"
                  (if (string? tag)
                    tag
                    (name tag))
                  ".html")
        html (slurp url)]
    html))

(defn tei-description
  [html]
  (-> (xh/parse html)
      (h/get (match/has-child
               (match/hiccup [:span {:class "label" :xml/lang nil}])))
      (h/hiccup->text h/html-conversion)))

;; TODO: punctuation not working great, maybe postprocess?
(def tei-conversion
  {:conversions {:teiHeader      zip/remove
                 #{:head :p :pb} z/surround-lb
                 :w              z/insert-space
                 :lb             z/append-lb}
   :postprocess str/trim})

(def tei-matchers
  "Matchers for doing metadata searches in parsed TEI documents."
  {:settlement (match/tag+attr :settlement {:key true})
   :repository (match/tag+attr :repository {:key true})
   :id         (match/tag+attr :idno {:xml/id true})
   :locus      (match/tag+attr :locus {:from true :to true})
   :title      (match/tag+attr :title {:xml/id true})
   :lang       (match/tag+attr :textLang {:mainLang true})
   :name       (match/tag+attr :name {:type true :key true})
   :section    (match/tag+attr :div {:type true})})

(comment
  (tei-description (tei-ref :sourceDesc))
  (tei-description (tei-ref :TEI))

  ;; test text conversion on a full HTML document
  (-> (tei-ref :sourceDesc)
      (xh/parse)
      (h/hiccup->text h/html-conversion))

  ;; test text conversion on a TEI body
  ;; compare: https://github.com/bedebok/Data/blob/main/Prayers/org/AM08-0073_237v.org
  (-> (io/file "test/Data/Prayers/xml/AM08-0073_237v.xml")
      (xh/parse)
      (h/hiccup->text tei-conversion))

  ;; Metadata retrieval from a document
  ;; compare: https://github.com/bedebok/Data/blob/main/Prayers/org/AM08-0073_237v.org
  (-> (io/file "test/Data/Prayers/xml/AM08-0073_237v.xml")
      (xh/parse)
      (h/search tei-matchers))
  #_.)
