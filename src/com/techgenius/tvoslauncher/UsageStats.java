package com.techgenius.tvoslauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * Temps passé et nombre d'ouvertures par appli, jour par jour (31 jours gardés). Aucune donnée sur les
 * clients : seulement « quelle appli, combien de temps ». Envoyé à la page de la villa avec l'état de la TV.
 */
final class UsageStats {
    private UsageStats() { }

    private static final String PREFS = "villa_usage";
    private static final long MAX_SEGMENT_MS = 4L * 60 * 60_000L; // un segment ne compte jamais plus de 4 h
    private static String curPkg;
    private static long curSince;
    private static boolean receiverOn;
    private static final Map<String, Boolean> countable = new HashMap<>();

    /** À appeler une fois (service d'accessibilité) : l'écran éteint arrête le compteur. */
    static synchronized void init(Context ctx) {
        if (receiverOn) return;
        receiverOn = true;
        try {
            ctx.getApplicationContext().registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) { stop(c); }
            }, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        } catch (Throwable ignored) { }
    }

    /** Une appli passe au premier plan. */
    static synchronized void onForeground(Context c, String pkg) {
        if (pkg == null || pkg.equals(curPkg)) return;
        long now = System.currentTimeMillis();
        flush(c, now);
        if (isCountable(c, pkg)) {
            curPkg = pkg;
            curSince = now;
            add(c, pkg, 0L, 1);
        } else {
            curPkg = null;
        }
    }

    static synchronized void stop(Context c) {
        flush(c, System.currentTimeMillis());
        curPkg = null;
    }

    private static void flush(Context c, long now) {
        if (curPkg == null) return;
        long ms = Math.min(Math.max(0L, now - curSince), MAX_SEGMENT_MS);
        if (ms >= 1000L) add(c, curPkg, ms / 1000L, 0);
        curSince = now;
    }

    /** Applis lançables par l'utilisateur (pas le launcher, le clavier, les écrans système). */
    private static boolean isCountable(Context c, String pkg) {
        Boolean b = countable.get(pkg);
        if (b != null) return b;
        boolean ok = false;
        try {
            if (!pkg.equals(c.getPackageName()) && !pkg.startsWith("com.android.") && !pkg.contains("inputmethod")
                    && !pkg.contains("launcher") && !pkg.contains("setupwraith") && !pkg.contains("tv.settings")) {
                PackageManager pm = c.getPackageManager();
                ok = pm.getLeanbackLaunchIntentForPackage(pkg) != null || pm.getLaunchIntentForPackage(pkg) != null;
            }
        } catch (Throwable ignored) { }
        countable.put(pkg, ok);
        return ok;
    }

    private static String today() { return new SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(new Date()); }

    private static void add(Context c, String pkg, long sec, int opens) {
        try {
            SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONObject all = new JSONObject(p.getString("d", "{}"));
            String d = today();
            JSONObject day = all.optJSONObject(d);
            if (day == null) day = new JSONObject();
            org.json.JSONArray v = day.optJSONArray(pkg);
            long s0 = v == null ? 0 : v.optLong(0), n0 = v == null ? 0 : v.optLong(1);
            day.put(pkg, new org.json.JSONArray().put(s0 + sec).put(n0 + opens));
            all.put(d, day);
            // On ne garde que 31 jours.
            String limit = new SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(new Date(System.currentTimeMillis() - 31L * 86_400_000L));
            for (Iterator<String> it = all.keys(); it.hasNext(); ) if (it.next().compareTo(limit) < 0) it.remove();
            p.edit().putString("d", all.toString()).apply();
        } catch (Throwable ignored) { }
    }

    /** Totaux sur 30 jours : {"pkg":[secondes, ouvertures], …} (compte aussi le segment en cours). */
    static synchronized String summary30(Context c) {
        flush(c, System.currentTimeMillis());
        try {
            JSONObject all = new JSONObject(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("d", "{}"));
            String limit = new SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(new Date(System.currentTimeMillis() - 30L * 86_400_000L));
            Map<String, long[]> tot = new HashMap<>();
            for (Iterator<String> it = all.keys(); it.hasNext(); ) {
                String d = it.next();
                if (d.compareTo(limit) < 0) continue;
                JSONObject day = all.getJSONObject(d);
                for (Iterator<String> ip = day.keys(); ip.hasNext(); ) {
                    String pkg = ip.next();
                    org.json.JSONArray v = day.getJSONArray(pkg);
                    long[] t = tot.get(pkg);
                    if (t == null) tot.put(pkg, t = new long[2]);
                    t[0] += v.optLong(0);
                    t[1] += v.optLong(1);
                }
            }
            JSONObject out = new JSONObject();
            for (Map.Entry<String, long[]> e : tot.entrySet()) {
                out.put(e.getKey(), new org.json.JSONArray().put(e.getValue()[0]).put(e.getValue()[1]));
            }
            return out.toString();
        } catch (Throwable t) {
            return "{}";
        }
    }
}
