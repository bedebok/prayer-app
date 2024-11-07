(ns dk.cst.prayer.web.service
  (:require [dk.cst.prayer.web.html :as html]
            [dk.cst.prayer.web.interceptors :as ic]
            [io.pedestal.http :as http]
            [io.pedestal.http.route :as route])
  (:gen-class))

(def development?                                           ; TODO: implement
  true)

(defonce server (atom nil))

(def constraints
  {:id #"[0-9]+"})

(defn routes
  []
  (route/expand-routes
    #{["/api/entity/:id" :get [ic/with-db ic/entity] :constraints constraints]
      ["/entity/:id" :get [ic/app] :constraints constraints]}))

(defn ->service-map
  []
  (let [csp (if development?
              {:default-src "'self' 'unsafe-inline' 'unsafe-eval' localhost:* 0.0.0.0:* ws://localhost:* ws://0.0.0.0:* mac:* ws://mac:*"}
              {:default-src "'none'"
               :script-src  "'self' 'unsafe-inline'"        ; unsafe-eval possibly only needed for dev main.js
               :connect-src "'self'"
               :img-src     "'self'"
               :font-src    "'self'"
               :style-src   "'self' 'unsafe-inline'"
               :base-uri    "'self'"})]
    (-> {::http/routes         #((deref #'routes))
         ::http/type           :jetty
         ::http/host           "0.0.0.0"
         ::http/port           3456
         ::http/resource-path  "public"
         ::http/secure-headers {:content-security-policy-settings csp}}

        ;; Extending default interceptors here.
        (http/default-interceptors)
        (update ::http/interceptors #(cons %2 %1) ic/trailing-slash)

        (cond-> development? (http/dev-interceptors)))))

(def shadow-handler
  html/app-handler)

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
  (restart)
  (stop-dev)
  #_.)
