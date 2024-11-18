(ns dk.cst.prayer.web.app.api
  "Handlers for Reitit frontend routing matches."
  (:require [dk.cst.prayer.web.app.state :refer [state]]
            [lambdaisland.fetch :as fetch]))

(defn add-entity
  [id e]
  (when-not (empty? e)
    (swap! state assoc-in [:entities id] e)))

(defn fetch-entity
  [{:keys [params]}]
  (let [{:keys [id]} params]
    ;; TODO: swap built-in fetch transit parsing for transito?
    (when-not (get-in @state [:entities id])
      (-> (fetch/get (str "/api/entity/" id))
          ;; TODO: handle 404 explicitly
          (.then #(add-entity id (:body %)))))))

(defn handle
  [req handler-data]
  (condp = handler-data
    [::fetch-entity] (fetch-entity req)))