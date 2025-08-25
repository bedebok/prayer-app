(ns dk.cst.prayer.web.frontend.api
  "Handlers for Reitit frontend routing matches that all directly translate into
  async backend API calls."
  (:require [dk.cst.hiccup-tools.elem :as e]
            [dk.cst.hiccup-tools.hiccup :as h]
            [dk.cst.prayer.web :as web]
            [dk.cst.prayer.web.frontend.error :as err]
            [dk.cst.prayer.web.frontend.state :as state :refer [state]]
            [taoensso.telemere :as t]
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
  (swap! state assoc-in [:entities id] entity)

  ;; Generate paginated content and cache it for subsequent views.
  (when (= "text" (:bedebok/type entity))
    (swap! state assoc-in [:cached id :pages]
           (node->pages (:file/node entity)))))

(defn add-work
  [id work]
  (swap! state assoc-in [:works id] work))

(defn add-search-result
  [query search-result]
  (swap! state assoc-in [:search query] search-result))

(defn add-index
  [type kvs]
  (swap! state assoc-in [:index type] kvs))

(defn coll-body
  "Make sure that only a collection is returned from the `response`.

  This is to ensure that e.g. error strings or other unexpected output doesn't
  blow up the frontend in some way."
  [response]
  (let [body (:body response)]
    (if (coll? body)
      body
      (do (t/log! {:level    :warn
                   :url      (some-> response meta ::fetch/request .-url)
                   :response response}
                  "Response body is not a collection, ignoring.")
          nil))))

;; TODO: create a macro for the (some-> ...) code?
(defn cancel-on-5xx!
  "Return nil on server error in `fetch-promise` and write to error log."
  [fetch-promise]
  (.then fetch-promise
         #(if (<= 500 (:status %) 599)
            (let [url (some-> % meta ::fetch/request .-url)]
              ;; NOTE: when throwing an error inside a promise, the error will
              ;;       not be caught by window.onerror, so we should always
              ;;       explicitly call `display!` in such cases!
              (err/display! {:name    "Server error"
                             :message (str "status code " (:status %) " received")
                             :url     url
                             :body    (:body %)}))
            %)))

(defn warn-on-4xx!
  "Return nil on server error in `fetch-promise` and write to error log."
  [fetch-promise]
  (.then fetch-promise
         #(if (<= 400 (:status %) 499)
            (let [url    (some-> % meta ::fetch/request .-url)
                  status (:status %)]
              (t/log! {:level :warn
                       :data  {:url    url
                               :status status}}
                      (str "Could not fetch: " url " (" status ")")))
            %)))

;; TODO: swap built-in fetch transit parsing for transito?
(defn fetch
  [url & [opts]]
  (->> (assoc opts
         :headers {"X-session-id" state/session-id})        ; track in logs
       (fetch/get url)
       (warn-on-4xx!)
       (cancel-on-5xx!)))

(defn fetch-entity
  [{:keys [params]}]
  (let [{:keys [id]} params]
    (when-not (contains? (:entities @state) id)
      (some-> (fetch (web/api-path "/api/entity/" id))
              (.then #(add-entity id (coll-body %)))))))

(defn fetch-work
  [{:keys [params]}]
  (let [{:keys [id]} params]
    (when-not (contains? (:works @state) id)
      (some-> (fetch (web/api-path "/api/work/" id))
              (.then #(add-work id (coll-body %)))))))

(defn search
  [{:keys [params]}]
  (let [{:keys [query]} params]
    (when-not (contains? (:search @state) query)
      (some-> (fetch (web/api-path "/api/search/" query))
              (.then #(add-search-result query (coll-body %)))))))

(defn fetch-index
  [type]
  (when-not (contains? (:index @state) type)
    (some-> (fetch (web/api-path "/api/index/" type))
            (.then #(add-index type (coll-body %))))))

(defn fetch-works
  []
  (when-not (contains? (:index @state) "work")
    (some-> (fetch (web/api-path "/api/works"))
            (.then #(add-index "work" (coll-body %))))))

(defn fetch-db-error
  []
  (when-not (contains? (:error @state) :db)
    (some-> (fetch (web/api-path "/api/db-error"))
            (.then #(swap! state assoc :db-error (coll-body %))))))

(defn backend-log
  [error-data]
  (fetch/post (web/api-path "/api/error/" state/session-id) {:body error-data})

  ;; ;; https://developer.mozilla.org/en-US/docs/Web/API/Navigator/sendBeacon
  ;; This is the ideal alternative, but it is blocked by e.g. UBlock Origin.
  #_(.sendBeacon js/navigator (web/api-path "/api/error/" state/session-id) error-data))

(defn handle
  [req handler-data]
  (case handler-data
    [::search] (search req)
    [::fetch-db-error] (fetch-db-error)
    [::fetch-entity] (fetch-entity req)
    [::fetch-work] (fetch-work req)
    [::fetch-index "work"] (fetch-works)
    [::fetch-index "text"] (fetch-index "text")
    [::fetch-index "manuscript"] (fetch-index "manuscript")))
