(ns dk.cst.prayer.db
  "Database creation and search logic.

  For the code that parses search query language, see: 'dk.cst.prayer.search'.
  For the code that parses TEI, see: 'dk.cst.prayer.tei'."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.zip :as zip]
            [datalevin.core :as d]
            [datalevin.search-utils :as su]
            [taoensso.telemere :as t]
            [dk.cst.hiccup-tools.hiccup :as h]
            [dk.cst.hiccup-tools.match :refer [match]]
            [dk.cst.xml-hiccup :as xh]
            [dk.cst.prayer.tei.schema :as schema]
            [dk.cst.prayer.search :as search]
            [dk.cst.prayer.static :as static]
            [dk.cst.prayer.tei :as tei])
  (:import [java.io File]))

(defonce error-data
  (atom {}))

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

(def conn
  (delay (d/get-conn db-path static/schema search-opts)))

(defn- xml-files*
  [dir]
  (->> (file-seq (io/file dir))
       (remove (fn [^File f] (.isDirectory f)))
       (filter (fn [^File f] (str/ends-with? (.getName f) ".xml")))))

(defn xml-files
  "Fetch XML File objects recursively from a starting `dir`."
  [dir & dirs]
  (mapcat xml-files* (into [dir] dirs)))

(defn rmdir
  [dir]
  (->> (io/file dir)
       (file-seq)
       (reverse)
       (run! io/delete-file)))

(defn delete-db!
  "Close any previous db at `db-path` and remove the data."
  [db-path]
  (d/close @conn)
  (rmdir db-path))

(defn validate-tei-files
  "Validate `tei-files` against the TEI spec, only keeping the valid ones.
  Invalid files are kept as metadata along with the validation error message."
  [tei-files]
  (reduce (fn [acc file]
            (if-let [error (schema/validate-tei file)]
              (vary-meta acc update :error assoc (.getName file) error)
              (conj acc file)))
          ^{:error {}} []
          tei-files))

(defn- entity-summary
  [entity]
  {:bedebok/id (:bedebok/id entity)
   :keys       (sort (keys entity))})

(defn check-required-data!
  "Returns TRUE if the TEI `entity` has the required data. Any missing required
  data will be both logged and displayed on the db error page."
  [entity]
  (if-let [missing-data (-> (select-keys entity [:bedebok/id :bedebok/type])
                            (set/rename-keys {:bedebok/id   "xml:id"
                                              :bedebok/type "type"})
                            (->> (remove second))
                            (keys))]
    (do
      (t/log! {:level :warn
               :data  missing-data}
              (str (:file/name entity) " is missing required data. "
                   "It has been excluded from the database."))
      (doseq [field missing-data]
        (swap! error-data update-in [:other (:file/name entity)]
               conj (str "The document is missing the required data: " field)))
      false)
    true))

(defn- initial-pb?
  [node]
  (let [body         (h/get node :tei-body)
        pb-or-string (match #{(comp string? zip/node)
                              [:tei-pb]})]
    (not (string? (h/get body (match pb-or-string))))))

(defn check-missing-initial-pb!
  "Returns TRUE if the TEI `entity` isn't missing the initial <pb> tag."
  [entity]
  (if (and (= (:bedebok/type entity) "text")
           (not (initial-pb? (:file/node entity))))
    (do
      (t/log! {:level :warn
               :data  (entity-summary entity)}
              (str "The text body in " (:file/name entity)
                   " is missing an initial <pb> tag. "
                   "It has been excluded from the database."))
      (swap! error-data update-in [:other (:file/name entity)]
             conj (str "The text body is missing an initial <pb> tag."))
      false)
    true))

(def alphanumeric-id
  #"[0-9a-zA-ZæøåÆØÅ_-]+")

(defn check-illegal-id!
  "Returns TRUE if the TEI `entity` isn't missing the initial <pb> tag."
  [{:keys [file/name bedebok/id tei/corresp] :as entity}]
  (let [bad? #(and (string? %)
                   (not (re-matches alphanumeric-id %)))
        bad-id      (bad? id)
        bad-corresp (some bad? corresp)]
    (if (and (not bad-id) (not bad-corresp))
      true
      (do
        (when bad-id
          (t/log! {:level :warn
                   :data  (entity-summary entity)}
                  (str "The ID of " name " should be an alphanumeric string,"
                       " not " id ". "
                       "It has been excluded from the database."))
          (swap! error-data update-in [:other name]
                 conj (str "The xml:id should be an alphanumeric string.")))
        (when bad-corresp
          (t/log! {:level :warn
                   :data  (entity-summary entity)}
                  (str "The corresp attribute of " name " should be an alphanumeric string,"
                       " not " (str/join ", " corresp) ". "
                       "It has been excluded from the database."))
          (swap! error-data update-in [:other name]
                 conj (str "The corresp attribute should be an alphanumeric string.")))))))

(defn- get-duplicate-ids
  [entities]
  (->> (group-by :bedebok/id entities)
       (filter (comp #(> % 1) count second))
       (vals)
       (apply concat)
       (map :bedebok/id)
       (set)))

(defn build-db!
  [db-path files-path & files-paths]
  (io/make-parents db-path)
  (let [files    (validate-tei-files (apply xml-files files-path files-paths))
        entities (map tei/file->entity files)]

    ;; The first line of defence is validation based on the official TEI schema.
    ;; Invalid TEI is removed from the pipeline and added to the db error page.
    (when-let [error (some-> files meta :error not-empty)]
      (swap! error-data assoc :validation error)
      (t/log! {:level :warn
               :data  error}
              (str "TEI validation error: " (count error) " file(s) excluded")))

    (let [duplicate-ids (get-duplicate-ids entities)
          duplicate?    (comp duplicate-ids :bedebok/id)
          duplicates    (filter duplicate? entities)

          ;; Before transacting, any TEI entities without required data are
          ;; removed and their removal is logged on the db error page.
          entities'     (->> (remove duplicate? entities)
                             (filter check-required-data!)
                             (filter check-missing-initial-pb!)
                             (filter check-illegal-id!))]

      ;; Some TEI files might not have unique IDs, so they are excluded next.
      (doseq [duplicate duplicates]
        (swap! error-data assoc-in [:other (:file/name duplicate)]
               #{(str "The document has a duplicate xml:id: " (:bedebok/id duplicate))}))
      (t/log! {:level :warn
               :data  {:affected-ids duplicate-ids
                       :duplicates   (map entity-summary duplicates)}}
              (str "Duplicate IDs found: " (count duplicates) " files(s) excluded."))

      ;; Before transacting we present an overview of the validated files.
      (t/log! {:level :info
               :data  (update-vals (group-by :bedebok/type entities')
                                   (comp sort #(map :file/name %)))}
              (str (count entities') " documents are ready to be transacted ("
                   (- (count entities)
                      (count entities')) " documents were excluded)."))

      ;; Files are transacted one at a time to allow for a partial success,
      (doseq [entity entities']
        (t/log! {:level :info
                 :data  (entity-summary entity)}
                (str "Transacting entity: " (:bedebok/id entity)))
        (d/transact! @conn [entity])))))

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
  "Normalize user input `v` to canonical forms for use in database queries."
  [a v]
  (let [abbr (str/upper-case v)]
    (or
      ;; Look up either by long-form label or by the canonical abbreviation.
      ;; NOTE: it is the abbreviated form that is returned in either case!
      (and (get-in static/labels [a abbr]) abbr)            ; by abbreviation
      (get-in field-by-label [a (str/lower-case v)])        ; by long-form

      ;; The fallback (i.e. no match) is to return the raw value.
      v)))

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
      UNION (with-meta {:UNION UNION}))))

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
  (if-let [query (some->> triples (remove nil?) not-empty build-query)]
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
       ;; Since we only accept entities with a type and id, but currently allow
       ;; index other types of entities, we have to remove all nil results.
       (remove nil?)
       (not-empty)))

(comment
  (search (d/db @conn) "herren")
  (search (d/db @conn) "NOT corresp:AM08-0073")
  (search (d/db @conn) "NOT (corresp:AM08-0073)")
  (search (d/db @conn) "\"deme stole\" deme stole")
  (search (d/db @conn) "\"deme stasaole\" | corresp:AM08-0073")
  (search (d/db @conn) "\"syneme arme\" corresp:AM08-0073")
  (search (d/db @conn) "\"syneme arme\" | glen")


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
  (build-db! db-path files-path)

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
