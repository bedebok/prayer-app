(ns dk.cst.prayer.web.frontend.error
  (:require [dk.cst.prayer.web.frontend.state :refer [state]]))

(defn error->data
  "Get error data from an `error`."
  [error]
  (if (map? error)
    error
    (assoc
      (if (instance? ExceptionInfo error)
        (ex-data error)
        {:name    (.-name error)
         :message (.-message error)})

      ;; Add these values to get the full picture.
      :url js/window.location.href
      :body (.-stack error))))

;; The following data keys are allowed: :name, :message, :url, and :body.
(defn display!
  "Universal registration of `error-data` for display in the UI."
  [error-data]
  (swap! state assoc :error error-data))
