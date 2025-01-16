(ns dk.cst.prayer.web.frontend.html
  "Frontend HTML-generation, returning Replicant-style Hiccup."
  (:require [cljs.pprint :refer [pprint]]
            [dk.cst.prayer.static :as static]
            [dk.cst.prayer.web :as page]
            [dk.cst.prayer.web.frontend.event :as event]
            [dk.cst.hiccup-tools.hiccup :as h]
            [dk.cst.hiccup-tools.elem :as e]))

(defn pp
  [x]
  (with-out-str (pprint x)))

(defn nav
  [state]
  [:nav
   [:button {:on {:click [::event/reset-state]}}
    "reset"]
   [:ul
    [:li [:a {:href "/"} "Main"]]
    [:li [:a {:href "/texts"} "Texts"]]
    [:li [:a {:href "/manuscripts"} "Manuscripts"]]]
   #_[:details [:summary "state"]
      [:pre (pp state)]]])

(defn node->pages
  [node]
  (as-> (h/get node :tei-body) $
        (h/split :tei-pb $ :retain :between)
        (e/children $)
        (partition-by #(= :tei-pb (first %)) $)
        (partition 2 $)))

(defn locus-view
  [[from to]]
  (str from " ⋯ " to))

(defn list-view
  [coll]
  (interpose ", " (sort coll)))

;; TODO: properly implement sorting rules, e.g. for stuff like "94r" to "95r"
(def locus-order
  (juxt (comp :tei/from :tei/locus)
        (comp :tei/to :tei/locus)))

(declare table-view)

(defn in
  [x y]
  (if (set? x)
    (get x y)
    (= x y)))

(defn table-tr-view
  [bedebok-type [k v]]
  ;; NOTE: we are overloading the :bedebok/type value with keywords to better
  ;; decipher other keys during the recursive build of the frontend views.
  (when-not (and (= k :bedebok/type)
                 (keyword? v))
    [:tr
     [:td (when-let [doc (static/attr-doc k)]
            {:title doc})
      (name k)]
     [:td (condp in k
            #{:tei/msItem :tei/collationItem}
            (apply table-view (map #(assoc % :bedebok/type k) v))

            :tei/locus
            (locus-view v)

            ;; Put simple inline tables here.
            #{:tei/dimensions :tei/origDate :tei/origPlace}
            [:table
             (map (partial table-tr-view k) v)]

            ;; TODO: support other key types through overloaded :bedebok/type
            :tei/key
            (if (= bedebok-type :tei/msItem)
              [:a {:href  (str "/works/" v)
                   :title "View documents containing key"}
               v]
              v)

            :tei/corresp
            [:a (if (= bedebok-type "text")
                  {:href  (str "/manuscripts/" v)
                   :title "View manuscript"}
                  {:href  (str "/texts/" v)
                   :title "View text"})
             v]

            :bedebok/type
            [:a {:href  (str "/" bedebok-type "s")
                 :title "View more of this type"}
             v]

            ;; else
            (if (set? v)
              (list-view v)
              (pp v)))]]))

(defn prepare-for-table
  "Modifies the data of `m` for table display."
  [{:keys [tei/from tei/to]
    :as   m}]
  (-> m
      (dissoc :db/id :file/node :tei/from :tei/to)
      (cond->
        (or from to) (assoc :tei/locus [from to]))))

(defn table-view
  [{:keys [bedebok/type db/id] :as m} & ms]
  (let [metadata-tr-view' (partial table-tr-view type)
        entity'           (prepare-for-table m)
        table-data        (some-> entity'
                                  (dissoc :file/node)
                                  (not-empty))]
    (if ms
      (map table-view (sort-by locus-order (conj ms m)))
      [:table {:id (str "db-" id)}
       (map metadata-tr-view' (sort-by first table-data))])))

(defn pages-view
  [node]
  (for [[pb content] (node->pages node)]
    (let [data-n (-> pb first second :data-n)]
      (into [:article.page [:header data-n] content]))))

(defn header-view
  [{:keys [tei/title tei/head] :as entity}]
  [:header
   [:h1 title]
   (when head [:p head])])

(defn- section
  [title view & [attr]]
  (when view
    [:section (when attr attr)
     [:h2 title]
     view]))

(defn entity-view
  [{:keys [bedebok/type file/node tei/msItem tei/collationItem]
    :as   entity}]
  (let [general    (some-> entity
                           (dissoc :tei/title
                                   :tei/head
                                   :tei/msItem
                                   :tei/collationItem)
                           (not-empty)
                           (table-view))
        manuscript (some->> msItem
                            (map #(assoc % :bedebok/type :tei/msItem))
                            (not-empty)
                            (apply table-view))
        collation  (some->> collationItem
                            (map #(assoc % :bedebok/type :tei/collationItem))
                            (not-empty)
                            (apply table-view))]
    (list
      (header-view entity)

      ;; When viewing a text, the pages are centred; when viewing a manuscript,
      ;; the manuscript items are centred.
      (if (= type "text")
        [:main.text
         (section "Pages" (pages-view node))
         [:aside.metadata
          (section "General" general)
          (section "Manuscript" manuscript)
          (section "Collation" collation)]]

        [:main.manuscript
         (section "Manuscript" manuscript)
         [:aside.metadata
          (section "General" general)
          (section "Collation" collation)]]))))

(defn work-view
  [work]
  (for [[type ids] (sort-by first work)]
    [:dl
     type
     (for [id ids]
       [:dd [:a {:href (str "/" type "s/" id)} id]])]))

(defn index-view
  [type index]
  [:ul
   (for [[e id] index]
     [:li [:a {:href (str "/" type "s/" id)} id]])])

(defn content-view
  [{:keys [location] :as state}]
  (let [{:keys [name params]} location]
    (condp = name
      ::page/main [:p "main page"]
      ::page/work (work-view (get-in state [:works (:id params)]))
      ::page/text (entity-view (get-in state [:entities (:id params)]))
      ::page/manuscript (entity-view (get-in state [:entities (:id params)]))
      ::page/text-index (index-view "text" (get-in state [:index "text"]))
      ::page/manuscript-index (index-view "manuscript" (get-in state [:index "manuscript"])))))

(defn page
  [{:keys [location] :as state}]
  ;; Some kind of ID is needed for replicant to properly re-render
  ;; TODO: is there a better ID?
  [:div {:id js/window.location.pathname}
   (nav state)
   (content-view state)])
