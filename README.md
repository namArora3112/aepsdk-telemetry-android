# aepsdk-telemetry-android (POC)

Proof of concept for the standalone **Telemetry plugin for Mobile Core** (see the design wiki,
page 4128573161, Questions 1–6). Branch: `poc/telemetry-plugin`.

## What this POC demonstrates

| Design decision | Where in code |
|---|---|
| Customer entry: one line before `MobileCore.initialize` | `testapp/TelemetryTestApplication.kt` |
| Install-once shared recorder (dual-path ready) | `Telemetry.install` / `TelemetryRecorder` |
| Safe default off, kill switch purges | `TelemetryConfig` / `Telemetry.applyConfig` |
| Bounded queue (256), drop counting | `TelemetryRecorder` |
| Omega lessons: dedupe window, empty-record guard, dry-run seam, injectable clock/IDs | `TelemetryRecorder`, `Telemetry.dryRun`, `Clock`/`IdGenerator` |
| Schema-3 records + batch envelope (installationId, sdkVersions, operationId lifecycle) | `TelemetryRecord`, `TelemetryBatch` |
| Core Networking usable pre-init (Q5, verified) | `TelemetryUploader` resolves `ServiceProvider.getInstance().networkService` at send time |
| operationId correlation (call → outcome, durationMs) | `Telemetry.operationStarted/operationCompleted` |

## Run

```bash
cd code && ./gradlew :testapp:installDebug
```

The config lives in `testapp/src/main/assets/telemetry-config.json` (enabled=true, empty endpoint =
capture-only mode; set an endpoint to exercise the uploader).
