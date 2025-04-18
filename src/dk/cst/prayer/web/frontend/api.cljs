(ns dk.cst.prayer.web.frontend.api
  "Handlers for Reitit frontend routing matches."
  (:require [dk.cst.hiccup-tools.elem :as e]
            [dk.cst.hiccup-tools.hiccup :as h]
            [dk.cst.prayer.web :as web]
            [dk.cst.prayer.web.frontend.state :as state :refer [state]]
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

;; TODO: create a macro for the (some-> ...) code?
(defn check-response
  "Return nil on server error in `fetch-promise` and write to error log."
  [fetch-promise]
  (.then fetch-promise
         #(if (<= 500 (:status %) 511)
            (let [url (some-> % meta ::fetch/request .-url)]
              ;; NOTE: when throwing an error inside a promise, the error will
              ;;       not be caught by window.onerror, so we should always
              ;;       explicitly call `register-error!` in such cases!
              (state/register-error! {:name    "Server error"
                                      :message (str "status code " (:status %) " received")
                                      :url     url
                                      :body    (:body %)}))
            %)))

;; TODO: swap built-in fetch transit parsing for transito?
;; TODO: handle 404?
(defn fetch
  [url & [opts]]
  (check-response (fetch/get url opts)))

(defn fetch-entity
  [{:keys [params]}]
  (let [{:keys [id]} params]
    (when-not (get-in @state [:entities id])
      (some-> (fetch (web/api-path "/api/entity/" id))
              (.then #(add-entity id (:body %)))))))

(defn fetch-work
  [{:keys [params]}]
  (let [{:keys [id]} params]
    (when-not (get-in @state [:works id])
      (some-> (fetch (web/api-path "/api/work/" id))
              (.then #(add-work id (:body %)))))))

(defn search
  [{:keys [params]}]
  (let [{:keys [query]} params]
    (when-not (get-in @state [:search query])
      (some-> (fetch (web/api-path "/api/search/" query))
              (.then #(add-search-result query (:body %)))))))

(defn fetch-index
  [type]
  (when-not (get-in @state [:index type])
    (some-> (fetch (web/api-path "/api/index/" type))
            (.then #(add-index type (:body %))))))

(defn fetch-works
  []
  (when-not (get-in @state [:index "work"])
    (some-> (fetch (web/api-path "/api/works"))
            (.then #(add-index "work" (:body %))))))

(defn handle
  [req handler-data]
  (condp = handler-data
    [::fetch-entity] (fetch-entity req)
    [::fetch-work] (fetch-work req)
    [::search] (search req)
    [::fetch-index "work"] (fetch-works)
    [::fetch-index "text"] (fetch-index "text")
    [::fetch-index "manuscript"] (fetch-index "manuscript")))
