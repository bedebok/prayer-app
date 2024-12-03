(ns dk.cst.prayer.web.backend.html
  "Server-side HTML generation."
  (:require [huff2.core :as h]
            [dk.cst.prayer.web :as web])
  (:import [java.util Date]))

(def main-js                                                ; TODO: implement (see Glossematics or clarin-tei)
  "main.js")

(def init-hash
  (hash (Date.)))

;; https://javascript.plainenglish.io/what-is-cache-busting-55366b3ac022
(defn- cb
  "Decorate the supplied `path` with a cache busting string."
  [path]
  (str path "?v=" (abs init-hash)))

(defn index-hiccup
  [negotiated-language]
  [:html {:lang (or negotiated-language "da")}
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:name    "viewport"
            :content "width=device-width, initial-scale=1.0"}]
    [:title (str (when web/development? "(dev) ")
                 "When Danes Prayed in German")]
    #_[:link {:rel "icon" :href (cb "/images/favicon.svg")}]
    #_[:link {:rel "mask-icon" :href (cb "/images/favicon.svg") :color "#a02c2c"}]
    [:link {:rel "stylesheet" :href (cb "/css/main.css")}]
    [:link {:rel "stylesheet" :href (cb "/css/tei.css")}]]
   [:body
    [:div#app]
    [:script
     ;; Rather than having an extra endpoint that the SPA needs to access, these
     ;; values are passed on to the SPA along with the compiled main.js code.
     (str "var negotiatedLanguage = '" (pr-str negotiated-language) "';\n")]
    [:script {:src (cb (str "/js/" main-js))}]]])

(def index-html
  (memoize (comp h/page index-hiccup)))

(defn app-handler
  [{:keys [accept-language] :as request}]
  {:status  200
   :headers {"Content-Type" "text/html"}
   :body    (index-html accept-language)})
