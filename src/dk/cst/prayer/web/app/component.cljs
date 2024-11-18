(ns dk.cst.prayer.web.app.component
  "Functions returning Replicant-style Hiccup."
  (:require [dk.cst.prayer.web.app.event :as event]
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

(defn content
  [{:keys [location] :as state}]
  (let [{:keys [name params]} location]
    (condp = name
      ::page/entity (get-in state [:entities (:id params) :file/node]))))

(defn page
  [state]
  [:article
   (nav state)
   (content state)])
