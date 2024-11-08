(ns dk.cst.prayer.web.app.api
  "Handlers for Reitit frontend routing matches."
  (:require [dk.cst.prayer.web.app.state :refer [state]]
            [lambdaisland.fetch :as fetch]))

(defn add-entity
  [id e]
  (when-not (empty? e)
    (swap! state assoc id e)))

(defn fetch-entity
  [{:keys [parameters]}]
  (let [id (get-in parameters [:path :id])]
    ;; TODO: swap built-in fetch transit parsing for transito?
    (when-not (get @state [:entities id])
      (-> (fetch/get (str "/api/entity/" id))
          ;; TODO: handle 404 explicitly
          (.then #(add-entity id (:body %)))))))

(defn handle
  [req handler-data]
  (condp = handler-data
    [::fetch-entity] (fetch-entity req)))
