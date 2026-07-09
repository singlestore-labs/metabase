
## Release process

Releases are fully automated. The only step required is to push a version tag.

Use semantic versioning with a `v` prefix:

```
v<major>.<minor>.<patch>[-<pre-release>]
```

Examples:

- Stable: `v1.0.0`, `v1.2.3`
- Pre-release: `v1.0.0-alpha.1`, `v1.0.0-rc.1`

```bash
# Stable release
git tag v1.0.1
git push origin v1.0.1

# Pre-release
git tag v1.0.1-alpha.1
git push origin v1.0.1-alpha.1
```

The release version is derived from the tag (the leading `v` is stripped). Consumers can install a specific version by downloading `singlestore.metabase-driver.jar` from the matching [GitHub Release](https://github.com/singlestore-labs/metabase/releases) (for example, the release created for tag `v1.0.1`).

Pushing a tag triggers the [SingleStore Driver Release workflow](../../.github/workflows/singlestore-driver-release.yml), which builds and verifies the driver JAR, then creates a GitHub Release with auto-generated release notes and attaches `singlestore.metabase-driver.jar`.

Before tagging, merge your changes to `master` and confirm the **SingleStore Driver CI** workflow has passed on that commit.

## Driver versioning

The driver uses **its own semantic version**, independent of the Metabase version in this fork.

| What | Where | Example |
|------|--------|---------|
| **Release tag** | Git tag / GitHub Release | `v1.0.1` |
| **Driver version (in JAR)** | `resources/metabase-plugin.yaml` → `info.version` | `1.0.1` |
| **Metabase compatibility** | Release notes / README | Built against Metabase `v0.57.x` at commit `abc123` |

The git tag names the release and triggers CI. The version Metabase displays for the plugin comes from `metabase-plugin.yaml`, which is **not** updated automatically when you push a tag. Before tagging, set `info.version` in `modules/drivers/singlestore/resources/metabase-plugin.yaml` to match the release (without the `v` prefix):

```bash
# Example: releasing v1.0.1
# 1. Set info.version to 1.0.1 in metabase-plugin.yaml, commit to master
# 2. Tag and push
git tag v1.0.1
git push origin v1.0.1
```

Metabase and the driver do not share one version number. A driver `v1.0.1` may be built from a fork based on Metabase `v0.57.5`. Document the supported Metabase version range in each GitHub Release so users install a JAR that matches their Metabase build.

## CI integration tests

CI runs unit tests plus a curated integration suite:

```bash
clojure -X:dev:ci:ee:ee-dev:drivers:drivers-dev:singlestore-ci-test
```

This runs `:mb/driver-tests` while excluding:

- `:mb/upload-tests` and `:mb/transforms-python-test` (tags)
- Namespaces and individual tests listed in `resources/singlestore-ci-exclusions.edn` (alpha gaps: timezone, casts, sync, persistence, etc.)

To run the full driver suite locally (expect failures until gaps are fixed):

```bash
DRIVERS=singlestore MB_SINGLESTORE_TEST_*=... \
clojure -X:dev:ee:ee-dev:drivers:drivers-dev:test \
  :only-tags '[:mb/driver-tests]'
```

Remove entries from `singlestore-ci-exclusions.edn` as failures are fixed.
