(ns dk.cst.prayer.web.app.html
  "Frontend HTML-generation, returning Replicant-style Hiccup."
  (:require [dk.cst.prayer.web.app.event :as event]
            [dk.cst.hiccup-tools.hiccup :as h]
            [dk.cst.hiccup-tools.elem :as e]
            [dk.cst.prayer.web.shared :as page]))

(defn nav
  [state]
  [:nav
   [:button {:on {:click [::event/reset-state]}}
    "reset"]
   [:ul.cards
    [:li {:replicant/key 1}
     [:a {:href "/entity/1"} "Item #1"]]
    [:li {:replicant/key 2}
     [:a {:href "/entity/2"} "Item #2"]]
    [:li {:replicant/key 3}
     [:a {:href "/entity/3"} "Item #3"]]
    [:li {:replicant/key 4}
     [:a {:href "/entity/4"} "Item #4"]]]
   [:details [:summary "state"]
    [:pre (with-out-str (cljs.pprint/pprint state))]]])

(defn node->pages
  [node]
  (as-> (h/get node :tei-body) $
        (h/split :tei-pb $ :retain :between)
        (e/children $)
        (partition-by #(= :tei-pb (first %)) $)
        (partition 2 $)))

(defn metadata-display
  [entity]
  [:table
   (for [[k v] (dissoc entity :file/node)]
     [:tr [:td (str k)] [:td (str v)]])])

(defn text-display
  [node]
  [:section.pages
   (for [[pb content] (node->pages node)]
     (let [data-n (-> pb first second :data-n)]
       (into [:article.page [:header data-n] content])))])

(defn entity-display
  [{:keys [bedebok/type file/node] :as entity}]
  [:div.columns
   (condp = type
     "text" (text-display node)

     ;; TODO: for dev usage, remove eventually
     [:pre (str entity)])
   (metadata-display entity)])

(defn index-display
  [index]
  [:ul
   (for [[e id] index]
     [:li [:a {:href (str "/entity/" e)} id]])])

(defn content
  [{:keys [location] :as state}]
  (let [{:keys [name params]} location]
    (condp = name
      ::page/entity (entity-display (get-in state [:entities (:id params)]))
      ::page/index (index-display (get-in state [:index (:type params)])))))

(defn page
  [state]
  [:article
   (nav state)
   (content state)])
