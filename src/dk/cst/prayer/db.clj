(ns dk.cst.prayer.db
  (:require [datalevin.core :as d]
            [clojure.java.io :as io]
            [dk.cst.xml-hiccup :as xh]
            [dk.cst.prayer.tei :as tei]))

(def db-path
  "test/db")

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

(defn conn
  []
  (d/get-conn db-path schema))

;;; Retract the name attribute of an entity
#_(d/transact! conn [[:db/retract 1 :name "Frege"]])
;(d/transact! conn [{:name "Frege" :aka "glen"}])
;
;;; Pull the entity, now the name is gone
;(d/q '[:find (pull ?e [*])
;       :in $ ?alias
;       :where
;       [?e :aka ?alias]]
;     (d/db conn)
;     "fred")
;;; => ([{:db/id 1, :aka ["foo" "fred"], :nation "France"}])
;
;;; Close DB connection
;(d/close conn)


(comment
  (-> (io/file "test/Data/Prayers/xml/Holm-A42_032r.xml")
      (xh/parse)
      (tei/hiccup->entity tei/manuscript-search-kvs))


  (try
    (d/transact! (conn)
                 [(-> (io/file "test/Data/Prayers/xml/AM08-0075_063r.xml")
                      (xh/parse)
                      (tei/hiccup->entity tei/manuscript-search-kvs))])
    (catch Exception e
      (prn e)))

  ;; delete an entity
  (d/transact! (conn) [[:db/retractEntity 1]])

  (d/q '[:find ?e ?a ?v
         :where
         [?e ?a ?v]]
       (d/db (conn)))

  (d/q '[:find ?a ?v
         :in $ ?e
         :where
         [?e ?a ?v]]
       (d/db (conn))
       1
       #_[:tei/name+type ["Mary" "person"]])

  (do
    (d/close (conn))
    (run! io/delete-file (reverse (file-seq (io/file db-path)))))
  #_.)
