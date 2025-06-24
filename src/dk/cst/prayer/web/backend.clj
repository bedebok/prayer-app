(ns dk.cst.prayer.web.backend
  "The main namespace of the backend web service."
  (:require [dk.cst.prayer.web :as web]
            [dk.cst.prayer.web.backend.html :as html]
            [dk.cst.prayer.web.backend.interceptor :as ic]
            [io.pedestal.http :as http]
            [io.pedestal.http.body-params :refer [body-params]]
            [io.pedestal.http.route :as route]))

(defonce server (atom nil))

(def api-routes
  #{["/api/entity/:id" :get [ic/with-eid ic/entity] :route-name ::entity]
    ["/api/index/:type" :get [ic/by-type] :route-name ::index]
    ["/api/works" :get [ic/works] :route-name ::work-index]
    ["/api/work/:id" :get [ic/with-eid ic/by-work] :route-name ::work]
    ["/api/search/:query" :get [ic/search] :route-name ::search]
    ["/api/db-error" :get [ic/db-error] :route-name ::db-error]
    ["/api/error/:session-id" :post [(body-params) ic/frontend-error] :route-name ::frontend-error]})

(defn backend-route
  "Add common parts to a Pedestal API `route`."
  [[path :as route]]
  (if-let [constraints (web/path->constraints path)]
    (into route [:constraints constraints])
    route))

;; All frontend routes serve the same HTML page + JS blob.
(defn frontend-route
  "Turn a Reitit frontend route into a Pedestal route.

  This mirrors the frontend route in the backend such that any route that can be
  created by the frontend single-page app is also reachable in the backend."
  [[path & {:keys [name]} :as route]]
  (if-let [constraints (web/path->constraints path)]
    [path :get [ic/app] :route-name name :constraints constraints]
    [path :get [ic/app] :route-name name]))

(defn routes
  []
  (route/expand-routes
    (set (into (map backend-route api-routes)
               (map frontend-route web/frontend-routes)))))

(defn ->service-map
  []
  (let [csp (if html/dev?
              {:default-src "'self' 'unsafe-inline' 'unsafe-eval' localhost:* 0.0.0.0:* ws://localhost:* ws://0.0.0.0:* mac:* ws://mac:*"}
              {:default-src "'none'"
               :script-src  "'self' 'unsafe-inline'"        ; unsafe-eval possibly only needed for dev main.js
               :connect-src "'self' 0.0.0.0:*"              ; 0.0.0.0 added for local Docker container
               :img-src     "'self'"
               :font-src    "'self'"
               :style-src   "'self' 'unsafe-inline'"
               :base-uri    "'self'"})]
    (-> {::http/routes          #((deref #'routes))
         ::http/type            :jetty
         ::http/host            web/host
         ::http/port            web/port
         ::http/resource-path   "public"
         ::http/secure-headers  {:content-security-policy-settings csp}
         ::http/request-logger  ic/log-request

         ::http/allowed-origins (if html/dev?
                                  (constantly true)
                                  "https://bedebog.dk")}

        ;; Extending default interceptors here.
        (http/default-interceptors)
        (update ::http/interceptors #(cons %2 %1) ic/trailing-slash)
        (update ::http/interceptors concat [ic/coercion ic/with-db])

        #_(http/enable-debug-interceptor-observer)

        ;; The reason this is enabled for now is that I have no way of
        ;; discerning if something that is running in a Docker container using
        ;; the release version of the app is running in prod or just locally.
        (cond-> html/dev? (ic/dev-interceptors)))))

(defn start-prod []
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

(comment
  (restart)
  #_.)

