(ns dk.cst.prayer.web.frontend
  "The main namespace of the frontend single-page app."
  (:require [clojure.edn :as edn]
            [dk.cst.prayer.web :as web]
            [dk.cst.prayer.web.frontend.event :as event]
            [dk.cst.prayer.web.frontend.state :refer [state]]
            [dk.cst.prayer.web.frontend.api :as api]
            [dk.cst.prayer.web.frontend.html :as html]
            [replicant.dom :as d]
            [reitit.frontend :as rf]
            [reitit.frontend.easy :as rfe :refer [href]]))

;; https://stackoverflow.com/questions/5004978/check-if-page-gets-reloaded-or-refreshed-in-javascript#53307588
(def hard-refresh?
  (delay
    (and (exists? js/window.performance.navigation)
         (= js/window.performance.navigation.type 1)
         (->> (.getEntriesByType js/window.performance "navigation")
              (map #(.-type %))
              (filter #{"reload"})
              (not-empty)))))

;; https://github.com/metosin/reitit/blob/master/examples/frontend/src/frontend/core.cljs

(defn on-navigate
  [{:keys [data] :as req}]
  ;; Replace Reitit coercion with our own that we can also use in the backend.
  (let [coerced-req (web/coerce-request req)]               ; TODO: log errors?
    (swap! state assoc :location {:name   (:name data)
                                  :params (:params coerced-req)})
    (when-let [handler (:handle data)]
      (api/handle coerced-req handler))))

;; TODO: should ignore final slash
(def router
  (rf/router
    web/frontend-routes
    {:conflicts nil}))

(defn set-up-navigation!
  []
  (rfe/start! router on-navigate {:use-fragment false}))

(defn render
  []
  (d/render js/document.body (html/page)))

(defn ^:dev/after-load init!
  []
  ;; Reitit (frontend routing).
  (set-up-navigation!)

  ;; Replicant (rendering and events).
  (d/set-dispatch! event/handle)
  (render)
  (add-watch state ::render (fn [_ _ _ _new-state] (render)))

  ;; A user-initiated page reload is used as an indicator that any preserved
  ;; state should be removed from localStorage.
  (when-let [ls (and (exists? (.-localStorage js/window))
                     (.-localStorage js/window))]
    (if hard-refresh?
      (when-let [chars (some-> ls (.getItem "state") (count))]
        (println "hard refresh detected -- remove old state:" chars "chars")
        (doto (.-localStorage js/window)
          (.removeItem "hash")
          (.removeItem "state")))

      ;; Otherwise, we attempt to read in any existing state from localStorage.
      ;; We load this state ONLY WHEN running the exact same version of the app!
      (when-let [hash (.getItem ls "initHash")]
        (when (not= hash (when (exists? js/initHash) js/initHash))
          (some-> ls
                  (.getItem "state")
                  (edn/read-string)
                  (assoc :location (:location @state))
                  (->> (reset! state)))))))

  ;; Store state for next session.
  (js/window.addEventListener
    "beforeunload"
    (fn []
      (doto (.-localStorage js/window)
        (.setItem "hash" (when (exists? js/initHash) js/initHash))
        (.setItem "state" (pr-str (dissoc @state :location)))))))
