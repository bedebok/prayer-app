(ns dk.cst.prayer.web
  "Code shared between the frontend single-page app and backend web service."
  (:require [dk.cst.prayer.web.frontend.api :as-alias api]
            #?(:cljs [reitit.frontend.easy :as rfe])
            #?(:cljs [reitit.frontend.history :as rfh])))

(def port
  3456)

;; NOTE: must be 0.0.0.0 when running in a container, cannot be localhost!
(def host
  "localhost")

(def protocol
  "http://")

(def api-prefix
  (str protocol host ":" port))

(defn api-path
  [& path]
  (apply str api-prefix path))

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

;; This function was originally copied over from the DanNet source code.
(defn navigate-to
  "Navigate to internal `url` using reitit.

  Optionally, specify whether to `replace` the state in history."
  [url & [replace]]
  #?(:cljs (let [history @rfe/history]
             (if replace
               (.replaceState js/window.history nil "" (rfh/-href history url))
               (.pushState js/window.history nil "" (rfh/-href history url)))
             (rfh/-on-navigate history url))))

(def frontend-routes
  "Reitit routes for the frontend (also shared with the backend)."
  [["/" {:name ::main}]
   ["/privacy" {:name ::privacy}]
   ["/texts" {:name   ::text-index
              :handle [::api/fetch-index "text"]}]
   ["/manuscripts" {:name   ::manuscript-index
                    :handle [::api/fetch-index "manuscript"]}]
   ["/works" {:name   ::work-index
              :handle [::api/fetch-index "work"]}]
   ["/search/:query" {:name   ::search
                      :handle [::api/search]}]
   ["/works/:id" {:name   ::work
                  :handle [::api/fetch-work]}]
   ["/texts/:id" {:name   ::text
                  :handle [::api/fetch-entity]}]
   ["/manuscripts/:id" {:name   ::manuscript
                        :handle [::api/fetch-entity]}]])
