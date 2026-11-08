package com.techgenius.tvoslauncher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.widget.Toast;

/**
 * Verrouillage invités (actif par défaut une fois la configuration initiale terminée).
 *
 * Verrouillé : seules les touches de navigation et d'usage restent actives (flèches, OK, Retour,
 * Home, Power, Muet, Volume +/-). Toutes les autres touches sont ignorées, les réglages de la TV
 * renvoient à l'accueil, et les réglages du launcher et l'appui long sur les applis sont bloqués.
 *
 * Déverrouillage admin : 7 appuis rapides sur Retour depuis l'accueil du launcher. Tout est
 * ouvert pendant 10 minutes, ou jusqu'à la mise en veille.
 */
final class GuestLock {
    private static final String PREFS = "guest_lock";
    private static final String K_ENABLED = "enabled";
    private static final long UNLOCK_MS = 10 * 60_000L;
    private static final int PRESSES = 7;
    private static final long PRESS_GAP_MS = 800L;

    private static volatile long unlockedUntil = 0L;
    private static int pressCount = 0;
    private static long lastPressMs = 0L;
    private static long lastBounceMs = 0L;

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

    static boolean isEnabled(Context c) { return prefs(c).getBoolean(K_ENABLED, true); }

    static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean(K_ENABLED, on).apply();
        unlockedUntil = on ? System.currentTimeMillis() + UNLOCK_MS : 0L;
    }

    private static boolean setupDone(Context c) {
        return c.getApplicationContext().getSharedPreferences("setup", Context.MODE_PRIVATE)
                .getBoolean(SetupActivity.K_DONE, false);
    }

    static boolean isLocked(Context c) {
        return isEnabled(c) && setupDone(c) && System.currentTimeMillis() > unlockedUntil;
    }

    static void lock() { unlockedUntil = 0L; }

    static boolean isLockedPackage(String pkg) {
        if (pkg == null || pkg.startsWith("com.techgenius.")) return false;
        for (String p : LOCKED_PACKAGES) if (p.equals(pkg)) return true;
        // Panneaux de réglages des fabricants (ex. réglages rapides Sony).
        return pkg.toLowerCase(java.util.Locale.ROOT).contains("settings");
    }

    /** Touches toujours autorisées pour les invités. */
    private static boolean isAllowedKey(int k) {
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_POWER:
            case KeyEvent.KEYCODE_VOLUME_UP: case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                return true;
            default:
                return false;
        }
    }

    private static boolean onHome() {
        MainActivity m = MainActivity.activeInstance;
        return m != null && m.resumed;
    }

    /** Appelée pour chaque touche par le service d'accessibilité. Renvoie true si la touche est avalée. */
    static boolean onAnyKey(Context c, KeyEvent e) {
        if (!isLocked(c)) return false;
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_DOWN
                && e.getRepeatCount() == 0 && onHome()) {
            long now = SystemClock.elapsedRealtime();
            pressCount = (now - lastPressMs <= PRESS_GAP_MS) ? pressCount + 1 : 1;
            lastPressMs = now;
            if (pressCount >= PRESSES) {
                pressCount = 0;
                unlockedUntil = System.currentTimeMillis() + UNLOCK_MS;
                Toast.makeText(c, "Mode admin : tout est déverrouillé pendant 10 minutes",
                        Toast.LENGTH_LONG).show();
            }
        }
        return !isAllowedKey(code);
    }

    /** Réglages de la TV ouverts alors que c'est verrouillé : retour à l'accueil. */
    static void bounceHome(Context c) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastBounceMs < 1500L) return;
        lastBounceMs = now;
        try {
            c.startActivity(new Intent(c, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        } catch (Throwable ignored) {
        }
    }

    /** Lance onOk si déverrouillé ; sinon onCancel (peut être null). */
    static void require(Activity a, Runnable onOk, Runnable onCancel) {
        if (!isLocked(a)) {
            onOk.run();
        } else if (onCancel != null) {
            onCancel.run();
        }
    }
}
