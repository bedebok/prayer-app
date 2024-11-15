(ns dk.cst.prayer.web.app
  "The main namespace of the frontend single-page app."
  (:require [clojure.edn :as edn]
            [dk.cst.prayer.web.app.event :as event]
            [dk.cst.prayer.web.app.state :refer [state]]
            [dk.cst.prayer.web.app.api :as api]
            [dk.cst.prayer.web.shared :as shared]
            [replicant.dom :as d]
            [reitit.frontend :as rf]
            [reitit.frontend.easy :as rfe :refer [href]]))

;; https://github.com/metosin/reitit/blob/master/examples/frontend/src/frontend/core.cljs

(defn on-navigate
  [{:keys [data] :as req}]
  (swap! state assoc :location (:name data))
  ;; Replace Reitit coercion with our own that we can also use in the backend.
  (let [coerced-req (shared/coerce-request req)]            ; TODO: log errors?
    (when-let [handler (:handle data)]
      (api/handle coerced-req handler))))

;; TODO: should ignore final slash
(def router
  (rf/router
    shared/frontend-routes
    {:conflicts nil}))

(defn set-up-navigation!
  []
  (rfe/start! router on-navigate {:use-fragment false}))

(def el
  (js/document.getElementById "app"))

(defn render
  [state]
  (d/render el
            [:div
             [:button {:on {:click [::event/reset-state]}}
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
  (d/set-dispatch! event/handle)
  (render @state)
  (add-watch state ::render (fn [_ _ _ state] (render state)))

  ;; Load old session state ONLY IF we're running the same version of the app.
  (when-let [hash (.getItem (.-localStorage js/window) "initHash")]
    (when (not= hash (when (exists? js/initHash) js/initHash))
      (some-> (.-localStorage js/window)
              (.getItem "state")
              (edn/read-string)
              (assoc :location (:location @state))
              (->> (reset! state)))))

  ;; Store state for next session.
  (js/window.addEventListener
    "beforeunload"
    (fn []
      (doto (.-localStorage js/window)
        (.setItem "hash" (when (exists? js/initHash) js/initHash))
        (.setItem "state" (pr-str (dissoc @state :location)))))))
