(ns dk.cst.prayer.web.interceptors
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [dk.cst.prayer.web.html :as html]
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

(def with-db
  (interceptor
    {:name  ::with-db
     :enter (fn [ctx]
              (assoc ctx :db (d/db (d/get-conn db/db-path db/schema))))
     :leave (fn [{:keys [db] :as ctx}]
              (d/close-db db)
              (dissoc ctx :db))}))

(def entity
  (interceptor
    {:name  ::entity
     :enter (fn [{:keys [db] :as ctx}]
              (let [id 1 #_(get-in parameters [:path :id])
                    e  (d/entity db id)]
                (update
                  ctx :response merge
                  ;; As Datalevin seems to have a bug where entity
                  ;; always non-empty, we need to check this.
                  (if (> (count (keys e)) 1)
                    {:status  200
                     :headers {"Content-Type" "application/transit+json"}

                     ;; TODO: use a proper solution to datafy
                     ;;       (need to wait for a fix in Datalevin)
                     :body    (-> (d/touch e)
                                  (str)
                                  (edn/read-string)
                                  (transito/write-str))}
                    {:status 404}))))}))

(def app
  (interceptor
    {:name  ::app
     :enter (fn [{:keys [request] :as ctx}]
              (assoc ctx :response (html/app-handler request)))}))
