(ns dk.cst.prayer.web.frontend.event
  "Handlers for Replicant event dispatches."
  (:require [reitit.impl :refer [form-encode]]
            [dk.cst.prayer.web :as web]
            [dk.cst.prayer.web.frontend.state :refer [state]]))

(defn handle
  [{:keys [replicant/dom-event replicant/node] :as replicant-data} handler-data]
  (condp = handler-data
    [::reset-state] (swap! state select-keys [:location])
    [::select] (do
                 (.preventDefault dom-event)
                 (.select node))
    [::search] (do
                 (.preventDefault dom-event)
                 (let [{:strs [query]} (->> (new js/FormData node)
                                            (.entries)
                                            (map (juxt first second))
                                            (into {}))
                       path (str "/search/" (form-encode query))]
                   (when (not= path js/location.pathname)
                     (web/navigate-to path))))))
