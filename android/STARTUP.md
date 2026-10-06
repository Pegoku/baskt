# Startup measurements

Measured on a connected Pixel 8 Pro on 2026-10-07, using the existing account and saved basket.
Each run force-stopped the process without clearing app data. No forced ahead-of-time compilation
or changes to system animation scales were used. These are process-cold launches, not fresh installs
or first launches after reboot.

| Build | Runs | Median first frame | Median restored screen |
| --- | ---: | ---: | ---: |
| Original debug build, with fully-drawn reporting added | 3 | 951 ms | 1,739 ms |
| Debug build, without eager adjacent pages | 3 | 885 ms | 1,510 ms |
| Optimized build, without eager adjacent pages | 5 | 140 ms | 227 ms |

Final restored-screen timings: **240, 232, 219, 217, 227 ms**. This is about a 7.7× improvement
over the original debug build. Network refreshes and product images may finish after the restored
screen; the app does not wait for those to report ready.

## Findings and fixes

- Android's `am start -W` first-frame timing hid much of the delay: the activity initially draws
  without content while saved settings load. `ReportDrawnWhen` now counts the restored screen.
- `HomePager` eagerly composed both adjacent pages. This added Orders and Compare layout work and
  triggered their history/comparison loads before the basket was usable. Leaving offscreen page
  retention at zero lets the pager compose/prefetch those pages as the user navigates.
- The installed APK and the CI artifact were debug builds, and release shrinking was disabled.
  The optimized variant runs without debug overhead and enables R8 and resource shrinking. CI
  now publishes it alongside the debug APK, and the README recommends it for everyday use.
- WorkManager 2.7.1 and its Room 2.2.5 dependency have legacy reflection rules that do not retain
  no-argument constructors under R8 full mode. Explicit constructor rules preserve database
  creation and input merger creation, including jobs persisted by the earlier debug APK.

Saved-data reads still occur on the main thread. They are not the dominant remaining cost with this
phone's data; larger caches may warrant a separate move to background loading. This change avoids
altering the offline queue or account restoration semantics.

## Verification

- All 60 debug unit tests passed; optimized assembly and release-vital lint passed.
- Installed the optimized APK over the original app without clearing data.
- Verified the saved basket, Orders, and Review on the phone; app resumes retained the task.
- Confirmed `SyncWorker` reports `SUCCESS` in logcat in the final optimized build.
- No application crashes were logged for the final test process.

Reproduce with an unlocked adb-connected device:

```sh
cd android
JAVA_HOME=/opt/android-studio/jbr ./gradlew :app:testDebugUnitTest :app:assembleOptimized
adb install -r app/build/outputs/apk/optimized/app-optimized.apk
# Let Android finish its package-update transition before measuring.
python3 scripts/measure-startup.py --runs 5
```

Use the same device, saved data, and network conditions when comparing builds. The debug APK
remains useful for development, but it is not representative of everyday startup performance.
