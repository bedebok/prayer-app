(ns dk.cst.prayer.web.service
  (:require [clojure.string :as str]
            [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.ring-middlewares :as middleware])
  (:gen-class))

(defonce server (atom nil))
(defonce conf (atom {}))                                    ;TODO: use?

(defn routes
  []
  (route/expand-routes
    #{}))

(defn remove-trailing-slash
  [s]
  (if (and (str/ends-with? s "/") (not= s "/"))
    (subs s 0 (dec (count s)))
    s))

(def trailing-slash
  (io.pedestal.interceptor/interceptor
    {:name  ::trailing-slash
     :enter (fn [ctx]
              (-> ctx
                  (update-in [:request :uri] remove-trailing-slash)
                  (update-in [:request :path-info] remove-trailing-slash)))}))

(defn ->service-map
  [conf]
  (let [csp (if true ; TODO: make this conditional for prod
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
         ::http/resource-path  "/public"
         ::http/secure-headers {:content-security-policy-settings csp}}

        ;; Extending default interceptors here.
        (http/default-interceptors)
        (update ::http/interceptors #(cons %2 %1) trailing-slash)
        (update ::http/interceptors conj middleware/cookies)

        ;; Make sure we can communicate with the Shadow CLJS app during dev.
        (cond->
          ;; TODO: make this conditional for prod
          true (assoc ::http/allowed-origins (constantly true))))))

(defn start []
  (let [service-map (->service-map @conf)]
    (http/start (http/create-server service-map))))

(defn start-dev []
  (reset! server (http/start (http/create-server (assoc (->service-map @conf)
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
