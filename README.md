# Bedside Clock

An Android bedside clock that activates automatically as a screensaver (DreamService) whenever the phone is on charge and the screen times out. Designed for overnight use on a bedside charging dock.

## Features

- **Auto-start** — activates via Android's screensaver (Dream) system when the phone is charging and the screen goes idle
- **Manual daytime use** — tap **Open Clock** from the main screen to show the same clock immediately, with no need to be charging
- **Clock display** — large time and date, with OLED burn-in protection (slow pixel drift every 3 minutes)
- **Customisable** — choose colour, font, and size (S / M / L / XL); XL splits into a two-line giant clock
- **Brightness control** — slider in the settings panel controls screen brightness independently of system settings; restored when the clock exits
- **Battery indicator** — shows charge percentage and charging status in the top-right corner
- **Session logging** — writes a log to `Documents/bedside-clock.log` (newest entry first, max 200 lines) recording start/stop times, elapsed duration, battery level change, and a heartbeat every 30 minutes
- **Screen stays on** — uses a wake lock and window flags to keep the display lit all night; disables Android's Always-On Display (AOD) for the duration of the dream
- **In-app update checker** — checks GitHub for a newer release

## Screenshots

*Clock display (amber, XL size):*

```
       08:
       47
Wednesday, June 18
                              ⚡ 82%    ⚙
```

## Requirements

- Android 8.0 (API 26) or later
- One-time ADB commands to grant elevated permissions (see Setup)

## Installation

1. Download `m21-bedside-clock.apk` from the [latest release](https://github.com/harrowmd/android-bedside-clock/releases/latest)
2. On the phone: Settings → Security → enable **Install unknown apps** for your file manager
3. Open the APK and install

## Setup

### 1. Grant permissions via ADB

These two permissions cannot be granted through the normal Android UI. Run once after each install:

```bash
adb shell pm grant com.manytwo.besideclock android.permission.WRITE_SECURE_SETTINGS
adb shell appops set com.manytwo.besideclock MANAGE_EXTERNAL_STORAGE allow
```

> **Note:** Both permissions are wiped on reinstall and must be re-granted each time.

To connect wirelessly: Settings → Developer options → Wireless debugging, then `adb pair <ip>:<port>`.

### 2. Grant in-app permissions

Open the **Bedside Clock** app and tap:

- **Grant Brightness** — allows the clock to control screen brightness
- **Grant Log File Access** — allows the log to be written to `Documents/bedside-clock.log`

### 3. Enable as screensaver

- Settings → Display → Screensaver (or **Dream**) → select **Bedside Clock** → set **When to start** to **While charging**
- Or tap **Open Screensaver Settings** from inside the app

### 4. Check phone settings

The clock requires two phone settings to be correct (visible in the app's **Phone Settings** section):

| Setting | Location | Required value |
|---|---|---|
| **Screen timeout** | Settings → Display → Sleep | Any value (30 s recommended for quick start) |
| **Lock after screen timeout** | Settings → Security → Screen lock | Must **not** be "Immediately" — use 5 min or more |
| **Adaptive sleep** | Settings → Display | Off (if present) |

> If "Lock after screen timeout" is set to **Immediately**, the phone locks at the exact moment the screen turns off and the clock never starts. Set it to 5 minutes or longer.

### 5. First use

- Place the phone on the charging dock
- Leave it alone — the clock appears within 30 seconds of the screen timing out
- Tap the **⚙** gear (bottom-right) to open settings; tap **Save settings** or the backdrop to close; tap **Exit clock** to dismiss, or tap the **⏏** icon (bottom-left) to exit immediately without opening settings

### Using the clock during the day

Tap **Open Clock** on the main screen to show the clock immediately, whether or not the phone is charging — useful for reviewing colours, fonts, sizes, and brightness without waiting for the screensaver. It's the exact same clock face and settings panel as the overnight version; tap the **⏏** icon (bottom-left) or **Exit clock** to close it.

## Log file

The log is written to `Documents/bedside-clock.log` (requires storage permission above). Example entries:

```
[2026-06-18 22:15:00] [heartbeat] battery=81% elapsed=00:30:00
[2026-06-18 21:45:01] Clock started — font=sans-serif-thin size=96sp brightness=15% battery=79%
[2026-06-18 21:40:23] Clock stopped — elapsed 07:22:14 battery=95% (+16%)
```

Share the log via the **Share Log File** button in the app, or read it directly from `Internal Storage / Documents / bedside-clock.log`.

Since v1.1.1, any app crash is also written to the log as a `CRASH:` line before the process exits, so unexpected issues can be diagnosed from the log file alone.

## Troubleshooting

**Clock doesn't start**
- Confirm the phone is on charge and unlocked when the screen times out
- Check the app's **Phone Settings** section — "Lock after screen timeout" must not be "Immediately"
- On some devices, the dream only starts from the unlocked home screen (not from the lock screen)
- Some phones have a motion/pickup detector (e.g. a built-in pedometer or fitness-tracking app) that holds the screen on while the phone is being moved or handled. This is expected — the screensaver only kicks in once the phone is stationary and the screen actually times out, so set it down on the dock and leave it alone for a moment.

**Screen goes blank overnight and doesn't come back on its own**
- Update to v1.1.1 or later. Earlier versions (v1.1) had two bugs that could leave the screen dark for the rest of the night with no recovery until the phone was manually woken and re-docked:
  - A crash in the session-logging code that killed the app every time the dream stopped (also the cause of brightness not being restored after **Exit clock**)
  - Android occasionally handing the screensaver slot to its own (blank) ambient-display service instead of restarting Bedside Clock, if "Ambient display on pick-up" is enabled and the phone gets moved while charging
- If it still happens after updating, share the log file — crashes are now written to it directly (see **Log file** below)

**Screen goes blank after a few minutes**
- Make sure `WRITE_SECURE_SETTINGS` has been granted via ADB (the app disables AOD during the dream to prevent the DozeService from stealing the display)

**Permissions lost after reinstall**
- Re-run both ADB commands from Step 1

## Building from source

```bash
git clone https://github.com/harrowmd/android-bedside-clock.git
cd android-bedside-clock
gradle assembleDebug
adb install -r app/build/outputs/apk/debug/m21-bedside-clock.apk
```

Requires Android Studio or the Android SDK command-line tools.

## Version history

- **v1.2.2** — The settings gear and exit icon now match the selected clock colour, instead of a fixed white/grey.
- **v1.2.1** — Fixed a crash when tapping **Share Log File** (`FileProvider` didn't know about the public Documents folder where the log actually lives). Removed the redundant orange title bar on the main screen, and cleaned up the setup instructions there.
- **v1.2** — Added **Open Clock**, a one-tap button for manual daytime use (the same clock face and settings, no need to be charging). Added a quick-exit icon (bottom-left) so the clock can be dismissed without going through the settings panel.
- **v1.1.1** — Fixed a crash in session/heartbeat logging that killed the app every time the dream stopped, silently leaving the screen blank until manually woken (also fixed brightness not being restored after **Exit clock**, same root cause). Fixed Android occasionally handing the screensaver slot to its own ambient-display service while charging. Crashes are now logged to the on-device log file.
- **v1.1** — Phone settings panel, battery level/wattage in logs, 30-minute heartbeat logging.
- **v1.0** — Initial release.

## Licence

MIT
