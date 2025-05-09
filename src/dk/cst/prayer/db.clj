(ns dk.cst.prayer.db
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [datalevin.core :as d]
            [datalevin.analyzer :as da]
            [datalevin.search-utils :as su]
            [taoensso.telemere :as t]
            [dk.cst.xml-hiccup :as xh]
            [dk.cst.prayer.tei.schema :as schema]
            [dk.cst.prayer.search :as search]
            [dk.cst.prayer.static :as static]
            [dk.cst.prayer.tei :as tei])
  (:import [java.io File]))

;; The db-path and files-path values are meant only for the dev environment.
;; In the production server they are replaced with hardcoded values as part of
;; executing the -main function.
(def db-path
  "test/db")                                                ; dev value

(def files-path
  "../Data/Gold corpus")                                    ; dev value

;; This custom token analyzer foregoes any kind of stop words, as they interfere
;; with phrase search (see comments in issue #19). This code is based roughly on
;; https://clojurians.slack.com/archives/C01RD3AF336/p1708690854918189
;; I initially tried modifying the en-analyzer fn, but just couldn't get it to
;; work. This was a lot simpler. If the punctuation regex below is too lax or
;; aggressive, that too can be easily fixed at a later date.
(def analyzer
  (su/create-analyzer
    {:tokenizer     (su/create-regexp-tokenizer #"[\s:/\.;,!=?\"'()\[\]{}|<>&@#^*\\~`]+")
     :token-filters [su/lower-case-token-filter]}))

;; https://github.com/juji-io/datalevin/blob/master/doc/search.md#search-domains
(def search-opts
  {:search-domains {"datalevin" {:index-position? true
                                 :analyzer        analyzer}}})

(defn get-conn
  []
  (d/get-conn db-path static/schema search-opts))

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

(defn delete-db!
  "Close any previous db at `db-path` and remove the data."
  [db-path]
  (d/close (get-conn))
  (rmdir db-path))

(defn validate-files
  [files]
  (reduce (fn [acc file]
            (if-let [error (schema/validate-tei file)]
              (vary-meta acc update :error assoc (.getName file) error)
              (conj acc file)))
          ^{:error {}} []
          files))

(defn build-db!
  [files-path db-path]
  (io/make-parents db-path)
  (let [files    (validate-files (xml-files files-path))
        entities (map tei/file->entity files)
        conn     (get-conn)]
    (when-let [error (some-> files meta :error not-empty)]
      (t/log! {:level :warn
               :data  error}
              (str "TEI validation error: " (count error) " file(s) excluded")))
    (t/log! {:level :info
             :data  (update-vals (group-by :bedebok/type entities)
                                 (comp sort #(map :file/name %)))}
            (str "Populating database with " (count entities) " documents."))
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

;; TODO: remove this now that the built-in phrase search is in use?
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
  (let [{:keys [path-triple?]} (meta triple)]
    ;; Path triples shouldn't be negated, only the final value triple should!
    (if path-triple?
      triple
      (list 'not triple))))

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

(def field-by-label
  (update-vals static/labels #(update-keys (set/map-invert %) str/lower-case)))

;; TODO: accept Danish labels too?
(defn fuzzy-value
  "Attempt to match a fuzzy input value `v` of the attribute `a` to an already
  known value of that attribute."
  [a v]
  (let [v' (str/upper-case v)]
    (or
      (and (get-in static/labels [a v']) v')                ; short-form
      (get-in field-by-label [a (str/lower-case v)])        ; long-form (label)
      v)))                                                  ; raw value

(def field->attribute
  (comp static/field->attribute str/lower-case))

(defn path->triples
  "Convert a property `path` into triples."
  [path]
  (loop [rem-path path
         triples  []]
    (if (> (count rem-path) 3)
      ;; The triples that form a path leading to the final triple containing the
      ;; value are marked such that negations will work properly, i.e. only the
      ;; final triple should ever be negated (see: enclose-not).
      (let [path-triple (with-meta (subvec rem-path 0 3) {:path-triple? true})]
        (recur (subvec rem-path 2) (conj triples path-triple)))
      (conj triples rem-path))))

;; TODO: full-text negations
(defn intersection->triples
  "Convert an `intersection` element from the search query AST into datalog
  triples to be used in a datalog query.

  There's no explicit handling of INTERSECTION, as that is removed during the
  assumed preceding call to 'simplify' which converts the raw search query parse
  tree into an AST.

  UNION/or-clauses found within the intersection are set aside as metadata."
  [[_ & vs :as intersection]]
  (let [{:keys [text FIELD NEGATION UNION]} (group-by operand-type vs)]
    (cond-> []

      ;; Text search is simplified in the code, by turning every single fulltext
      ;; call into an explicit phrase search. This may have some performance
      ;; drawbacks (I'm not sure), but I don't think it matters in our case.
      text (concat
             (for [s text]
               ;; All fulltext attributes participate in the "datalevin" domain.
               [(list 'fulltext '$ {:phrase s} {:domains ["datalevin"]}) '[[?e ?a ?text]]]))

      ;; Fields are only included when they match a known field type.
      FIELD (concat (->> (for [[_ k v] FIELD]
                           (when-let [a (field->attribute k)]
                             (if (vector? a)
                               (path->triples (conj a (fuzzy-value a v)))
                               [['?e a (fuzzy-value a v)]])))
                         (remove nil?)
                         (apply concat)))

      ;; Negations are just triple intersections enclosed with (not ...).
      NEGATION (add-negations NEGATION)

      ;; Unions/or-clauses are not included in the initial datalog query.
      ;; Instead, they are set aside for subsequent queries that will run
      ;; sequentially. The union of every query result set is returned instead.
      UNION (with-meta {:UNION UNION})

      ;; In cases where we end up with no search triples, we return nil.
      true (->> (remove nil?) (not-empty)))))

(defn build-query
  "Build a datalog query from a coll of `triples`."
  [triples]
  (into '[:find ?text ?e
          :in $ %
          :where
          ;; Note that these required triples essentially limit the results
          ;; to certain results, e.g if we wanted manuscript items to be
          ;; directly searchable too we would need to modify this query.
          ;; TODO: add more fulltext attributes
          (or [?e :bedebok/text ?text]                      ; for texts/works
              [?e :tei/head ?text])]                        ; for manuscripts
        triples))

(def manuscript-ancestor-rule
  '[[(ancestor ?msItem ?ancestor)
     [?ancestor :tei/msItem ?msItem]]
    [(ancestor ?msItem ?ancestor)
     [?parent :tei/msItem ?msItem]
     (ancestor ?parent ?ancestor)]])


(defn run-search
  [db triples]
  (if-let [query (some-> triples not-empty build-query)]
    (do
      (t/log! {:level :info :data query} "Executed datalog query.")
      ;; We execute two separate queries and return the union of the results.
      ;; One of the queries contains a rule that assumes the existence of a
      ;; :tei/msItem attribute, which isn't the case with e.g. work entities,
      ;; thus requiring the two separate queries to run.
      (set/union
        (d/q (conj query '(ancestor ?msItem ?e)) db manuscript-ancestor-rule)
        (d/q query db [])))
    #{}))

(defn search-intersection
  "Query the `db` for the `intersection-ast`."
  [db intersection-ast]
  (run-search db (intersection->triples intersection-ast)))

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
  (let [triples        (intersection->triples ast)
        initial-result (run-search db triples)]
    (if-let [or-clauses (:UNION (meta triples))]
      (->> (map (comp (partial search-union db initial-result)) or-clauses)
           (reduce result-intersection))
      initial-result)))

(defn search
  "Parse and execute a search `query` in `db`."
  [db query]
  (->> (search/query->ast query)
       (execute-search-ast db)
       (map (fn [[?text ?e]]
              (d/pull db [:bedebok/type :bedebok/id] ?e)))
       (not-empty)))

(comment
  (search (d/db (d/get-conn db-path static/schema)) "class=antiphone")
  (search (d/db (d/get-conn db-path static/schema)) "NOT corresp:AM08-0073")
  (search (d/db (d/get-conn db-path static/schema)) "\"deme stole\" deme stole")
  (search (d/db (d/get-conn db-path static/schema)) "\"deme stasaole\" | corresp:AM08-0073")
  (search (d/db (d/get-conn db-path static/schema)) "\"syneme arme\" corresp:AM08-0073")
  (search (d/db (d/get-conn db-path static/schema)) "\"syneme arme\" | glen")


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

  (do
    (d/close (d/get-conn db-path static/schema))
    (run! io/delete-file (reverse (file-seq (io/file db-path)))))
  #_.)
