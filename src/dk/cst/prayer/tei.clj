(ns dk.cst.prayer.tei
  (:require [clojure.string :as str]
            [clojure.zip :as zip]
            [dk.cst.hiccup-tools.elem :as elem]
            [dk.cst.xml-hiccup :as xh]
            [dk.cst.hiccup-tools.hiccup :as h]
            [dk.cst.hiccup-tools.zip :as z]
            [dk.cst.hiccup-tools.match :as match :refer [match]]
            [clojure.java.io :as io]))

(defn tei-ref
  [tag]
  (let [url  (str "https://tei-c.org/release/doc/tei-p5-doc/en/html/ref-"
                  (if (string? tag)
                    tag
                    (name tag))
                  ".html")
        html (slurp url)]
    html))

(defn tei-description
  [html]
  (-> (xh/parse html)
      (h/get (match/has-child
               (match/hiccup [:span {:class "label" :xml/lang nil}])))
      (h/hiccup->text h/html-conversion)))

;; TODO: punctuation not working great, maybe postprocess?
(def tei-conversion
  {:conversions {:teiHeader      zip/remove
                 #{:head :p :pb} z/surround-lb
                 :w              z/insert-space
                 :lb             z/append-lb}
   :postprocess str/trim})

;; Since the order matters, this part of the search is written as kvs
(def msItem-search-kvs
  "The [matcher process] kvs for doing a recursive sweep of <msItem> elements."
  [[(match :msItem
           (match/has-parent (match/tag :msItem)))
    [:tei/msItem 'recursive]]

   ;; TODO: should also match {:to true}, but currently the TEI files have errors
   [[:locus {:from true}]
    (fn [node]
      (let [{:keys [from to]} (elem/attr node)]
        (cond-> {:tei/from from}
          to (assoc :tei/to to))))]

   [[:title {:xml/id true}]
    (fn [node]
      (let [{:keys [xml/id]} (elem/attr node)
            title (first (elem/children node))]
        {:tei/title title
         :xml/id    id}))]

   [[:textLang {:mainLang true}]
    (fn [node]
      (let [{:keys [mainLang]} (elem/attr node)]
        {:tei/mainLang mainLang}))]])

(def manuscript-search-kvs
  "The core [matcher process] kvs for initiating a TEI data search."
  [[(match [:idno {:xml/id true}]
           (match/has-parent (match/tag :msIdentifier)))
    (fn [node]
      (let [{:keys [xml/id]} (elem/attr node)]
        ;; TODO: do this properly
        {:xml/id id}))]

   [(match [:settlement {:key true}]
           (match/has-parent (match/tag :msIdentifier)))
    (fn [node]
      (let [{:keys [key]} (elem/attr node)]
        {:tei/settlement key}))]

   [(match [:repository {:key true}]
           (match/has-parent (match/tag :msIdentifier)))
    (fn [node]
      (let [{:keys [key]} (elem/attr node)]
        {:tei/repository key}))]

   [[:name {:type true :key true}]
    (fn [node]
      (let [{:keys [type key]} (elem/attr node)
            label (h/hiccup->text node tei-conversion)]
        ;; NOTE: upserts require both composite & constituent parts!
        {:tei/named [{:tei/entity {:tei/name+type [key type]
                                   :tei/name      key
                                   :tei/type      type}
                      :tei/label  label}]}))]

   ;; Marks the sub-search of a variable depth tree of <msItem> elements.
   [(match :msItem
           (match/has-parent (match/tag :msContents)))
    [:tei/msItem msItem-search-kvs]]])

(defn hiccup->entity
  "Convert TEI `hiccup` into an Datom entity based on a `search-kvs`."
  [hiccup search-kvs]
  (let [matchers (map-indexed (fn [n [matcher _]]
                                [n (match matcher)]) search-kvs)
        fns      (map-indexed (fn [n [_ process]] [n process]) search-kvs)
        result   (h/search hiccup matchers :exhaustive false)]
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
                             (if (sequential? v1)
                               (into v1 v2)
                               (merge v1 v2)))))))

(comment
  (tei-description (tei-ref :sourceDesc))
  (tei-description (tei-ref :msItem))

  ;; test text conversion on a full HTML document
  (-> (tei-ref :sourceDesc)
      (xh/parse)
      (h/hiccup->text h/html-conversion))

  ;; test text conversion on a TEI body
  ;; compare: https://github.com/bedebok/Data/blob/main/Prayers/org/AM08-0073_237v.org
  (-> (io/file "test/Data/Prayers/xml/AM08-0073_237v.xml")
      (xh/parse)
      (h/hiccup->text tei-conversion))

  ;; Metadata retrieval from a document
  ;; compare: https://github.com/bedebok/Data/blob/main/Prayers/org/AM08-0073_237v.org
  (-> (io/file "test/Data/Prayers/xml/AM08-0075_063r.xml")
      (xh/parse)
      (hiccup->entity manuscript-search-kvs))

  ;; TODO: "test/Data/Prayers/xml/AM08-0073_237v.xml" and  "test/Data/Catalogue/xml/AM08-0073.xml" use the same ID!!
  ;; Triple generation from a document
  ;; compare: https://github.com/bedebok/Data/blob/main/Prayers/org/AM08-0075_063r.org
  (-> (io/file "test/Data/Prayers/xml/AM08-0073_237v.xml")
      (xh/parse)
      (hiccup->entity manuscript-search-kvs))
  #_.)
