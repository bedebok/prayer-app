(ns dk.cst.prayer.db
  (:require [clojure.set :as set]
            [clojure.string :as str]
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

;; NOTE: Datalevin returns the UNION of results rather than the INTERSECTION,
;;       e.g. the query "this that" is equivalent to "this OR that".
;;       It also doesn't support phrase search (only token search is supported),
;;       so the phrase "\"this that\"" returns the equivalent of the UNION of
;;       results of searching for either "this" or "that".
(defn find-tokens
  [db s]
  (->> s
       (d/q '[:find ?v ?e ?a
              :in $ ?q
              :where [(fulltext $ ?q) [[?e ?a ?v]]]]
            db)
       (not-empty)))

;; Used to combine search results into their intersection (e.g. this AND that).
(defn- result-intersection
  [results more-results]
  (or (not-empty (if results
                   (set/intersection results more-results)
                   more-results))
      (reduced nil)))

;; The phrase search implementation: Datalevin's existing full-text search is
;; augmented with a fairly loose regex search for the required phrase.
(defn has-phrase
  "Does the string `s` contain the `phrase` (case-insensitive)."
  [phrase s]
  (-> (str "(?i)" phrase)                                   ; case-insensitive
      (str/replace #"\s+" "\\\\s")                          ; whitespace-insensitive
      (re-pattern)
      (re-find s)))

;; We need to build this extra bit of machinery to do full-text searches as
;;   1) Datalevin currently doesn't support searching for phrases and
;;   2) defaults to the UNION of results, not the INTERSECTION.
(defn full-text-intersection
  "Find matching entities intersection in `db` for `strs` via full-text search,
  i.e. `strs` are the strings in the query 's1 AND s2 AND s3' and so on.

  Matching is case-insensitive and searching for phrases is supported."
  [db strs]
  (reduce (fn [acc s]
            (or (when-let [results (find-tokens db s)]
                  (letfn [(hit? [[text]] (has-phrase s text))]
                    (result-intersection acc (if (re-find #"\s" s)
                                               (set (filter hit? results))
                                               results))))
                (reduced nil)))
          nil
          strs))

;; TODO: implement common parsable search query params
(defn search
  [db query]
  (some-> (full-text-intersection db [query])
          (->> (map (fn [[?e _ ?text]]
                      ;; produce a seq of [?id ?type ?text] vectors
                      (conj (d/q '[:find [?id ?type]
                                   :in $ ?e
                                   :where
                                   [?e :bedebok/type ?type]
                                   [?e :bedebok/id ?id]]
                                 db
                                 ?e)
                            ?text)))
               (group-by second))
          (update-vals (fn [kvs]
                         (sort (map first kvs))))))

(comment
  (search (d/db (d/get-conn db-path static/schema)) "geist")
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
  (d/q '[:find ?v ?e ?a
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

  (full-text-intersection (d/db (d/get-conn db-path static/schema))
                          ["grote den herren"])

  ;; Testing phrase search and "AND".
  (let [db (-> (d/empty-db "/tmp/mydb"
                           {:text {:db/valueType :db.type/string
                                   :db/fulltext  true}})
               (d/db-with
                 [{:db/id 1,
                   :other 123
                   :text  "The quick red fox jumped over the lazy red dogs."}
                  {:db/id 2,
                   :other 456
                   :text  "Mary had a little lamb whose fleece was red as fire."}
                  {:db/id 3,
                   :other 789
                   :text  "Moby Dick is a story of a whale and a man obsessed."}]))]
    (full-text-intersection db ["Mary" "little lamb" "red as fire"]))

  (let [db (-> (d/empty-db "/tmp/mydb"
                           {:text {:db/valueType :db.type/string
                                   :db/fulltext  true}})
               (d/db-with
                 [{:db/id 1,
                   :other 123
                   :text  "The quick red fox jumped over the lazy red dogs."}
                  {:db/id 2,
                   :other 456
                   :text  "Mary had a little lamb whose fleece was red as fire."}
                  {:db/id 3,
                   :other 789
                   :text  "Moby Dick is a story of a whale and a man obsessed."}]))]
    (d/q '[:find ?e ?other ?text
           :in $ ?q
           :where
           [?e :other ?other]
           [?e :text ?text]
           [(fulltext $ ?q) [[?e ?a ?v]]]]
         db
         "asdasdasd red"))

  (d/q '[:find ?e ?type
         :where
         [?e :db/doc ?type]]
       (d/db (d/get-conn db-path static/schema)))

  (do
    (d/close (d/get-conn db-path static/schema))
    (run! io/delete-file (reverse (file-seq (io/file db-path)))))
  #_.)
