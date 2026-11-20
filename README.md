# WearTAK Ditto Plugin

## Purpose and Capabilities

Development-only Wear OS plugin scaffold for a future Ditto extraction. It bundles the
licensed Ditto Android SDK but **does not initialize or start Ditto, or provide mesh,
CoT, chat, data exchange, or provisioning**. A plugin-owned watch screen provides local
group controls; the runtime remains unavailable.

The watch is standalone; the ATAK Companion plugin is not required.

## Status

Not deployable. The user-selected application ID is `com.weartak.plugin.ditto`.
Release builds are non-debuggable. Local builds without all authorized CI signing
inputs are intentionally unsigned and cannot be installed. Debug builds use the
standard local Android debug key. Host permission grants and device integration remain
unverified; do not use the debug signer for a release trial.
The earlier `com.weartak.plugin.ditto.dev` package is a different Android application
and cannot be updated in place by this package.

The user intends to reuse the existing Ditto community identity. Its scope and secure
provisioning path for this separate package remain unverified. No identity or PDL is
included or loaded, and the runtime stays disabled until provisioning and safe ownership
handoff are implemented.

The plugin consumes Community contract v1 from the [WearTAK host-services SDK
module](https://git.tak.gov/core/weartak-core/wearos-tak-civ/-/tree/07d8649559dee44543f82cd1895c726fd904e64f/weartak-plugin-host-services-sdk)
at core commit `07d8649559dee44543f82cd1895c726fd904e64f`; source and license notices
remain in core, and no SDK binary is vendored. CI fetches the pinned SDK module and
version catalog using the scoped job token, builds its release AAR with Gradle 8.14.5,
and checks its contents against pinned SHA-256 digests before use. Synthetic tests
exercise SDK-generated Binder responses, bounded retries and stale-session fencing.
Production reads remain disabled, and the client does not retain or deliver an
identity. Capability discovery is not provisioning or proof of host process identity,
exclusive runtime ownership, or a safe handoff. Ownership and recovery fencing,
migration and device validation remain required.

## Point of Contacts

Undocumented.

## Ports Required

The pinned Ditto SDK contributes BLE, location, nearby Wi-Fi, network-state and
multicast permissions. No custom listener or server is declared.

## Equipment Required

Development requires JDK 17, Android SDK platforms 34 and 35, Gradle 8.14.5, and
verified combined WearTAK plugin SDK and host-services SDK AARs. A compatible Wear OS
watch is required for eventual device validation.

## Equipment Supported

The manifest requires watch hardware and Android API 30 or later. No watch or host
version has been validated. Release APKs include `armeabi-v7a` and `arm64-v8a`
native libraries for physical ARM watches. Debug APKs also retain `x86` and
`x86_64` libraries for emulator development. Builds still produce a single APK
per variant; CI artifact paths and release signing checks are unchanged.

## Compilation

The SDK AARs are not included. On Windows, the following obtains only the required
tracked build inputs from canonical core commits
`00290203f9a6bb09870c53f2cdac0e110ed88125` and
`07d8649559dee44543f82cd1895c726fd904e64f` into a temporary directory, then builds
both SDKs. It requires read access to
[TAK.gov core](https://git.tak.gov/core/weartak-core/wearos-tak-civ),
[GitHub SDK source](https://github.com/aegorsuch/weartak-plugin-sdk), Git, Python 3,
JDK 17, Gradle 8.14.5, Android SDK platforms 34 and 35, and `ANDROID_HOME` pointing to the Android SDK.
Gradle and Maven dependencies may require network access. Configure Git credentials
beforehand; do not put credentials in commands or this repository. Run from this
plugin repository in PowerShell. Install Gradle separately and verify its distribution
against the [published checksum](https://gradle.org/release-checksums/); the wrapper
JAR is not included. The checked-in wrapper scripts require a locally generated wrapper
JAR and are not used in the commands below.

```powershell
$ErrorActionPreference = 'Stop'
function Invoke-CheckedGit {
    & git @args
    if ($LASTEXITCODE -ne 0) { throw 'Git command failed.' }
}
$root = Join-Path $env:TEMP ('wtd-sdk-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
if (Test-Path $root) { throw 'Temporary directory already exists.' }
New-Item -ItemType Directory -Path $root | Out-Null
$env:GIT_TERMINAL_PROMPT = '0'
$commit = '00290203f9a6bb09870c53f2cdac0e110ed88125'
Invoke-CheckedGit -C $root init --quiet
Invoke-CheckedGit -C $root remote add origin 'https://git.tak.gov/core/weartak-core/wearos-tak-civ.git'
Invoke-CheckedGit -C $root fetch --quiet --depth=1 --filter=blob:none origin $commit
$objects = @(
    'ci/build_plugin_sdk.py',
    'ci/plugin-sdk.json',
    'ci/plugin-sdk-transport/.gitattributes',
    'ci/plugin-sdk-transport/overlay-source.sha256',
    'ci/plugin-sdk-transport/transport-v1-overlay.zip'
)
foreach ($object in $objects) { Invoke-CheckedGit -C $root cat-file -e "${commit}:$object" }
Invoke-CheckedGit -c core.autocrlf=false -C $root checkout $commit -- `
    'ci\build_plugin_sdk.py' 'ci\plugin-sdk.json' 'ci\plugin-sdk-transport'
Push-Location $root
try {
    python .\ci\build_plugin_sdk.py
    if ($LASTEXITCODE -ne 0) { throw 'SDK build failed.' }
} finally { Pop-Location }
$aar = Join-Path $root '.gradle\sdk\86bdc8b04797-a818af15b332\build\outputs\aar\weartak-plugin-sdk-release.aar'
if ((Get-FileHash $aar -Algorithm SHA256).Hash -ne '6ec84b18e1c8ac6b4be727083c4ea7a572150b521384270212434c239e5a1e0b') {
    throw 'Unexpected SDK AAR hash.'
}
$hostCommit = '07d8649559dee44543f82cd1895c726fd904e64f'
Invoke-CheckedGit -C $root fetch --quiet --depth=1 --filter=blob:none origin $hostCommit
foreach ($object in @('gradle/libs.versions.toml', 'weartak-plugin-host-services-sdk')) {
    Invoke-CheckedGit -C $root cat-file -e "${hostCommit}:$object"
}
Invoke-CheckedGit -c core.autocrlf=false -C $root checkout $hostCommit -- `
    'gradle\libs.versions.toml' 'weartak-plugin-host-services-sdk'
$hostServicesSdkAar = Join-Path $root 'weartak-plugin-host-services-sdk\build\outputs\aar\weartak-plugin-host-services-sdk-release.aar'
gradle -p (Join-Path $root 'weartak-plugin-host-services-sdk') assembleRelease `
    --no-daemon --max-workers=2 '-Pkotlin.compiler.execution.strategy=in-process' --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Host-services SDK build failed.' }
gradle :app:assembleDebug :app:testDebugUnitTest :app:assembleRelease :app:testReleaseUnitTest `
    :app:verifyReleaseSigning `
    --offline --no-daemon --max-workers=2 `
    '-Pkotlin.compiler.execution.strategy=in-process' `
    "-PweartakPluginSdkAar=$aar" "-PweartakHostServicesSdkAar=$hostServicesSdkAar" --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Plugin build or tests failed.' }
```

The canonical core release signer is pinned to SHA-256
`1579b600cd84e619e76a91c2db6b031938c999bc2aad926b9a51ec2b166d348d`. In the
authorized protected CI environment, the release build consumes these existing
variables: `WEARTAK_RELEASE_KEYSTORE_B64`, `RELEASE_STORE_PASSWORD`,
`RELEASE_KEY_ALIAS`, and `RELEASE_KEY_PASSWORD`. All must be present; partial
configuration fails. The base64 keystore is written only to a private POSIX
temporary file, cleared from memory after writing, and deleted at build completion.
`:app:verifyReleaseSigning` checks the resulting APK against the pinned certificate;
CI release builds fail if signing inputs are absent or the verifier is not requested.
No secret values or key files belong in this repository.

Without signing inputs, the local release output is
`app/build/outputs/apk/release/app-release-unsigned.apk`. It is explicitly verified
as unsigned and cannot be installed. An installable UI trial still requires the
plugin project’s authorized CI to receive the protected variables, run
`:app:verifyReleaseSigning`, and produce a signer Android accepts as matching the
installed core. The core must also hold `com.weartak.plugin.permission.BIND_PLUGIN`;
the plugin enforces this signature permission on both its service and group activity.

The [core builder](https://git.tak.gov/core/weartak-core/wearos-tak-civ/-/blob/00290203f9a6bb09870c53f2cdac0e110ed88125/ci/build_plugin_sdk.py)
verifies SDK commit `86bdc8b04797c2cf6caa30e78e796abac1c2a330`, tree
`a03d33bc077d81f80cdb53b04ea75e18cf0200a1`, overlay archive SHA-256
`a51f54fbfc1c95a9583ac97daeec2f957fb42de87d27a9bbd823597184033ffd`, overlay
inventory SHA-256 `a818af15b3328b817b972e2fe81d9585cb54e9a952a0ec8e13936ee5987ee6a6`,
and the resulting AAR. The plugin also accepts the independently pinned Linux AAR
hash `8f911b81ac85e691ad182097fe0ef0f00ffa0d2da3442d3d2f03c5ec2529184f`;
Linux build steps have not been verified here. Use a short temporary path if Windows
AIDL exceeds path limits. The offline plugin command requires cached dependencies;
Robolectric may separately require its Android test runtime cache or network access.
Do not commit the AAR, vendor binaries, credentials, signing keys, Ditto identity, or PDL.

TAK.gov CI runs a debug build and debug/release unit tests on branch,
merge-request, and tag pipelines without production signing credentials. It builds
both SDK AARs from pinned core inputs, including host-services SDK commit
`07d8649559dee44543f82cd1895c726fd904e64f`, using `CI_JOB_TOKEN`; the core project
must allow this plugin project in its job-token scope. CI downloads the official Gradle
8.14.5 binary distribution and checks its SHA-256 against the
[Gradle-published checksum](https://services.gradle.org/distributions/gradle-8.14.5-bin.zip.sha256).
It seeds the pinned SDK wrapper's isolated cache with the verified distribution and
uses the same distribution for the plugin build. The signed release-build job runs
only on a protected default-branch push and requires protected
`WEARTAK_RELEASE_KEYSTORE_B64`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, and
`RELEASE_KEY_PASSWORD` variables scoped to the `signed-plugin-build` environment.
Keep these variables protected and masked, and do not leave wildcard-scoped copies
inherited by test jobs. It publishes a seven-day, developer-accessible CI artifact
only after `:app:verifyReleaseSigning` accepts the canonical certificate.
It does not create a GitLab release or publish to a mirror. Pipelines other than
protected default-branch pushes do not produce signed APKs. The artifact remains a
local subgroup UI trial build; Ditto runtime and mesh remain unavailable.

The current source reports a disconnected Ditto route with "Runtime unavailable" to
compatible authenticated hosts, which can list it in icon-priority settings. There is
no connectivity toggle or connected icon while the runtime is unavailable; hosts
without route-status support show no route. A previously built artifact does not
necessarily include this source change.

## Developer Notes

The core baseline uses Ditto `4.14.6`. The plugin includes Ditto `4.14.7` as a
production dependency, and the APK packages the SDK-backed runtime handle plus
`app/src/main/assets/licenses/ditto-binary-license.txt`. The production binder uses a
fail-closed runtime owner with no factory and a closed activation gate, so it creates no
Ditto instance, reads no identity, configures no transport, and publishes no data.
The SDK-declared mesh and network permissions remain in the merged app manifest.
The group screen has an explicit permission opt-in and requests only permissions
reported missing by Ditto 4.14.7; grants and revocations are rechecked after the
permission result and on resume. This is permission handling only: no Ditto instance
or sync starts, and runtime identity/ownership gates remain closed. The SDK-backed
handle is compiled against Ditto `4.14.7`; unit tests exclude the vendor artifact at
runtime and use only a synthetic test double. Device installation, SDK class loading,
permission UX and radio behavior have not been verified. Core retains its existing
Ditto runtime.
The existing host SDK provides lifecycle, generic CoT/chat transport, route status,
and hosted UI. It does not provide a native Ditto document/store,
point-ledger, PDL migration, or host-owned mesh-runtime contract.

The SDK-backed factory requires explicit SharedKey provisioning and uses the public
identity-based constructor supported by Ditto `4.14.7`. That API derives configuration
from the identity; exact parity with the core's database and connect settings remains
unverified, so the factory is not wired into the production binder. Its handle delegates
`stopSync()` and `close()` only for an explicitly supplied Ditto instance; the service
supplies none. Each cleanup request is recorded before calling the SDK and is never
repeated, including after an error. The result records local call outcomes only; it is
not proof of quiescence or permission to hand off ownership. This is not an exclusive
ownership lock. Exception details are not logged. The production binder has no identity
source, transport registration, or radio startup. It does not close core's monitors,
workers, observers, subscriptions or instance: those objects and their host ownership
contracts do not exist here. Synthetic tests exercise local controller behavior, not SDK
or network behavior.
Keep real initialization, synchronization, identity reads, and publication disabled
until authenticated exclusive ownership, provisioning/migration, and crash recovery
are verified.

An unwired power policy mirrors core's off-wrist, low-battery, sleep and power-save
suppression, charging and alert overrides, and 60-second alert grace. Its inputs are
explicit: a future watch runtime must obtain current wrist and battery observations,
charging and alert state, sleep and power-save state, the configured threshold and
off-wrist preference, and a monotonic elapsed-realtime clock. As in core, unknown
wrist or battery observations alone do not suppress sync; unavailable required
inputs must not be silently treated as known values. Unlike core, this adapter
rejects battery and threshold values outside 0-100 and invalid elapsed times.
Core obtains wrist, battery, charging, alert and preference state from its own data
repository, which the plugin cannot directly assume it can access. A future plugin
runtime needs authenticated sources or its own permitted watch observations and
settings. Core reads device-idle and power-save from Android's `PowerManager`, plus
optional OEM bedtime settings, and uses `SystemClock.elapsedRealtime()` for the
grace deadline. Re-evaluation at grace expiry also needs a lifecycle-owned timer.
No monitor, sync control, sensor, or recovery timer is connected by this policy.

An unwired transport policy mirrors core's use of the public Wi-Fi Aware feature,
any observed Wi-Fi network for LAN, and validated internet plus configured
endpoints for WebSocket. A future adapter must preserve BLE and other SDK settings,
retain endpoints separately across connectivity changes, and use current trusted
observations: core derives Wi-Fi and validated internet from Android network
capabilities. Wi-Fi Direct is not treated as Wi-Fi Aware. Missing observations
must not be silently treated as known values; no network callback or SDK transport
configuration is connected here. A separate SDK transport-config adapter compiles
against Ditto 4.14.7 and is exercised against a synthetic test double; like the stop
bridge, it is excluded from the APK and cannot start or configure a live runtime.

Local group groundwork stores a single Community/private selection and memberships
in an authenticated encrypted, app-private no-backup file, wrapped by an Android
Keystore AES key. A missing key, invalid file, or failed write fails closed rather
than selecting Community. Group IDs are opaque and independent of display names.
Authenticated host settings expose group names and IDs, not keys.

The signature-permission-protected launcher activity provides watch-local creation,
selection, invitation import and deliberate export. Core currently falls back to
`getLaunchIntentForPackage` when hosted main UI is unavailable; settings capability
flags do not select the activity route. The document picker may be unavailable.
Invitations contain the group key: export requires a warning and destination choice,
and import warns that the provider can access it. A selected provider may upload it;
use only trusted providers and a trusted transfer channel. Partial exports can also
contain credentials. This plugin provides no transfer channel or invitation
authentication. The binary invitation is exactly 52 bytes: ASCII `WTG1`, a 16-byte
opaque group ID, and a 32-byte group data key. Names are entered locally.
Invitations and group keys are separate from, and do not replace, community PDL
credentials. Version-2 and version-3 local state is upgraded on open, preserving
memberships and counters; older or invalid formats fail closed.

An unwired AES-GCM envelope encrypts synthetic chat, location and map documents
using the separate 256-bit group data key. It authenticates format, group ID,
sender ID and content kind, and contains no native public document fields.
Local chat, track/location, mapitem, and alert encoding boundaries emit native
documents only for Community and authenticated envelopes only for a private
selection; encoding or encryption errors never fall back to Community.
Prepared output carries a persisted selection generation and scope; release is
single-use and rejected after any selection change, including switching away and
back. Per-group memberships and sender counters remain stored separately, so
local users can switch groups after use. This only fences prepared local encoder
output: no runtime dispatcher or pending delivery state is connected.

Each installation derives a sender-specific encryption key and counts envelope
attempts before output, with a per-sender 100,000-envelope limit and a 1,000,000
wrapping-key storage-write limit. Counters are retained per membership across local
group switches. Nonces are random 96-bit
values, so collisions remain possible. Accounting is not rollback-resistant:
restoring an older encrypted state or losing a recent filesystem update can defeat
the limits. A member can also create other sender IDs. These limits are not a
production nonce-safety guarantee. Local tests use synthetic keys and do not verify
Android Keystore, filesystem crash durability or a real host/device.

Encrypted local state version 4 also supports a group-scoped replay watermark
journal capped at 8,192 records. Watermarks persist a caller-supplied 64-hex
peer/UID key, timestamp, retention bound, and document fingerprint; ephemeral
seen/expiry state is intentionally not persisted, preserving core's non-chat replay
rehydration behavior after restart. Admission tokens are bound to the selected
group, selection generation and live store instance. Superseded admissions cannot
overwrite newer watermarks. Recording is explicit and persists only after the
caller reports successful local ingress; storage failures fail closed. At capacity,
expired records can be pruned; a full unexpired journal rejects the new record
without consuming its admission. The encrypted file is capped at 2 MiB.
This API has no receiver caller and cannot establish delivery.

This is not yet private communication: no transport, subscription, receiver,
delivery, cache, point ledger, outbox, or ownership handoff is wired. The local
replay journal is disconnected; future runtime integration must scope connected
pending or replay state by group or reject switching while state is pending, and
must verify the prepared scope/generation immediately before transport release.
No local encoding or decryption marks data as delivered.
Existing public encoders remain independently callable and must be gated by the
eventual dispatcher before runtime activation; pending state must be isolated or
group switching rejected. Encrypted state has no rollback protection. Encryption
does not hide peers, traffic metadata or group IDs, prevent replay or member
impersonation, or provide per-member revocation. TAK, SITX, multicast and
Companion routes remain independent and are not disabled or made private by this
plugin selection.

Isolated, currently unwired v2 Ditto-to-CoT decoders validate and convert PLI,
marker, emergency, and inbound native/extended chat documents. They reject malformed,
oversized, and sensitive enrollment payloads. An unwired outbound chat encoder
maps caller-supplied, privacy-filtered GeoChat and peer identity to native v2
fields and validates the resulting document; it neither sends nor provisions.
It rejects ambiguous structure, malformed or oversized XML, and sensitive
enrollment commands, including in envelope fields. Extended chat also rejects
conflicting callsign or parent values between its envelope and detail; core does
not check those two fields. A separate, unwired chat
freshness check rejects events more than five minutes in the future and drops
events stale at or before caller-supplied current time. Decoding alone does not
perform that check. Unlike core's implicit clock assumption, the check requires
a positive, representable current time and a valid chat TTL. Unwired replay
comparison and chat retention calculations mirror core's decisions and query
strings but do not deliver, subscribe, or evict. The encrypted group journal
persists caller-recorded successful ingress, but is not connected to a receiver
or subscription. It rejects invalid watermark or clock inputs rather than
assuming trusted state. As in core, a persistence failure after delivery can
still cause redelivery. Safe integration requires an explicit delivery and
durable-storage contract. Retention also requires persisted attempt ordering and
exclusive subscription ownership before SDK eviction.
Recipient delivery, mesh, and end-to-end interoperability are not implemented.
An unwired mapitem/alert encoder maps explicit, privacy-filtered host inputs to
core aliases. It validates the common v2/CoT envelope, restricts mapitem types to markers
and routes and alert types to core emergency types, and does not encode generic
deletions as tombstones. Unlike core's raw extraction it rejects blank or
unsafe fields and requires explicit peer and author inputs. It does not verify
type-specific detail semantics, authorization, point ownership, routing, or
delivery; future marker writes must use the local owner ledger and retain
pending tombstones. Returned documents contain sensitive data and must not be logged.
Isolated point ledgers preserve owner/revision watermarks and pending tombstones;
point ingress accepts only decoded, validated point objects.
they are not connected to the service or migrated from core. Shared-key writer
ownership is an application rule, not a cryptographic signature. Local ledger changes expose a coroutine state flow and generation counter.
An unwired retry job uses the local ledger, core's backoff and an explicit
caller-supplied allowed flow. Cancellation stops pending timers but does not
prove that an in-flight write stopped; a future integration must serialize and
recheck actual ownership and write authorization. Each ledger
requires an exclusively owned preferences file and a single active instance;
handoff and migration of core's ledgers and separate replay journal are not implemented.
Local point documents are stored in private app preferences, not encrypted by
these classes; future integration must assess storage and deletion requirements.
An unwired track publication gate mirrors core's heartbeat, movement and
meaningful-change decisions. The host must provide privacy-filtered identity,
physiology, fresh valid location and a monotonic clock, and serialize gate checks
with confirmed writes. The gate neither publishes nor provides permission,
freshness, ownership-handoff, or delivery guarantees.
Unlike core's internal gate, this isolated entry point rejects invalid coordinates
and negative elapsed time, snapshots the identity list after acknowledgement,
and redacts sample diagnostics instead of displaying track inputs.
An unwired native track encoder and expiry helper reproduce core's document
aliases, location defaults, old-fix marker, and expiry timestamp updates. The
caller must supply already-filtered, mutually consistent identity, full CoT
detail, timestamps, location, and stale policy. Encoding does not verify
permission, freshness, delivery, or ownership. Unlike core's trusted internal
builder, this encoder rejects invalid identity or timestamps, non-finite
coordinates, altitude or speed, infinite CE/LE, malformed or oversized detail
or final CoT, and sensitive enrollment commands. Null or NaN CE/LE use core's sentinel.
It requires a closing `</detail>` tag and no preexisting
`weartak_location` element. Marker insertion targets the closing root rather
than replacing matching text inside comments or CDATA. Expiry is a shallow copy
of a trusted track and requires a caller-supplied valid current time. Neither
helper constructs host-owned detail or migrates/publishes tracks. Unlike core,
expiry rejects zero, negative, and unrepresentable timestamps.
Core and a future plugin can both receive generic outbound chat, markers, and
emergencies; avoid registering plugin transport until runtime ownership and
handoff prevent duplicate publication.

The separate Android package does not inherit core's permission grants or DataStore
configuration. Before any runtime can be enabled, establish secure community identity
and PDL provisioning/migration, validate package-scoped permission UX on device,
define host contracts, and verify lifecycle ownership. No placeholder credentials or
default PDL are provided.

The service action and signature-level bind permission follow the
[WearTAK plugin template](https://git.tak.gov/weartak-plugins/weartak-plugin-template)
at commit `a4af9f1b9bd015310743a6b0aef4bb0b24d65bf6`. A host connection alone
does not mean Ditto is available. Service startup deliberately leaves the SDK lifecycle
outside `RUNNING`; only the local group selection can be changed through settings.

Initialization checks the Binder caller UID against the claimed UID and package,
requires that package to hold the bind permission and share the plugin's signing
identity, and checks that the permission itself is signature-protected and defined
by a package with that signing identity. Subsequent calls recheck the authenticated
UID, package, permission and signature. Before initialization only inert getters
and callback registration by a similarly trusted caller are accepted; the callback
is not retained. Unbind or service destruction invalidates the old Binder handle.
An unbind on any distinct binding intent may invalidate other clients' handles;
they must reconnect. There is no package allowlist: any same-signing-identity package
with the permission is trusted, and packages sharing a UID cannot be distinguished
as Binder callers. These checks do not prove host process identity, exclusive
ownership, provisioning, or shutdown. Android package visibility, permission grants, activity launch and picker behavior,
signing lineage, real host handshake order and reconnect behavior need device
validation; different debug/production keys can prevent launch, binding or initialization.
The session is UID/package-scoped, not process-scoped: host process death or restart
alone is not detected, and there is no Binder death-linked ownership lease.
The coordinating core session verified current `PluginConnection.initialize` ordering:
`getPluginInfo`, `getApiVersion`, `initializePluginV2` or `initializePluginV3`
(with the host package and process UID), `registerHostCallback`, `startPlugin`,
host-side `activateBinding`, then `settingsCoordinator.onPluginConnected`. The
corresponding plugin-call sequence is covered by synthetic tests; the host-side
steps and device interoperability are not verified by those tests.

## License

Original project code is licensed under GPL-3.0-or-later; see [LICENSE](LICENSE).
The document decoders and encoders, replay and retention policies, XML helpers,
point ledgers, track gate, retry job, and lifecycle stop ordering are adapted from WearTAK core at commit
`00290203f9a6bb09870c53f2cdac0e110ed88125` and are GPL-3.0-only. Their source
files identify that license; the combined work must respect those terms.
The Gradle wrapper scripts come from the template and retain their Apache-2.0 notices;
the wrapper JAR is not included.
Kotlin coroutines are Apache-2.0 licensed; the license is included in
`app/src/main/assets/licenses/apache-2.0.txt`.
The externally built WearTAK SDK and Ditto SDK retain their own terms; this repository
does not grant redistribution rights for them. The
[Ditto Binary License](https://www.ditto.com/sdk-license) is reproduced in
`app/src/main/assets/licenses/ditto-binary-license.txt`. The user confirmed the
separate plugin agreement and GPL compatibility; written terms were not independently
reviewed. Preserve all applicable third-party notices before distributing any future
build.
