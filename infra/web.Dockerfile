# Immutable web edge image: the pinned Nginx base with the verified frontend
# build and the routing templates baked in. No TLS material is baked; direct
# TLS deployments keep supplying certificates through Compose secrets, and
# CloudFront deployments terminate TLS at the edge.
ARG NGINX_IMAGE=nginx:1.30.6-alpine-slim@sha256:f65ed258206d1eb6c4eb6141111715a2cf94ac0a4ec9675fc2da186f7aadb163
FROM ${NGINX_IMAGE}

COPY dist/ /usr/share/nginx/html/
COPY templates/ /etc/nginx/templates/

# Match the unprivileged runtime the Compose nginx service declares: uid/gid
# 101 is the image's nginx account, and the writable paths it needs are
# supplied as Compose tmpfs mounts.
USER 101:101

EXPOSE 8080 8443
