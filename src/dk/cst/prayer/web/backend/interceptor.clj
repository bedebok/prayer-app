(ns dk.cst.prayer.web.backend.interceptor
  "Pedestal interceptors for the backend web service."
  (:require [clj-commons.format.exceptions :as exceptions]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [ring.util.response :as ring-response]
            [taoensso.telemere :as t]
            [io.pedestal.http :as http]
            [io.pedestal.http.cors :as cors]
            [reitit.impl :refer [form-decode]]
            [dk.cst.prayer.static :as static]
            [dk.cst.prayer.web :as web]
            [dk.cst.prayer.web.backend.html :as html]
            [com.wsscode.transito :as transito]
            [io.pedestal.interceptor :refer [interceptor]]
            [datalevin.core :as d]
            [dk.cst.prayer.db :as db]))

(defn- format-exception
  [exception]
  (binding [exceptions/*fonts* nil]
    (exceptions/format-exception exception)))

(defn error-debug
  "When an error propagates to this interceptor error fn, trap it,
  print it to the output stream of the HTTP request, and do not
  rethrow it."
  [context exception]
  #_(t/error! :msg "Dev interceptor caught an exception; Forwarding it as the response."
               :exception exception)
  (assoc context
    :response (-> (ring-response/response
                    (with-out-str (println "Error processing request!")
                                  (println "Exception:\n")
                                  (println (format-exception exception))
                                  (println "\nContext:\n")
                                  (pprint/pprint context)))
                  (ring-response/status 500)
                  (assoc-in [:headers "Access-Control-Allow-Origin"] "*"))))

(def fixed-exception-debug
  "An interceptor which catches errors, renders them to readable text
  and sends them to the user. This interceptor is intended for
  development time assistance in debugging problems in pedestal
  services. Including it in interceptor paths on production systems
  may present a security risk by exposing call stacks of the
  application when exceptions are encountered."
  (interceptor
    {:name  ::fixed-exception-debug
     :error error-debug}))

;; An alternative to the built-in dev-interceptors which is fundamentally broken
;; with regard to CORS: errors cannot be displayed in the browser since it
;; doesn't set the necessary "Access-Control-Allow-Origin: *" header before
;; returning the error result.
(defn dev-interceptors
  [service-map]
  (update service-map ::http/interceptors
          #(into [cors/dev-allow-origin fixed-exception-debug] %)))

(defn basic-response
  [ctx res]
  (update
    ctx :response merge
    (if (not (empty? res))
      {:status  200
       :headers {"Content-Type" "application/transit+json"}
       :body    (transito/write-str res)}
      {:status 404})))

(def trailing-slash
  (letfn [(remove-trailing-slash
            [s]
            (if (and (str/ends-with? s "/") (not= s "/"))
              (subs s 0 (dec (count s)))
              s))]
    (interceptor
      {:name  ::trailing-slash
       :enter (fn [ctx]
                (-> ctx
                    (update-in [:request :uri] remove-trailing-slash)
                    (update-in [:request :path-info] remove-trailing-slash)))})))

(def coercion
  (interceptor
    {:name  ::coercion
     :enter (fn [ctx] (update ctx :request web/coerce-request))}))

(def with-db
  (interceptor
    {:name  ::with-db
     :enter (fn [ctx]
              (assoc ctx :db (d/db (d/get-conn db/db-path static/schema))))
     :leave (fn [{:keys [db] :as ctx}]
              (d/close-db db)
              (dissoc ctx :db))}))

(def with-entity
  (interceptor
    {:name  ::entity
     :enter (fn [{:keys [db request] :as ctx}]
              (assoc ctx :id (->> (d/q '[:find ?e .
                                         :in $ ?id
                                         :where
                                         [?e :bedebok/id ?id]]
                                       db
                                       (get-in request [:params :id])))))}))

(def entity
  (interceptor
    {:name  ::entity
     :enter (fn [{:keys [db id] :as ctx}]
              (let [e (d/entity db id)]
                (update
                  ctx :response merge
                  ;; As Datalevin seems to have a bug where entity
                  ;; always non-empty, we need to check this.
                  (if (> (count (keys e)) 1)
                    {:status  200
                     :headers {"Content-Type" "application/transit+json"}

                     ;; TODO: use a proper solution to datafy
                     ;; https://github.com/juji-io/datalevin/issues/292
                     :body    (-> (d/touch e)
                                  (str)
                                  (edn/read-string)
                                  (dissoc :bedebok/text :db/id) ; clean
                                  (transito/write-str))}
                    {:status 404}))))}))

(def by-work
  (interceptor
    {:name  ::by-work
     :enter (fn [{:keys [db request] :as ctx}]
              (let [key (get-in request [:params :work])
                    res (-> (d/q '[:find ?id ?type
                                   :in $ % ?key
                                   :where
                                   ;; Fetching relevant entities by searching
                                   ;; the tree of manuscript items recursively.
                                   (ancestor ?msItem ?e)
                                   [?e :bedebok/type ?type]
                                   (or [?e :bedebok/type "text"]
                                       [?e :bedebok/type "manuscript"])
                                   [?msItem :bedebok/work ?work]
                                   [?work :tei/key ?key]
                                   [?e :bedebok/id ?id]]
                                 db
                                 ;; The % added to the :in clause above
                                 ;; references the rule set provided below.
                                 db/manuscript-ancestor-rule
                                 key)
                            (->> (group-by second))
                            (update-vals (fn [kvs]
                                           (sort (map first kvs)))))]
                (basic-response ctx res)))}))

(def works
  (interceptor
    {:name  ::works
     :enter (fn [{:keys [db] :as ctx}]
              (let [res (d/q '[:find ?key ?title
                               :in $ %
                               :where
                               ;; Fetching relevant entities by searching
                               ;; the tree of manuscript items recursively.
                               (ancestor ?msItem ?e)
                               (or [?e :bedebok/type "text"]
                                   [?e :bedebok/type "manuscript"])
                               [?msItem :bedebok/work ?work]
                               [?work :tei/key ?key]
                               [?work :tei/title ?title]]
                             db
                             ;; The % added to the :in clause above
                             ;; references the rule set provided below.
                             db/manuscript-ancestor-rule)]
                (basic-response ctx res)))}))

(def search
  (interceptor
    {:name  ::search
     :enter (fn [{:keys [db request] :as ctx}]
              (let [query (form-decode (get-in request [:params :query]))
                    res   (db/search db query)]
                (basic-response ctx res)))}))

(def by-type
  (interceptor
    {:name  ::by-type
     :enter (fn [{:keys [db request] :as ctx}]
              (let [type (get-in request [:params :type])
                    res  (d/q '[:find ?id ?title
                                :in $ ?type
                                :where
                                [?e :bedebok/type ?type]
                                [?e :bedebok/id ?id]
                                [?e :tei/title ?title]]
                              db
                              type)]
                (basic-response ctx res)))}))

(def app
  (interceptor
    {:name  ::app
     :enter (fn [{:keys [request] :as ctx}]
              (assoc ctx :response (html/app-handler request)))}))
