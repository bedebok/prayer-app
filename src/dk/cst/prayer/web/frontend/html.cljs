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

(defn header-view
  [{:keys [location] :as state}]
  (let [{:keys [name]} location]
    [:header.top
     [:nav
      [:ul
       [:li [:a (when-not (= name ::page/main) {:href "/"}) "Main"]]
       [:li [:a (when-not (= name ::page/text-index) {:href "/texts"}) "Texts"]]
       [:li [:a (when-not (= name ::page/manuscript-index) {:href "/manuscripts"}) "Manuscripts"]]]
      [:form {:on {:submit [::event/search]}}
       [:input {:on          {:focus [::event/select]}
                :placeholder "search"
                :type        "search"
                :name        "query"}]]
      #_[:button {:on {:click [::event/reset-state]}}
         "reset"]
      #_[:details [:summary "state"]
         [:pre (pp state)]]]]))

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

(defn locus-parts
  "Split a `locus-str` into its constituent parts for comparison purposes."
  [locus-str]
  (if (string? locus-str)
    (some-> (re-matches #"(\d+)([rv])?" locus-str)
            ((fn [[_ n rv]]
               [(parse-long n) rv])))
    ;; In case of missing data, we put these cases at the end.
    ;; TODO: log the missing data
    [9999 "v"]))

;; This assumes we have put :tei/from and :tei/to into a vector in :tei/locus.
(def locus-order
  (juxt (comp locus-parts first :tei/locus)
        (comp locus-parts second :tei/locus)))

(declare table-views)

(defn in
  [x y]
  (if (set? x)
    (get x y)
    (= x y)))

;; TODO: link up with searches, e.g. :tei/settlement "KBH"?
(defn label-view
  [k v]
  (let [label (get-in static/labels [k v] (str v))]
    label))

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
            (table-views (map #(assoc % :bedebok/type k) v))

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
              (list-view (map (partial label-view k) v))
              (label-view k v)))]]))

(defn prepare-for-table
  "Modifies the data of `m` for table display."
  [{:keys [tei/from tei/to]
    :as   m}]
  (cond-> m
    true (dissoc :db/id :file/node :tei/from :tei/to)
    (or from to) (assoc :tei/locus [from to])))

(defn table-view
  [{:keys [bedebok/type db/id] :as m}]
  (let [metadata-tr-view' (partial table-tr-view type)
        entity'           (prepare-for-table m)
        table-data        (some-> entity'
                                  (dissoc :file/node)
                                  (not-empty))]
    [:table {:id (str "db-" id)}
     (map metadata-tr-view' (sort-by first table-data))]))

;; For displaying msItems and collation.
(defn table-views
  [ms]
  (->> ms
       (map prepare-for-table)
       (sort-by locus-order)
       (map table-view)))

(defn pages-view
  [node]
  (for [[pb content] (node->pages node)]
    (let [data-n (-> pb first second :data-n)]
      (into [:article.page [:header data-n] content]))))

(defn page-header-view
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
                            (table-views))
        collation  (some->> collationItem
                            (map #(assoc % :bedebok/type :tei/collationItem))
                            (not-empty)
                            (table-views))]
    (list
      (page-header-view entity)

      ;; When viewing a text, the pages are centred; when viewing a manuscript,
      ;; the manuscript items are centred.
      (if (= type "text")
        [:article.content.text
         (section "Pages" (pages-view node))
         [:aside.metadata
          (section "General" general)
          (section "Manuscript" manuscript)
          (section "Collation" collation)]]

        [:article.content.manuscript
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

(defn search-view
  [search-result]
  (for [[type hits] (group-by :bedebok/type search-result)]
    [:dl
     type
     (for [{:keys [bedebok/id]} hits]
       [:dd [:a {:href (str "/" type "s/" id)} id]])]))

(defn index-view
  [type index]
  [:ul
   (for [[e id] index]
     [:li [:a {:href (str "/" type "s/" id)} id]])])

(defn frontpage-view
  []
  (list
    [:h1.brutalist "When " [:br] [:span.big.red "Danes"] [:br] " Prayed in " [:br] [:span.big.yellow "German"]]
    [:p "This project examines the role of Low German in the transition from Latin to Danish as the primary language of religious devotion."]
    [:p "A common misconception holds that religious devotion was practiced solely through the medium of Latin until the Reformation. However, devotional books already began to appear in the vernacular in Denmark during the Middle Ages; not only in Danish, but also in another vernacular, Low German."]))

(defn content-view
  [{:keys [location] :as state}]
  (let [{:keys [name params]} location]
    [:main {:id js/window.location.pathname}
     (condp = name
       ::page/main (frontpage-view)
       ::page/search (search-view (get-in state [:search (:query params)]))
       ::page/work (work-view (get-in state [:works (:id params)]))
       ::page/text (entity-view (get-in state [:entities (:id params)]))
       ::page/manuscript (entity-view (get-in state [:entities (:id params)]))
       ::page/text-index (index-view "text" (get-in state [:index "text"]))
       ::page/manuscript-index (index-view "manuscript" (get-in state [:index "manuscript"])))]))

(defn page
  [{:keys [location] :as state}]
  ;; Some kind of ID is needed for replicant to properly re-render
  ;; TODO: is there a better ID?
  [:div.container
   (header-view state)
   (content-view state)
   [:footer
    [:ul.links
     [:li
      [:a {:href "https://nors.ku.dk/english/research/projects/when-danes-prayed-in-german/"}
       "Project page"]]
     [:li
      [:a {:href "https://github.com/bedebok/prayer-app"}
       "Github"]]]
    [:address
     "Department of Nordic Studies and Linguistics" [:br]
     "University of Copenhagen" [:br]
     "Emil Holms Kanal 2, DK-2300 Copenhagen S"]]])
