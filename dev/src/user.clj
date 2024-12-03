(ns user
  (:require [dk.cst.prayer.web :as web]))

(defn shadow-handler
  "Handler used by shadow-cljs to orient itself on page load."
  [{:keys [uri query-string] :as request}]
  {:status  200
   :headers {"Content-Type" "text/html"}
   :body    (slurp (web/api-path uri (when query-string
                                       (str "?" query-string))))})
