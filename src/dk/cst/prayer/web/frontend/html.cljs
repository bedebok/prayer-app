(ns dk.cst.prayer.web.frontend.html
  "Frontend HTML-generation, returning Replicant-style Hiccup."
  (:require [cljs.pprint :refer [pprint]]
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
   [:details [:summary "state"]
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
  (apply str (interpose ", " (sort coll))))

;; TODO: properly implement sorting rules, e.g. for stuff like "94r" to "95r"
(defn locus-sort
  [entities]
  (sort-by :tei/locus entities))

(declare metadata-view)

(defn in
  [x y]
  (if (set? x)
    (get x y)
    (= x y)))

(defn metadata-tr-view
  [bedebok-type [k v]]
  ;; NOTE: we are overloading the :bedebok/type value with keywords to better
  ;; decipher other keys during the recursive build of the frontend views.
  (when-not (and (= k :bedebok/type)
                 (keyword? v))
    [:tr
     [:td (str k)]
     [:td (condp in k
            :tei/msItem
            (for [msitem (locus-sort v)]
              (metadata-view (assoc msitem :bedebok/type k)))

            :tei/collationItem
            (for [collation-item (locus-sort v)]
              (metadata-view (assoc collation-item :bedebok/type k)))

            :tei/locus
            (locus-view v)

            ;; Put simple inline tables here.
            #{:tei/dimensions :tei/origDate :tei/origPlace}
            [:table
             (map (partial metadata-tr-view k) v)]

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
            (if (sequential? v)
              (list-view v)
              (pp v)))]]))

(defn prepare-entity
  "Modifies the data of `entity` for display purposes."
  [{:keys [tei/from tei/to]
    :as   entity}]
  (-> entity
      (dissoc :db/id :tei/from :tei/to)
      (cond->
        (or from to) (assoc :tei/locus [from to]))))

(defn metadata-view
  [{:keys [bedebok/type tei/msItem tei/collationItem]
    :as   entity}]
  (let [entity' (prepare-entity entity)]
    (let [metadata-tr' (partial metadata-tr-view type)]
      [:table
       (when-let [metadata (some-> entity'
                                   (dissoc :file/node :tei/msItem :tei/collationItem)
                                   (not-empty))]
         [:tbody.metadata
          (->> metadata
               (sort-by first)
               (map metadata-tr'))])
       (when msItem
         [:tbody.msItem (metadata-tr' [:tei/msItem msItem])])
       (when collationItem
         [:tbody.collationItem (metadata-tr' [:tei/collationItem collationItem])])])))

(defn text-view
  [node]
  [:section.pages
   (for [[pb content] (node->pages node)]
     (let [data-n (-> pb first second :data-n)]
       (into [:article.page [:header data-n] content])))])

(defn entity-view
  [{:keys [bedebok/type file/node] :as entity}]
  [:div.grid
   ;; TODO: only temporarily hidden while debugging
   #_(condp = type
       "text" (text-view node)

       ;; TODO: for dev usage, remove eventually
       [:pre (pp entity)])
   (metadata-view entity)])

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
  [:article {:id js/window.location.pathname}
   (nav state)
   (content-view state)])
