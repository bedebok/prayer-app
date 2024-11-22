(ns dk.cst.prayer.web.frontend
  "The main namespace of the frontend single-page app."
  (:require [clojure.edn :as edn]
            [dk.cst.prayer.web.frontend.event :as event]
            [dk.cst.prayer.web.frontend.state :refer [state]]
            [dk.cst.prayer.web.frontend.api :as api]
            [dk.cst.prayer.web.shared :as shared]
            [dk.cst.prayer.web.frontend.html :as html]
            [replicant.dom :as d]
            [reitit.frontend :as rf]
            [reitit.frontend.easy :as rfe :refer [href]]))

;; https://github.com/metosin/reitit/blob/master/examples/frontend/src/frontend/core.cljs

(defn on-navigate
  [{:keys [data] :as req}]
  ;; Replace Reitit coercion with our own that we can also use in the backend.
  (let [coerced-req (shared/coerce-request req)]            ; TODO: log errors?
    (swap! state assoc :location {:name   (:name data)
                                  :params (:params coerced-req)})
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
  (d/render el (html/page state)))

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
