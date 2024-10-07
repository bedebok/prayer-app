(ns dk.cst.prayer.db
  (:require [clojure.string :as str]
            [datalevin.core :as d]
            [clojure.java.io :as io]
            [dk.cst.xml-hiccup :as xh]
            [dk.cst.prayer.tei :as tei])
  (:import [java.io File]))

(def db-path
  "test/db")

(def files-path
  "test/Data")


(def schema
  {:xml/id         {:db/valueType :db.type/string
                    :db/unique    :db.unique/identity
                    :db/doc       (str "The xml:id is reused to identify TEI documents.")}

   :tei/title      {:db/valueType   :db.type/string
                    :db/cardinality :db.cardinality/one}
   :tei/settlement {:db/valueType   :db.type/string
                    :db/cardinality :db.cardinality/one}
   :tei/mainLang   {:db/valueType   :db.type/string
                    :db/cardinality :db.cardinality/one}
   :tei/repository {:db/valueType   :db.type/string
                    :db/cardinality :db.cardinality/one}

   :tei/msItem     {:db/cardinality :db.cardinality/many
                    :db/valueType   :db.type/ref
                    :db/isComponent true}
   :tei/from       {:db/valueType   :db.type/string
                    :db/cardinality :db.cardinality/one}
   :tei/to         {:db/valueType   :db.type/string
                    :db/cardinality :db.cardinality/one}

   :tei/entity     {:db/cardinality :db.cardinality/one
                    :db/valueType   :db.type/ref
                    :db/doc         (str "For internal entities appearing across the documents "
                                         "such as named entities or biblical references. "
                                         "Multiple references to an entity are allowed, "
                                         "but only a single entity exists.")}
   :tei/name       {:db/valueType   :db.type/string
                    :db/cardinality :db.cardinality/one}
   :tei/type       {:db/valueType   :db.type/string
                    :db/cardinality :db.cardinality/one}
   :tei/name+type  {:db/valueType   :db.type/tuple
                    :db/tupleAttrs  [:tei/name :tei/type]
                    :db/cardinality :db.cardinality/one
                    :db/unique      :db.unique/identity}
   :tei/named      {:db/cardinality :db.cardinality/many
                    :db/valueType   :db.type/ref
                    :db/isComponent true
                    :db/doc         (str "A named entity reference.")}
   :tei/ref        {:db/cardinality :db.cardinality/many
                    :db/valueType   :db.type/ref
                    :db/isComponent true
                    :db/doc         (str "A literary reference.")}
   :tei/label      {:db/cardinality :db.cardinality/many
                    :db/valueType   :db.type/string}})

(defn xml-files
  "Fetch XML File objects recursively from a starting `dir`."
  [dir]
  (->> (file-seq (io/file dir))
       (remove (fn [^File f] (.isDirectory f)))
       (filter (fn [^File f] (str/ends-with? (.getName f) ".xml")))))

(defn rmdir
  [dir]
  (->> (io/file dir)
       (file-seq)
       (reverse)
       (run! io/delete-file)))


;; TODO: add full-text search
(defn ->db
  [files-path db-path]
  (let [files           (xml-files files-path)
        hiccup->entity' #(tei/hiccup->entity % tei/manuscript-search-kvs)
        entities        (map (comp hiccup->entity' xh/parse) files)
        ;; TODO: kills old db first (make this a bit more elegant)
        conn            (do
                          (d/close (d/get-conn db-path schema))
                          (rmdir db-path)
                          (d/get-conn db-path schema))]
    (d/transact! conn entities)))

(comment
  (xml-files files-path)
  (->db files-path db-path)

  (-> (io/file "test/Data/Prayers/xml/Holm-A42_032r.xml")
      (xh/parse)
      (tei/hiccup->entity tei/manuscript-search-kvs))


  (try
    (d/transact! (d/get-conn db-path schema)
                 [(-> (io/file "test/Data/Prayers/xml/AM08-0075_063r.xml")
                      (xh/parse)
                      (tei/hiccup->entity tei/manuscript-search-kvs))])
    (catch Exception e
      (prn e)))

  ;; delete an entity
  (d/transact! (d/get-conn db-path schema) [[:db/retractEntity 1]])

  (->> (d/q '[:find ?e ?a ?v
              :where
              [?e ?a ?v]]
            (d/db (d/get-conn db-path schema)))
       (count))


  (d/q '[:find ?a ?v
         :in $ ?e
         :where
         [?e ?a ?v]]
       (d/db (d/get-conn db-path schema))
       [:tei/name+type ["Mary" "person"]])

  (do
    (d/close (d/get-conn db-path schema))
    (run! io/delete-file (reverse (file-seq (io/file db-path)))))
  #_.)
