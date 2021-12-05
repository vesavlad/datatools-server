FROM openjdk:8u121-jre-alpine

RUN apk add --update curl bash ttf-dejavu && rm -rf /var/cache/apk/*

ENV PORT=9966
ENV APP_ROOT="/opt/app"
ENV TIMEZONE="Europe/Bucharest"
ENV STARTUP_SLEEP=0

WORKDIR ${APP_ROOT}

ADD libs/ ${APP_ROOT}/libs/
ADD datatools-*.jar ${APP_ROOT}/app.jar
ADD entrypoint.sh ${APP_ROOT}/entrypoint.sh

EXPOSE ${PORT}

ENTRYPOINT ["./entrypoint.sh"]
