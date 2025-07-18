(ns dk.cst.prayer.web.frontend.event
  "Handlers for Replicant event dispatches.

  For handlers accessing the backend, see: 'dk.cst.prayer.web.frontend.api'."
  (:require [clojure.string :as str]
            [reitit.impl :refer [form-encode]]
            [dk.cst.prayer.web :as web]
            [dk.cst.prayer.web.frontend.state :as state :refer [state]]))

(defn e->v
  [dom-event]
  (some-> dom-event .-target .-value))

;; Q: why not a custom ordered set data type + regular disj?
;; A: because we need to cache it as EDN in localStorage.
(defn vec-disj
  [coll v]
  (vec (remove #{v} coll)))

(defn handle
  [{:keys [replicant/dom-event replicant/node] :as replicant-data} handler-data]
  (condp = (first handler-data)
    ::throw (let [[_ {:keys [name message] :as m}] handler-data
                  ex-message (str/join ": " (remove nil? [name message]))]
              (throw (ex-info ex-message m)))
    ::reset-state (reset! state (merge
                                  (select-keys @state [:location])
                                  (state/default-state)))
    ::toggle (swap! state update-in [:user :prefs (second handler-data)] not)
    ::page (let [[_ id arg] handler-data]
             (.preventDefault dom-event)
             (cond
               (= arg :forward) (swap! state update-in [:user :entities id :n] inc)
               (= arg :backward) (swap! state update-in [:user :entities id :n] dec)
               :else (let [v* (e->v dom-event)
                           v  (if v* (parse-double v*) 0)]
                       (swap! state assoc-in [:user :entities id :n] v))))
    ::pin (let [id   (second handler-data)
                pins (get-in @state [:user :pins])]
            (if (empty? (filter #{id} pins))
              (swap! state update-in [:user :pins] conj id)
              (swap! state update-in [:user :pins] vec-disj id)))
    ::reset-pins (swap! state update-in [:user :pins] empty)
    ::reset-error (swap! state assoc :error nil)
    ::select (do
               (.preventDefault dom-event)
               (.select node))
    ::search (do
               (.preventDefault dom-event)
               (let [{:strs [query]} (->> (new js/FormData node)
                                          (.entries)
                                          (map (juxt first second))
                                          (into {}))
                     path (str "/search/" (form-encode query))]
                 (when (and (not-empty query) (not= path js/location.pathname))
                   (web/navigate-to path))))))
