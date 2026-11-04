package io.lite.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.security.MessageDigest;
import java.security.SecureRandom;

/** Remote-friendly 4-digit PIN pad (D-pad keypad + the remote's number keys). */
final class PinPad {

    interface Done { void onPin(String pin); }

    static final int LEN = 4;

    // ---------------------------------------------------------------- storage (salted SHA-256)

    static boolean hasPin(SharedPreferences p) { return p.getString("pin_hash", null) != null; }

    static void savePin(SharedPreferences p, String pin) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        String s = Base64.encodeToString(salt, Base64.NO_WRAP);
        p.edit().putString("pin_salt", s).putString("pin_hash", hash(s, pin)).apply();
    }

    static void clearPin(SharedPreferences p) { p.edit().remove("pin_salt").remove("pin_hash").apply(); }

    static boolean check(SharedPreferences p, String pin) {
        String s = p.getString("pin_salt", ""), h = p.getString("pin_hash", null);
        return h != null && MessageDigest.isEqual(h.getBytes(), hash(s, pin).getBytes());
    }

    static String hash(String salt, String pin) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt.getBytes("UTF-8"));
            return Base64.encodeToString(md.digest(pin.getBytes("UTF-8")), Base64.NO_WRAP);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---------------------------------------------------------------- UI

    /** Shows the pad; calls back with the 4 digits entered. Back cancels. */
    static AlertDialog show(final Activity a, String title, final int accent, final Done done) {
        final float d = a.getResources().getDisplayMetrics().density;
        final StringBuilder entered = new StringBuilder();

        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding((int) (24 * d), (int) (20 * d), (int) (24 * d), (int) (20 * d));

        TextView t = new TextView(a);
        t.setText(title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        t.setGravity(Gravity.CENTER);
        root.addView(t);

        final TextView dots = new TextView(a);
        dots.setTextColor(Color.WHITE);
        dots.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        dots.setLetterSpacing(0.4f);
        dots.setGravity(Gravity.CENTER);
        dots.setPadding(0, (int) (10 * d), 0, (int) (14 * d));
        root.addView(dots);

        GridLayout keys = new GridLayout(a);
        keys.setColumnCount(3);
        root.addView(keys);

        final AlertDialog dlg = new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setView(root).create();

        final Runnable refresh = new Runnable() {
            @Override public void run() {
                StringBuilder s = new StringBuilder();
                for (int i = 0; i < LEN; i++) s.append(i < entered.length() ? '●' : '○');
                dots.setText(s);
            }
        };
        final Runnable submit = new Runnable() {
            @Override public void run() {
                if (entered.length() != LEN) return;
                String pin = entered.toString();
                dlg.dismiss();
                done.onPin(pin);
            }
        };
        refresh.run();

        String[] labels = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "OK"};
        Button first = null;
        for (final String l : labels) {
            Button b = new Button(a);
            b.setText(l);
            b.setTextColor(Color.WHITE);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            b.setAllCaps(false);
            b.setBackground(keyBg(d, accent));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = (int) (64 * d);
            lp.height = (int) (48 * d);
            lp.setMargins((int) (5 * d), (int) (5 * d), (int) (5 * d), (int) (5 * d));
            keys.addView(b, lp);
            if (first == null) first = b;
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (l.equals("OK")) { submit.run(); return; }
                    if (l.equals("⌫")) {
                        if (entered.length() > 0) entered.setLength(entered.length() - 1);
                    } else if (entered.length() < LEN) {
                        entered.append(l);
                    }
                    refresh.run();
                    if (entered.length() == LEN) submit.run();
                }
            });
        }

        // Number keys / delete on the remote work too.
        dlg.setOnKeyListener(new android.content.DialogInterface.OnKeyListener() {
            @Override public boolean onKey(android.content.DialogInterface di, int code, KeyEvent e) {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
                    if (entered.length() < LEN) entered.append((char) ('0' + code - KeyEvent.KEYCODE_0));
                    refresh.run();
                    if (entered.length() == LEN) submit.run();
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DEL && entered.length() > 0) {
                    entered.setLength(entered.length() - 1);
                    refresh.run();
                    return true;
                }
                return false;
            }
        });

        dlg.show();
        if (first != null) first.requestFocus();
        return dlg;
    }

    static StateListDrawable keyBg(float d, int accent) {
        StateListDrawable s = new StateListDrawable();
        GradientDrawable on = new GradientDrawable();
        on.setCornerRadius(10 * d);
        on.setColor(0x33FFFFFF);
        on.setStroke((int) (3 * d), accent);
        GradientDrawable off = new GradientDrawable();
        off.setCornerRadius(10 * d);
        off.setColor(0x22FFFFFF);
        s.addState(new int[]{android.R.attr.state_focused}, on);
        s.addState(new int[]{android.R.attr.state_pressed}, on);
        s.addState(new int[]{}, off);
        return s;
    }
}
