(ns dk.cst.prayer.web.frontend.api
  "Handlers for Reitit frontend routing matches."
  (:require [dk.cst.prayer.web :as web]
            [dk.cst.prayer.web.frontend.state :refer [state]]
            [lambdaisland.fetch :as fetch]))

(defn add-entity
  [id e]
  (when-not (empty? e)
    (swap! state assoc-in [:entities id] e)))

(defn add-work
  [id work]
  (when-not (empty? work)
    (swap! state assoc-in [:works id] work)))

(defn add-search-result
  [query search-result]
  (when-not (empty? search-result)
    (swap! state assoc-in [:search query] search-result)))

(defn add-index
  [type kvs]
  (when-not (empty? kvs)
    (swap! state assoc-in [:index type] kvs)))

(defn fetch-entity
  [{:keys [params]}]
  (let [{:keys [id]} params]
    ;; TODO: swap built-in fetch transit parsing for transito?
    (when-not (get-in @state [:entities id])
      (-> (fetch/get (web/api-path "/api/entity/" id))
          ;; TODO: handle 404 explicitly
          (.then #(add-entity id (:body %)))))))

(defn fetch-work
  [{:keys [params]}]
  (let [{:keys [id]} params]
    ;; TODO: swap built-in fetch transit parsing for transito?
    (when-not (get-in @state [:works id])
      (-> (fetch/get (web/api-path "/api/work/" id))
          ;; TODO: handle 404 explicitly
          (.then #(add-work id (:body %)))))))

(defn search
  [{:keys [params]}]
  (let [{:keys [query]} params]
    ;; TODO: swap built-in fetch transit parsing for transito?
    (when-not (get-in @state [:search query])
      (-> (fetch/get (web/api-path "/api/search/" query))
          ;; TODO: handle 404 explicitly
          (.then #(add-search-result query (:body %)))))))

(defn fetch-index
  [type]
  ;; TODO: swap built-in fetch transit parsing for transito?
  (when-not (get-in @state [:index type])
    (-> (fetch/get (web/api-path "/api/index/" type))
        ;; TODO: handle 404 explicitly
        (.then #(add-index type (:body %))))))

(defn handle
  [req handler-data]
  (condp = handler-data
    [::fetch-entity] (fetch-entity req)
    [::fetch-work] (fetch-work req)
    [::search] (search req)
    [::fetch-index "text"] (fetch-index "text")
    [::fetch-index "manuscript"] (fetch-index "manuscript")))
