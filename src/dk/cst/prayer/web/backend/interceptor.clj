(ns dk.cst.prayer.web.backend.interceptor
  "Pedestal interceptors for the backend web service."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [reitit.impl :refer [form-decode]]
            [dk.cst.prayer.static :as static]
            [dk.cst.prayer.web :as web]
            [dk.cst.prayer.web.backend.html :as html]
            [com.wsscode.transito :as transito]
            [io.pedestal.interceptor :refer [interceptor]]
            [datalevin.core :as d]
            [dk.cst.prayer.db :as db]))

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

(def manuscript-ancestor-rule
  '[[(ancestor ?msItem ?ancestor)
     [?ancestor :tei/msItem ?msItem]]
    [(ancestor ?msItem ?ancestor)
     [?parent :tei/msItem ?msItem]
     (ancestor ?parent ?ancestor)]])

(def by-work
  (interceptor
    {:name  ::by-work
     :enter (fn [{:keys [db request] :as ctx}]
              (let [work (get-in request [:params :work])
                    res  (-> (d/q '[:find ?id ?type
                                    :in $ % ?work
                                    :where
                                    ;; Fetching relevant entities by searching
                                    ;; the tree of manuscript items recursively.
                                    (ancestor ?msItem ?e)
                                    [?e :bedebok/type ?type]
                                    (or [?e :bedebok/type "text"]
                                        [?e :bedebok/type "manuscript"])
                                    [?msItem :tei/key ?work]
                                    [?e :bedebok/id ?id]]
                                  db
                                  ;; The % added to the :in clause above
                                  ;; references the rule set provided below.
                                  manuscript-ancestor-rule
                                  work)
                             (->> (group-by second))
                             (update-vals (fn [kvs]
                                            (sort (map first kvs)))))]
                (update
                  ctx :response merge
                  (if (not (empty? res))
                    {:status  200
                     :headers {"Content-Type" "application/transit+json"}
                     :body    (transito/write-str res)}
                    {:status 404}))))}))

(def search
  (interceptor
    {:name  ::search
     :enter (fn [{:keys [db request] :as ctx}]
              (let [query (form-decode (get-in request [:params :query]))
                    res   (db/search db query)]
                (update
                  ctx :response merge
                  (if (not (empty? res))
                    {:status  200
                     :headers {"Content-Type" "application/transit+json"}
                     :body    (transito/write-str res)}
                    {:status 404}))))}))

(def by-type
  (interceptor
    {:name  ::by-type
     :enter (fn [{:keys [db request] :as ctx}]
              (let [type (get-in request [:params :type])
                    res  (d/q '[:find ?e ?id
                                :in $ ?type
                                :where
                                [?e :bedebok/type ?type]
                                [?e :bedebok/id ?id]]
                              db
                              type)]
                (update
                  ctx :response merge
                  (if (not (empty? res))
                    {:status  200
                     :headers {"Content-Type" "application/transit+json"}
                     :body    (transito/write-str (sort-by second res))}
                    {:status 404}))))}))

(def app
  (interceptor
    {:name  ::app
     :enter (fn [{:keys [request] :as ctx}]
              (assoc ctx :response (html/app-handler request)))}))
