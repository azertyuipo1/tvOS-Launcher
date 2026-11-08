package com.techgenius.tvoslauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Verrouillage invités : un code protège les réglages de la TV, les réglages du launcher et
 * les menus d'édition (déplacer, renommer, masquer, désinstaller). Une fois le code saisi,
 * tout reste déverrouillé 10 minutes, ou jusqu'à la mise en veille de la TV.
 */
final class GuestLock {
    private static final String PREFS = "guest_lock";
    private static final String K_HASH = "pin_hash";
    private static final long UNLOCK_MS = 10 * 60_000L;
    private static volatile long unlockedUntil = 0L;

    /** Applis système verrouillées (réglages Android TV / Google TV). */
    static final String[] LOCKED_PACKAGES = {
            "com.android.tv.settings",
            "com.google.android.tv.settings",
            "com.android.settings",
    };

    private GuestLock() { }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean hasPin(Context c) { return !prefs(c).getString(K_HASH, "").isEmpty(); }

    static boolean isLocked(Context c) {
        return hasPin(c) && System.currentTimeMillis() > unlockedUntil;
    }

    static void lock() { unlockedUntil = 0L; }

    static boolean isLockedPackage(String pkg) {
        for (String p : LOCKED_PACKAGES) if (p.equals(pkg)) return true;
        return false;
    }

    static void setPin(Context c, String pin) {
        prefs(c).edit().putString(K_HASH, pin == null || pin.isEmpty() ? "" : hash(pin)).apply();
    }

    private static boolean check(Context c, String pin) {
        return hasPin(c) && hash(pin).equals(prefs(c).getString(K_HASH, ""));
    }

    private static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(("tvos-guest-lock:" + s.trim()).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return s;
        }
    }

    /** Lance onOk directement si déverrouillé, sinon demande le code d'abord. */
    static void require(Activity a, Runnable onOk, Runnable onCancel) {
        if (!isLocked(a)) {
            onOk.run();
            return;
        }
        EditText in = new EditText(a);
        in.setSingleLine(true);
        in.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        in.setHint("Code");
        int pad = (int) (14 * a.getResources().getDisplayMetrics().density);
        FrameLayout box = new FrameLayout(a);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(in);
        final boolean[] ok = {false};
        AlertDialog dlg = new AlertDialog.Builder(a)
                .setTitle("Accès protégé")
                .setView(box)
                .setPositiveButton("Valider", (d, w) -> {
                    if (check(a, in.getText().toString())) {
                        ok[0] = true;
                        unlockedUntil = System.currentTimeMillis() + UNLOCK_MS;
                        onOk.run();
                    } else {
                        Toast.makeText(a, "Code incorrect", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Annuler", null)
                .create();
        dlg.setOnDismissListener(d -> { if (!ok[0] && onCancel != null) onCancel.run(); });
        dlg.show();
        Ui.setupDialogEditText(in, dlg);
        in.requestFocus();
    }

    /** Écran de code affiché par-dessus les réglages de la TV. */
    public static class PinActivity extends Activity {
        @Override
        protected void onCreate(Bundle b) {
            super.onCreate(b);
            require(this, this::finish, () -> {
                startActivity(new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
                finish();
            });
        }
    }
}
