(ns dk.cst.prayer.web.frontend.state
  "The state referenced in various parts of the frontend code.")

(defn default-state
  []
  {:user {:session-id (random-uuid)
          :pins       []}})

(defonce state
  (atom (default-state)))

;; NOTE: this value will be automatically carried over from localStorage unless
;;       the user performs a hard page refresh!
(def session-id
  "For identifying the current session/user when logging frontend errors."
  (get-in @state [:user :session-id]))
