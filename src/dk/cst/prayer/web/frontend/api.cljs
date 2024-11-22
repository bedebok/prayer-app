(ns dk.cst.prayer.web.frontend.api
  "Handlers for Reitit frontend routing matches."
  (:require [dk.cst.prayer.web.frontend.state :refer [state]]
            [dk.cst.prayer.web.shared :as shared]
            [lambdaisland.fetch :as fetch]))

(defn add-entity
  [id e]
  (when-not (empty? e)
    (swap! state assoc-in [:entities id] e)))

(defn add-index
  [type kvs]
  (when-not (empty? kvs)
    (swap! state assoc-in [:index type] kvs)))

(defn fetch-entity
  [{:keys [params]}]
  (let [{:keys [id]} params]
    ;; TODO: swap built-in fetch transit parsing for transito?
    (when-not (get-in @state [:entities id])
      (-> (fetch/get (shared/api-path "/api/entity/" id))
          ;; TODO: handle 404 explicitly
          (.then #(add-entity id (:body %)))))))

(defn fetch-index
  [{:keys [params]}]
  (let [{:keys [type]} params]
    ;; TODO: swap built-in fetch transit parsing for transito?
    (when-not (get-in @state [:index type])
      (-> (fetch/get (shared/api-path "/api/index/" type))
          ;; TODO: handle 404 explicitly
          (.then #(add-index type (:body %)))))))

(defn handle
  [req handler-data]
  (condp = handler-data
    [::fetch-entity] (fetch-entity req)
    [::fetch-index] (fetch-index req)))
