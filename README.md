# SingleStore driver for Metabase

SingleStore plugin driver for Metabase. The driver inherits from MySQL where behavior matches and uses the official SingleStore JDBC client.

Metabase Cloud does not support community drivers. See [Community drivers](https://www.metabase.com/docs/latest/developers-guide/community-drivers).

## Install (for self hosted metabase)

1. Download `singlestore.metabase-driver.jar` from [GitHub Releases](https://github.com/singlestore-labs/metabase/releases) (tags named `v*`, e.g. `v1.0.0`).
2. Copy the JAR into your Metabase `plugins/` directory (or set `MB_PLUGINS_DIR`).
3. Restart Metabase.
4. **Admin settings → Databases → Add database → SingleStore**.

Requires a self-hosted Metabase build compatible with the driver release notes.

## Connection

| Field | Notes |
|-------|--------|
| Host / Port | Default port `3306` |
| Database name | Required for sync |
| SSL / SSH tunnel | Supported via standard Metabase connection UI |
| Additional options | e.g. `connectTimeout=10000&socketTimeout=60000` |


## Build from source

```bash
./bin/build-driver.sh singlestore
clojure -X:build:build/verify-driver :driver :singlestore
```

Output: `resources/modules/singlestore.metabase-driver.jar`

## Test locally

```bash
docker compose -f modules/drivers/singlestore/docker-compose.yml up -d

DRIVERS=singlestore \
MB_SINGLESTORE_TEST_HOST=localhost \
MB_SINGLESTORE_TEST_PORT=3306 \
MB_SINGLESTORE_TEST_USER=root \
MB_SINGLESTORE_TEST_PASSWORD=metabase \
clojure -X:dev:drivers:drivers-dev:test \
  :only-tags '[:mb/driver-tests]'
```
