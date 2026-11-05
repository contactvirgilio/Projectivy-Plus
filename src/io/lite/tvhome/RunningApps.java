package io.lite.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Apps opened since this boot, as seen by the accessibility service. Android no longer lets
 * ordinary apps list other running apps, so this is the launcher's own record of them.
 * Only user-installed apps (not system or preinstalled ones) are kept.
 */
final class RunningApps {

    static final String PREFS = "running";
    /** Packages already written this process, to avoid a disk write on every window change. */
    static final Set<String> written = new HashSet<>();

    static long bootWallTime() {
        return System.currentTimeMillis() - SystemClock.elapsedRealtime();
    }

    static boolean isUserApp(PackageManager pm, String pkg) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return (ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Called by the service for every app window that comes to the front. */
    static void seen(Context c, String pkg) {
        if (pkg.equals(c.getPackageName())) return;
        synchronized (written) {
            if (written.contains(pkg)) return;
        }
        if (!isUserApp(c.getPackageManager(), pkg)) return;
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(pkg, System.currentTimeMillis()).apply();
        synchronized (written) { written.add(pkg); }
    }

    static void forget(Context c, String pkg) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(pkg).apply();
        synchronized (written) { written.remove(pkg); }
    }

    /** User apps opened since boot, most recent first. Entries from before the last boot are dropped. */
    static List<String> list(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long boot = bootWallTime() - 5000;
        PackageManager pm = c.getPackageManager();
        final Map<String, ?> all = p.getAll();
        List<String> out = new ArrayList<>();
        SharedPreferences.Editor stale = p.edit();
        for (Map.Entry<String, ?> e : all.entrySet()) {
            Object v = e.getValue();
            if (!(v instanceof Long) || (Long) v < boot || !isUserApp(pm, e.getKey())) {
                stale.remove(e.getKey());
                continue;
            }
            out.add(e.getKey());
        }
        stale.apply();
        Collections.sort(out, new Comparator<String>() {
            @Override public int compare(String a, String b) {
                return Long.compare((Long) all.get(b), (Long) all.get(a));
            }
        });
        return out;
    }
}
