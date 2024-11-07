(ns dk.cst.prayer.web.app
  (:require [replicant.dom :as d]
            [lambdaisland.fetch :as fetch]
            [reitit.coercion.malli]
            [reitit.frontend :as rf]
            [reitit.frontend.easy :as rfe :refer [href]]))

;; https://github.com/metosin/reitit/blob/master/examples/frontend/src/frontend/core.cljs

;; TODO: do some kind of mirroring in the backend
(def routes
  [["/entity/:id" {:name       ::entity
                   :parameters {:path {:id int?}}
                   :handler    (fn [{:keys [parameters]}]
                                 (let [id (get-in parameters [:path :id])]
                                   (-> (fetch/get (str "/api/entity/" id))
                                       (.then prn))))}]])

(defn on-navigate
  [{:keys [data] :as m}]
  (when-let [handler (:handler data)]
    (handler m)))

(def router
  (rf/router
    routes
    {:conflicts nil
     :data      {:coercion reitit.coercion.malli/coercion}}))

(defn set-up-navigation!
  []
  (rfe/start! router on-navigate {:use-fragment false}))

(def el
  (js/document.getElementById "app"))

(defn render []
  (d/render el
            [:ul.cards
             [:li {:replicant/key 1
                   :on            {:click [:whatever]}}
              [:a {:href "/entity/1"} "Item #1"]]
             [:li {:replicant/key 2}
              [:a {:href "/entity/2"} "Item #2"]]
             [:li {:replicant/key 3}
              [:a {:href "/entity/3"} "Item #3"]]
             [:li {:replicant/key 4}
              [:a {:href "/entity/4"} "Item #4"]]]))

(defn ^:dev/after-load init!
  []
  (set-up-navigation!)
  (d/set-dispatch!
    (fn [replicant-data handler-data]
      (prn replicant-data handler-data)))
  (render))
