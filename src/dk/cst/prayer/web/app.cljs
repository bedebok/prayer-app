(ns dk.cst.prayer.web.app
  (:require [clojure.edn :as edn]
            [replicant.dom :as d]
            [lambdaisland.fetch :as fetch]
            [reitit.coercion.malli]
            [reitit.frontend :as rf]
            [reitit.frontend.easy :as rfe :refer [href]]))

(defonce state
  (atom {}))

(defn add-entity!
  [id e]
  (when-not (empty? e)
    (swap! state assoc id e)))

;; https://github.com/metosin/reitit/blob/master/examples/frontend/src/frontend/core.cljs

;; TODO: do some kind of path mirroring in the backend
(def routes
  [["/entity/:id"
    {:name       ::entity
     :parameters {:path {:id int?}}
     :handler    (fn [{:keys [parameters]}]
                   (let [id (get-in parameters [:path :id])]
                     ;; TODO: swap built-in fetch transit parsing for transito?
                     (when-not (get @state [:entities id])
                       (-> (fetch/get (str "/api/entity/" id))
                           ;; TODO: handle 404 explicitly
                           (.then #(add-entity! id (:body %)))))))}]])

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

(defn render
  [state]
  (d/render el
            [:div
             [:button {:on {:click [:reset-state]}}
              "reset"]
             [:ul.cards
              [:li {:replicant/key 1
                    :on            {:click [:whatever]}}
               [:a {:href "/entity/1"} "Item #1"]]
              [:li {:replicant/key 2}
               [:a {:href "/entity/2"} "Item #2"]]
              [:li {:replicant/key 3}
               [:a {:href "/entity/3"} "Item #3"]]
              [:li {:replicant/key 4}
               [:a {:href "/entity/4"} "Item #4"]]]
             [:pre (with-out-str (cljs.pprint/pprint state))]]))

(defn ^:dev/after-load init!
  []
  ;; Reitit (frontend routing).
  (set-up-navigation!)

  ;; Replicant (rendering and events).
  (d/set-dispatch!
    (fn [replicant-data handler-data]
      (when (= handler-data [:reset-state])
        (reset! state {}))))
  (render @state)
  (add-watch state ::render (fn [_ _ _ state] (render state)))

  ;; Load old session state ONLY IF we're running the same version of the app.
  (when-let [hash (.getItem (.-localStorage js/window) "initHash")]
    (when (not= hash (when (exists? js/initHash) js/initHash))
      (some->> (.getItem (.-localStorage js/window) "state")
               (edn/read-string)
               (reset! state))))

  ;; Store state for next session.
  (js/window.addEventListener
    "beforeunload"
    (fn []
      (doto (.-localStorage js/window)
        (.setItem "hash" (when (exists? js/initHash) js/initHash))
        (.setItem "state" (pr-str @state))))))
