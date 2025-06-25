(ns dk.cst.prayer
  "The entry namespace of the system; both dev and prod envs boot from here."
  (:require [dk.cst.prayer.db :as db]
            [taoensso.telemere :as t]
            [dk.cst.prayer.web :as web]
            [dk.cst.prayer.web.backend :as backend])
  (:gen-class))

;; Setting up backend logging here.
;; See: https://github.com/taoensso/telemere/wiki/3-Config#system-streams
;; Ensure that the logs are written to output, but only for errors.
(t/streams->telemere!)
(t/set-min-level! :info)

;; Starts a production server available at port 3456.
;; NOTE: this is expected to be done by booting system via docker compose!
(defn -main
  [& _args]
  ;; Hardcoded production paths as they are used in the Docker container.
  (alter-var-root #'db/files-path (constantly "/etc/prayer-app/files"))
  (alter-var-root #'db/db-path (constantly "/etc/prayer-app/db"))

  ;; #15 Required to listen outside the Docker container itself.
  (alter-var-root #'web/host (constantly "0.0.0.0"))

  ;; This will hopefully mitigate potential database corruption scenarios.
  ;; See 'datalevin.core/open-kv' for more on this.
  (.addShutdownHook (Runtime/getRuntime) (Thread. #(db/delete-db! db/db-path)))

  ;; We clear any previous database remnants, (re-)build the database and only
  ;; then start up the backend web server.
  (db/rmdir db/db-path)
  (db/build-db! db/db-path db/files-path)
  (backend/start-prod))

(comment
  ;; Check if backend logging works as expected.
  (t/check-interop)
  (t/set-min-level! :error)
  (t/set-min-level! :info)
  (t/set-min-level! :debug)

  ;; Starts a normal dev server available at port 9876.
  ;; NOTE: The following JVM args must be present (required by Datalevin):
  ;;   --add-opens=java.base/java.nio=ALL-UNNAMED
  ;;   --add-opens=java.base/sun.nio.ch=ALL-UNNAMED
  ;; This requires running shadow-cljs to access the frontend:
  ;;   $ npx shadow-cljs watch app
  (do
    (db/rmdir db/db-path)
    (db/build-db! db/db-path
                  "../Data/Gold corpus"
                  "../Data/Manuscripts/xml"
                  "../Data/Texts/xml"
                  "../Data/Works/xml")
    (backend/restart))

  (db/delete-db! db/db-path)
  (datalevin.core/close @db/conn)
  #_.)
