(ns dk.cst.prayer.web.shared
  "Code shared between the frontend single-page app and backend web service."
  (:require [dk.cst.prayer.web.frontend.api :as-alias api]))

(def port
  3456)

(def host
  "localhost")

(def protocol
  "http://")

(def api-prefix
  (str protocol host ":" port))

(defn api-path
  [& path]
  (apply str api-prefix path))

(def development?
  true)

(def coercion
  {:type {:constraint #"text|manuscript"}})                 ; TODO: expand

(defn path->constraints
  [path]
  (-> (->> (re-seq #":[^/]+" path)
           (map (comp keyword #(subs % 1)))
           (select-keys coercion))
      (update-vals :constraint)
      (not-empty)))

(defn coerce-params
  [params]
  (->> (for [[k v] params]
         (let [{:keys [constraint coerce]} (get coercion k)]
           ;; Only apply constraints in ClojureScript here as constraints are
           ;; already applied at the route level in Pedestal.
           #?(:cljs (when (and constraint (not (re-matches constraint v)))
                      (throw (ex-info (str k " constraint did not match: " v)
                                      {:params   params
                                       :coercion coercion}))))
           (if coerce
             [k (coerce v)]
             [k v])))
       (into {})))

(defn coerce-request
  [{:keys [path-params query-params] :as req}]
  (assoc req :params (coerce-params (merge path-params query-params))))

(def frontend-routes
  "Reitit routes for the frontend (also shared with the backend)."
  [["/" {:name ::main}]
   ["/texts" {:name   ::text-index
              :handle [::api/fetch-index "text"]}]
   ["/manuscripts" {:name   ::manuscript-index
                    :handle [::api/fetch-index "manuscript"]}]
   ["/works/:id" {:name   ::work
                  :handle [::api/fetch-work]}]
   ["/texts/:id" {:name   ::text
                  :handle [::api/fetch-entity]}]
   ["/manuscripts/:id" {:name   ::manuscript
                        :handle [::api/fetch-entity]}]])
