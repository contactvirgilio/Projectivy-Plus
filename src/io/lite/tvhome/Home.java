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
    // Fixed tiles after the apps.
    static final int T_MENU = 0, T_SETTINGS = 1, T_POWER = 2, SYSTEM_TILES = 3;
    static final String[] SYSTEM_LABELS = {"Lite Home", "Android settings", "Power"};
    static final int[] SYSTEM_ICONS = {
            android.R.drawable.ic_menu_manage, android.R.drawable.ic_menu_preferences,
            android.R.drawable.ic_lock_power_off};

    static final String EXTRA_LOCKED = "io.lite.tvhome.LOCKED";
    static final String[] ACCENT_NAMES = {"Match background", "Amber", "Cyan", "Lime", "Pink", "Violet", "Red"};
    static final int[] ACCENTS = {0, 0xFFFFB020, 0xFF22D3EE, 0xFFA3E635, 0xFFFF6FB5, 0xFFB18CFF, 0xFFFF5252};

    /** Locked app the user just unlocked; the lock service lets it through until Home shows again. */
    static volatile String unlockedPkg;

    static boolean isLocked(Context c, String pkg) {
        SharedPreferences p = c.getSharedPreferences("home", MODE_PRIVATE);
        return PinPad.hasPin(p) && p.getStringSet("locked", Collections.<String>emptySet()).contains(pkg);
    }

    static final class App {
        String pkg;
        String label;
        ComponentName cn;
        boolean leanback;
        Drawable banner;
        Drawable icon;
        int tint = 0xFF2A2F3A;  // dark tile colour for Regular icon tiles
        int vivid = 0xFF6B7280; // the app's own colour, for Tinted glass
    }

    static final String[] TILE_STYLES = {"Regular", "Glass", "Tinted glass"};

    int tileStyle() { return prefs.getInt("tile_style", 0); }

    final Handler ui = new Handler(Looper.getMainLooper());
    final List<App> apps = new ArrayList<>();
    SharedPreferences prefs;
    GridView grid;
    TileAdapter adapter;
    Holder focusedHolder;
    android.animation.ValueAnimator pulse;
    long adminUntil;          // options stay unlocked for 2 min after the PIN, or until Home is left
    AlertDialog pinDlg;
    int wrongPins;
    long pinBlockedUntil;
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
        handleLockIntent(getIntent());

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
        unlockedPkg = null;
        bg.start();
        pulse.start();
        if (dirty) reload();
        grid.requestFocus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        adminUntil = 0;
        bg.stop();
        pulse.cancel();
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
        if (handleLockIntent(intent)) return;
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
        int padH = dp(48), gridPad = dp(22), hSpace = dp(30), vSpace = dp(20);

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

        // One breathing animation, applied to whichever tile has focus.
        pulse = android.animation.ValueAnimator.ofFloat(0f, 1f);
        pulse.setDuration(1300);
        pulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        pulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        pulse.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        pulse.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                if (focusedHolder != null) focusedHolder.ring.setPulse((Float) a.getAnimatedValue());
            }
        });

        // Restyle every visible tile on each change, so no stale highlight can survive.
        grid.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { refreshFocus(); }
            @Override public void onNothingSelected(AdapterView<?> p) { refreshFocus(); }
        });
        grid.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) { refreshFocus(); }
        });
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() { @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
            if (moving >= 0) { endMove(); return; }
            if (pos < apps.size()) { launch(apps.get(pos)); return; }
            switch (pos - apps.size()) {
                case T_MENU: showOptions(pos); break;
                case T_SETTINGS: openSettings(); break;
                case T_POWER: power(); break;
            }
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

    int accent() {
        int i = prefs.getInt("accent", 0);
        return i > 0 && i < ACCENTS.length ? ACCENTS[i] : bg.accent();
    }

    void refreshFocus() {
        int sel = grid.getSelectedItemPosition();
        boolean has = grid.hasFocus();
        int first = grid.getFirstVisiblePosition();
        for (int i = 0; i < grid.getChildCount(); i++) {
            View c = grid.getChildAt(i);
            if (c.getTag() instanceof Holder)
                applyFocus((Holder) c.getTag(), has && first + i == sel, true);
        }
    }

    void applyFocus(Holder h, boolean focused, boolean animate) {
        float s = focused ? 1.08f : 1f;
        if (animate && h.focused != focused) {
            h.wrap.animate().scaleX(s).scaleY(s).setDuration(140).start();
        } else {
            h.wrap.animate().cancel();
            h.wrap.setScaleX(s);
            h.wrap.setScaleY(s);
        }
        h.focused = focused;
        // While moving a tile the ring turns white so the mode is obvious.
        h.ring.setColor(moving >= 0 ? Color.WHITE : accent());
        h.glow.setVisibility(focused ? View.VISIBLE : View.INVISIBLE);
        if (focused) {
            if (focusedHolder != null && focusedHolder != h) focusedHolder.glow.setVisibility(View.INVISIBLE);
            focusedHolder = h;
            if (pulse.isRunning()) h.ring.setPulse((Float) pulse.getAnimatedValue());
        } else if (focusedHolder == h) {
            focusedHolder = null;
        }
        // Glass app tiles already show their name, so no caption underneath.
        boolean cap = focused && !(h.isApp && tileStyle() > 0);
        h.caption.setTextColor(cap ? Color.WHITE : 0x00FFFFFF);
        h.caption.setShadowLayer(cap ? dp(4) : 0, 0, cap ? dp(1) : 0, cap ? 0xAA000000 : 0);
    }


    static final class Holder {
        FrameLayout wrap;   // scaled on focus; holds glow + card
        View glow;
        GlowRing ring;
        FrameLayout card;
        ImageView banner;
        LinearLayout fallback;
        ImageView icon;
        TextView name;
        TextView caption;
        TextView label;     // Glass styles: the app name on the tile
        boolean isApp;
        ImageView lock;
        boolean focused;
    }

    /** Semi-transparent tile; with a tint colour it gets a faint wash of that colour. */
    GradientDrawable glassBg(int tint) {
        GradientDrawable g;
        if (tint == 0) {
            g = new GradientDrawable();
            g.setColor(0x17FFFFFF);
        } else {
            int rgb = tint & 0x00FFFFFF;
            g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{0x59000000 | rgb, 0x24000000 | rgb});
        }
        g.setCornerRadius(dp(10));
        g.setStroke(Math.max(1, dp(1)), 0x29FFFFFF);
        return g;
    }

    void sizeIcon(Holder h, int px) {
        ViewGroup.LayoutParams lp = h.icon.getLayoutParams();
        if (lp.width == px) return;
        lp.width = lp.height = px;
        h.icon.setLayoutParams(lp);
    }

    final class TileAdapter extends BaseAdapter {
        @Override public int getCount() { return apps.size() + SYSTEM_TILES; }
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

                h.wrap = new FrameLayout(Home.this);
                h.wrap.setClipChildren(false);
                h.wrap.setClipToPadding(false);
                cell.addView(h.wrap, new LinearLayout.LayoutParams(-1, tileH));

                h.ring = new GlowRing(getResources().getDisplayMetrics().density, 10);
                h.glow = new View(Home.this);
                h.glow.setBackground(h.ring);
                h.glow.setVisibility(View.INVISIBLE);
                int m = (int) Math.ceil(h.ring.margin);
                FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(-1, -1);
                glp.setMargins(-m, -m, -m, -m);
                h.wrap.addView(h.glow, glp);

                h.card = new FrameLayout(Home.this);
                h.card.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
                h.card.setClipToOutline(true);
                h.wrap.addView(h.card, new FrameLayout.LayoutParams(-1, -1));

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

                h.label = new TextView(Home.this);
                h.label.setTextColor(Color.WHITE);
                h.label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
                h.label.setGravity(Gravity.CENTER);
                h.label.setMaxLines(2);
                h.label.setEllipsize(android.text.TextUtils.TruncateAt.END);
                h.label.setPadding(dp(10), 0, dp(10), 0);
                h.label.setShadowLayer(dp(6), 0, dp(1), 0x59000000);
                h.label.setVisibility(View.GONE);
                h.card.addView(h.label, new FrameLayout.LayoutParams(-1, -1));

                h.lock = new ImageView(Home.this);
                h.lock.setImageDrawable(getDrawable(android.R.drawable.ic_lock_lock));
                GradientDrawable badge = new GradientDrawable();
                badge.setShape(GradientDrawable.OVAL);
                badge.setColor(0xB0000000);
                h.lock.setBackground(badge);
                h.lock.setPadding(dp(4), dp(4), dp(4), dp(4));
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.TOP | Gravity.END);
                lp.setMargins(0, dp(6), dp(6), 0);
                h.card.addView(h.lock, lp);

                h.caption = new TextView(Home.this);
                h.caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                h.caption.setSingleLine(true);
                h.caption.setGravity(Gravity.CENTER);
                h.caption.setPadding(0, dp(14), 0, 0);
                cell.addView(h.caption, new LinearLayout.LayoutParams(-1, -2));

                cell.setTag(h);
                convert = cell;
            } else {
                h = (Holder) convert.getTag();
            }

            int style = tileStyle();
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(10));
            if (pos >= apps.size()) {
                int t = pos - apps.size();
                String label = SYSTEM_LABELS[t];
                h.isApp = false;
                h.label.setVisibility(View.GONE);
                if (style > 0) bg = glassBg(0); else bg.setColor(0xC8232834);
                h.card.setBackground(bg);
                h.banner.setVisibility(View.GONE);
                h.fallback.setVisibility(View.VISIBLE);
                // Lite Home (cog) and Android settings (robot): just the icon, no words inside the tile.
                Drawable icon;
                if (t == T_SETTINGS) icon = new AndroidRobot(0xFF232834);
                else if (t == T_MENU) icon = new CogIcon(0xFFE6E9F0, 0xFF232834);
                else icon = getDrawable(SYSTEM_ICONS[t]);
                boolean iconOnly = t == T_SETTINGS || t == T_MENU;
                h.icon.setImageDrawable(icon);
                h.name.setText(label);
                h.name.setVisibility(iconOnly ? View.GONE : View.VISIBLE);
                sizeIcon(h, iconOnly ? tileH * 62 / 100 : tileH / 2);
                h.caption.setText(label);
                boolean locked = (t == T_SETTINGS && isLocked(Home.this, settingsPkg()))
                        || (t == T_MENU && PinPad.hasPin(prefs));
                h.lock.setVisibility(locked ? View.VISIBLE : View.GONE);
            } else {
                App a = apps.get(pos);
                h.isApp = true;
                h.lock.setVisibility(isLocked(Home.this, a.pkg) ? View.VISIBLE : View.GONE);
                h.caption.setText(a.label);
                h.label.setVisibility(style > 0 ? View.VISIBLE : View.GONE);
                if (style > 0) {
                    // Glass / Tinted glass: see-through tile with just the name.
                    bg = glassBg(style == 2 ? a.vivid : 0);
                    h.label.setText(a.label);
                    h.banner.setVisibility(View.GONE);
                    h.fallback.setVisibility(View.GONE);
                } else if (a.banner != null) {
                    bg.setColor(0xFF000000);
                    h.banner.setImageDrawable(a.banner);
                    h.banner.setVisibility(View.VISIBLE);
                    h.fallback.setVisibility(View.GONE);
                } else {
                    bg.setColor((a.tint & 0x00FFFFFF) | 0xD8000000);
                    h.icon.setImageDrawable(a.icon);
                    h.name.setText(a.label);
                    h.name.setVisibility(View.VISIBLE);
                    sizeIcon(h, tileH / 2);
                    h.banner.setVisibility(View.GONE);
                    h.fallback.setVisibility(View.VISIBLE);
                }
                h.card.setBackground(bg);
            }

            applyFocus(h, pos == grid.getSelectedItemPosition() && grid.hasFocus(), false);
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
            a.vivid = vividFor(a.banner != null ? a.banner : a.icon);
            into.put(a.pkg, a);
        }
    }

    /** The app's main colour (average of its colourful pixels), brightened, for Tinted glass. */
    static int vividFor(Drawable d) {
        if (d == null) return 0xFF6B7280;
        try {
            int n = 16;
            Bitmap bm = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            d.setBounds(0, 0, n, n);
            d.draw(c);
            float[] hsv = new float[3];
            double r = 0, g = 0, b = 0, w = 0, ar = 0, ag = 0, ab = 0, an = 0;
            for (int y = 0; y < n; y++)
                for (int x = 0; x < n; x++) {
                    int px = bm.getPixel(x, y);
                    if (Color.alpha(px) < 128) continue;
                    ar += Color.red(px); ag += Color.green(px); ab += Color.blue(px); an++;
                    Color.colorToHSV(px, hsv);
                    if (hsv[1] < 0.3f || hsv[2] < 0.25f) continue;   // skip greys, whites, blacks
                    r += Color.red(px) * hsv[1]; g += Color.green(px) * hsv[1]; b += Color.blue(px) * hsv[1];
                    w += hsv[1];
                }
            bm.recycle();
            int col;
            if (w > 0) col = Color.rgb((int) (r / w), (int) (g / w), (int) (b / w));
            else if (an > 0) col = Color.rgb((int) (ar / an), (int) (ag / an), (int) (ab / an));
            else return 0xFF6B7280;
            Color.colorToHSV(col, hsv);
            if (hsv[1] > 0.12f) hsv[1] = Math.max(hsv[1], 0.55f);
            hsv[2] = 0.82f;
            return Color.HSVToColor(hsv);
        } catch (Exception e) {
            return 0xFF6B7280;
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

    void launch(final App a) {
        if (isLocked(this, a.pkg)) {
            askPin("Unlock " + a.label, new Runnable() {
                @Override public void run() { unlockedPkg = a.pkg; doLaunch(a); }
            });
            return;
        }
        doLaunch(a);
    }

    void doLaunch(App a) {
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
        final String sp = settingsPkg();
        final Runnable go = new Runnable() {
            @Override public void run() {
                unlockedPkg = sp;
                start(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        };
        if (isLocked(this, sp)) askPin("Unlock Settings", go); else go.run();
    }

    /** Opens a settings screen from inside the (already PIN-checked) options. */
    void startSystem(Intent i) {
        unlockedPkg = settingsPkg();
        start(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    String settingsPkg() {
        try {
            ResolveInfo ri = getPackageManager().resolveActivity(new Intent(Settings.ACTION_SETTINGS), 0);
            if (ri != null && ri.activityInfo != null) return ri.activityInfo.packageName;
        } catch (Exception ignored) { }
        return "com.android.tv.settings";
    }

    // ---------------------------------------------------------------- power

    void power() {
        HomeOverride s = HomeOverride.instance;
        if (s != null && s.showPowerMenu()) return;
        dialog().setTitle("Power")
                .setMessage("Android only lets apps open the system power menu (Restart / Power off) "
                        + "through an accessibility service. Turn on \"Override Home button\" to use this tile.")
                .setPositiveButton("Set it up", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) { asAdmin(new Runnable() {
                        @Override public void run() { toggleOverride(); }
                    }); }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    // ---------------------------------------------------------------- PIN

    /** Locked app opened from elsewhere: the lock service sent us here. */
    boolean handleLockIntent(Intent i) {
        if (i == null) return false;
        final String pkg = i.getStringExtra(EXTRA_LOCKED);
        if (pkg == null) return false;
        i.removeExtra(EXTRA_LOCKED);
        if (pinDlg != null && pinDlg.isShowing()) return true;
        String label = pkg;
        try {
            PackageManager pm = getPackageManager();
            label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception ignored) { }
        askPin("Unlock " + label, new Runnable() {
            @Override public void run() {
                unlockedPkg = pkg;
                for (App a : apps) if (a.pkg.equals(pkg)) { doLaunch(a); return; }
                Intent l = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
                if (l == null) l = getPackageManager().getLaunchIntentForPackage(pkg);
                if (l != null) start(l.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        });
        return true;
    }

    void askPin(String title, final Runnable ok) {
        long wait = pinBlockedUntil - android.os.SystemClock.elapsedRealtime();
        if (wait > 0) {
            Toast.makeText(this, "Too many wrong PINs. Try again in " + (wait / 1000 + 1) + " s",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        pinDlg = PinPad.show(this, title, accent(), new PinPad.Done() {
            @Override public void onPin(String pin) {
                if (PinPad.check(prefs, pin)) {
                    wrongPins = 0;
                    ok.run();
                } else {
                    if (++wrongPins >= 5) {
                        wrongPins = 0;
                        pinBlockedUntil = android.os.SystemClock.elapsedRealtime() + 30_000;
                    }
                    Toast.makeText(Home.this, "Wrong PIN", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    /** Runs r if no PIN is set or it was already entered during this visit; otherwise asks first. */
    void asAdmin(final Runnable r) {
        if (!PinPad.hasPin(prefs) || android.os.SystemClock.elapsedRealtime() < adminUntil) { r.run(); return; }
        askPin("Enter PIN", new Runnable() {
            @Override public void run() { unlockAdmin(); r.run(); }
        });
    }

    void unlockAdmin() { adminUntil = android.os.SystemClock.elapsedRealtime() + 120_000; }

    void setUpPin(final Runnable then) {
        PinPad.show(this, "Choose a 4-digit PIN", accent(), new PinPad.Done() {
            @Override public void onPin(final String first) {
                PinPad.show(Home.this, "Enter it again", accent(), new PinPad.Done() {
                    @Override public void onPin(String second) {
                        if (!first.equals(second)) {
                            Toast.makeText(Home.this, "PINs didn't match", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        PinPad.savePin(prefs, first);
                        unlockAdmin();
                        Toast.makeText(Home.this, "PIN saved", Toast.LENGTH_SHORT).show();
                        if (then != null) then.run();
                    }
                });
            }
        });
    }

    void setLocked(String pkg, boolean lock) {
        Set<String> l = new HashSet<>(prefs.getStringSet("locked", Collections.<String>emptySet()));
        if (lock) l.add(pkg); else l.remove(pkg);
        prefs.edit().putStringSet("locked", l).apply();
        adapter.notifyDataSetChanged();
        if (lock && !overrideOn())
            Toast.makeText(this, "Locked in Lite Home. Turn on Override Home button to also "
                    + "lock it when opened from elsewhere.", Toast.LENGTH_LONG).show();
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
                            unlockedPkg = settingsPkg();
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
        adapter.notifyDataSetChanged();
        refreshFocus();
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
        asAdmin(new Runnable() {
            @Override public void run() {
                if (pos >= apps.size()) showHomeOptions(); else showAppOptions(pos);
            }
        });
    }

    void showHomeOptions() {
        final List<String> hiddenPkgs = new ArrayList<>(
                prefs.getStringSet("hidden", Collections.<String>emptySet()));
        final boolean pin = PinPad.hasPin(prefs);
        final String sp = settingsPkg();
        final List<String> items = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();
        items.add("Open Android settings");
        acts.add(new Runnable() { public void run() { startSystem(new Intent(Settings.ACTION_SETTINGS)); } });
        items.add("Background: " + paletteName(prefs.getInt("bg_palette", 0)));
        acts.add(new Runnable() { public void run() { pickBackground(); } });
        items.add("Motion: " + FlowBackground.SPEED_NAMES[prefs.getInt("bg_speed", 1)]);
        acts.add(new Runnable() { public void run() { pickSetting("Motion", FlowBackground.SPEED_NAMES, "bg_speed", 1); } });
        items.add("Brightness: " + FlowBackground.BRIGHT_NAMES[prefs.getInt("bg_bright", 1)]);
        acts.add(new Runnable() { public void run() { pickSetting("Brightness", FlowBackground.BRIGHT_NAMES, "bg_bright", 1); } });
        items.add("Highlight: " + ACCENT_NAMES[Math.min(prefs.getInt("accent", 0), ACCENT_NAMES.length - 1)]);
        acts.add(new Runnable() { public void run() { pickSetting("Highlight colour", ACCENT_NAMES, "accent", 0); } });
        items.add("Tile style: " + TILE_STYLES[Math.min(tileStyle(), TILE_STYLES.length - 1)]);
        acts.add(new Runnable() { public void run() { pickSetting("Tile style", TILE_STYLES, "tile_style", 0); } });
        items.add(pin ? "Change PIN" : "Set up PIN lock");
        acts.add(new Runnable() { public void run() { setUpPin(null); } });
        if (pin) {
            items.add("Lock Android settings: " + (isLocked(this, sp) ? "ON" : "OFF"));
            acts.add(new Runnable() { public void run() { setLocked(sp, !isLocked(Home.this, sp)); } });
            items.add("Remove PIN and all locks");
            acts.add(new Runnable() { public void run() {
                PinPad.clearPin(prefs);
                prefs.edit().remove("locked").apply();
                adapter.notifyDataSetChanged();
                Toast.makeText(Home.this, "PIN removed", Toast.LENGTH_SHORT).show();
            } });
        }
        items.add("Show hidden apps (" + hiddenPkgs.size() + ")");
        acts.add(new Runnable() { public void run() { showHidden(hiddenPkgs); } });
        items.add("Reset app order");
        acts.add(new Runnable() { public void run() { prefs.edit().remove("order").apply(); reload(); } });
        items.add("Override Home button: " + (overrideOn() ? "ON" : "OFF"));
        acts.add(new Runnable() { public void run() { toggleOverride(); } });

        dialog().setTitle("Lite Home").setItems(items.toArray(new String[0]),
                new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) { acts.get(which).run(); }
                }).show();
    }

    void showAppOptions(final int pos) {
        final App a = apps.get(pos);
        final boolean locked = isLocked(this, a.pkg);
        String[] items = {"Open", "Move", "Move to first", "Hide",
                locked ? "Unlock (no PIN needed)" : "Lock with PIN", "App info", "Uninstall"};
        dialog().setTitle(a.label).setItems(items, new android.content.DialogInterface.OnClickListener() { @Override public void onClick(android.content.DialogInterface d, int which) {
            switch (which) {
                case 0: unlockedPkg = a.pkg; doLaunch(a); break;
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
                    if (locked) setLocked(a.pkg, false);
                    else if (PinPad.hasPin(prefs)) setLocked(a.pkg, true);
                    else setUpPin(new Runnable() { public void run() { setLocked(a.pkg, true); } });
                    break;
                case 5:
                    startSystem(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", a.pkg, null)));
                    break;
                case 6:
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
