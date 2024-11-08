(ns dk.cst.prayer.web.shared
  "Code shared between the frontend single-page app and backend web service."
  (:require [dk.cst.prayer.web.app.api :as-alias api]))

(def development?
  true)

(def coercion
  {:id {:constraint #"[0-9]+"
        :coerce     parse-long}})

(defn path->constraints
  [path]
  (-> (->> (re-seq #":[^/]+" path)
           (map (comp keyword #(subs % 1)))
           (select-keys coercion))
      (update-vals :constraint)
      (not-empty)))

(def frontend-routes
  "Reitit routes for the frontend (also shared with the backend)."
  [["/" {:name ::main}]
   ["/entity/:id" {:name   ::entity
                   :handle [::api/fetch-entity]}]])
