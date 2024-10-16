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
  {:xml/src        {:db/valueType :db.type/string
                    :db/doc       "The XML source code of the entity."}
   :xml/filename   {:db/valueType :db.type/string
                    :db/doc       "The filename of the document entity."}
   :xml/node       {:db/doc "The Hiccup node that is the source of this entity."}

   :xml/id         {:db/valueType :db.type/string
                    :db/unique    :db.unique/identity
                    :db/doc       (str "The xml:id is used to identify canonical works.")}
   :tei/corresp    {:db/valueType :db.type/string
                    :db/unique    :db.unique/identity
                    :db/doc       (str "The tei:corresp is used to identify instances of text.")}
   :tei/key        {:db/valueType :db.type/string
                    :db/doc       (str "The tei:key is used to link a text to a canonical work.")}

   :tei/text       {:db/valueType :db.type/string
                    :db/fulltext  true
                    :db/doc       "The text of the document, enabling full-text search."}

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
   :tei/class      {:db/cardinality :db.cardinality/many
                    :db/valueType   :db.type/string}
   :tei/from       {:db/valueType   :db.type/string
                    :db/cardinality :db.cardinality/one}
   :tei/to         {:db/valueType   :db.type/string
                    :db/cardinality :db.cardinality/one}
   :tei/rubric     {:db/valueType   :db.type/string
                    :db/cardinality :db.cardinality/many}
   :tei/incipit    {:db/valueType   :db.type/string
                    :db/cardinality :db.cardinality/many}
   :tei/explicit   {:db/valueType   :db.type/string
                    :db/cardinality :db.cardinality/many}

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

(defn build-db!
  [files-path db-path]
  (let [files    (xml-files files-path)
        entities (map tei/file->entity files)
        ;; TODO: kills old db first (make this a bit more elegant)
        conn     (do
                   (d/close (d/get-conn db-path schema))
                   (rmdir db-path)
                   (d/get-conn db-path schema))]
    (d/transact! conn entities)))

(defn top-items
  "Get the top-level <msItem> data, i.e. not the children."
  [db]
  (->> (d/q '[:find [?e ...]
              :where
              (not [?gpe :tei/msItem ?pe])                  ; top-level
              [?pe :tei/msItem ?e]
              [?e :tei/key ?key]]
            (d/db (d/get-conn db-path schema)))
       (map (fn [id]
              (d/touch (d/entity db id))))))

(comment
  (xml-files files-path)
  (build-db! files-path db-path)

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

  ;; Get full entities and their subcomponents back.
  (d/touch (d/entity (d/db (d/get-conn db-path schema)) 1))
  (d/touch (d/entity (d/db (d/get-conn db-path schema)) 64))

  ;; Test retrieval of works (canonical texts)
  (d/q '[:find [?v ...]
         :where
         [?e :tei/key ?v]]
       (d/db (d/get-conn db-path schema)))

  (top-items (d/db (d/get-conn db-path schema)))

  ;; Test full-text search
  (d/q '[:find ?e ?a ?v
         :in $ ?q
         :where [(fulltext $ ?q)
                 [[?e ?a ?v]]]]
       (d/db (d/get-conn db-path schema))
       "heren")

  (d/q '[:find ?a ?v
         :in $ ?e
         :where
         [?e ?a ?v]]
       (d/db (d/get-conn db-path schema))
       1)

  (do
    (d/close (d/get-conn db-path schema))
    (run! io/delete-file (reverse (file-seq (io/file db-path)))))
  #_.)
