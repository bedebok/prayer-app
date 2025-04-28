(ns dk.cst.prayer.tei
  "Patterns/functions for scraping data from the project TEI files."
  (:require [clojure.string :as str]
            [clojure.zip :as zip]
            [clojure.java.io :as io]
            [clojure.walk :as walk]
            [taoensso.telemere :as t]
            [dk.cst.hiccup-tools.elem :as elem]
            [dk.cst.xml-hiccup :as xh]
            [dk.cst.hiccup-tools.hiccup :as h]
            [dk.cst.hiccup-tools.zip :as z]
            [dk.cst.hiccup-tools.match :as match :refer [match]])
  (:import [java.io File FileNotFoundException]))

(defn tei-ref
  [tag]
  (let [url  (str "https://tei-c.org/release/doc/tei-p5-doc/en/html/ref-"
                  (if (string? tag)
                    tag
                    (name tag))
                  ".html")
        html (try
               (slurp url)
               (catch FileNotFoundException _))]
    html))

(defn tei-description
  [html]
  (some-> (xh/parse html)
          (h/get (match/has-child [:span {:class "label" :xml/lang nil}]))
          (h/hiccup->text h/html-text)))

(defn attr-parts
  "Explode an `attr-val` into its constituent parts."
  [attr-val]
  (when attr-val
    (str/split (str/trim attr-val) #"\s+")))

;; TODO: punctuation not working great, maybe postprocess?
(def tei-conversion
  {:single      {:teiHeader      zip/remove
                 #{:head :p :pb} z/surround-lb
                 :w              z/insert-space
                 :lb             z/append-lb}
   :postprocess str/trim})

(def tei-html
  ;; Structural changes that emit valid HTML go here (exits after first match).
  {:single [[:teiHeader zip/remove]
            [:lb (fn [loc] (let [[_ & rem] (zip/node loc)]
                             (zip/replace loc (into [:br] rem))))]

            ;; Anything not matched above is turned into custom HTML elements.
            [(complement (match #{:ruby :rt})) z/html-safe]]

   ;; Other changes go below (all matchers are tested and possibly applied).
   ;; NOTE: outer elements must match custom element prefix, i.e. tei- and the
   ;;       attributes must be HTML-safe data-* attributes.
   :multi  [[#{:tei-w :tei-pc} (fn [loc]
                                 (if-let [right (zip/right loc)]
                                   (if ((match/tag :pc) right)
                                     loc
                                     (zip/insert-right loc " "))
                                   loc))]
            [:tei-w (fn [loc]
                      (let [w         (zip/node loc)
                            {:keys [data-lemma
                                    data-pos]} (elem/attr w)
                            data-pos' (when data-pos
                                        (str/replace data-pos #"HiNTS=" ""))
                            title     (not-empty (str/join " | " [data-lemma data-pos']))]
                        ;; The combination of zip/insert-left and zip/remove
                        ;; basically results in a replace-and-skip operation
                        ;; when used in conjunction with a zip/next loop.
                        (-> loc
                            (zip/insert-left [:ruby.token {:title title}
                                              [:ruby
                                               (binding [z/*custom-element-prefix* "tei"]
                                                 (h/reshape w tei-html))
                                               [:rt.lemma (or (not-empty data-lemma) "□")]]
                                              [:rt.pos (or (not-empty data-pos') "□")]])
                            (zip/remove))))]]})

(defn hiccup->entity
  "Convert TEI `hiccup` into an Datom entity based on a `search-kvs`."
  [hiccup search-kvs]
  (let [matchers (map-indexed (fn [n [matcher _]]
                                [n (match matcher)]) search-kvs)
        fns      (map-indexed (fn [n [_ process]] [n process]) search-kvs)
        result   (h/search hiccup matchers :on-match :skip-tree)]
    (->> fns

         (mapcat (fn [[k process]]
                   (when-let [matches (get result k)]
                     (cond
                       ;; Process functions take matched nodes and produce maps.
                       ;; These maps are assumed to be attributes & values of the
                       ;; same entity and will be merged later.
                       (fn? process)
                       (map process matches)

                       ;; Special vectors mark sub-searches in the matched node.
                       ;; This solves the issue of matching e.g. list items that
                       ;; are not distinguished by an ID such as msItem.
                       ;; Sub-searches result in component data keyed to the
                       ;; first item in the vector; the second item are the kvs
                       ;; to be used for the sub-search OR the special symbol
                       ;; 'recursive when the current kvs should be reused.
                       (vector? process)
                       (let [[k v] process]
                         ;; This special symbol marks recursive sub-searches as
                         ;; you can't do direct self-references in Clojure data.
                         (if (= v 'recursive)
                           [{k (mapv #(hiccup->entity % search-kvs) matches)}]
                           [{k (mapv #(hiccup->entity % v) matches)}]))))))

         ;; The entity and its components are merged into a single data structure.
         ;; This data can be transacted into a Datomic-compatible db
         ;; e.g. Datalevin in our case.
         (apply merge-with (fn [v1 v2]
                             (cond
                               (and (sequential? v1) (sequential? v2))
                               (into v1 v2)

                               (and (map? v1) (map? v2))
                               (merge v1 v2)

                               :else
                               (do
                                 (t/log! {:level :error
                                          :data  {:merge-params [v1 v2]
                                                  :file-meta    (meta hiccup)}}
                                         (str
                                           "Attempted merge of unsupported data types, "
                                           "likely because a TEI pattern meant to find a single occurrence has matched several."))

                                 ;; Return empty map to continue the operation.
                                 {}))))

         ;; Keep Hiccup for every component for display purposes.
         ;; TODO: should recursive subsearches be removed from parent? (duplication)
         (merge {:file/node (binding [z/*custom-element-prefix* "tei"]
                              (h/reshape hiccup tei-html))}))))


(defn inner-text
  "Store the inner text of the matched node under `k`."
  [k & [k2]]
  (fn [node]
    (when-let [text (h/hiccup->text node tei-conversion)]
      (if k2
        {k {k2 text}}
        {k text}))))

;; Since the order matters, this part of the search is written as kvs
(def msItem-search-kvs
  "The [matcher process] kvs for doing a recursive sweep of <msItem> elements."
  [;; Capture the attributes from <msItem> itself.
   ;; This should take place before the recursive sub-search defined below.
   [(with-meta (match :msItem) {:on-match :continue})
    (fn [node]
      (let [{:keys [class]} (elem/attr node)
            class' (attr-parts class)]
        (when class'
          {:tei/class class'})))]

   ;; Continues the same sub-search if other <msItem> elements are found inside
   ;; the parent <msItem> element.
   [(match :msItem (match/has-parent :msItem))
    [:tei/msItem 'recursive]]

   [:note
    (inner-text :tei/note)]

   [[:locus {:from true
             :to   true}]
    (fn [node]
      (let [{:keys [from to corresp]} (elem/attr node)]
        (cond-> {:tei/from from}
          to (assoc :tei/to to)
          corresp (assoc :tei/corresp corresp))))]

   [[:textLang {:mainLang true}]
    (fn [node]
      (let [{:keys [mainLang otherLangs]} (elem/attr node)]
        (if otherLangs
          {:tei/mainLang   mainLang
           :tei/otherLangs (set (str/split (str/trim otherLangs) #"\s"))}
          {:tei/mainLang mainLang})))]

   ;; Capture references to canonical works.
   [:title
    (fn [node]
      (let [{:keys [key]} (elem/attr node)
            title (first (elem/children node))]
        (when key
          {:bedebok/work {:tei/key   key
                          :tei/title (or title key)}})))]

   ;; TODO: should also extract the <persName> inside the text
   [:author
    (fn [node]
      (let [{:keys [key]} (elem/attr node)
            title (first (elem/children node))]
        (when key
          {:tei/author {:tei/key   key
                        :tei/title (or title key)}})))]

   [:rubric
    (fn [node]
      {:tei/rubric [(h/hiccup->text node tei-conversion)]})]

   ;; TODO: also register xml:lang and type, see: AM08-0073.xml
   [:incipit
    (fn [node]
      {:tei/incipit [(h/hiccup->text node tei-conversion)]})]

   [:explicit
    (fn [node]
      {:tei/explicit [(h/hiccup->text node tei-conversion)]})]])

(def collationItem-search-kvs
  "The [matcher process] kvs for doing a recursive sweep of <item> elements
  found inside the <collation><list>...</list></collation> structure."
  [;; Capture the attributes from <item> itself.
   ;; This should take place before the recursive sub-search defined below.
   [(with-meta (match :item) {:on-match :continue})
    (fn [node]
      (let [{:keys [n]} (elem/attr node)]
        (when n
          {:tei/n n})))]

   ;; Continues the same sub-search if other <item> elements are found inside
   ;; the parent <item> element.
   [(match :item (match/has-parent :list))
    [:tei/collationItem 'recursive]]

   [[:locus {:from true
             :to   true}]
    (fn [node]
      (let [{:keys [from to]} (elem/attr node)]
        (cond-> {:tei/from from}
          to (assoc :tei/to to))))]

   [:desc
    (fn [node]
      {:tei/desc (h/hiccup->text node tei-conversion)})]])

(def respStmt-search-kvs
  [[:resp
    (fn [node]
      (let [{:keys [when]} (elem/attr node)]
        (cond-> {:tei/resp (h/hiccup->text node tei-conversion)}
          when (assoc :tei/when when))))]
   [[:persName {:key true}]
    (fn [node]
      (let [{:keys [key]} (elem/attr node)]
        {:tei/persName (h/hiccup->text node tei-conversion)
         :tei/key      key}))]])

(def tei-search-kvs
  "The core [matcher process] kvs for initiating a TEI data search."
  [[(with-meta (match :TEI) {:on-match :continue})
    (fn [node]
      (let [{:keys [xml/id type]} (elem/attr node)]
        {:bedebok/id   id
         :bedebok/type type}))]

   ;; Only matches the document title itself.
   [(match :title (match/has-parent :titleStmt))
    (inner-text :tei/title)]

   ;; Only used for human-readable shelfmarks, e.g. for displaying on a website.
   [:idno
    (fn [node]
      (let [idno (first (elem/children node))
            {:keys [corresp]} (elem/attr node)]
        (if corresp
          {:tei/corresp corresp}
          {:tei/idno idno})))]

   [:head
    (inner-text :tei/head)]

   [:provenance
    (inner-text :tei/provenance)]

   [:summary
    (inner-text :tei/summary)]

   ;; We first capture the description as text; the data inside comes after.
   [(with-meta (match :origin) {:on-match :continue})
    (inner-text :tei/origin)]

   [(match [:origPlace {:key true}] (match/has-parent :origin))
    (fn [node]
      (let [{:keys [key]} (elem/attr node)
            title (first (elem/children node))]
        {:tei/origPlace {:tei/key   key
                         :tei/title title}}))]

   [(match :origDate (match/has-parent :origin))
    (fn [node]
      (let [attr  (elem/attr node)
            title (first (elem/children node))]
        ;; TODO: discover finite list of attr keys, add them to the schema
        {:tei/origDate (-> attr
                           (update-keys (fn [k] (keyword "tei" (name k))))
                           (merge {:tei/title title}))}))]

   [(with-meta (match [:supportDesc {:material true}]) {:on-match :continue})
    (fn [node]
      (let [{:keys [material]} (elem/attr node)
            support (->> (elem/children node)
                         (filter #(and (vector? %)
                                       (= :support (first %))))
                         (first))]
        {:tei/supportDesc {:tei/support  (h/hiccup->text support tei-conversion)
                           :tei/material material}}))]

   [:acquisition
    (inner-text :tei/acquisition)]

   #_[:support
      (inner-text :tei/support)]

   ;; TODO: what is this value? what should be done about it?
   #_[:extent
      (fn [node])]

   #_[:dimensions]

   [:height
    (inner-text :tei/dimensions :tei/height)]

   [:width
    (inner-text :tei/dimensions :tei/width)]

   [:attDef
    (fn [node]
      (let [{:keys [ident]} (elem/attr node)
            desc (h/hiccup->text node tei-conversion)]
        {:bedebok/process {(keyword ident) desc}}))]

   [(match [:settlement {:key true}] (match/has-parent :msIdentifier))
    (fn [node]
      (let [{:keys [key]} (elem/attr node)]
        {:tei/settlement key}))]

   [(match [:repository {:key true}] (match/has-parent :msIdentifier))
    (fn [node]
      (let [{:keys [key]} (elem/attr node)]
        {:tei/repository key}))]

   [[:name {:type true :key true}]
    (fn [node]
      (let [{:keys [type key]} (elem/attr node)
            label (h/hiccup->text node tei-conversion)]
        (when (and key type)
          ;; NOTE: upserts require both composite & constituent parts!
          {:tei/named [(cond-> {:tei/entity {:tei/name+type [key type]
                                             :tei/name      key
                                             :tei/type      type}}
                         label (assoc :bedebok/label label))]})))]

   ;; TODO: ensure that these exist in prototypical files
   ;; These sentence references seem to be another type of named entity.
   [(with-meta (match [:s {:n true :type true}]) {:on-match :continue})
    (fn [node]
      (let [{:keys [type n]} (elem/attr node)
            label (h/hiccup->text node tei-conversion)]
        ;; NOTE: upserts require both composite & constituent parts!
        {:tei/named [(cond-> {:tei/entity {:tei/name+type [key type]
                                           :tei/name      key
                                           :tei/type      type}}
                       label (assoc :bedebok/label label))]}))]

   ;; The raw document text is included to facilitate full-text search.
   ;; This is enabled in the db schema definition for :tei/text.
   ;; Note that search continues after matching the <text> node!
   [(with-meta (match :text) {:on-match :continue})
    (fn [node]
      (when-let [text (not-empty (h/hiccup->text node tei-conversion))]
        {:bedebok/text text}))]

   ;; Capture the attributes from <msItem>.
   ;; As this relies on special behaviour, it should take place before the
   ;; sub-search defined below as that removes the tree from the current search.
   [(with-meta (match :msItem) {:on-match :continue})
    (fn [node]
      (let [{:keys [class]} (elem/attr node)
            class' (attr-parts class)]
        (when class'
          {:tei/class class'})))]

   [:respStmt
    (fn [node]
      {:tei/respStmt (hiccup->entity node respStmt-search-kvs)})]

   ;; BELOW: sub-searches of a variable depth tree of various elements.
   [(match :msItem (match/has-parent :msContents))          ; recursive
    [:tei/msItem msItem-search-kvs]]

   [(match :item (match/has-parent (match :list (match/has-parent :collation)))) ; recursive
    [:tei/collationItem collationItem-search-kvs]]])

(defn file->entity
  "Convert a `file` into a Datom entity based on `search-kvs`."
  [^File file]
  (-> (xh/parse file {:file-meta {:path :absolute}})
      (hiccup->entity tei-search-kvs)
      (merge {:file/name (.getName file)})))

(defn dev-view
  "Recursively remove clutter from entity `m` (for development)."
  [m]
  (walk/postwalk
    #(if (map? %)
       (dissoc % :file/node :tei/text)
       %)
    m))

(comment
  (tei-description (tei-ref :respStmt))
  (tei-description (tei-ref :supportDesc))

  ;; test text conversion on a full HTML document
  (-> (tei-ref :sourceDesc)
      (xh/parse)
      (h/hiccup->text h/html-conversion))

  ;; test text conversion on a TEI body
  ;; compare: https://github.com/bedebok/Data/blob/main/Prayers/org/AM08-0073_237v.org
  (binding [z/*custom-element-prefix* "tei"]
    (-> (io/file "../Data/Gold corpus/AM08-0073_237v.xml")
        (xh/parse)
        (h/reshape tei-html)))

  ;; Metadata retrieval from a document
  ;; compare: https://github.com/bedebok/Data/blob/main/Prayers/org/AM08-0073_237v.org
  (-> (io/file "../Data/Gold corpus/AM08-0073_237v.xml")
      (xh/parse)
      (hiccup->entity tei-search-kvs)
      (dev-view))

  (file->entity (io/file "../Data/Gold corpus/MAGNIFICAT.xml"))

  ;; TODO: "../Data/Gold corpus/AM08-0073_237v.xml" and  "../Data/Gold corpus/AM08-0073.xml" use the same ID!!
  ;; Triple generation from a document
  ;; compare: https://github.com/bedebok/Data/blob/main/Prayers/org/AM08-0075_063r.org
  (-> (io/file "../Data/Gold corpus/AM08-0073.xml")
      (xh/parse)
      (hiccup->entity tei-search-kvs)
      (dev-view))
  #_.)

