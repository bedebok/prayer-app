(ns dk.cst.prayer.web.frontend
  "The main namespace of the frontend single-page app."
  (:require [clojure.edn :as edn]
            [dk.cst.prayer.web :as web]
            [dk.cst.prayer.web.frontend.error :as err]
            [dk.cst.prayer.web.frontend.event :as event]
            [dk.cst.prayer.web.frontend.state :refer [state]]
            [dk.cst.prayer.web.frontend.api :as api]
            [dk.cst.prayer.web.frontend.html :as html]
            [replicant.dom :as d]
            [reitit.frontend :as rf]
            [reitit.frontend.easy :as rfe :refer [href]]))

;; https://stackoverflow.com/questions/5004978/check-if-page-gets-reloaded-or-refreshed-in-javascript#53307588
(def hard-refresh?
  "Says whether the user initiated a page refresh or navigated in/to the app."
  (delay
    (and (exists? js/window.performance.navigation)
         (= js/window.performance.navigation.type 1)
         (->> (.getEntriesByType js/window.performance "navigation")
              (map #(.-type %))
              (filter #{"reload"})
              (not-empty)))))

(def local-storage
  (delay
    (and (exists? (.-localStorage js/window))
         (.-localStorage js/window))))

(defn handle-error
  [error]
  (let [error-data (err/error->data error)]
    (api/backend-log error-data)
    (err/log! error-data)
    (err/display! error-data)))

(defn on-error
  [_message _url _line-number _col-number error]
  (handle-error error)

  ;; We do not short-circuit regular error handling (e.g. console output).
  ;; To do that we would have to return true instead of returning false.
  false)

;; https://github.com/metosin/reitit/blob/master/examples/frontend/src/frontend/core.cljs

(defn on-navigate
  [{:keys [data fragment] :as req}]
  ;; Replace Reitit coercion with our own that we can also use in the backend.
  (let [coerced-req (web/coerce-request req)]               ; TODO: log errors?
    (swap! state assoc
           :location {:name   (:name data)
                      :params (:params coerced-req)}

           ;; NOTE: fragment not considered part of the location.
           :fragment fragment)
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

;; NOTE: cannot render inside the <body> as this element contains a <script>
;;       element with version-specific values in it, e.g. versionHash!
(defn safe-render
  "Render the page in a safe way, i.e. ensuring the any potential errors in the
  various view functions do not break the whole application."
  []
  (try
    (d/render (js/document.getElementById "app") (html/page))
    (catch js/Error e
      ;; For whatever reason, I can't just throw the error and let it be handled
      ;; by js/window.onerror, so it must be handled directly here.
      (handle-error e)
      (vswap! d/state (constantly {}))                      ; reset Replicant
      (.back js/window.history))))                          ; last working page

(defn scroll-to
  [id]
  (some-> id
          (js/document.getElementById)
          (.scrollIntoView)))

(defn cache-size
  [ls]
  (some-> ls (.getItem "state") (count)))

(defn ^:dev/after-load init!
  []
  ;; Refer all uncaught errors to a universal error handler.
  ;; https://www.staticapps.org/articles/front-end-error-handling/
  (set! (.-onerror js/window) on-error)

  ;; Reitit (frontend routing)
  (set-up-navigation!)

  ;; Replicant (rendering and events)
  (d/set-dispatch! event/handle)
  (safe-render)
  (add-watch state ::render (fn [_ _ _ _new-state]
                              (safe-render)
                              (scroll-to (:fragment @state))))

  ;; Caching
  (when-let [ls @local-storage]
    ;; A user-initiated page reload is used as an indicator that any preserved
    ;; state should be removed from localStorage.
    (if @hard-refresh?
      (do
        (println "hard refresh -- removing cache:" (cache-size ls))
        (doto (.-localStorage js/window)
          (.removeItem "hash")
          (.removeItem "state")))

      ;; Otherwise, we attempt to read in the cached state from localStorage.
      ;; We load this state ONLY WHEN running the exact same version of the app,
      ;; which is determined by comparing the cached hash with the current hash
      (when-let [cached-hash (.getItem ls "hash")]
        (when (= cached-hash (when (exists? js/versionHash) js/versionHash))
          (println "compatible app version -- loading cache:" (cache-size ls))
          (some-> ls
                  (.getItem "state")
                  (edn/read-string)
                  (assoc :location (:location @state))
                  (->> (reset! state))))))

    ;; Cache the current state for the next session when closing the window/tab.
    (js/window.addEventListener
      "beforeunload"
      (fn []
        (doto ls
          (.setItem "hash" (when (exists? js/versionHash) js/versionHash))
          (.setItem "state" (pr-str (dissoc @state :location))))))))
