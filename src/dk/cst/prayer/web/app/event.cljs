(ns dk.cst.prayer.web.app.event
  "Handlers for Replicant event dispatches."
  (:require [dk.cst.prayer.web.app.state :refer [state]]))

(defn handle
  [replicant-data handler-data]
  (condp = handler-data
    [::reset-state] (reset! state {})))
