(ns dk.cst.prayer.tei
  "Patterns/functions for scraping data from the project TEI files."
  (:require [clojure.string :as str]
            [clojure.zip :as zip]
            [clojure.walk :as walk]
            [dk.cst.hiccup-tools.elem :as elem]
            [dk.cst.xml-hiccup :as xh]
            [dk.cst.hiccup-tools.hiccup :as h]
            [dk.cst.hiccup-tools.zip :as z]
            [dk.cst.hiccup-tools.match :as match :refer [match]]
            [clojure.java.io :as io])
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

   [[:locus {:from true
             :to   true}]
    (fn [node]
      (let [{:keys [from to corresp]} (elem/attr node)]
        (cond-> {:tei/from from}
          to (assoc :tei/to to)
          corresp (assoc :tei/corresp corresp))))]

   [[:textLang {:mainLang true}]
    (fn [node]
      (let [{:keys [mainLang]} (elem/attr node)]
        {:tei/mainLang mainLang}))]

   ;; Capture references to canonical works.
   [:title
    (fn [node]
      (let [{:keys [key]} (elem/attr node)]
        ;; TODO: what about the title label?
        (when key
          {:tei/key key})))]

   [:rubric
    (fn [node]
      {:tei/rubric [(h/hiccup->text node tei-conversion)]})]

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

(def tei-search-kvs
  "The core [matcher process] kvs for initiating a TEI data search."
  [[(with-meta (match :TEI) {:on-match :continue})
    (fn [node]
      (let [{:keys [xml/id type]} (elem/attr node)]
        {:bedebok/id   id
         :bedebok/type type}))]

   [:title
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
      (let [{:keys [material]} (elem/attr node)]
        {:tei/material material}))]

   [:support
    (inner-text :tei/support)]

   ;; TODO: what is this value? what should be done about it?
   #_[:extent
      (fn [node])]

   #_[:dimensions]

   [:height
    (inner-text :tei/dimensions :tei/height)]

   [:width
    (inner-text :tei/dimensions :tei/width)]

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

   [(match :item (match/has-parent (match :list (match/has-parent :collation))))
    [:tei/collationItem collationItem-search-kvs]]

   ;; Capture the attributes from <msItem>.
   ;; As this relies on special behaviour, it should take place before the
   ;; sub-search defined below as that removes the tree from the current search.
   [(with-meta (match :msItem) {:on-match :continue})
    (fn [node]
      (let [{:keys [class]} (elem/attr node)
            class' (attr-parts class)]
        (when class'
          {:tei/class class'})))]

   ;; Marks the sub-search of a variable depth tree of <msItem> elements.
   [(match :msItem (match/has-parent :msContents))
    [:tei/msItem msItem-search-kvs]]])

(def tei-html
  ;; Structural changes that emit valid HTML go here.
  {:single [[:teiHeader zip/remove]
            [:lb (fn [loc] (let [[_ & rem] (zip/node loc)]
                             (zip/replace loc (into [:br] rem))))]

            ;; Anything not matched above is turned into custom HTML elements.
            [(match/any) z/html-safe]]

   ;; Other changes go here (NOTE: outer matchers must match custom elements!)
   :multi  {#{:tei-w :tei-pc} (fn [loc]
                                (if-let [right (zip/right loc)]
                                  (if ((match/tag :pc) right)
                                    loc
                                    (zip/insert-right loc " "))
                                  loc))}})

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
                             ;; TODO: better exception handling when type doesn't match
                             ;;       e.g. strings being merged.
                             (if (sequential? v1)
                               (into v1 v2)
                               (merge v1 v2))))

         ;; Keep Hiccup for every component for display purposes.
         ;; TODO: should recursive subsearches be removed from parent? (duplication)
         (merge {:file/node (binding [z/*custom-element-prefix* "tei"]
                              (h/reshape hiccup tei-html))}))))

(defn file->entity
  "Convert a `file` into a Datom entity based on `search-kvs`."
  [^File file]
  (merge (hiccup->entity (xh/parse file) tei-search-kvs)
         {#_#_:file/src (slurp file)
          :file/name (.getName file)}))

(defn dev-view
  "Recursively remove clutter from entity `m` (for development)."
  [m]
  (walk/postwalk
    #(if (map? %)
       (dissoc % :file/node :tei/text)
       %)
    m))

(comment
  (tei-description (tei-ref :textLang))
  (tei-description (tei-ref :rubric))

  ;; test text conversion on a full HTML document
  (-> (tei-ref :sourceDesc)
      (xh/parse)
      (h/hiccup->text h/html-conversion))

  ;; test text conversion on a TEI body
  ;; compare: https://github.com/bedebok/Data/blob/main/Prayers/org/AM08-0073_237v.org
  (binding [z/*custom-element-prefix* "tei"]
    (-> (io/file "test/Data/Prayers/xml/AM08-0073_237v.xml")
        (xh/parse)
        (h/reshape tei-html)))

  ;; Metadata retrieval from a document
  ;; compare: https://github.com/bedebok/Data/blob/main/Prayers/org/AM08-0073_237v.org
  (-> (io/file "test/Data/Prayers/xml/AM08-0073_237v.xml")
      (xh/parse)
      (hiccup->entity tei-search-kvs)
      (dev-view))

  ;; TODO: "test/Data/Prayers/xml/AM08-0073_237v.xml" and  "test/Data/Catalogue/xml/AM08-0073.xml" use the same ID!!
  ;; Triple generation from a document
  ;; compare: https://github.com/bedebok/Data/blob/main/Prayers/org/AM08-0075_063r.org
  (-> (io/file "test/Data/Catalogue/xml/AM08-0073.xml")
      (xh/parse)
      (hiccup->entity tei-search-kvs)
      (dev-view))
  #_.)

