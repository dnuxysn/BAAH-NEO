# Android display API reference

NEO uses Android hidden display APIs under the user's Shizuku permission. Android 14 physical display enumeration moved to `com.android.server.display.DisplayControl` in the system server classpath, whose native methods require `android_servers` registration.

API loading behavior was checked against the official scrcpy wrappers (Apache-2.0), rather than MaaFwApp host code:

- https://github.com/Genymobile/scrcpy/blob/master/server/src/main/java/com/genymobile/scrcpy/wrappers/DisplayControl.java
- https://github.com/Genymobile/scrcpy/blob/master/server/src/main/java/com/genymobile/scrcpy/wrappers/SurfaceControl.java

NEO's control service, ownership cleanup and frame pipeline remain local implementations. No scrcpy server binary or MaaFwApp display service is bundled.
