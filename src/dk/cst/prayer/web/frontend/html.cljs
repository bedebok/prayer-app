(ns dk.cst.prayer.web.frontend.html
  "Frontend HTML-generation, returning Replicant-style Hiccup."
  (:require [cljs.pprint :refer [pprint]]
            [dk.cst.prayer.web.frontend.event :as event]
            [dk.cst.hiccup-tools.hiccup :as h]
            [dk.cst.hiccup-tools.elem :as e]
            [dk.cst.prayer.web.shared :as page]))

(defn pp
  [x]
  (with-out-str (pprint x)))

(defn nav
  [state]
  [:nav
   [:button {:on {:click [::event/reset-state]}}
    "reset"]
   [:ul
    [:li {:replicant/key 2}
     [:a {:href "/"} "Main"]]
    [:li {:replicant/key 2}
     [:a {:href "/texts"} "Texts"]]
    [:li {:replicant/key 3}
     [:a {:href "/manuscripts"} "Manuscripts"]]]
   [:details [:summary "state"]
    [:pre (with-out-str (cljs.pprint/pprint state))]]])

(defn node->pages
  [node]
  (as-> (h/get node :tei-body) $
        (h/split :tei-pb $ :retain :between)
        (e/children $)
        (partition-by #(= :tei-pb (first %)) $)
        (partition 2 $)))

(defn msitem-display
  [{:keys [file/node tei/to tei/from tei/key tei/mainLang tei/msItem]
    :as   msitem}]
  [:section.msitem
   [:table
    [:tr [:td "node"] [:td node]]
    [:tr [:td "key"] [:td key]]
    [:tr [:td "language"] [:td mainLang]]
    [:tr [:td "locus"] [:td (str from " ⋯ " to)]]
    (when msItem
      [:tr [:td "msItem"] [:td (for [msitem msItem]
                                 (msitem-display msitem))]])
    [:tr [:td "DEV"] [:td {:style {:color     "#AAA"
                                   :font-size "10px"}}
                      [:pre (pp msitem)]]]]])

(defn metadata-display
  [entity]
  [:table
   (for [[k v] (dissoc entity :file/node)]
     [:tr [:td (str k)] [:td (condp = k
                               :tei/msItem
                               (for [msitem v]
                                 (msitem-display msitem))

                               ;; else
                               (str v))]])])

(defn text-display
  [node]
  [:section.pages
   (for [[pb content] (node->pages node)]
     (let [data-n (-> pb first second :data-n)]
       (into [:article.page [:header data-n] content])))])

(defn entity-display
  [{:keys [bedebok/type file/node] :as entity}]
  [:div.grid
   (condp = type
     "text" (text-display node)

     ;; TODO: for dev usage, remove eventually
     [:pre (pp entity)])
   (metadata-display entity)])

(defn index-display
  [type index]
  [:ul
   (for [[e id] index]
     [:li [:a {:href (str "/" type "s/" id)} id]])])

(defn content
  [{:keys [location] :as state}]
  (let [{:keys [name params]} location]
    (condp = name
      ::page/main [:p "main page"]
      ::page/text (entity-display (get-in state [:entities (:id params)]))
      ::page/manuscript (entity-display (get-in state [:entities (:id params)]))
      ::page/text-index (index-display "text" (get-in state [:index "text"]))
      ::page/manuscript-index (index-display "manuscript" (get-in state [:index "manuscript"])))))

(defn page
  [state]
  [:article
   (nav state)
   (content state)])
