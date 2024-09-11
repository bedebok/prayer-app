(ns dk.cst.db
  (:require [dk.cst.xml-hiccup :as xh]
            [dk.cst.hiccup-tools.hiccup :as h]
            [dk.cst.hiccup-tools.match :as match]
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
      (h/get (match/child
               (match/hiccup [:span {:class "label" :xml/lang nil}])))
      (h/hiccup->text h/html-conversion)))

(comment
  (xh/parse (io/file "test/Data/Prayers/xml/AM08-0073_237v.xml"))
  (tei-description (tei-ref :sourceDesc))
  (tei-description (tei-ref :TEI))

  ;; test text conversion on a full HTML document
  (-> (tei-ref :sourceDesc)
      (xh/parse)
      (h/hiccup->text h/html-conversion))
  #_.)

