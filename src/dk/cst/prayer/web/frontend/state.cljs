(ns dk.cst.prayer.web.frontend.state)

;; TODO: carry over from localStorage when available
(def session-id
  "For identifying the current session/user when logging frontend errors."
  (random-uuid))

(defonce state
  (atom {:user {:session-id session-id
                :pins       []}}))

;; TODO: keep error log in the client?
;; The following data keys are allowed: :name, :message, :url, and :body.
(defn register-error!
  "Universal registration of exceptions/errors for display in the UI."
  [error]
  (swap! state assoc :error (cond
                              (map? error)
                              error

                              (instance? ExceptionInfo error)
                              (ex-data error)

                              :else
                              {:name    (.-name error)
                               :message (.-message error)})))
