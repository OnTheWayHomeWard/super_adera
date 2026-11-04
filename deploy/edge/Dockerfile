# Edge for staging: nginx serving the documentation, plus the two dev inboxes behind basic auth (see nginx.conf).
# Build from the repo root: docker build -f deploy/edge/Dockerfile .
FROM nginx:1.29-alpine
COPY deploy/edge/nginx.conf /etc/nginx/conf.d/default.conf
COPY deploy/edge/sms /usr/share/nginx/sms
COPY docs /usr/share/nginx/html
# Not published: local credential files and drafts that are gitignored never reach the image (CI builds from git).
RUN rm -f /usr/share/nginx/html/*.env /usr/share/nginx/html/*credentials*
