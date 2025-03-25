#!/bin/bash

# Start the actual system
java --add-opens=java.base/java.nio=ALL-UNNAMED \
     --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
     -jar prayer-app.jar

# TODO: exit and print the error/stacktrace when needed, e.g. "address in use"
