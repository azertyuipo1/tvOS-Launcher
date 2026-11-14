package com.techgenius.tvoslauncher;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

import java.util.Calendar;

/**
 * Réveil discret toutes les 12 h (vers 3 h 30 et 15 h 30), écran éteint : le boîtier réveille seulement
 * son processeur — la TV reste éteinte. Il donne de ses nouvelles à la page de la villa, relit le calendrier
 * (déconnexion des comptes si un départ est passé) et installe une mise à jour éventuelle, pour que rien de
 * tout ça n'arrive pendant que les clients regardent.
 */
public class NightlyWake extends BroadcastReceiver {

    static void schedule(Context ctx) {
        Context c = ctx.getApplicationContext();
        try {
            Calendar next = Calendar.getInstance();
            next.set(Calendar.SECOND, 0);
            next.set(Calendar.MILLISECOND, 0);
            next.set(Calendar.MINUTE, 30);
            next.set(Calendar.HOUR_OF_DAY, 3);
            while (next.getTimeInMillis() <= System.currentTimeMillis() + 60_000L) next.add(Calendar.HOUR_OF_DAY, 12);
            AlarmManager am = c.getSystemService(AlarmManager.class);
            PendingIntent pi = PendingIntent.getBroadcast(c, 7, new Intent(c, NightlyWake.class),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            am.cancel(pi);
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.getTimeInMillis(), pi);
        } catch (Throwable ignored) { }
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        final Context c = ctx.getApplicationContext();
        schedule(c);
        PowerManager pm = c.getSystemService(PowerManager.class);
        final PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "villa:nightly");
        wl.acquire(5 * 60_000L); // le temps de télécharger et d'installer une mise à jour
        new Thread(() -> {
            try {
                // Le réseau revient quelques secondes après le réveil.
                for (int i = 0; i < 30 && !online(c); i++) Thread.sleep(2000L);
                VillaFolder.refreshBlocking(c, true);   // nouvelles à la page + mise à jour éventuelle (écran éteint)
                RentalReset.forceCheckSoon(c, null);     // départ passé : déconnexion des comptes
                Thread.sleep(4 * 60_000L);               // laisse finir le téléchargement / l'installation
            } catch (Throwable ignored) {
            } finally {
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

    /** Écran allumé (quelqu'un regarde) : pas d'installation de mise à jour à ce moment-là. */
    static boolean screenOn(Context c) {
        try {
            return c.getSystemService(PowerManager.class).isInteractive();
        } catch (Throwable t) {
            return false;
        }
    }
}
