(ns dk.cst.prayer.tei.schema
  (:require [clojure.string :as str])
  (:import [java.io File StringReader]
           [javax.xml XMLConstants]
           [javax.xml.transform Source]
           [javax.xml.transform.stream StreamSource]
           [javax.xml.validation SchemaFactory]
           [org.xml.sax SAXException]
           [javax.xml.transform.stream StreamSource]))

(defn path->stream-source
  [^String path]
  (StreamSource. (File. path)))

;; Based on https://github.com/rkday/clj-xml-validation
(defn ->validator
  [& schemas]
  (let [sources   (into-array StreamSource (map path->stream-source schemas))
        validator (-> (SchemaFactory/newInstance XMLConstants/W3C_XML_SCHEMA_NS_URI)
                      (.newSchema ^Source/1 sources)
                      (.newValidator))]
    (fn [xml]
      (try
        (if (and (string? xml)
                 (str/starts-with? xml "<?xml"))
          (.validate validator (StreamSource. (StringReader. xml)))
          (.validate validator (StreamSource. (StringReader. (slurp xml)))))
        (catch SAXException e
          (.getMessage e))))))

(def validator
  (delay (->validator "resources/tei_all.xsd")))

(defn validate-tei
  "Return the first found validation error for TEI `xml` (if invalid)."
  [xml]
  (@validator xml))

(defn valid-tei?
  "Is the `xml` a valid TEI document?"
  [xml]
  (nil? (validate-tei xml)))

(comment
  (->validator "resources/tei_all.xsd")

  (validate-tei "test/Data/Catalogue/xml/AM04-0789.xml")
  (validate-tei "test/Data/Catalogue/xml/AM08-0073.xml")
  (validate-tei "test/Data/Prayers/xml/AM08-0073_237v.xml")

  (valid-tei? "test/Data/Catalogue/xml/AM04-0789.xml")
  (valid-tei? "test/Data/Catalogue/xml/AM08-0073.xml")
  (valid-tei? "test/Data/Prayers/xml/AM08-0073_237v.xml")
  #_.)
