package io.lite.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * "Override current launcher": when any other Home screen comes to the front
 * (stock launcher, Projectivy...), open Lite Home in its place.
 * Also enforces app locks and opens the power menu. Reads no screen content.
 */
public class HomeOverride extends AccessibilityService {

    Set<String> homePkgs = new HashSet<>();
    Set<String> homeClasses = new HashSet<>();
    long refreshedAt, lastLaunch;

    /** The running service, so the launcher can open the power menu. Null when switched off. */
    static volatile HomeOverride instance;

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override
    public boolean onUnbind(Intent i) {
        instance = null;
        return super.onUnbind(i);
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 0;
        info.flags = AccessibilityServiceInfo.DEFAULT | AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        setServiceInfo(info);
        refresh();
    }

    /** Catch the remote's Home button directly, like Button Mapper does. */
    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        if (e.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) openLiteHome();
        return true; // swallow both down and up so the old launcher never opens
    }

    void openLiteHome() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastLaunch < 700) return;
        lastLaunch = now;
        try {
            startActivity(new Intent(this, Home.class)
                    .setAction(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (Exception ex) {
            Log.w("LiteHome", "could not open Lite Home", ex);
        }
    }

    void refresh() {
        Set<String> pkgs = new HashSet<>(), classes = new HashSet<>();
        try {
            List<ResolveInfo> l = getPackageManager().queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
            for (ResolveInfo ri : l) {
                ActivityInfo ai = ri.activityInfo;
                if (ai == null || ai.packageName.equals(getPackageName())) continue;
                pkgs.add(ai.packageName);
                classes.add(ai.name);
                if (ai.targetActivity != null) classes.add(ai.targetActivity);
            }
        } catch (Exception ignored) { }
        homePkgs = pkgs;
        homeClasses = classes;
        refreshedAt = SystemClock.elapsedRealtime();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence p = e.getPackageName(), c = e.getClassName();
        if (p == null || c == null) return;
        String pkg = p.toString(), cls = c.toString();
        if (pkg.equals(getPackageName())) return;

        // App lock: a locked app opened any other way (voice, another app, recents) gets the PIN pad.
        if (Home.isLocked(this, pkg) && !pkg.equals(Home.unlockedPkg)) {
            Log.i("LiteHome", "locked app opened: " + pkg);
            try {
                startActivity(new Intent(this, Home.class)
                        .putExtra(Home.EXTRA_LOCKED, pkg)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
            } catch (Exception ex) {
                Log.w("LiteHome", "could not show PIN", ex);
            }
            return;
        }

        long now = SystemClock.elapsedRealtime();
        if (now - refreshedAt > 60_000) refresh();
        if (!homePkgs.contains(pkg)) return;
        // Only the launcher's home screen itself, so its settings etc. still open normally.
        if (!homeClasses.contains(cls)) return;
        Log.i("LiteHome", "launcher window seen: " + pkg + "/" + cls);
        openLiteHome();
    }

    /** Opens the system power menu (Restart / Power off). */
    boolean showPowerMenu() {
        return performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
    }

    @Override
    public void onInterrupt() { }
}
