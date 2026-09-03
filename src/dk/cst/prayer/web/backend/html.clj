(ns dk.cst.prayer.web.backend.html
  "Server-side HTML generation.

  Since this is a single-page app, most of the HTML is generated in the frontend
  and the only HTML generated server-side is a skeleton page."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [replicant.string :as rs])
  (:import [java.util Date]))

(def main-js
  "When making a release, the filename will be appended with a hash;
  that is not the case when running the regular shadow-cljs watch process.

  It relies on :module-hash-names being set to true in shadow-cljs.edn."
  (if-let [url (io/resource "public/js/manifest.edn")]
    (-> url slurp edn/read-string first :output-name)
    "main.js"))

(def dev?
  (= main-js "main.js"))

;; Used not only for cache-busting static assets such as CSS files, but also
;; for clearing localStorage cache (see: dk.cst.prayer.web.frontend namespace).
(def version-hash
  "Unique versioning of the frontend app."
  (abs (hash (Date.))))

;; https://javascript.plainenglish.io/what-is-cache-busting-55366b3ac022
(defn- cb
  "Decorate the supplied `path` with a cache busting string."
  [path]
  (str path "?hash=" version-hash))

(defn index-hiccup
  [negotiated-language]
  ;; NOTE: the lang describes the language of the user interface, which is
  ;; currently English only; TEI content is marked up with its own lang attrs.
  [:html {:lang (or negotiated-language "en")}
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:name    "viewport"
            :content "width=device-width, initial-scale=1.0"}]
    [:title (str (when dev? "(dev) ")
                 "When Danes Prayed in German")]
    #_[:link {:rel "icon" :href (cb "/images/favicon.svg")}]
    #_[:link {:rel "mask-icon" :href (cb "/images/favicon.svg") :color "#a02c2c"}]
    [:link {:rel "stylesheet" :href (cb "/css/main.css")}]
    [:link {:rel "stylesheet" :href (cb "/css/tei.css")}]]
   [:body
    ;; TODO: server-side render the SPA content here and hydrate the frontend.
    [:div#app]
    ;; Rather than having an extra endpoint that the SPA needs to access, these
    ;; values are passed on to the SPA along with the compiled main.js code.
    ;; NOTE: :innerHTML is needed since Replicant escapes all text children,
    ;; which would corrupt the quotes in the JavaScript code.
    [:script {:innerHTML (str "var negotiatedLanguage = '" (pr-str negotiated-language) "';\n"
                              "var versionHash = '" version-hash "';\n")}]
    [:script {:src (cb (str "/js/" main-js))}]]])

(def index-html
  (memoize (comp (partial str "<!DOCTYPE html>") rs/render index-hiccup)))

(defn app-handler
  [{:keys [accept-language] :as request}]
  {:status  200
   :headers {"Content-Type" "text/html"}
   :body    (index-html accept-language)})
