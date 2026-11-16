package com.techgenius.tvoslauncher;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

import java.util.Calendar;

/**
 * Réveil discret à midi et à 18 h, écran éteint : le boîtier réveille seulement
 * son processeur — la TV reste éteinte. Il donne de ses nouvelles à la page de la villa, relit le calendrier
 * (déconnexion des comptes si un départ est passé) et installe une mise à jour éventuelle, pour que rien de
 * tout ça n'arrive pendant que les clients regardent.
 * « Tester le réveil » (À propos) programme un réveil d'essai 2 min plus tard.
 * Au réveil, la mise à jour se fait même si l'accueil du launcher a été fermé pendant la veille.
 */
public class NightlyWake extends BroadcastReceiver {

    /** Vrai pendant un réveil de midi / 18 h. */
    static volatile boolean running;

    public static void schedule(Context ctx) {
        Context c = ctx.getApplicationContext();
        try {
            // Prochain créneau parmi 12 h et 18 h (aujourd'hui ou demain).
            Calendar next = null;
            for (int d = 0; d < 2 && next == null; d++) {
                for (int h : new int[]{12, 18}) {
                    Calendar k = Calendar.getInstance();
                    k.add(Calendar.DAY_OF_MONTH, d);
                    k.set(Calendar.HOUR_OF_DAY, h);
                    k.set(Calendar.MINUTE, 0);
                    k.set(Calendar.SECOND, 0);
                    k.set(Calendar.MILLISECOND, 0);
                    if (k.getTimeInMillis() > System.currentTimeMillis() + 60_000L) { next = k; break; }
                }
            }
            AlarmManager am = c.getSystemService(AlarmManager.class);
            PendingIntent pi = PendingIntent.getBroadcast(c, 7, new Intent(c, NightlyWake.class),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            am.cancel(pi);
            // Réveil « alarme » : à l'heure pile et sorti de la veille profonde. Sans le droit aux alarmes
            // exactes, réveil approximatif (Android peut alors le décaler de plusieurs heures).
            boolean exact = android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
            try {
                if (exact) am.setAlarmClock(new AlarmManager.AlarmClockInfo(next.getTimeInMillis(), null), pi);
            } catch (SecurityException e) {
                exact = false;
            }
            if (!exact) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.getTimeInMillis(), pi);
            prefs(c).edit().putLong("prevu", next.getTimeInMillis()).apply();
        } catch (Throwable ignored) { }
    }

    /** Alarmes exactes autorisées (réveil à l'heure pile, même en veille profonde). */
    static boolean exactAllowed(Context c) {
        try {
            return android.os.Build.VERSION.SDK_INT < 31 || c.getSystemService(AlarmManager.class).canScheduleExactAlarms();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Réveil d'essai dans 2 min (en plus de midi / 18 h), pour vérifier qu'une box en veille se réveille. */
    static long testSoon(Context ctx) {
        Context c = ctx.getApplicationContext();
        long at = System.currentTimeMillis() + 2 * 60_000L;
        try {
            AlarmManager am = c.getSystemService(AlarmManager.class);
            PendingIntent pi = PendingIntent.getBroadcast(c, 8, new Intent(c, NightlyWake.class),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            if (exactAllowed(c)) am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, null), pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (Throwable ignored) { }
        return at;
    }

    /** « 16/11 à 12h00 ». */
    static String when(long t) {
        return new java.text.SimpleDateFormat("dd/MM 'à' H'h'mm", java.util.Locale.FRANCE).format(new java.util.Date(t));
    }

    static android.content.SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("nightly_wake", Context.MODE_PRIVATE);
    }

    /** Dernier réveil (ms, 0 = jamais), réseau retrouvé ou non, prochain réveil prévu : envoyés à la page. */
    static long lastWake(Context c) { return prefs(c).getLong("dernier", 0L); }
    static boolean lastWakeOnline(Context c) { return prefs(c).getBoolean("reseau", false); }
    static long nextWake(Context c) { return prefs(c).getLong("prevu", 0L); }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        final Context c = ctx.getApplicationContext();
        schedule(c);
        prefs(c).edit().putLong("dernier", System.currentTimeMillis()).putBoolean("reseau", false).apply();
        PowerManager pm = c.getSystemService(PowerManager.class);
        final PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "villa:nightly");
        wl.acquire(8 * 60_000L); // le temps de rattraper le Wi‑Fi, de télécharger et d'installer une mise à jour
        // Boîtier en veille : on demande au Wi‑Fi de rester allumé pendant le réveil.
        android.net.wifi.WifiManager.WifiLock wifi = null;
        try {
            wifi = c.getSystemService(android.net.wifi.WifiManager.class)
                    .createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "villa:nightly");
            wifi.setReferenceCounted(false);
            wifi.acquire();
        } catch (Throwable ignored) { }
        final android.net.wifi.WifiManager.WifiLock wifiLock = wifi;
        new Thread(() -> {
            try {
                // Le réseau revient quelques secondes (parfois une à deux minutes) après le réveil.
                running = true;
                for (int i = 0; i < 90 && !online(c); i++) Thread.sleep(2000L);
                prefs(c).edit().putBoolean("reseau", online(c)).apply();
                VillaFolder.refreshBlocking(c, true);   // nouvelles à la page + mise à jour éventuelle (écran éteint)
                RentalReset.forceCheckSoon(c, null);     // départ passé : déconnexion des comptes
                Thread.sleep(2 * 60_000L);               // laisse finir le téléchargement / l'installation
                VillaFolder.reportOnly(c);               // résultat de la mise à jour transmis à la page
                Thread.sleep(2 * 60_000L);
            } catch (Throwable ignored) {
            } finally {
                running = false;
                try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Throwable ignored) { }
                try { if (wl.isHeld()) wl.release(); } catch (Throwable ignored) { }
            }
        }, "nightly-wake").start();
    }

    private static boolean online(Context c) {
        try {
            android.net.ConnectivityManager cm = c.getSystemService(android.net.ConnectivityManager.class);
            android.net.Network n = cm.getActiveNetwork();
            android.net.NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
            return nc != null && nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * Personne devant la TV : écran éteint, ou — pendant un réveil — boîtier resté allumé (TV coupée avec
     * sa propre télécommande) sans touche pressée depuis 3 h ni son en cours.
     */
    static boolean unattended(Context c) {
        if (!screenOn(c)) return true;
        if (!running || PowerService.instance == null) return false;
        if (System.currentTimeMillis() - PowerService.lastKeyWallMs < 3 * 3600_000L) return false;
        try {
            return !c.getSystemService(android.media.AudioManager.class).isMusicActive();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Écran allumé (quelqu'un regarde) : pas d'installation de mise à jour à ce moment-là. */
    static boolean screenOn(Context c) {
        try {
            return c.getSystemService(PowerManager.class).isInteractive();
        } catch (Throwable t) {
            return false;
        }
    }
}
