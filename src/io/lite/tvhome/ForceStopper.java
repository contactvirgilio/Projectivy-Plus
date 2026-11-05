package io.lite.tvhome;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Android 14+ no longer lets apps close other apps, so this does what you would do by hand:
 * open each app's App info, press "Force stop", then press OK. Button labels are read from the
 * Settings app itself, so it works in any language. Only runs when you use the Close apps tile.
 */
final class ForceStopper {

    static final String TAG = "LiteHome";
    static final long STAGE_TIMEOUT = 6000, POLL = 250;

    final HomeOverride svc;
    final Handler h = new Handler(Looper.getMainLooper());
    final ArrayDeque<String> queue = new ArrayDeque<>();
    final List<String> stopLabels = new ArrayList<>(), okLabels = new ArrayList<>();
    String settingsPkg, current;
    int stage, stopped, failed;
    long stageStart;
    boolean active;

    ForceStopper(HomeOverride svc) { this.svc = svc; }

    void start(List<String> pkgs) {
        if (active || pkgs.isEmpty()) return;
        loadLabels();
        if (stopLabels.isEmpty()) {
            Toast.makeText(svc, "Couldn't find the Force stop button on this TV", Toast.LENGTH_LONG).show();
            return;
        }
        queue.clear();
        queue.addAll(pkgs);
        stopped = failed = 0;
        active = true;
        next();
    }

    // ---------------------------------------------------------------- labels in the TV's language

    void loadLabels() {
        PackageManager pm = svc.getPackageManager();
        settingsPkg = "com.android.tv.settings";
        try {
            ResolveInfo ri = pm.resolveActivity(new Intent(Settings.ACTION_SETTINGS), 0);
            if (ri != null && ri.activityInfo != null) settingsPkg = ri.activityInfo.packageName;
        } catch (Exception ignored) { }

        Set<String> stop = new LinkedHashSet<>(), ok = new LinkedHashSet<>();
        try {
            Resources r = pm.getResourcesForApplication(settingsPkg);
            add(r, settingsPkg, stop, "device_apps_app_management_force_stop", "force_stop");
            add(r, settingsPkg, ok, "settings_ok", "okay", "dlg_ok", "ok", "force_stop_dlg_confirm");
        } catch (Exception e) {
            Log.w(TAG, "settings labels", e);
        }
        try { ok.add(Resources.getSystem().getString(android.R.string.ok)); } catch (Exception ignored) { }
        stop.add("Force stop");
        ok.add("OK");
        stopLabels.clear(); stopLabels.addAll(stop);
        okLabels.clear(); okLabels.addAll(ok);
        Log.i(TAG, "force stop labels " + stopLabels + " ok labels " + okLabels);
    }

    static void add(Resources r, String pkg, Set<String> into, String... names) {
        for (String n : names) {
            int id = r.getIdentifier(n, "string", pkg);
            if (id == 0) continue;
            try {
                String s = r.getString(id).trim();
                if (!s.isEmpty() && s.length() < 40 && !s.contains("%")) into.add(s);
            } catch (Exception ignored) { }
        }
    }

    // ---------------------------------------------------------------- the routine

    void next() {
        h.removeCallbacks(poll);
        current = queue.poll();
        if (current == null) { finish(); return; }
        stage = 0;
        stageStart = SystemClock.elapsedRealtime();
        Home.unlockedPkg = settingsPkg; // our own app lock must not block Settings here
        try {
            svc.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", current, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK
                            | Intent.FLAG_ACTIVITY_NO_HISTORY | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS));
        } catch (Exception e) {
            Log.w(TAG, "app info for " + current, e);
            failed++;
            next();
            return;
        }
        h.postDelayed(poll, 600);
    }

    final Runnable poll = new Runnable() {
        @Override public void run() { step(); }
    };

    void step() {
        if (!active) return;
        long now = SystemClock.elapsedRealtime();
        if (now - stageStart > STAGE_TIMEOUT) {
            Log.w(TAG, "timed out at stage " + stage + " for " + current);
            failed++;
            next();
            return;
        }
        AccessibilityNodeInfo root = null;
        try { root = svc.getRootInActiveWindow(); } catch (Exception ignored) { }
        if (root == null || root.getPackageName() == null) { h.postDelayed(poll, POLL); return; }

        if (stage == 0) {
            AccessibilityNodeInfo btn = clickable(find(root, stopLabels));
            if (btn == null) { h.postDelayed(poll, POLL); return; }
            if (!btn.isEnabled()) {          // greyed out: the app is already stopped
                done(true);
                return;
            }
            btn.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            btn.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            stage = 1;
            stageStart = now;
            h.postDelayed(poll, 500);
        } else {
            // Confirmation: an OK button, or (phone style) a second "Force stop" button in a dialog.
            AccessibilityNodeInfo btn = clickable(find(root, okLabels));
            if (btn == null) {
                AccessibilityNodeInfo again = clickable(find(root, stopLabels));
                if (again != null && again.getClassName() != null
                        && again.getClassName().toString().contains("Button")) btn = again;
            }
            if (btn == null) { h.postDelayed(poll, POLL); return; }
            btn.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            btn.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            done(true);
        }
    }

    void done(boolean ok) {
        if (ok) { stopped++; RunningApps.forget(svc, current); } else failed++;
        h.postDelayed(new Runnable() { @Override public void run() { next(); } }, 400);
    }

    void finish() {
        active = false;
        svc.openLiteHome();
        String msg = "Closed " + stopped + (stopped == 1 ? " app" : " apps")
                + (failed > 0 ? ", " + failed + " didn't respond" : "");
        Toast.makeText(svc, msg, Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- node helpers

    static AccessibilityNodeInfo find(AccessibilityNodeInfo root, List<String> labels) {
        for (String l : labels) {
            List<AccessibilityNodeInfo> found;
            try { found = root.findAccessibilityNodeInfosByText(l); } catch (Exception e) { continue; }
            if (found == null) continue;
            for (AccessibilityNodeInfo n : found) {
                CharSequence t = n.getText();
                if (t == null) t = n.getContentDescription();
                if (t != null && t.toString().trim().equalsIgnoreCase(l)) return n;
            }
        }
        return null;
    }

    static AccessibilityNodeInfo clickable(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo c = n;
        for (int i = 0; c != null && i < 6; i++) {
            if (c.isClickable()) return c;
            c = c.getParent();
        }
        return n;
    }
}
