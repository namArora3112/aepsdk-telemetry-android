# aepsdk-telemetry-android (POC)

Proof of concept for the standalone **Telemetry plugin for Mobile Core** (see the design wiki,
page 4128573161, Questions 1–6). Branch: `poc/telemetry-eventhub-typed`.

This branch requires the matching `poc/telemetry-eventhub-typed` Core, Edge and
Campaign Classic branches from the `namArora3112` forks. They are composed from
source below; the typed POC coordinates are not public Maven releases.

## What this POC demonstrates

| Design decision | Where in code |
|---|---|
| Customer entry: install before normal Core initialization | `testapp/TelemetryTestApplication.kt` |
| Install-once shared recorder (dual-path ready) | `Telemetry.install` / `TelemetryRecorder` |
| Safe default off, kill switch purges | `TelemetryConfig` / `Telemetry.applyConfig` |
| Bounded queue (256), drop counting | `TelemetryRecorder` |
| Omega lessons: dedupe window, empty-record guard, dry-run seam, injectable clock/IDs | `TelemetryRecorder`, `Telemetry.dryRun`, `Clock`/`IdGenerator` |
| Schema-3 records + batch envelope (installationId, sdkVersions, operationId lifecycle) | `TelemetryRecord`, `TelemetryBatch` |
| Core Networking usable pre-init (Q5, verified) | `TelemetryUploader` resolves `ServiceProvider.getInstance().networkService` at send time |
| operationId correlation (call → outcome, durationMs) | `Telemetry.operationStarted/operationCompleted` |
| Reflection-free optional capability | Core `ITelemetryPlugin` / `TelemetryBridge.configure` |
| Central read-only EventHub capture, including extension-dispatched events | Core `EventHubTelemetry` |
| Real Core, Edge and Campaign API validation; separate no-Edge host | `testapp/src/androidTest`, `withEdge` / `noEdge` flavors |

Core observes accepted original dispatches, not successful delivery or upload.
Selectors use hub type/source, optional fixed event name, approved action/state
key presence, and optional `xdm.eventType` (exact or trailing `.*`). Customer
payloads, tokens, identities and raw XDM never cross the typed sink.

The plugin publishes an immutable policy/catalog; no plugin callback runs on the
EventHub thread. The handoff is bounded to 64 pending tasks. Core policy generations
and a second generation check under the recorder lock fence callbacks across
disable/re-enable. Unknown/denied policy captures nothing. Durable data purge and
upload-time checks remain plugin responsibilities.

`Telemetry.setEventMappings(...)` replaces the reviewed catalog, including each
entry's `enabled` flag. `eventMappings()` returns a copy. The compatibility helper
`registerEventHubProjection(...)` now replaces its type/source catalog group and
does not install a second listener. Distinct hub/operation IDs are not collapsed
by the dedupe window; duplicate enabled mappings emit once per output/event.

## Run

Set these paths to the four matching branch checkouts. Use a selected test
emulator/device, not every attached device:

```sh
export JAVA_HOME=/path/to/jdk17
export ANDROID_SERIAL=your-test-device
export CORE_CODE=/path/to/aepsdk-core-android/code
export EDGE_CODE=/path/to/aepsdk-edge-android/code
export CAMPAIGN_CODE=/path/to/aepsdk-campaignclassic-android/code

./code/gradlew -p code \
  -PtelemetryCorePath="$CORE_CODE" \
  -PtelemetryEdgePath="$EDGE_CODE" \
  -PtelemetryCampaignPath="$CAMPAIGN_CODE" \
  :telemetry:testDebugUnitTest \
  :testapp:connectedWithEdgeDebugAndroidTest \
  :testapp:connectedNoEdgeDebugAndroidTest
```

The config lives in `testapp/src/main/assets/telemetry-config.json` (enabled=true, empty endpoint =
capture-only mode; set an endpoint to exercise the uploader).

For the interactive host use the same path properties with
`:testapp:installWithEdgeDebug` or `:testapp:installNoEdgeDebug`. The hosts have
separate application IDs so they do not overwrite the original POC app.

The integration tests call real APIs, including valid hub-dispatch and invalid
direct-hook paths, catalog/XDM matching, opt-out, stale callbacks, and customer
data exclusion. The no-Edge flavor has no Edge dependency; Edge-specific tests
are skipped there. Business completion/response behavior is additionally covered
by the existing Core and SDK unit suites.

Release hosts enable R8. Use the same path properties with
`:testapp:assembleWithEdgeRelease :testapp:assembleNoEdgeRelease` to check shrinking
and typed linkage. Kotlin 1.9.22 and AGP 8.2.0 match the SDK composite's Android
toolchain; the older shrinker cannot safely process Kotlin 2.2 metadata.

## POC limits

Installation is explicit; automatic Core discovery/bootstrap is not implemented.
The recorder is currently memory-backed, not a durable SQLite outbox.
These tests prove capture and business-dispatch compatibility, not collector
ingestion or a production consent integration. `flushQueue` retains the separate
Core Networking upload path; no production endpoint or credentials are needed
for the capture tests.
