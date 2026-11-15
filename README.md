# MitWit

An open-source Android app that reads your data straight from a Withings ScanWatch 2 over
Bluetooth and shows it on your phone, without the Withings app or Withings servers.

The app has no internet permission: nothing it reads ever leaves the phone. It only reads
from the watch; it never deletes data on the watch or changes its settings, so the official
Withings app keeps working alongside it if you still use it.

Not affiliated with or endorsed by Withings.

## What it shows

- Activity: steps, distance and active calories per day, week, month and year, steps by
  hour, workouts, goal tracking.
- Sleep: time asleep, deep/light/REM/awake stages with a hypnogram, bedtime and wake-up,
  wake-ups and sleeping heart rate, with week/month/year summaries.
- Vitals: heart rate, sleeping heart rate, HRV (RMSSD), body temperature, respiratory
  rate and SpO2.
- Every summary compares the period with the one before it and with the same period a
  year earlier.

It can also import your full history from a Withings data export (Withings account →
Settings → Download my data → the `data_….zip` file).

Tested with a ScanWatch 2 (HWA10) on Android 16. Other recent Withings watches that use
the same protocol may work.

## Install

Download the APK from [Releases](../../releases) on your phone and open it (allow
installing from your browser when Android asks). Updates install over the top and keep
your data.

## Getting your watch secret

The watch only talks to a phone that knows the secret the Withings app set when you paired
it. The Withings app sends it to the watch in plain text on every sync, so you can read it
from Android's Bluetooth log:

1. Enable Developer options on the phone, then turn on "Enable Bluetooth HCI snoop log"
   (set it to "Enabled"), and turn Bluetooth off and on.
2. Open the Withings app and sync the watch.
3. Take a bug report: `adb bugreport bugreport.zip` (or Developer options → Take bug report).
4. Unzip it and run the decoder on the log
   (`FS/data/misc/bluetooth/logs/btsnoop_hci.log` in the bug report):

   ```
   python3 tools/withings_secret.py btsnoop_hci.log
   ```

   It prints `AccountKey: account_id=… klSecret='…'`.
5. In MitWit, open the Watch tab, paste the klSecret and save. Turn the snoop log off again.

The secret changes if you remove and re-add the watch in the Withings app.

## Build

Needs JDK 17 and the Android SDK (platform 36).

```
./gradlew assembleRelease   # or installDebug
```

Release signing reads `signing.properties` (not in git):

```
storeFile=/path/to/keystore
storePassword=…
keyAlias=…
keyPassword=…
```

## How it works

- `WatchClient.kt`: finds the watch by the identity in its 0xFD77 advertisement (it uses a
  random Bluetooth address), connects over BLE, and authenticates with
  SHA1(challenge ‖ MAC ‖ secret).
- `Wpp.kt`: the Withings framing (`01 | cmd | len | [type | len | data]*`).
- `Sync.kt`: reads battery, activity/sleep, heart rate, SpO2, temperature, HRV and
  respiratory-rate time series since the last sync.
- `Db.kt`: one SQLite database; daily totals and sleep sessions are derived from the
  minute data.
- `Importer.kt`: the Withings export importer.

## License

MIT
