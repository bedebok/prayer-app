(ns dk.cst.prayer.tei
  (:require [clojure.string :as str]
            [clojure.zip :as zip]
            [dk.cst.hiccup-tools.elem :as elem]
            [dk.cst.xml-hiccup :as xh]
            [dk.cst.hiccup-tools.helper :as helper]
            [dk.cst.hiccup-tools.hiccup :as h]
            [dk.cst.hiccup-tools.zip :as z]
            [dk.cst.hiccup-tools.match :as match]
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
(def msItem-search
  [[:part {:matcher (every-pred
                      (match/has-parent (match/tag :msItem))
                      (match/tag :msItem))
           :process 'recursive}]
   ;; TODO: should also match {:to true}, but currently the TEI files have errors
   [:locus {:matcher (match/tag+attr :locus {:from true})
            :process (fn [node]
                       (let [{:keys [from to]} (elem/attr node)]
                         (cond-> {:locus/from from}
                           to (assoc :to to))))}]
   [:title {:matcher (match/tag+attr :title {:xml/id true})
            :process (fn [node]
                       (let [{:keys [xml/id]} (elem/attr node)
                             title (first (elem/children node))]
                         {:title    title
                          :title/id id}))}]
   [:lang {:matcher (match/tag+attr :textLang {:mainLang true})
           :process (fn [node]
                      (let [{:keys [mainLang]} (elem/attr node)]
                        {:lang mainLang}))}]])

(def manuscript-search
  {:id         {:matcher (every-pred
                           (match/tag+attr :idno {:xml/id true})
                           (match/has-parent (match/tag :msIdentifier)))
                :process (fn [node]
                           (let [{:keys [xml/id]} (elem/attr node)]
                             ;; TODO: do this properly
                             {:db/id id}))}
   :settlement {:matcher (every-pred
                           (match/tag+attr :settlement {:key true})
                           (match/has-parent (match/tag :msIdentifier)))
                :process (fn [node]
                           (let [{:keys [key]} (elem/attr node)]
                             {:settlement key}))}
   :repository {:matcher (every-pred
                           (match/tag+attr :repository {:key true})
                           (match/has-parent (match/tag :msIdentifier)))
                :process (fn [node]
                           (let [{:keys [key]} (elem/attr node)]
                             {:repository key}))}
   :msItem     {:matcher (every-pred
                           (match/has-parent (match/tag :msContents))
                           (match/tag :msItem))
                :process msItem-search}
   :name       {:matcher (match/tag+attr :name {:type true :key true})
                :process (fn [node]
                           (let [{:keys [type key]} (elem/attr node)
                                 label (h/hiccup->text node tei-conversion)]
                             {:name [{(keyword "name" type) key
                                      :label                label}]}))}
   #_#_:section {:matcher (match/tag+attr :div {:type true})} ;TODO
   :refs {:matcher (match/attr {:n true :type true})
          :process (fn [node]
                     (let [{:keys [n type]} (elem/attr node)
                           label (h/hiccup->text node tei-conversion)]
                       ;; TODO: keywordify ref IDs?
                       {:ref [{(keyword "ref" type) n
                               :label               label}]}))}})

(defn hiccup->entity
  "Convert TEI `hiccup` into an Datom entity based on a `search-kvs`."
  [hiccup search-kvs]
  (let [k->matcher (helper/update-kv-vals search-kvs :matcher)
        k->process (->> (helper/update-kv-vals search-kvs :process)
                        (remove (comp nil? second))
                        (into (empty search-kvs)))
        result     (h/search hiccup k->matcher :exhaustive false)]
    (->> k->process

         (mapcat (fn [[k process]]
                   (when-let [matches (get result k)]
                     (cond
                       ;; Process functions take matched nodes and produce maps.
                       ;; These maps are assumed to be attributes & values of the
                       ;; same entity and will be merged later.
                       (fn? process)
                       (map process matches)

                       ;; Maps are used for sub-searches inside the matched node.
                       ;; This solves the issue of matching e.g. list items that
                       ;; are not distinguished by an ID such as msItem.
                       ;; Sub-searches result in component data.
                       (or (map? process) (vector? process))
                       [{k (mapv #(hiccup->entity % process) matches)}]

                       ;; This special symbol marks recursive sub-searches as
                       ;; you can't do direct self-references in Clojure data.
                       (= process 'recursive)
                       [{k (mapv #(hiccup->entity % search-kvs) matches)}]))))

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
  (-> (io/file "test/Data/Prayers/xml/Holm-A42_032r.xml")
      (xh/parse)
      (hiccup->entity manuscript-search))

  ;; TODO: "test/Data/Prayers/xml/AM08-0073_237v.xml" and  "test/Data/Catalogue/xml/AM08-0073.xml" use the same ID!!
  ;; Triple generation from a document
  ;; compare: https://github.com/bedebok/Data/blob/main/Prayers/org/AM08-0075_063r.org
  (-> (io/file "test/Data/Catalogue/xml/AM08-0073.xml")
      (xh/parse)
      (hiccup->entity manuscript-search))
  #_.)
