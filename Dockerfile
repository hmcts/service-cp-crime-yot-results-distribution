# See ci-build-publish.yml which sets baseImage=hmcts/apm-services:25-jre and agentDemand:ubuntu-j25
# azure pipeline replaces $BASE_IMAGE with crmdvrepo01.azurecr.io + $baseImage
# This image has the hmcts self signing certificate authority added to truststore so we dont need to worry about about the certs
# If pulling this locally we need to authenticate to acr ... az login; az acr login -n crmdvrepo01
ARG BASE_IMAGE
# The default is only used for a local build; ADO substitutes the ACR base and CI passes the same
# noble digest. Kept on the deployed base's Ubuntu release (24.04) so the package names below
# resolve locally too: 26.04 renames libapt-pkg6.0t64 to libapt-pkg7.0.
FROM ${BASE_IMAGE:-eclipse-temurin:25-jre-noble@sha256:398f810215757dc1926390014272579fb0e57c41ef1c8aa4f64ae761613a168b}

# install curl for debugging; take openssl (CVE-2026-84782), apt (CVE-2026-109518) and brotli
# (CVE-2026-111497) past the base image's until the base image is rebuilt with them
RUN apt-get update \
    && apt-get install -y curl \
    && apt-get install -y --only-upgrade openssl libssl3t64 apt libapt-pkg6.0t64 libbrotli1 \
    && rm -rf /var/lib/apt/lists/*

# run as non-root ... group and user "app"
RUN groupadd -r app && useradd -r -g app app
WORKDIR /app

# ---- Application files ----
COPY docker/* /app/
COPY build/libs/*.jar /app/
# The agent config reads the connection string from APPLICATIONINSIGHTS_CONNECTION_STRING.
# The per-environment value is mounted from Key Vault by the Secrets Store CSI driver and
# exported into the container environment; it is never baked into the image.
COPY lib/applicationinsights.json /app/

USER app
ENTRYPOINT ["/bin/sh","./startup.sh"]