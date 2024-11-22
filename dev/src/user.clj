(ns user
  (:require [dk.cst.prayer.web.shared :as shared]))

(defn shadow-handler
  "Handler used by shadow-cljs to orient itself on page load."
  [{:keys [uri query-string] :as request}]
  {:status  200
   :headers {"Content-Type" "text/html"}
   :body    (slurp (shared/api-path uri (when query-string
                                          (str "?" query-string))))})
