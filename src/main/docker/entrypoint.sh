#!/bin/sh

# Fail fast if app start fails
set -e

echo "The application will start in ${STARTUP_SLEEP}s..." && sleep ${STARTUP_SLEEP}
exec java ${JAVA_OPTS} -noverify -XX:+AlwaysPreTouch -Djava.security.egd=file:/dev/./urandom -cp ${APP_ROOT}/app.jar:${APP_ROOT}/resources/:/app/classes/:${APP_ROOT}/libs/* "com.conveyal.datatools.manager.DataManager"  "$@"
