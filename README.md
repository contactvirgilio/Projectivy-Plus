# Lite Home

A tiny Android TV launcher: one activity, no libraries, no network permission, ~31 KB APK.

Not affiliated with or based on Projectivy Launcher.

- Flowing animated gradient background: 7 themes (Aurora, Sunset, Ocean, Forest, Ember, Lavender, Midnight)
  or "Cycle through all", with Motion (Still/Slow/Normal/Fast) and Brightness (Dim/Normal/Bright). Choices preview live.
- Clock and date, then a grid of your apps (TV banners when the app has one, otherwise its icon on a tinted tile)
- Settings and Power tiles always last. Power opens the system power menu (Restart / Power off);
  it needs the Home button override on, since Android only allows that through accessibility.
- PIN lock: lock any app (lock badge on its tile), optionally lock Android Settings, and the Lite Home
  options themselves ask for the PIN. With the override on, locked apps also ask when opened from
  elsewhere (voice, other apps, recents). 5 wrong tries = 30 s wait. Forgot it? `adb shell pm clear io.lite.tvhome`
- Highlight colour: match the background theme, or Amber, Cyan, Lime, Pink, Violet, Red
- Long-press OK (or Menu) on an app: Open, Move, Move to first, Hide, Lock with PIN, App info, Uninstall
- Long-press OK on Settings or Power: Android settings, Background, Motion, Brightness, Highlight,
  PIN lock, Lock Android settings, show/unhide hidden apps, reset order, Override Home button
- Move mode: arrows move the tile, OK or Back to finish
- Updates itself when apps are installed or removed

## Install and set as Home

```
adb install LiteHome.apk
adb shell cmd package set-home-activity io.lite.tvhome/.Home
```

### Or: Override current launcher (no adb needed)
Long-press OK on the Settings tile > **Override Home button** > Open accessibility > **Lite Home override** > On.
The service catches the remote's Home button (and, as a fallback, any other launcher's home screen) and opens
Lite Home instead. Turn off Projectivy's own "Override current launcher" first. It reads no screen content.
After updating the APK, switch the service off and on again.

From a computer instead:
```
adb shell settings put secure enabled_accessibility_services io.lite.tvhome/.HomeOverride
adb shell settings put secure accessibility_enabled 1
```

Back to Projectivy:
```
adb shell pm enable com.spocky.projengmenu
adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME
adb shell cmd package set-home-activity <the Projectivy component listed above>
```

Remove: `adb uninstall io.lite.tvhome`

## Build

Needs only a JDK, Python 3, an API 29 `android.jar` and `dx.jar`. No Gradle, no aapt:
`tools/manifest.py` writes the binary `AndroidManifest.xml`, `res/xml/accessibility.xml` and `resources.arsc`.

```
# android.jar (API 29)
git clone --depth 1 --filter=blob:none --sparse https://github.com/Sable/android-platforms
git -C android-platforms sparse-checkout set android-29

# dx.jar, compiled from AOSP source
git clone --depth 1 --filter=blob:none --sparse https://github.com/aosp-mirror/platform_dalvik dalvik
git -C dalvik sparse-checkout set dx/src
mkdir dxout && javac -nowarn -d dxout $(find dalvik/dx/src -name '*.java')
jar cfe dx.jar com.android.dx.command.Main -C dxout .

ANDROID_JAR=android-platforms/android-29/android.jar DX_JAR=dx.jar ./build.sh
```

`build.sh` creates `litehome.keystore` on first run. Keep it private and reuse it, or Android
will refuse to install updates over an APK signed with a different key.
