(ns dk.cst.prayer.tei.schema
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File StringReader]
           [javax.xml XMLConstants]
           [javax.xml.transform Source]
           [javax.xml.transform.stream StreamSource]
           [javax.xml.validation SchemaFactory]
           [org.xml.sax SAXException]
           [javax.xml.transform.stream StreamSource]))

(defn path->stream-source
  "Make a StreamSource from a classpath-relative `path`.

  The resource URL doubles as the system id, so that any relative imports in
  the schema resolve correctly both in dev and inside the production uberjar."
  [^String path]
  (StreamSource. (.toExternalForm (io/resource path))))

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
  (delay (->validator "schema/tei_all.xsd")))

(defn- shorten-alternatives
  "Truncate a long comma-separated list `s` of expected elements."
  [s]
  (let [alternatives (str/split s #", ")]
    (if (> (count alternatives) 6)
      (str (str/join ", " (take 5 alternatives))
           " … (+" (- (count alternatives) 5) " more)")
      s)))

(defn condense-error
  "Make a Xerces validation `error-message` easier to read.

  Strips XML namespace URIs from element references and truncates long lists
  of expected elements, e.g. in cvc-complex-type.2.4.a/b messages."
  [error-message]
  (-> error-message
      (str/replace #"\"[^\"]+\":" "")
      (str/replace #"\{([^}]+)\}" (comp #(str "{" % "}")
                                        shorten-alternatives
                                        second))))

(defn validate-tei
  "Return the first found validation error message for TEI `xml` (if invalid)."
  [xml]
  (some-> (@validator xml) condense-error))
