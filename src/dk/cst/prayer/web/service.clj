(ns dk.cst.prayer.web.service
  (:require [clojure.edn :as edn]
            [huff2.core :as h]
            [datalevin.core :as d]
            [dk.cst.prayer.db :as db]
            [io.pedestal.http :as http]
            [reitit.http.coercion :as httpc]
            [com.wsscode.transito :as transito]
            [reitit.coercion.malli]
            [reitit.http :as rh]
            [reitit.pedestal :as rp])
  (:import [java.util Date])
  (:gen-class))

(def development?                                           ; TODO: implement
  true)

(def main-js                                                ; TODO: implement (see Glossematics or clarin-tei)
  "main.js")

(defonce server (atom nil))
(defonce conf (atom {}))                                    ;TODO: use?

(def init-hash
  (hash (Date.)))

;; https://javascript.plainenglish.io/what-is-cache-busting-55366b3ac022
(defn- cb
  "Decorate the supplied `path` with a cache busting string."
  [path]
  (str path "?" init-hash))

(defn index-hiccup
  [{:keys [proxy-prefix] :as conf} negotiated-language]
  (let [proxied    #(str proxy-prefix %)
        proxied-cb (comp cb proxied)]
    [:html {:lang (or negotiated-language "da")}
     [:head
      [:meta {:charset "utf-8"}]
      [:meta {:name    "viewport"
              :content "width=device-width, initial-scale=1.0"}]
      [:title (str (when development? "(dev) ") "When Danes Prayed in German")]
      #_[:link {:rel "icon" :href (proxied-cb "/images/favicon.svg")}]
      #_[:link {:rel "mask-icon" :href (proxied-cb "/images/favicon.svg") :color "#a02c2c"}]
      #_[:link {:rel "stylesheet" :href (proxied-cb "/css/main.css")}]]
     [:body
      [:div#app]
      [:script
       ;; Rather than having an extra endpoint that the SPA needs to access, these
       ;; values are passed on to the SPA along with the compiled main.js code.
       (str "var negotiatedLanguage = '" (pr-str negotiated-language) "';\n"
            "var proxyPrefix = '" proxy-prefix "';\n"
            "var inDevelopmentEnvironment = " development? ";\n")]
      [:script {:src (proxied-cb (str "/js/" main-js))}]]]))

(def index-html
  (memoize (comp h/page index-hiccup)))

(defn ->handler
  [conf]
  (fn handler
    [{:keys [accept-language] :as request}]
    {:status  200
     :headers {"Content-Type" "text/html"}
     :body    (index-html conf accept-language)}))

(def shadow-handler
  (->handler nil))

(def with-conn
  {:enter (fn [ctx]
            (assoc-in ctx [:request :conn] (d/get-conn db/db-path db/schema)))
   :exit  (fn [{:keys [conn] :as ctx}]
            (d/close-db conn)
            (update ctx :request dissoc :conn))})


;; Example code: https://github.com/metosin/reitit/blob/master/examples/pedestal-swagger/src/example/server.clj
;; TODO: set up content negotiation (e.g. transit, edn)
;; TODO: make a working entity route
(defn routes
  [conf]
  ["/"
   ["api/"
    ["entity/:id"
     {:get {:interceptors [with-conn]
            :parameters   {:path {:id int?}}
            :handler      (fn [{:keys [conn parameters]}]
                            (let [id (get-in parameters [:path :id])
                                  e  (d/entity (d/db conn) id)]
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

                                {:status 404})))}}]]
   ["*" {:get {:handler (->handler conf)}}]])

(defn routes-fn
  [conf]
  (routes conf))

(defn ->service-map
  [conf]
  (let [csp (if development?
              {:default-src "'self' 'unsafe-inline' 'unsafe-eval' localhost:* 0.0.0.0:* ws://localhost:* ws://0.0.0.0:* mac:* ws://mac:*"}
              {:default-src "'none'"
               :script-src  "'self' 'unsafe-inline'"        ; unsafe-eval possibly only needed for dev main.js
               :connect-src "'self'"
               :img-src     "'self'"
               :font-src    "'self'"
               :style-src   "'self' 'unsafe-inline'"
               :base-uri    "'self'"})]
    (-> {::http/routes         []                           ; empty, uses reitit
         ::http/type           :jetty
         ::http/host           "0.0.0.0"
         ::http/port           3456
         ::http/resource-path  "public"
         ::http/secure-headers {:content-security-policy-settings csp}}

        ;; Extending default interceptors here.
        (http/default-interceptors)

        ;; The Pedestal router is replaced with reitit.
        ;; https://github.com/metosin/reitit/blob/master/doc/http/pedestal.md
        (rp/replace-last-interceptor
          (rp/routing-interceptor
            ;; NOTE that {:conflicts nil} is what makes the wildcard route work!
            (rh/router (routes conf)
                       {:conflicts nil
                        :data      {:coercion reitit.coercion.malli/coercion
                                    :interceptors
                                    [(httpc/coerce-response-interceptor)
                                     (httpc/coerce-request-interceptor)]}})))

        ;; Interceptors only available during dev.
        (cond-> development? (http/dev-interceptors)))))

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
