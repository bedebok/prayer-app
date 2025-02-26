(ns dk.cst.prayer.web.frontend.api
  "Handlers for Reitit frontend routing matches."
  (:require [dk.cst.hiccup-tools.elem :as e]
            [dk.cst.hiccup-tools.hiccup :as h]
            [dk.cst.prayer.web :as web]
            [dk.cst.prayer.web.frontend.state :refer [state]]
            [lambdaisland.fetch :as fetch]))

(defn node->pages
  "Paginate a TEI Hiccup node."
  [node]
  (as-> (h/get node :tei-body) $
        (h/split :tei-pb $ :retain :between)
        (e/children $)
        (partition-by #(= :tei-pb (first %)) $)
        (partition 2 $)))

(defn add-entity
  [id entity]
  (when-not (empty? entity)
    (swap! state assoc-in [:entities id] entity)

    ;; Generate paginated content and cache it for subsequent views.
    (when (= "text" (:bedebok/type entity))
      (swap! state assoc-in [:cached id :pages]
             (node->pages (:file/node entity))))))

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

(defn fetch-works
  []
  ;; TODO: swap built-in fetch transit parsing for transito?
  (when-not (get-in @state [:index "work"])
    (-> (fetch/get (web/api-path "/api/works"))
        ;; TODO: handle 404 explicitly
        (.then (fn [resp]
                 ;; TODO: return kvs from AP
                 ;;       (currently only keys are returned, not titles)
                 (let [kvs (zipmap (:body resp) (:body resp))]
                   (add-index "work" kvs)))))))

(defn handle
  [req handler-data]
  (condp = handler-data
    [::fetch-entity] (fetch-entity req)
    [::fetch-work] (fetch-work req)
    [::search] (search req)
    [::fetch-index "work"] (fetch-works)
    [::fetch-index "text"] (fetch-index "text")
    [::fetch-index "manuscript"] (fetch-index "manuscript")))
