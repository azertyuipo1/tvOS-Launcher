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

    /** Début de la période « TV allumée » en cours (0 = écran éteint). */
    private static long screenSince;
    private static final long MAX_SCREEN_MS = 6L * 60 * 60_000L;
    private static final String P_HOURS = "h";
    private static final String P_SENT = "h_sent";

    /** À appeler une fois (service d'accessibilité) : l'écran éteint arrête les compteurs. */
    static synchronized void init(Context ctx) {
        if (receiverOn) return;
        receiverOn = true;
        try {
            android.os.PowerManager pm = (android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm != null && pm.isInteractive()) screenSince = System.currentTimeMillis();
        } catch (Throwable ignored) { }
        try {
            IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
            f.addAction(Intent.ACTION_SCREEN_ON);
            ctx.getApplicationContext().registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) screenOn(c);
                    else stop(c);
                }
            }, f);
        } catch (Throwable ignored) { }
    }

    private static synchronized void screenOn(Context c) {
        if (screenSince == 0L) screenSince = System.currentTimeMillis();
    }

    /** Ajoute le temps « TV allumée » écoulé depuis la dernière mesure. */
    private static void flushScreen(Context c, long now) {
        if (screenSince == 0L) return;
        long from = Math.max(screenSince, now - MAX_SCREEN_MS);
        if (now - from >= 1000L) {
            add(c, "_tv", (now - from) / 1000L, 0);
            addHours(c, "_tv", from, now);
        }
        screenSince = now;
    }

    private static String hourKey(long t) { return new SimpleDateFormat("yyyyMMddHH", Locale.ROOT).format(new Date(t)); }

    /**
     * Temps heure par heure (heure locale de la villa), pour retrouver le temps passé pendant chaque séjour :
     * {"yyyyMMddHH": {"_tv": secondes TV allumée, "pkg": secondes}, …} ; 62 jours gardés.
     */
    private static void addHours(Context c, String key, long from, long to) {
        try {
            SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONObject all = new JSONObject(p.getString(P_HOURS, "{}"));
            java.util.Calendar cal = java.util.Calendar.getInstance();
            long t = from;
            while (t < to) {
                cal.setTimeInMillis(t);
                cal.set(java.util.Calendar.MINUTE, 0);
                cal.set(java.util.Calendar.SECOND, 0);
                cal.set(java.util.Calendar.MILLISECOND, 0);
                cal.add(java.util.Calendar.HOUR_OF_DAY, 1);
                long end = Math.min(to, cal.getTimeInMillis());
                long sec = (end - t) / 1000L;
                if (sec > 0) {
                    String h = hourKey(t);
                    JSONObject b = all.optJSONObject(h);
                    if (b == null) b = new JSONObject();
                    b.put(key, Math.min(3600L, b.optLong(key) + sec));
                    all.put(h, b);
                }
                t = end;
            }
            String limit = hourKey(System.currentTimeMillis() - 62L * 86_400_000L);
            for (Iterator<String> it = all.keys(); it.hasNext(); ) if (it.next().compareTo(limit) < 0) it.remove();
            p.edit().putString(P_HOURS, all.toString()).apply();
        } catch (Throwable ignored) { }
    }

    /** Heures à envoyer : depuis le dernier envoi réussi (moins 2 h de marge), 62 jours au plus. */
    static synchronized String hoursToSend(Context c) {
        long now = System.currentTimeMillis();
        flush(c, now);
        flushScreen(c, now);
        try {
            SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONObject all = new JSONObject(p.getString(P_HOURS, "{}"));
            long sent = p.getLong(P_SENT, 0L);
            if (sent == 0L) return all.toString();
            String from = hourKey(sent - 2L * 60 * 60_000L);
            JSONObject out = new JSONObject();
            for (Iterator<String> it = all.keys(); it.hasNext(); ) {
                String k = it.next();
                if (k.compareTo(from) >= 0) out.put(k, all.get(k));
            }
            return out.toString();
        } catch (Throwable t) {
            return "{}";
        }
    }

    /** La page de la villa a bien reçu les heures. */
    static void hoursSent(Context c, long at) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(P_SENT, at).apply();
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
        long now = System.currentTimeMillis();
        flush(c, now);
        flushScreen(c, now);
        screenSince = 0L;
        curPkg = null;
    }

    private static void flush(Context c, long now) {
        if (curPkg == null) return;
        long ms = Math.min(Math.max(0L, now - curSince), MAX_SEGMENT_MS);
        if (ms >= 1000L) {
            add(c, curPkg, ms / 1000L, 0);
            addHours(c, curPkg, now - ms, now);
        }
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
                // Chaînes et entrées HDMI de la TV (quelle que soit la marque) : ce ne sont pas des applis.
                if (ok && !pm.queryIntentServices(new Intent("android.media.tv.TvInputService").setPackage(pkg), 0).isEmpty()) ok = false;
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
            // Totaux depuis l'installation (au premier passage, repris des jours déjà comptés).
            JSONObject tot = p.contains("t") ? new JSONObject(p.getString("t", "{}")) : seedTotals(all, pkg, sec, opens);
            org.json.JSONArray tv = tot.optJSONArray(pkg);
            if (p.contains("t")) {
                tot.put(pkg, new org.json.JSONArray().put((tv == null ? 0 : tv.optLong(0)) + sec).put((tv == null ? 0 : tv.optLong(1)) + opens));
            }
            // On ne garde que 31 jours.
            String limit = new SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(new Date(System.currentTimeMillis() - 31L * 86_400_000L));
            for (Iterator<String> it = all.keys(); it.hasNext(); ) if (it.next().compareTo(limit) < 0) it.remove();
            p.edit().putString("d", all.toString()).putString("t", tot.toString()).apply();
        } catch (Throwable ignored) { }
    }

    /** Premier calcul des totaux : somme des jours gardés (qui incluent déjà l'ajout en cours). */
    private static JSONObject seedTotals(JSONObject all, String pkg, long sec, int opens) throws org.json.JSONException {
        JSONObject tot = new JSONObject();
        for (Iterator<String> it = all.keys(); it.hasNext(); ) {
            JSONObject day = all.getJSONObject(it.next());
            for (Iterator<String> ip = day.keys(); ip.hasNext(); ) {
                String k = ip.next();
                org.json.JSONArray v = day.getJSONArray(k), t = tot.optJSONArray(k);
                tot.put(k, new org.json.JSONArray().put((t == null ? 0 : t.optLong(0)) + v.optLong(0)).put((t == null ? 0 : t.optLong(1)) + v.optLong(1)));
            }
        }
        return tot;
    }

    /** Totaux depuis l'installation : {"pkg":[secondes, ouvertures], …}. */
    static synchronized String summaryAll(Context c) {
        flush(c, System.currentTimeMillis());
        flushScreen(c, System.currentTimeMillis());
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("t", "{}");
    }

    /** Totaux sur 30 jours : {"pkg":[secondes, ouvertures], …} (compte aussi le segment en cours). */
    static synchronized String summary30(Context c) {
        flush(c, System.currentTimeMillis());
        flushScreen(c, System.currentTimeMillis());
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
