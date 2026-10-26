#!/bin/sh
# Builds the ACL file from env at start so no password is committed or baked into the image.
# The default user is disabled; every client gets its own user.
# NB: the ACL file format does not allow comment lines. infisical: job-queue user, no admin commands,
# not prefix-restricted (key names not verified). Client users are added here as each tool is configured (key patterns are verified then).
set -eu

cat > /tmp/users.acl <<ACL
user default off
user admin on >${REDIS_ADMIN_PASSWORD} ~* &* +@all
user infisical on >${INFISICAL_REDIS_PASSWORD} ~* &* +@all -@admin
user apisix on >${APISIX_REDIS_PASSWORD} ~plugin-limit-count:* +@all -@admin -@dangerous
ACL

exec redis-server /etc/redis/redis.conf --aclfile /tmp/users.acl
