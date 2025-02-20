(ns dk.cst.prayer.web.frontend.event
  "Handlers for Replicant event dispatches."
  (:require [reitit.impl :refer [form-encode]]
            [dk.cst.prayer.web :as web]
            [dk.cst.prayer.web.frontend.state :refer [state]]))

(defn e->v
  [dom-event]
  (some-> dom-event .-target .-value))

(defn handle
  [{:keys [replicant/dom-event replicant/node] :as replicant-data} handler-data]
  (condp = (first handler-data)
    ::reset-state (swap! state select-keys [:location])
    ::pages-display (swap! state update-in [:user :prefs :pages-display] not)
    ::page (let [arg (second handler-data)
                 id  (get-in @state [:location :params :id])]
             (.preventDefault dom-event)
             (cond
               (= arg :forward) (swap! state update-in [:user :entities id :n] inc)
               (= arg :backward) (swap! state update-in [:user :entities id :n] dec)
               :else (let [v* (e->v dom-event)
                           v  (if v* (parse-double v*) 0)]
                       (swap! state assoc-in [:user :entities id :n] v))))
    ::select (do
               (.preventDefault dom-event)
               (.select node))
    ::search (do
               (.preventDefault dom-event)
               (let [{:strs [query]} (->> (new js/FormData node)
                                          (.entries)
                                          (map (juxt first second))
                                          (into {}))
                     path (str "/search/" (form-encode query))]
                 (when (not= path js/location.pathname)
                   (web/navigate-to path))))))
