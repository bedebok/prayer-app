(ns dk.cst.prayer.web.app
  (:require [replicant.dom :as d]
            [reitit.frontend :as rf]
            [reitit.frontend.easy :as rfe :refer [href]]))

(def routes
  [["/{*path}" :noop]])

(defn on-navigate
  [x]
  (prn 'on-navigate x))

(defn set-up-navigation!
  []
  (rfe/start! (rf/router routes)
              on-navigate
              {:use-fragment false}))

(def el
  (js/document.getElementById "app"))

(defn render []
  (d/render el
            [:ul.cards
             [:li {:replicant/key 1
                   :on            {:click [:whatever]}}
              [:a {:href "/1"} "Item #1"]]
             [:li {:replicant/key 2}
              [:a {:href "/2"} "Item #2"]]
             [:li {:replicant/key 3}
              [:a {:href "/3"} "Item #3"]]
             [:li {:replicant/key 4}
              [:a {:href "/4"} "Item #4"]]]))

(defn ^:dev/after-load init!
  []
  (set-up-navigation!)
  (d/set-dispatch!
    (fn [replicant-data handler-data]
      (prn replicant-data handler-data)))
  (render))
