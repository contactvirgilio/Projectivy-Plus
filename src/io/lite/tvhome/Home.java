package io.lite.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lite Home: a tiny Android TV launcher.
 * One activity, no libraries, no network, no tracking.
 */
public class Home extends Activity {

    static final int COLUMNS = 5;
    static final int ACCENT = 0xFFFFB020;

    static final class App {
        String pkg;
        String label;
        ComponentName cn;
        boolean leanback;
        Drawable banner;
        Drawable icon;
        int tint = 0xFF2A2F3A;
    }

    final Handler ui = new Handler(Looper.getMainLooper());
    final List<App> apps = new ArrayList<>();
    SharedPreferences prefs;
    GridView grid;
    TileAdapter adapter;
    View lastFocused;
    int moving = -1;
    boolean dirty = true;
    boolean resumed;
    int loadGen;
    int tileW, tileH;
    BroadcastReceiver pkgReceiver;
    FlowBackground bg;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        setContentView(buildUi());

        pkgReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                dirty = true;
                if (resumed) reload();
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        bg.start();
        if (dirty) reload();
        grid.requestFocus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        bg.stop();
        if (moving >= 0) endMove();
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(pkgReceiver); } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: jump back to the first tile.
        if (moving >= 0) endMove();
        if (resumed && adapter.getCount() > 0) grid.setSelection(0);
    }

    @Override
    public void onBackPressed() {
        if (moving >= 0) endMove();
        // A launcher has nowhere to go back to.
    }

    // ---------------------------------------------------------------- UI

    int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    View buildUi() {
        DisplayMetrics m = getResources().getDisplayMetrics();
        int padH = dp(48), gridPad = dp(14), hSpace = dp(26), vSpace = dp(18);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        bg = new FlowBackground(this);
        bg.configure(prefs.getInt("bg_palette", 0), prefs.getInt("bg_speed", 1), prefs.getInt("bg_bright", 1));
        root.addView(bg, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(padH - gridPad, dp(28), padH - gridPad, 0);
        col.setClipChildren(false);
        col.setClipToPadding(false);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        // Header: date on the left, clock on the right.
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.BOTTOM);
        header.setPadding(gridPad, 0, gridPad, dp(10));

        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, d MMMM");
        date.setFormat24Hour("EEEE, d MMMM");
        date.setTextColor(0xCCFFFFFF);
        date.setShadowLayer(dp(6), 0, dp(1), 0x99000000);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        header.addView(date, new LinearLayout.LayoutParams(0, -2, 1));

        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTextColor(Color.WHITE);
        clock.setShadowLayer(dp(8), 0, dp(1), 0x99000000);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40);
        clock.setIncludeFontPadding(false);
        header.addView(clock, new LinearLayout.LayoutParams(-2, -2));
        col.addView(header, new LinearLayout.LayoutParams(-1, -2));

        int usable = m.widthPixels - 2 * padH;
        tileW = (usable - (COLUMNS - 1) * hSpace) / COLUMNS;
        tileH = tileW * 9 / 16;

        grid = new GridView(this);
        grid.setNumColumns(COLUMNS);
        grid.setColumnWidth(tileW);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(hSpace);
        grid.setVerticalSpacing(vSpace);
        grid.setPadding(gridPad, gridPad, gridPad, dp(40));
        grid.setClipToPadding(false);
        grid.setClipChildren(false);
        grid.setSelector(new GradientDrawable()); // transparent; we draw our own focus
        grid.setVerticalScrollBarEnabled(false);
        grid.setOverScrollMode(View.OVER_SCROLL_NEVER);
        grid.setFocusable(true);
        col.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));

        adapter = new TileAdapter();
        grid.setAdapter(adapter);

        grid.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (lastFocused != null && lastFocused != v) style(lastFocused, false);
                style(v, true);
                lastFocused = v;
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
                if (lastFocused != null) style(lastFocused, false);
                lastFocused = null;
            }
        });
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() { @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
            if (moving >= 0) { endMove(); return; }
            if (pos >= apps.size()) openSettings(); else launch(apps.get(pos));
        }});
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() { @Override public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
            if (moving < 0) showOptions(pos);
            return true;
        }});
        grid.setOnKeyListener(new View.OnKeyListener() { @Override public boolean onKey(View v, int code, KeyEvent ev) { return onGridKey(code, ev); } });
        return root;
    }

    boolean onGridKey(int code, KeyEvent ev) {
        if (moving < 0) {
            if (code == KeyEvent.KEYCODE_MENU) {
                if (ev.getAction() == KeyEvent.ACTION_UP) showOptions(grid.getSelectedItemPosition());
                return true;
            }
            return false;
        }
        if (ev.getAction() != KeyEvent.ACTION_DOWN) return true;
        int target = moving;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: target = moving - 1; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: target = moving + 1; break;
            case KeyEvent.KEYCODE_DPAD_UP: target = moving - COLUMNS; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: target = moving + COLUMNS; break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BACK:
                endMove();
                return true;
            default:
                return true;
        }
        if (target < 0) target = 0;
        if (target > apps.size() - 1) target = apps.size() - 1;
        if (target != moving) {
            App a = apps.remove(moving);
            apps.add(target, a);
            moving = target;
            adapter.notifyDataSetChanged();
            grid.setSelection(target);
        }
        return true;
    }

    void style(View cell, boolean focused) {
        if (cell == null) return;
        Holder h = (Holder) cell.getTag();
        if (h == null) return;
        float s = focused ? 1.08f : 1f;
        h.card.animate().scaleX(s).scaleY(s).setDuration(120).start();
        h.card.setForeground(focused ? focusRing(moving >= 0 ? ACCENT : Color.WHITE) : null);
        h.caption.setTextColor(focused ? Color.WHITE : 0x00FFFFFF);
        h.caption.setShadowLayer(focused ? dp(4) : 0, 0, focused ? dp(1) : 0, focused ? 0xAA000000 : 0);
    }

    Drawable focusRing(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(10));
        g.setStroke(dp(3), color);
        return g;
    }

    static final class Holder {
        FrameLayout card;
        ImageView banner;
        LinearLayout fallback;
        ImageView icon;
        TextView name;
        TextView caption;
    }

    final class TileAdapter extends BaseAdapter {
        @Override public int getCount() { return apps.size() + 1; } // + Settings tile
        @Override public Object getItem(int p) { return p < apps.size() ? apps.get(p) : null; }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            Holder h;
            if (convert == null) {
                h = new Holder();
                LinearLayout cell = new LinearLayout(Home.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setClipChildren(false);

                h.card = new FrameLayout(Home.this);
                h.card.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
                h.card.setClipToOutline(true);
                cell.addView(h.card, new LinearLayout.LayoutParams(-1, tileH));

                h.banner = new ImageView(Home.this);
                h.banner.setScaleType(ImageView.ScaleType.CENTER_CROP);
                h.card.addView(h.banner, new FrameLayout.LayoutParams(-1, -1));

                h.fallback = new LinearLayout(Home.this);
                h.fallback.setOrientation(LinearLayout.VERTICAL);
                h.fallback.setGravity(Gravity.CENTER);
                h.icon = new ImageView(Home.this);
                h.fallback.addView(h.icon, new LinearLayout.LayoutParams(tileH / 2, tileH / 2));
                h.name = new TextView(Home.this);
                h.name.setTextColor(Color.WHITE);
                h.name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                h.name.setSingleLine(true);
                h.name.setGravity(Gravity.CENTER);
                h.name.setPadding(dp(6), dp(4), dp(6), 0);
                h.fallback.addView(h.name, new LinearLayout.LayoutParams(-1, -2));
                h.card.addView(h.fallback, new FrameLayout.LayoutParams(-1, -1));

                h.caption = new TextView(Home.this);
                h.caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                h.caption.setSingleLine(true);
                h.caption.setGravity(Gravity.CENTER);
                h.caption.setPadding(0, dp(8), 0, 0);
                cell.addView(h.caption, new LinearLayout.LayoutParams(-1, -2));

                cell.setTag(h);
                convert = cell;
            } else {
                h = (Holder) convert.getTag();
            }

            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(10));
            if (pos >= apps.size()) {
                bg.setColor(0xC8232834);
                h.card.setBackground(bg);
                h.banner.setVisibility(View.GONE);
                h.fallback.setVisibility(View.VISIBLE);
                h.icon.setImageDrawable(getDrawable(android.R.drawable.ic_menu_preferences));
                h.name.setText("Settings");
                h.caption.setText("Settings");
            } else {
                App a = apps.get(pos);
                h.caption.setText(a.label);
                if (a.banner != null) {
                    bg.setColor(0xFF000000);
                    h.banner.setImageDrawable(a.banner);
                    h.banner.setVisibility(View.VISIBLE);
                    h.fallback.setVisibility(View.GONE);
                } else {
                    bg.setColor((a.tint & 0x00FFFFFF) | 0xD8000000);
                    h.icon.setImageDrawable(a.icon);
                    h.name.setText(a.label);
                    h.banner.setVisibility(View.GONE);
                    h.fallback.setVisibility(View.VISIBLE);
                }
                h.card.setBackground(bg);
            }

            boolean sel = pos == grid.getSelectedItemPosition() && grid.hasFocus();
            h.card.setScaleX(sel ? 1.08f : 1f);
            h.card.setScaleY(sel ? 1.08f : 1f);
            h.card.setForeground(sel ? focusRing(moving >= 0 ? ACCENT : Color.WHITE) : null);
            h.caption.setTextColor(sel ? Color.WHITE : 0x00FFFFFF);
            h.caption.setShadowLayer(sel ? dp(4) : 0, 0, sel ? dp(1) : 0, sel ? 0xAA000000 : 0);
            if (sel) lastFocused = convert;
            return convert;
        }
    }

    // ---------------------------------------------------------------- loading apps

    void reload() {
        dirty = false;
        final int gen = ++loadGen;
        new Thread(new Runnable() { @Override public void run() {
            final List<App> list = load();
            ui.post(new Runnable() { @Override public void run() {
                if (gen != loadGen) return;
                String keep = null;
                int sel = grid.getSelectedItemPosition();
                if (sel >= 0 && sel < apps.size()) keep = apps.get(sel).pkg;
                apps.clear();
                apps.addAll(list);
                adapter.notifyDataSetChanged();
                int idx = 0;
                for (int i = 0; keep != null && i < apps.size(); i++)
                    if (apps.get(i).pkg.equals(keep)) { idx = i; break; }
                grid.setSelection(idx);
            }});
        }}, "loader").start();
    }

    List<App> load() {
        PackageManager pm = getPackageManager();
        LinkedHashMap<String, App> byPkg = new LinkedHashMap<>();
        collect(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, true, byPkg);
        collect(pm, Intent.CATEGORY_LAUNCHER, false, byPkg);
        byPkg.remove(getPackageName());

        Set<String> hidden = prefs.getStringSet("hidden", Collections.<String>emptySet());
        final Map<String, Integer> rank = new HashMap<>();
        String[] order = prefs.getString("order", "").split("\n");
        for (int i = 0; i < order.length; i++) if (!order[i].isEmpty()) rank.put(order[i], i);

        List<App> out = new ArrayList<>();
        for (App a : byPkg.values()) if (!hidden.contains(a.pkg)) out.add(a);
        Collections.sort(out, new Comparator<App>() {
            @Override
            public int compare(App x, App y) {
                Integer rx = rank.get(x.pkg), ry = rank.get(y.pkg);
                if (rx != null && ry != null) return rx - ry;
                if (rx != null) return -1;
                if (ry != null) return 1;
                return x.label.compareToIgnoreCase(y.label);
            }
        });
        return out;
    }

    void collect(PackageManager pm, String category, boolean leanback, Map<String, App> into) {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
        List<ResolveInfo> found;
        try { found = pm.queryIntentActivities(q, 0); } catch (Exception e) { return; }
        for (ResolveInfo ri : found) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null || into.containsKey(ai.packageName)) continue;
            App a = new App();
            a.pkg = ai.packageName;
            a.cn = new ComponentName(ai.packageName, ai.name);
            a.leanback = leanback;
            CharSequence l = ri.loadLabel(pm);
            a.label = l == null ? a.pkg : l.toString();
            try { a.banner = ai.loadBanner(pm); } catch (Exception ignored) { }
            if (a.banner == null) {
                try { a.icon = ri.loadIcon(pm); } catch (Exception ignored) { }
                a.tint = tintFor(a.icon);
            }
            into.put(a.pkg, a);
        }
    }

    /** Average colour of the icon, darkened, for the tile background. */
    static int tintFor(Drawable d) {
        if (d == null) return 0xFF2A2F3A;
        try {
            Bitmap bm = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            d.setBounds(0, 0, 12, 12);
            d.draw(c);
            long r = 0, g = 0, b = 0, n = 0;
            for (int y = 0; y < 12; y++)
                for (int x = 0; x < 12; x++) {
                    int p = bm.getPixel(x, y);
                    if (Color.alpha(p) < 128) continue;
                    r += Color.red(p); g += Color.green(p); b += Color.blue(p); n++;
                }
            bm.recycle();
            if (n == 0) return 0xFF2A2F3A;
            return Color.rgb((int) (r / n * 45 / 100), (int) (g / n * 45 / 100), (int) (b / n * 45 / 100));
        } catch (Exception e) {
            return 0xFF2A2F3A;
        }
    }

    // ---------------------------------------------------------------- actions

    void launch(App a) {
        PackageManager pm = getPackageManager();
        Intent i = null;
        try {
            i = a.leanback ? pm.getLeanbackLaunchIntentForPackage(a.pkg) : null;
            if (i == null) i = pm.getLaunchIntentForPackage(a.pkg);
        } catch (Exception ignored) { }
        if (i == null) {
            i = new Intent(Intent.ACTION_MAIN)
                    .addCategory(a.leanback ? Intent.CATEGORY_LEANBACK_LAUNCHER : Intent.CATEGORY_LAUNCHER)
                    .setComponent(a.cn);
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        start(i);
    }

    void openSettings() {
        start(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    void start(Intent i) {
        try {
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Can't open that", Toast.LENGTH_SHORT).show();
        }
    }

    AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
    }

    // ---------------------------------------------------------------- override (accessibility)

    boolean overrideOn() {
        try {
            String s = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (s == null) return false;
            String full = getPackageName() + "/" + HomeOverride.class.getName();
            String shrt = getPackageName() + "/.HomeOverride";
            for (String part : s.split(":"))
                if (part.equalsIgnoreCase(full) || part.equalsIgnoreCase(shrt)) return true;
        } catch (Exception ignored) { }
        return false;
    }

    void toggleOverride() {
        final boolean on = overrideOn();
        dialog().setTitle(on ? "Turn off override" : "Override current launcher")
                .setMessage(on
                        ? "In the next screen open \"Lite Home override\" and switch it off."
                        : "Catches the remote's Home button and opens Lite Home, whatever launcher "
                        + "is set as default.\n\nIn the next screen open \"Lite Home override\" and "
                        + "switch it on. Turn off Projectivy's own override first.")
                .setPositiveButton("Open accessibility", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        try {
                            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        } catch (Exception e) {
                            showOverrideAdb();
                        }
                    }
                })
                .setNeutralButton("adb command", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) { showOverrideAdb(); }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    void showOverrideAdb() {
        dialog().setTitle("Enable from a computer")
                .setMessage("adb shell settings put secure enabled_accessibility_services "
                        + getPackageName() + "/.HomeOverride\n\n"
                        + "adb shell settings put secure accessibility_enabled 1\n\n"
                        + "Or make it the real default Home app:\n"
                        + "adb shell cmd package set-home-activity " + getPackageName() + "/.Home")
                .setPositiveButton("OK", null)
                .show();
    }

    // ---------------------------------------------------------------- personalisation

    static String paletteName(int i) {
        return i >= 0 && i < FlowBackground.NAMES.length ? FlowBackground.NAMES[i] : "Cycle through all";
    }

    void applyBackground() {
        bg.configure(prefs.getInt("bg_palette", 0), prefs.getInt("bg_speed", 1), prefs.getInt("bg_bright", 1));
        bg.start();
    }

    void pickBackground() {
        String[] names = new String[FlowBackground.NAMES.length + 1];
        for (int i = 0; i < names.length; i++) names[i] = paletteName(i);
        pickSetting("Background", names, "bg_palette", 0);
    }

    /** Single-choice list that previews each option live as you move over it; Back restores. */
    void pickSetting(String title, String[] names, final String key, int def) {
        final int before = prefs.getInt(key, def);
        final boolean[] chosen = {false};
        final AlertDialog d = dialog().setTitle(title)
                .setSingleChoiceItems(names, before, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface di, int which) {
                        chosen[0] = true;
                        prefs.edit().putInt(key, which).apply();
                        applyBackground();
                        di.dismiss();
                    }
                })
                .create();
        d.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface di) {
                if (!chosen[0]) { prefs.edit().putInt(key, before).apply(); applyBackground(); }
            }
        });
        d.show();
        if (d.getWindow() != null) d.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        d.getListView().setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                prefs.edit().putInt(key, pos).apply();
                applyBackground();
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
    }

    void showOptions(final int pos) {
        if (pos < 0) return;
        if (pos >= apps.size()) {
            final List<String> hiddenPkgs = new ArrayList<>(
                    prefs.getStringSet("hidden", Collections.<String>emptySet()));
            String[] items = {
                    "Open Android settings",
                    "Background: " + paletteName(prefs.getInt("bg_palette", 0)),
                    "Motion: " + FlowBackground.SPEED_NAMES[prefs.getInt("bg_speed", 1)],
                    "Brightness: " + FlowBackground.BRIGHT_NAMES[prefs.getInt("bg_bright", 1)],
                    "Show hidden apps (" + hiddenPkgs.size() + ")",
                    "Reset app order",
                    "Override Home button: " + (overrideOn() ? "ON" : "OFF")};
            dialog().setTitle("Lite Home").setItems(items, new android.content.DialogInterface.OnClickListener() { @Override public void onClick(android.content.DialogInterface d, int which) {
                switch (which) {
                    case 0: openSettings(); break;
                    case 1: pickBackground(); break;
                    case 2: pickSetting("Motion", FlowBackground.SPEED_NAMES, "bg_speed", 1); break;
                    case 3: pickSetting("Brightness", FlowBackground.BRIGHT_NAMES, "bg_bright", 1); break;
                    case 4: showHidden(hiddenPkgs); break;
                    case 5: prefs.edit().remove("order").apply(); reload(); break;
                    default: toggleOverride();
                }
            }}).show();
            return;
        }
        final App a = apps.get(pos);
        String[] items = {"Open", "Move", "Move to first", "Hide", "App info", "Uninstall"};
        dialog().setTitle(a.label).setItems(items, new android.content.DialogInterface.OnClickListener() { @Override public void onClick(android.content.DialogInterface d, int which) {
            switch (which) {
                case 0: launch(a); break;
                case 1: startMove(pos); break;
                case 2:
                    apps.remove(pos);
                    apps.add(0, a);
                    saveOrder();
                    adapter.notifyDataSetChanged();
                    grid.setSelection(0);
                    break;
                case 3: {
                    Set<String> h = new HashSet<>(prefs.getStringSet("hidden", Collections.<String>emptySet()));
                    h.add(a.pkg);
                    prefs.edit().putStringSet("hidden", h).apply();
                    apps.remove(pos);
                    adapter.notifyDataSetChanged();
                    grid.setSelection(Math.min(pos, apps.size()));
                    break;
                }
                case 4:
                    start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", a.pkg, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    break;
                case 5:
                    start(new Intent(Intent.ACTION_DELETE,
                            Uri.fromParts("package", a.pkg, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    break;
            }
        }}).show();
    }

    void showHidden(final List<String> pkgs) {
        if (pkgs.isEmpty()) {
            Toast.makeText(this, "No hidden apps", Toast.LENGTH_SHORT).show();
            return;
        }
        PackageManager pm = getPackageManager();
        String[] labels = new String[pkgs.size()];
        for (int i = 0; i < pkgs.size(); i++) {
            String l = pkgs.get(i);
            try { l = pm.getApplicationLabel(pm.getApplicationInfo(l, 0)).toString(); } catch (Exception ignored) { }
            labels[i] = l;
        }
        dialog().setTitle("Tap to unhide").setItems(labels, new android.content.DialogInterface.OnClickListener() { @Override public void onClick(android.content.DialogInterface d, int which) {
            Set<String> h = new HashSet<>(prefs.getStringSet("hidden", Collections.<String>emptySet()));
            h.remove(pkgs.get(which));
            prefs.edit().putStringSet("hidden", h).apply();
            reload();
        }}).show();
    }

    void startMove(int pos) {
        moving = pos;
        adapter.notifyDataSetChanged();
        grid.setSelection(pos);
        Toast.makeText(this, "Use the arrows to move, OK to finish", Toast.LENGTH_SHORT).show();
    }

    void endMove() {
        moving = -1;
        saveOrder();
        adapter.notifyDataSetChanged();
    }

    void saveOrder() {
        StringBuilder sb = new StringBuilder();
        for (App a : apps) sb.append(a.pkg).append('\n');
        prefs.edit().putString("order", sb.toString()).apply();
    }
}
