(ns dk.cst.prayer.db
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [datalevin.core :as d]
            [datalevin.analyzer :as da]
            [datalevin.search-utils :as dsu]
            [clojure.java.io :as io]
            [dk.cst.xml-hiccup :as xh]
            [dk.cst.prayer.search :as search]
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

;; The phrase search implementation: Datalevin's existing full-text search is
;; augmented with a fairly loose regex search for the required phrase.
;; NOTE: Datalevin returns the UNION of results rather than the INTERSECTION,
;;       e.g. the query "this that" is equivalent to "this OR that".
;;       It also doesn't support phrase search (only token search is supported),
;;       so the phrase "\"this that\"" returns the equivalent of the UNION of
;;       results of searching for either "this" or "that".
(defn contains-phrase
  "Does the string `s` contain the `phrase` (case-insensitive)."
  [s phrase]
  (-> (str "(?i)" phrase)                                   ; case-insensitive
      (str/replace #"\s+" "\\\\s")                          ; whitespace-insensitive
      (re-pattern)
      (re-find s)
      (boolean)))

(defn operand-type
  [v]
  (if (string? v)
    :text
    (first v)))

(defn enclose-not
  [triple]
  (list 'not triple))

(defn negation->intersection
  [negation-vec]
  (assoc negation-vec 0 :INTERSECTION))

(declare intersection->triples)

(defn add-negations
  "Add `negations` from a search query AST to the coll of datalog `triples`.
  This is used to build a datalog query."
  [triples negations]
  (concat
    triples
    (if (= (count negations) 1)
      (->> (first negations)
           (negation->intersection)
           (intersection->triples)
           (map enclose-not))
      (for [neg negations]
        (->> (negation->intersection neg)
             (intersection->triples)
             (map enclose-not)
             (cons 'and))))))

;; TODO: full-text negations
(defn intersection->triples
  "Convert an `intersection` element from the search query AST into datalog
  triples to be used in a datalog query.

  Unions/or-clauses found within the intersection are set aside as metadata."
  [[_ & vs :as intersection]]
  (let [{:keys [text FIELD NEGATION UNION]} (group-by operand-type vs)]
    (cond-> []

      ;; NOTE: there's no explicit handling of INTERSECTION, as it is removed
      ;; during the assumed simplify call which converts the raw search query
      ;; parse tree into an AST.

      text (concat
             (for [s text]
               [(list 'fulltext '$ s) '[[?e ?a ?text]]])

             ;; Phrase matching is added for every multi-token search string.
             ;; This will ensure that entire phrases are matched, not just the
             ;; UNION of tokens in the search string which is what Datalevin
             ;; defaults to.
             (for [phrase (filter #(re-find #"\s" %) text)]
               [(list 'contains-phrase '?text phrase)]))

      ;; Fields are only included when they match a known field type.
      FIELD (concat (->> (for [[_ k v] FIELD]
                           (when-let [a (static/field->attribute k)]
                             ['?e a v]))
                         (remove nil?)))

      ;; Negations are just triple intersections enclosed with (not ...).
      NEGATION (add-negations NEGATION)

      ;; Unions/or-clauses are not included in the initial datalog query.
      ;; Instead, they are set aside for subsequent queries that will run
      ;; sequentially. The union of every query result set is returned instead.
      UNION (with-meta {:UNION UNION}))))

(defn build-query
  "Build a datalog query from a coll of `triples`."
  [triples]
  (into '[:find ?text ?e
          :in $
          :where
          [?e :bedebok/text ?text]]
        triples))

(defn search-intersection
  "Query the `db` for the `intersection-ast`."
  [db intersection-ast]
  (-> (intersection->triples intersection-ast)
      (build-query)
      (d/q db)))

(defn search-union
  "Query the `db` sequentially for each part of the `union-ast`, given a
  `preliminary-result` set of triples, e.g. from the existing search."
  [db preliminary-result [_ & vs :as union-ast]]
  (->> (for [v vs]
         [:INTERSECTION v])
       (map (partial search-intersection db))
       (apply set/union preliminary-result)
       (not-empty)))

;; Used to combine search results into their intersection (e.g. this AND that).
(defn result-intersection
  "Helper fn to combine search `results` and `more-results` in an intersection."
  [results more-results]
  (or (not-empty (if results
                   (set/intersection results more-results)
                   more-results))
      ;; Abort when the intersection is empty.
      (reduced nil)))

(defn execute-search-ast
  "Execute a search query `ast` in `db`."
  [db ast]
  (let [triples (intersection->triples ast)
        run     (fn [triples] (-> triples
                                  (build-query)
                                  (d/q db)))]
    (if-let [or-clauses (:UNION (meta triples))]
      (->> (map (partial search-union db (run triples)) or-clauses)
           (reduce result-intersection))
      (run triples))))

(defn search
  "Parse and execute a search `query` in `db`."
  [db query]
  (execute-search-ast db (search/query->ast query)))

(comment
  (search (d/db (d/get-conn db-path static/schema)) "NOT corresp:AM08-0073")
  (search (d/db (d/get-conn db-path static/schema)) "\"deme stole\" deme stole")
  (search (d/db (d/get-conn db-path static/schema)) "\"deme stasaole\" | corresp:AM08-0073")
  (search (d/db (d/get-conn db-path static/schema)) "\"syneme arme\" corresp:AM08-0073")
  (search (d/db (d/get-conn db-path static/schema)) "\"syneme arme\" | glen")

  (defn glen [x] "glen")


  (d/q '[:find ?text ?e
         :in $
         :where
         [?e :bedebok/text ?text]
         (or-join [?e ?text]
                  (and [(fulltext $ "deme stasaole") [[?e ?a ?text]]]
                       [(integer? ?text)])
                  (not [?e :tei/corresp "AM08-0073"]))]
       (d/db (d/get-conn db-path static/schema)))

  (d/q '[:find ?text ?e
         :in $
         :where
         [?e :bedebok/text ?text]
         (or-join [?e ?text]
                  (and [(fulltext $ "deme stasaole") [[?e _ ?text]]]
                       [(string? ?text)])
                  (not [?e :tei/corresp "AM08-0073"]))]
       (d/db (d/get-conn db-path static/schema)))

  (d/q '[:find ?text ?type ?e
         :in $
         :where
         [?e :bedebok/text ?text]
         [?e :bedebok/type ?type]
         [(string? ?text)]
         (not [?e :bedebok/type "text"])
         (or-join [?e]
                  [(fulltext $ "gasaslen") [[?e ?a ?text]]
                   (and [?e :tei/corresp "AM08-0073"]
                        [?e :tei/title "Magnificat"])])]
       (d/db (d/get-conn db-path static/schema)))





  (search/simplify (search/parse "geist "))
  (search/simplify (search/parse "AND geist | den"))
  (search/simplify (search/parse "geist & (field:value | NOT den)"))
  (search (d/db (d/get-conn db-path static/schema)) "AND geist fiel:glen")
  (search (d/db (d/get-conn db-path static/schema)) "AND geist & (den | den)")
  (xml-files files-path)
  (build-db! files-path db-path)

  (search-intersection (d/db (d/get-conn db-path static/schema))
                       ["grote den herren"])

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
    (search-intersection db ["Mary" "little lamb"]))







  ;; TODO: why isn't redef working?? I need to ignore English stop words
  ;; Combining full-text search with triple patterns
  (with-redefs [datalevin.analyzer/en-stop-words? (constantly false)]
    (let [db (-> (d/empty-db "/tmp/mydb"
                             {:bedebok/text {:db/valueType :db.type/string
                                             :db/fulltext  true}}
                             {:search-engine {:analyzer (dsu/create-analyzer {:tokenizer da/en-analyzer})}})
                 (d/db-with
                   [{:db/id        1,
                     :other        123
                     :bedebok/text "The quick red fox jumped over the lazy red dogs."}
                    {:db/id        2,
                     :other        456
                     :bedebok/text "Mary had a little lamb whose fleece was red as fire."}
                    {:db/id        3,
                     :other        789
                     :bedebok/text "Moby Dick is a story of a whale and a man obsessed."}]))]
      (d/q '[:find ?text ?e :in $ :where
             [?e :bedebok/text ?text]
             [(fulltext $ "red") [[?e ?a ?text]]]
             #_[(fulltext $ "as") [[?e ?a ?text]]]]
           db)
      #_(search db "was red")))

  (let [db (-> (d/empty-db "/tmp/glen"
                           {:text {:db/valueType :db.type/string
                                   :db/fulltext  true}}
                           #_{:search-engine {:analyzer (datalevin.search-utils/create-analyzer {:tokenizer datalevin.analyzer/en-analyzer})}})
               (d/db-with
                 [{:db/id 1
                   :text  "The quick red fox jumped over the lazy red dogs."}]))]
    (d/q '[:find ?text ?e
           :in $
           :where
           [?e :text ?text]
           [(fulltext $ "red") [[?e ?a ?text]]]]
         db))













  (d/q [:find ?text ?e :in $ :where [?e :bedebok/text ?text] [(fulltext $ "red") [[?e ?a ?text]]] [(fulltext $ "as") [[?e ?a ?text]]]]
       db)

  (do
    (d/close (d/get-conn db-path static/schema))
    (run! io/delete-file (reverse (file-seq (io/file db-path)))))
  #_.)
