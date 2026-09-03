#!/bin/bash

# exec makes Java replace the shell as PID 1, so that SIGTERM from
# e.g. `docker stop` reaches the JVM directly and the shutdown hook
# (database cleanup) gets to run.
exec java --add-opens=java.base/java.nio=ALL-UNNAMED \
          --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
          -jar prayer-app.jar
