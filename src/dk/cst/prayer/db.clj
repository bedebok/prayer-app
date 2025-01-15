(ns dk.cst.prayer.db
  (:require [clojure.string :as str]
            [datalevin.core :as d]
            [clojure.java.io :as io]
            [dk.cst.xml-hiccup :as xh]
            [dk.cst.prayer.static :as static]
            [dk.cst.prayer.tei :as tei])
  (:import [java.io File]))

(def db-path
  "test/db")

(def files-path
  "test/Data")

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
                   (d/close (d/get-conn db-path static/schema))
                   (rmdir db-path)
                   (d/get-conn db-path static/schema))]
    (d/transact! conn entities)))

(defn top-items
  "Get the top-level <msItem> data, i.e. not the children."
  [db]
  (->> (d/q '[:find [?e ...]
              :where
              (not [?gpe :tei/msItem ?pe])                  ; top-level
              [?pe :tei/msItem ?e]
              [?e :tei/key ?key]]
            (d/db (d/get-conn db-path static/schema)))
       (map (fn [id]
              (d/touch (d/entity db id))))))

(comment
  (xml-files files-path)
  (build-db! files-path db-path)

  (-> (io/file "test/Data/Prayers/xml/Holm-A42_032r.xml")
      (xh/parse)
      (tei/hiccup->entity tei/tei-search-kvs))


  (try
    (d/transact! (d/get-conn db-path static/schema)
                 [(-> (io/file "test/Data/Prayers/xml/AM08-0075_063r.xml")
                      (xh/parse)
                      (tei/hiccup->entity tei/tei-search-kvs))])
    (catch Exception e
      (prn e)))

  ;; delete an entity
  (d/transact! (d/get-conn db-path static/schema) [[:db/retractEntity 1]])

  (->> (d/q '[:find ?e .
              :in $ ?id
              :where
              [?e :bedebok/id ?id]]
            (d/db (d/get-conn db-path static/schema))
            "AM08-0073_237v"))

  (d/q '[:find ?e .
         :in $ ?id
         :where
         [?e :bedebok/id ?id]]
       (d/db (d/get-conn db-path static/schema))
       "AM08-0073_237v")


  ;; Get full entities and their subcomponents back.
  (d/touch (d/entity (d/db (d/get-conn db-path static/schema)) 1))
  (d/touch (d/entity (d/db (d/get-conn db-path static/schema)) 64))

  ;; Test retrieval of works (canonical texts)
  (d/q '[:find ?id ?type
         :where
         [?e :tei/msItem ?msItem]
         [?e :bedebok/type ?type]
         (or [?e :bedebok/type "text"]
             [?e :bedebok/type "manuscript"])
         [?msitem :tei/key "MAGNIFICAT"]
         [?e :bedebok/id ?id]]
       (d/db (d/get-conn db-path static/schema)))

  (top-items (d/db (d/get-conn db-path static/schema)))

  ;; Test full-text search
  (d/q '[:find ?e ?a ?v
         :in $ ?q
         :where [(fulltext $ ?q)
                 [[?e ?a ?v]]]]
       (d/db (d/get-conn db-path static/schema))
       "geist")

  (d/q '[:find ?a ?v
         :in $ ?e
         :where
         [?e ?a ?v]]
       (d/db (d/get-conn db-path static/schema))
       1)

  (d/q '[:find ?e ?type
         :where
         [?e :db/doc ?type]]
       (d/db (d/get-conn db-path static/schema)))

  (do
    (d/close (d/get-conn db-path static/schema))
    (run! io/delete-file (reverse (file-seq (io/file db-path)))))
  #_.)
