# DisplayBridge — first device test

DisplayBridge is a GPLv3 fork of [Extend](https://github.com/jqssun/android-display-extend), focused on a simple Xiaomi tablet-to-HDMI work experience.

## Test scope (v0.1.0-dev)

- Installs **alongside** Extend with Android package `com.strokedot.displaybridge`, rather than replacing a known-working app.
- Home screen: **Control Tablet** and **Control Monitor** switch attached external mouse/keyboard input together.
- The monitor button is disabled if no external display is available; both buttons require an active, authorized Shizuku service.
- Pressing either control disables automatic input binding, so later app launching should not immediately steal the mouse/keyboard back.
- The buttons request the switch using the **existing** Extend input-binding code. A confirmation toast says "Switching", not "Succeeded", because input routing can fail on particular firmware.

**Not implemented:** seamless drag-across pointer, multi-window, up to 3 visible apps, standalone in-app ADB pairing/helper. The test build still needs Shizuku and wireless debugging, just like upstream. No root is required for the existing workflow.

## Test on the tablet

1. Install original Extend or keep it as a fallback. Download the `DisplayBridge-debug-APK` artifact from the GitHub Actions run for this branch; unzip and install `app-debug.apk`.
2. Start Shizuku via Wireless debugging, then authorize DisplayBridge separately.
3. Connect the UGREEN hub and HDMI monitor. Confirm that **two displays** are listed.
4. Connect the mouse and keyboard to the hub. Tap **Control Monitor** in DisplayBridge; open a text field on an app launched on HDMI and test both devices.
5. Tap the tablet screen, reopen DisplayBridge, tap **Control Tablet**, and test typing and mouse movement on the tablet.
6. Reopen Cast Applications and launch an app; check that **automatic binding remains off** and the mouse/keyboard stay on the selected display.
7. Unplug the monitor: **Control Monitor** should become unavailable; the tablet touchscreen should still operate normally. Do not change screen-off or resolution settings during this test.
8. Reconnect the monitor and repeat to check recovery.

If switching doesn't work, see the app's Logs tab and report device model, Android/HyperOS version, and any errors. Avoid changing unrelated system settings.

## Safety & product direction

- This proof-of-concept does **not** bypass Android's authorization mechanisms, disable security checks, or remove Shizuku.
- Android's wireless debugging pairing and ADB privilege lifecycle cannot be assumed to survive a reboot or toggling Developer options. We will test on the actual device before promising one-time setup.
- The intended final product is one APK with a guided permission flow, direct APK distribution, easy app-to-monitor launch, simple input switching, and conditional 2–3-window layouts where HyperOS supports it.
- No commits from this branch should be shipped as a stable release until the device test and debug build succeed.

## License

Based on Extend, GPL-3.0. Preserve upstream copyright and license notices. New contributions remain subject to GPLv3.
