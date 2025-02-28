(ns dk.cst.prayer.web.frontend.html
  "Frontend HTML-generation, returning Replicant-style Hiccup."
  (:require [cljs.pprint :refer [pprint]]
            [clojure.string :as str]
            [dk.cst.prayer.static :as static]
            [dk.cst.prayer.web :as page]
            [dk.cst.prayer.web.frontend.event :as event]
            [dk.cst.prayer.web.frontend.state :refer [state]]))

(defn pp
  [x]
  (with-out-str (pprint x)))

(defn dev-view []
  [:div
   [:button {:on {:click [::event/reset-state]}}
    "reset"]
   [:details [:summary "state"]
    [:pre (pp (dissoc @state :entities :cached))]]])

(defn header-view []
  (let [{:keys [location]} @state
        {:keys [name]} location]
    [:header.top
     [:nav
      [:ul
       [:li [:a (when-not (= name ::page/main) {:href "/"}) "Home"]]
       [:li [:a (when-not (= name ::page/text-index) {:href "/texts"}) "Texts"]]
       [:li [:a (when-not (= name ::page/manuscript-index) {:href "/manuscripts"}) "Manuscripts"]]
       [:li [:a (when-not (= name ::page/work-index) {:href "/works"}) "Works"]]]
      [:form {:on {:submit [::event/search]}}
       [:input#searchbar {:on          {:focus [::event/select]}
                          :value       (when (= name ::page/search)
                                         (get-in location [:params :query]))
                          :placeholder "search"
                          :type        "search"
                          :name        "query"}]]]]))

(defn locus-view
  [[from to]]
  (str from " — " to))

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

(defn label-view
  [k v]
  (let [label (get-in static/labels [k v] (str v))]
    label))

(defn basic-field-search
  [k v]
  (if (set? v)
    (interpose ", " (map (partial basic-field-search k) (sort v)))
    (let [v' (if (re-find #"\s" v)
               (str "\"" v "\"")
               v)]
      [:a {:title (str "Find more with this field value")
           :href  (str "/search/" (str (name k) "=" v'))}
       (label-view k v)])))

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

            :tei/dimensions
            (let [{:keys [tei/height tei/width]} v
                  ratio (if (and height width)
                          (/ height width)
                          1)]
              [:table.dimensions
               [:tr
                [:td]
                [:td.dimensions-width width]]
               [:tr
                [:td.dimensions-height height]
                [:div.dimensions-model {:style {:height (* ratio 100)
                                                :width  100}}]]])

            :tei/origDate
            (let [{:keys [tei/notAfter tei/notBefore tei/title]} v]
              (if title
                (list title [:br] " (c. " notBefore "–" notAfter ")")
                (str "c. " notBefore "–" notAfter)))

            :tei/origPlace
            (let [{:keys [tei/key tei/title]} v]
              [:a {:href (str "/search/" (str "origPlace=" key))}
               title])

            :tei/supportDesc
            (let [{:keys [tei/support tei/material]} v]
              [:a {:href (str "/search/" (str "material=" material))}
               support])

            :bedebok/work
            (let [{:keys [tei/key tei/title]} v]
              [:a {:href  (str "/works/" key)
                   :title "View documents referencing this work"}
               title])

            :tei/corresp
            [:a (if (= bedebok-type "text")
                  {:href  (str "/manuscripts/" v)
                   :title "View manuscript"}
                  {:href  (str "/texts/" v)
                   :title "View text"})
             v]

            #_#_:bedebok/type
            [:a {:href  (str "/" bedebok-type "s")
                 :title "View more of this type"}
             v]

            #{:bedebok/type
              :tei/class
              :tei/settlement
              :tei/repository
              :tei/mainLang
              :tei/otherLangs}
            (basic-field-search k v)

            ;; Put simple inline tables here.
            #_:tei/origPlace
            #_[:table
               (map (partial table-tr-view k) v)]

            ;; else
            (if (set? v)
              (list-view (map (partial label-view k) v))
              (label-view k v)))]]))

(defn prepare-for-table
  "Modifies the data of `m` for table display."
  [{:keys [tei/from tei/to]
    :as   m}]
  (cond-> m
    true (dissoc :db/id :file/node :tei/from :tei/to :bedebok/id)
    (or from to) (assoc :tei/locus [from to])))

(declare table-views)

(defn table-view
  [{:keys [bedebok/type db/id tei/locus] :as m}]
  (let [metadata-tr-view' (partial table-tr-view type)
        entity'           (prepare-for-table m)
        table-data        (some-> entity'
                                  (dissoc :file/node
                                          :tei/msItem
                                          :tei/collationItem
                                          :file/name
                                          :tei/locus)
                                  (not-empty))]
    [:table {:id (str "db-" (or id locus))}
     (if-let [locus (:tei/locus entity')]
       [:tr.header-row
        [:th {:colspan 2
              :title   (static/attr-doc :tei/locus)}
         (locus-view locus)]]
       (if-let [filename (:file/name entity')]
         [:tr.header-row
          [:th {:colspan 2
                :title   (static/attr-doc :file/name)}
           filename]]
         [:tr.header-row
          [:th {:colspan 2}]]))
     (map metadata-tr-view' (sort-by first table-data))
     (when-let [manuscripts (:tei/msItem entity')]
       [:tr.msitem-row
        [:td {:colspan 2}
         [:details
          [:summary "Parts"]
          (table-views (map #(assoc % :bedebok/type :tei/msItem) manuscripts))]]])
     (when-let [collation (:tei/collationItem entity')]
       [:tr.collation-row
        [:td {:colspan 2}
         [:details
          [:summary "Parts"]
          (table-views (map #(assoc % :bedebok/type :tei/collationItem) collation))]]])]))

;; For displaying msItems and collation.
(defn table-views
  [ms]
  (->> ms
       (map prepare-for-table)
       (sort-by locus-order)
       (map table-view)
       (interpose [:div.continuation "︙"])))

(defn controls-view []
  (let [{:keys [location] :as state'} @state
        id         (get-in location [:params :id])
        pages      (get-in state' [:cached id :pages])
        page-count (count pages)
        n          (get-in state' [:user :entities id :n] 0)]
    [:section.page-controls
     [:button {:on       {:click [::event/page :backward]}
               :disabled (= n 0)}
      "←"]
     [:select {:default-value n
               :on            {:change [::event/page]}}
      (for [i (range page-count)]
        [:option {:value    i
                  :selected (= n i)}
         (inc i) " / " page-count])]
     [:button {:on       {:click [::event/page :forward]}
               :disabled (= (inc n) page-count)}
      "→"]]))

(defn page-view
  [[pb content]]
  (let [data-n (-> pb first second :data-n)]
    (into [:article.page [:header.page-header data-n]
           [:section.page-content content]])))

(defn pages-view
  [id]
  (let [state'        @state
        {:keys [n]} (get-in state' [:user :entities id])
        pages         (get-in state' [:cached id :pages])
        pages-display (boolean (get-in state' [:user :prefs :pages-display]))]
    (if pages-display
      [:section.pages (map page-view pages)]
      [:section.pages (controls-view) (page-view (nth pages (or n 0)))])))

(defn uncapitalize
  [s]
  (str (str/lower-case (subs s 0 1))
       (subs s 1)))

(defn descriptive-view
  [{:keys [tei/origin tei/provenance] :as entity}]
  (cond
    (or origin provenance)
    [:ul.descriptive
     (when origin
       [:li#origin
        [:strong {:title (static/attr-doc :tei/origin)}
         "ORIGIN"] ": "
        (uncapitalize origin)])
     (when provenance
       [:li#provenance
        [:strong {:title (static/attr-doc :tei/provenance)}
         "PROVENANCE"] ": "
        (uncapitalize provenance)])]))

(defn page-header-view
  [{:keys [tei/title tei/summary tei/head bedebok/type]
    :as   entity}]
  [:header
   [:hgroup
    [:h1 title]
    (when summary
      [:p summary])]
   (when head
     [:p head])
   (descriptive-view entity)
   (when (= type "text")
     (let [pages-display (boolean (get-in @state [:user :prefs :pages-display]))]
       [:aside.preferences
        [:label [:input {:type    "checkbox"
                         :title   "Toggle single/multi page view"
                         :checked pages-display
                         :on      {:change [::event/pages-display]}}]
         " show all pages"]]))])

(defn- section
  [title view & [attr]]
  (when view
    [:section (when attr attr)
     [:h2 title]
     view]))

(defn entity-view
  [{:keys [bedebok/type tei/msItem tei/collationItem bedebok/id]
    :as   entity}]
  (let [general    (some-> entity
                           ;; TODO: why remove stuff both here and in prepare-for-table?
                           (dissoc :tei/title
                                   :tei/head
                                   :tei/summary
                                   :tei/origin
                                   :tei/provenance
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
         (section "Pages" (pages-view id))
         [:aside.metadata
          (section "Miscellaneous" general)
          (section "Manuscript Item" manuscript)
          (section "Collation Data" collation)]]

        [:article.content.manuscript
         (section "Manuscript Items" manuscript)
         [:aside.metadata
          (section "Miscellaneous" general)
          (section "Collation Data" collation)]]))))

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
   (for [[k v] (sort-by second index)]
     [:li [:a {:href (str "/" type "s/" k)} v]])])

(defn frontpage-view
  []
  (list
    [:h1.brutalist "When " [:br] [:span.big.red "Danes"] [:br] " Prayed in " [:br] [:span.big.yellow "German"]]
    [:p "This project examines the role of Low German in the transition from Latin to Danish as the primary language of religious devotion."]
    [:p "A common misconception holds that religious devotion was practiced solely through the medium of Latin until the Reformation. However, devotional books already began to appear in the vernacular in Denmark during the Middle Ages; not only in Danish, but also in another vernacular, Low German."]))

(defn content-view []
  (let [{:keys [location] :as state'} @state
        {:keys [name params]} location]
    [:main {:id js/window.location.pathname}
     (condp = name
       ::page/main (frontpage-view)
       ::page/search (search-view (get-in state' [:search (:query params)]))
       ::page/work (work-view (get-in state' [:works (:id params)]))
       ::page/text (entity-view (get-in state' [:entities (:id params)]))
       ::page/manuscript (entity-view (get-in state' [:entities (:id params)]))
       ::page/text-index (index-view "text" (get-in state' [:index "text"]))
       ::page/manuscript-index (index-view "manuscript" (get-in state' [:index "manuscript"]))
       ::page/work-index (index-view "work" (get-in state' [:index "work"])))]))

(defn page
  []
  ;; Some kind of ID is needed for replicant to properly re-render
  ;; TODO: is there a better ID?
  [:div.container
   (header-view)
   #_(dev-view)
   (content-view)
   [:footer
    [:address.grey
     [:div.big.black "© 2025"]
     "Department of Nordic Studies and Linguistics" [:br]
     "University of Copenhagen" [:br]
     "Emil Holms Kanal 2, DK-2300 Copenhagen S"]
    [:ul.links
     [:li
      [:a {:href "https://nors.ku.dk/english/research/projects/when-danes-prayed-in-german/"}
       "Project page"]]
     [:li
      [:a {:href "https://github.com/bedebok/prayer-app"}
       "Github"]]]]])
