(ns dk.cst.prayer.web.shared
  "Code shared between the frontend single-page app and backend web service."
  (:require [dk.cst.prayer.web.app.api :as-alias api]))

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
  {:id   {:constraint #"[0-9]+"
          :coerce     parse-long}
   #_#_:type {:constraint #"\w+"}})

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
   ["/entity/:id" {:name   ::entity
                   :handle [::api/fetch-entity]}]
   ["/index/:type" {:name   ::index
                    :handle [::api/fetch-index]}]])
