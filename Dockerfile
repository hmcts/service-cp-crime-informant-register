# See ci-build-publish.yml which sets baseImage=hmcts/apm-services:25-jre and agentDemand:ubuntu-j25
# azure pipeline replaces $BASE_IMAGE with crmdvrepo01.azurecr.io + $baseImage
# This image has the hmcts self signing certificate authority added to truststore so we dont need to worry about about the certs
# If pulling this locally we need to authenticate to acr ... az login; az acr login -n crmdvrepo01
ARG BASE_IMAGE
FROM ${BASE_IMAGE:-eclipse-temurin:25-jre}

# install curl for debugging
RUN apt-get update \
    && apt-get install -y curl \
    && rm -rf /var/lib/apt/lists/*

# run as non-root ... group and user "app"
RUN groupadd -r app && useradd -r -g app app
WORKDIR /app

# ---- Application files ----
COPY docker/* /app/
COPY build/libs/*.jar /app/
COPY lib/applicationinsights.json /app/

USER app
ENTRYPOINT ["/bin/sh","./startup.sh"]