(ns dk.cst.prayer.web.backend
  "The main namespace of the backend web service."
  (:require [dk.cst.prayer.web :as web]
            [dk.cst.prayer.web.backend.interceptor :as ic]
            [dk.cst.prayer.db :as db]
            [io.pedestal.http :as http]
            [io.pedestal.http.route :as route])
  (:gen-class))

(defonce server (atom nil))

(def api-routes
  #{["/api/entity/:id" :get [ic/with-entity ic/entity] :route-name ::entity]
    ["/api/index/:type" :get [ic/by-type] :route-name ::index]
    ["/api/work/:work" :get [ic/by-work] :route-name ::work]
    ["/api/search/:query" :get [ic/search] :route-name ::search]})

(defn backend-route
  "Add common parts to a Pedestal API `route`."
  [[path :as route]]
  (if-let [constraints (web/path->constraints path)]
    (into route [:constraints constraints])
    route))

(defn frontend-route
  "Turn a Reitit frontend route into a Pedestal route.

  This mirrors the frontend route in the backend such that any route that can be
  created by the frontend single-page app is also reachable in the backend."
  [[path & {:keys [name]} :as route]]
  (if-let [constraints (web/path->constraints path)]
    [path :get [ic/app] :route-name name :constraints constraints]
    [path :get [ic/app] :route-name name]))

(def routes
  (route/expand-routes
    (set (into (map backend-route api-routes)
               (map frontend-route web/frontend-routes)))))

(defn current-routes
  []
  routes)

(defn ->service-map
  []
  (let [csp (if web/development?
              {:default-src "'self' 'unsafe-inline' 'unsafe-eval' localhost:* 0.0.0.0:* ws://localhost:* ws://0.0.0.0:* mac:* ws://mac:*"}
              {:default-src "'none'"
               :script-src  "'self' 'unsafe-inline'"        ; unsafe-eval possibly only needed for dev main.js
               :connect-src "'self'"
               :img-src     "'self'"
               :font-src    "'self'"
               :style-src   "'self' 'unsafe-inline'"
               :base-uri    "'self'"})]
    (-> {::http/routes          #((deref #'current-routes))
         ::http/type            :jetty
         ::http/host            web/host
         ::http/port            web/port
         ::http/resource-path   "public"
         ::http/secure-headers  {:content-security-policy-settings csp}
         ::http/allowed-origins (when web/development?
                                  (constantly true))}

        ;; Extending default interceptors here.
        (http/default-interceptors)
        (update ::http/interceptors #(cons %2 %1) ic/trailing-slash)
        (update ::http/interceptors concat [ic/coercion ic/with-db])

        (cond-> web/development? (http/dev-interceptors)))))

(defn start []
  (let [service-map (->service-map)]
    (http/start (http/create-server service-map))))

(defn start-dev []
  (reset! server (http/start (http/create-server (assoc (->service-map)
                                                   ::http/join? false)))))

(defn stop-dev []
  (http/stop @server))

(defn restart []
  (when @server
    (stop-dev))
  (start-dev))

(defn -main
  [& args]
  (start))

(comment
  @conf
  (do
    (db/build-db! db/files-path db/db-path)
    (restart))
  (stop-dev)
  #_.)
