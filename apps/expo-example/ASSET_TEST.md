# iOS asset-delivery regression

Use the existing NitroPush **test** environment, not a new EAS Update project.
The native binary must be rebuilt once with the new SDK embedded-asset inventory
and asset-proxy capability. Preserve the app's runtime version and private native
deployment credential. Do not rebuild or reinstall between OTA stages.

`lib/asset-test-fixture.ts` deliberately imports only the current stage's files.
Do not put all stages behind a JavaScript conditional: Metro can include every
static `require`, accidentally embedding the files that are meant to be new OTA
downloads. Existing PNG files are reused unchanged; this test does not generate
or edit image data.

| Stage             | Static image requirements                                                      | Expected transfer                                                                                |
| ----------------- | ------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------ |
| Native `baseline` | `react-logo.png`, `partial-react-logo.png`                                     | Both images are in the immutable binary inventory.                                               |
| OTA `change-a`    | `react-logo.png`, `android-icon-foreground.png`                                | React logo is reused from the binary; foreground image is new.                                   |
| OTA `change-b`    | `react-logo.png`, `android-icon-monochrome.png`, `android-icon-foreground.png` | React logo stays embedded; monochrome is new; foreground is reused from verified download cache. |

For each OTA, update the stage/description and image entries in the fixture file.
Keep `embedded` as the React logo, `changed` as the new stage image, and add
`reused` for the foreground image in change B. Export **iOS only** to a fresh
output directory with the existing Expo CLI, then inspect `metadata.json` and
SHA-256/size of every file before publishing through the NitroPush CLI.

The image panel shows the actual stage and image-load status. The existing
**Check for updates** button uses native sync and immediate activation;
`notifyAppReady()` remains the only success acknowledgement after JS renders.
If activation is deferred, use the existing apply button or cold relaunch.

Record the release IDs, image hashes/sizes, native inventory, device cache,
server completed-delivery records, and billing statement deltas for each stage.
The UI does not claim a cache hit or report billable bytes. In particular, merely
requesting a manifest or loading an image in JS is not proof of a billable
network transfer. Leave the SDK's signature, rollback, and sequence checks on.

Build artifacts, logs, exported releases, screenshots, and private native config
are local verification evidence, not source files to commit.
