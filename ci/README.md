# Canonical WearTAK version

`prepare_wear_version.sh` authenticates a fresh fetch of
[`core/weartak-core/wearos-tak-civ`, project 6739](https://git.tak.gov/core/weartak-core/wearos-tak-civ)
at `refs/heads/main` with `CI_JOB_TOKEN`. It reads root
`wear-version.properties` (`versionName`, `baseVersionCode`) from that commit.
The existing core job-token access must permit this fetch; no credentials or
allowlist settings are changed here. Missing access, missing canonical file,
malformed values, or any mismatch fails the job.

Forwarded `WEARTAK_VERSION_NAME`, `WEARTAK_BASE_VERSION_CODE`,
`WEARTAK_VERSION_SOURCE_PROJECT_ID`, and `WEARTAK_VERSION_SOURCE_SHA` are
assertions, not canonical inputs. All four must match the fetched main commit.
Delayed triggers whose source SHA is no longer main's head fail closed and need
a fresh host pipeline. Pipeline-source strings and forwarded variables do not
authenticate an upstream pipeline.

The generated `.gradle/wear-version.properties` is private job workspace input,
not a downloadable artifact or user-configurable version override. Each CI job
deletes any previous copy before authenticating the source. Gradle validates the
canonical values again. CI and builds with production signing inputs never use
the unsigned local fallback (`5.8.4-dev`, code `5840000`).

CI version names use `<host version>-p<IID>+<first eight plugin SHA characters>`,
for example `5.8.4-p42+abcdef01`. Codes use `baseVersionCode * 10000 + IID`,
for example `5840042`. IID must be 1 through 9999; larger IIDs require an
explicit versioning migration, never wraparound. Codes above Android's
2100000000 limit are rejected. The IID always belongs to this plugin project,
not the host pipeline.

Run focused, SDK-independent checks with JDK 17:

```sh
sh ci/test_wear_version.sh
```

PowerShell equivalent:

```powershell
javac -d .gradle\version-tests buildSrc\src\main\java\com\weartak\version\WearVersion.java ci\WearVersionTest.java
if ($LASTEXITCODE -eq 0) { java -cp .gradle\version-tests WearVersionTest }
```

## Release readiness

This consumer is not production-ready merely because canonical version resolution
works. The current TAK.gov default is feature branch
`aegorsuch-weartak-313-ditto-plugin-extraction`, not a stable release branch.
Existing signing checks and protected default-branch restrictions remain required.
There is no release or mirror publisher: the signed job only retains expiring CI
artifacts. No project settings, token allowlists, credentials, or publishing
configuration are changed by this work.

Downstream pipelines currently run unsigned build/tests only. Signing remains
limited to protected default-branch pushes until an independently verified upstream
identity proof is available; matching canonical values alone does not authorize
downstream production signing.
