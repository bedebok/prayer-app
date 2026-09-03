#!/bin/bash

# exec makes Caddy replace the shell as PID 1 for direct signal handling.
exec /usr/bin/caddy run --environ --config /etc/caddy/Caddyfile
