package com.techgenius.tvoslauncher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.widget.Toast;

/**
 * Verrouillage invités : protège les réglages de la TV, les réglages du launcher et les menus
 * d'édition (déplacer, renommer, masquer, désinstaller). Pour déverrouiller, appuyer 7 fois de
 * suite sur la touche Réglages de la télécommande : tout reste ouvert 10 minutes, ou jusqu'à la
 * mise en veille de la TV.
 */
final class GuestLock {
    private static final String PREFS = "guest_lock";
    private static final String K_ENABLED = "enabled";
    private static final long UNLOCK_MS = 10 * 60_000L;
    private static final int PRESSES = 7;
    private static final long PRESS_GAP_MS = 3_000L;

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

    static boolean isEnabled(Context c) { return prefs(c).getBoolean(K_ENABLED, false); }

    /** À l'activation, on laisse 10 minutes ouvertes pour tester les 7 appuis avant que le verrou ne joue. */
    static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean(K_ENABLED, on).apply();
        unlockedUntil = on ? System.currentTimeMillis() + UNLOCK_MS : 0L;
    }

    static boolean isLocked(Context c) {
        return isEnabled(c) && System.currentTimeMillis() > unlockedUntil;
    }

    static void lock() { unlockedUntil = 0L; }

    static boolean isLockedPackage(String pkg) {
        if (pkg == null || pkg.startsWith("com.techgenius.")) return false;
        for (String p : LOCKED_PACKAGES) if (p.equals(pkg)) return true;
        // Panneaux de réglages des fabricants (ex. réglages rapides Sony).
        String l = pkg.toLowerCase(java.util.Locale.ROOT);
        return l.contains("settings");
    }

    /**
     * Touche Réglages (appelée par le service d'accessibilité). Renvoie true si la touche est
     * consommée. Au 7e appui rapproché : déverrouillage et ouverture des réglages de la TV.
     */
    private static int lastKeyCode = 0;

    /** Touches qui ne servent jamais au déverrouillage (navigation, volume, alimentation). */
    private static boolean isNavKey(int k) {
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_VOLUME_UP: case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE: case KeyEvent.KEYCODE_POWER:
                return true;
            default:
                return false;
        }
    }

    /**
     * Compte les appuis rapides sur une même touche (hors navigation). 7 appuis = déverrouillage.
     * Renvoie true si la touche doit être avalée (touche Réglages ou Menu pendant le verrou).
     */
    static boolean onAnyKey(Context c, KeyEvent e) {
        int code = e.getKeyCode();
        if (!isLocked(c) || isNavKey(code)) return false;
        boolean swallow = code == KeyEvent.KEYCODE_SETTINGS || code == KeyEvent.KEYCODE_MENU;
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            long now = SystemClock.elapsedRealtime();
            pressCount = (code == lastKeyCode && now - lastPressMs <= PRESS_GAP_MS) ? pressCount + 1 : 1;
            lastKeyCode = code;
            lastPressMs = now;
            if (pressCount >= PRESSES) {
                pressCount = 0;
                unlockedUntil = System.currentTimeMillis() + UNLOCK_MS;
                Toast.makeText(c, "Réglages déverrouillés pour 10 minutes", Toast.LENGTH_SHORT).show();
                try {
                    c.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Throwable ignored) {
                }
                return true;
            }
        }
        return swallow;
    }

    /** Réglages de la TV ouverts par un autre chemin alors que c'est verrouillé : retour à l'accueil. */
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
