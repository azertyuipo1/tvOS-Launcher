package com.techgenius.tvoslauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Mode location : déconnecte les applis de streaming une seule fois par départ client.
 *
 * Règle : au réveil de la TV, on cherche le départ le plus récent dont (date de départ + heure de
 * check-out) est passé. S'il est plus récent que le dernier départ traité, on lance `pm clear` sur
 * les applis via l'ADB local, puis on mémorise ce départ. Les allumages suivants ne refont rien.
 *
 * Le calendrier est soit un JSON {"data":[{"nom":"…","startDate":"yyyy-MM-dd","endDate":"yyyy-MM-dd"}]},
 * soit un lien ICS (converti au même format, sans nom). Son adresse vient de config.txt (dossier de
 * la villa) ou, à défaut, du réglage fait sur la TV.
 * Une copie est gardée pour fonctionner sans internet.
 */
final class RentalReset {
    private static final String TAG = "RentalReset";
    private static final String PREFS = "rental_reset";
    private static final String K_URL = "url";
    private static final String K_HOUR = "checkout_hour";
    private static final String K_CACHE = "cache_json";
    private static final String K_LAST_END = "last_end";
    private static final String K_LAST_RUN = "last_run_ms";
    private static final String K_LAST_RESULT = "last_result";
    private static final String K_LAST_CHECK = "last_check_ms";
    private static final String K_TEST_NEXT_WAKE = "test_next_wake";
    private static final String K_FETCH_STATUS = "fetch_status";

    static final String[] PACKAGES = {
            "com.netflix.ninja",                  // Netflix
            "com.amazon.amazonvideo.livingroom",  // Prime Video
            "com.disney.disneyplus",              // Disney+
            "com.wbd.stream",                     // HBO Max
            "com.hbo.hbonow",                     // HBO Max (ancien paquet)
            "com.hbo.go",                         // HBO (ancien paquet)
            "com.google.android.youtube.tv",      // YouTube (compte Google du client)
            "com.google.android.youtube.googletv",
    };

    private static final long MIN_INTERVAL_MS = 60_000L;
    private static volatile boolean running = false;

    private RentalReset() { }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Ancien calendrier intégré (villa CLO), conservé seulement pour les TV mises à jour. */
    private static final String LEGACY_URL = "https://creator.zoho.com/publishapi/v2/sbhassets/villa-clo/report/tv_calendar"
            + "?privatelink=h2uWZ05PA2EdDE7ROnGqtbHzN5jR8Rrv07JTGHAGjntp9OPqxzkBU4hArwWOXkbCAmZBwFB4pPBBRepggGR2SvZSBWCxX1SErAtY";

    /** Adresse du calendrier : config.txt si elle y est, sinon celle saisie sur la TV. */
    static String getUrl(Context c) {
        String f = VillaFolder.get(c, VillaFolder.K_CALENDAR);
        return f.isEmpty() ? getTvUrl(c) : f;
    }

    static String getTvUrl(Context c) { return prefs(c).getString(K_URL, ""); }

    static void keepLegacyCalendar(Context c) {
        if (getTvUrl(c).isEmpty()) prefs(c).edit().putString(K_URL, LEGACY_URL).putString("cal_src", LEGACY_URL).apply();
    }

    static void setUrl(Context c, String url) {
        prefs(c).edit().putString(K_URL, url == null ? "" : url.trim())
                .remove(K_CACHE).remove(K_LAST_END).apply();
    }

    static boolean isEnabled(Context c) { return !getUrl(c).isEmpty() || VillaFolder.isConfigured(c); }

    /** Heure de check-out : config.txt si elle y est (« 12 », « 12h », « 12:00 »), sinon celle de la TV. */
    static int getCheckoutHour(Context c) {
        String f = VillaFolder.get(c, VillaFolder.K_CHECKOUT);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d{1,2})").matcher(f);
        if (m.find()) {
            int h = Integer.parseInt(m.group(1));
            if (h >= 0 && h <= 23) return h;
        }
        return getTvCheckoutHour(c);
    }

    static int getTvCheckoutHour(Context c) { return prefs(c).getInt(K_HOUR, 12); }

    static void setCheckoutHour(Context c, int h) { prefs(c).edit().putInt(K_HOUR, h).apply(); }

    static String getLastEnd(Context c) { return prefs(c).getString(K_LAST_END, ""); }

    static String getLastResult(Context c) { return prefs(c).getString(K_LAST_RESULT, ""); }

    /** Simulation : le prochain allumage de la TV se comporte comme le lendemain d'un départ. */
    static void simulateDepartureOnNextWake(Context c) {
        prefs(c).edit().putBoolean(K_TEST_NEXT_WAKE, true).putLong(K_LAST_CHECK, 0L).apply();
    }

    static boolean isDepartureSimulated(Context c) { return prefs(c).getBoolean(K_TEST_NEXT_WAKE, false); }

    /** Appelée au réveil / au démarrage. Sans effet si le mode location n'est pas configuré. */
    static void check(Context c) { run(c, false, false, null); }

    /** Vérification immédiate (sans délai minimal), sans forcer la déconnexion. */
    static void forceCheckSoon(Context c, Runnable onDone) { run(c, false, true, onDone); }

    /** Déconnexion immédiate (bouton de test dans les réglages). */
    static void forceNow(Context c, Runnable onDone) { run(c, true, true, onDone); }

    private static void run(Context ctx, boolean force0, boolean now00, Runnable onDone) {
        final Context c = ctx.getApplicationContext();
        boolean simulated = !force0 && prefs(c).getBoolean(K_TEST_NEXT_WAKE, false);
        if (simulated) prefs(c).edit().putBoolean(K_TEST_NEXT_WAKE, false).apply();
        final boolean force = force0 || simulated;
        final boolean now0 = now00 || simulated;
        if ((!isEnabled(c) && !force) || running) {
            if (onDone != null) onDone.run();
            return;
        }
        long now = System.currentTimeMillis();
        if (!now0 && now - prefs(c).getLong(K_LAST_CHECK, 0L) < MIN_INTERVAL_MS) return;
        running = true;
        prefs(c).edit().putLong(K_LAST_CHECK, now).apply();
        new Thread(() -> {
            try {
                String due = latestDueDeparture(c);
                String last = getLastEnd(c);
                if (!force) {
                    if (due == null) return;
                    if (last.isEmpty()) {
                        // Première configuration : on prend le départ passé comme référence sans rien effacer.
                        prefs(c).edit().putString(K_LAST_END, due).apply();
                        return;
                    }
                    if (due.compareTo(last) <= 0) return;
                }
                String result = clearApps(c);
                SharedPreferences.Editor e = prefs(c).edit()
                        .putLong(K_LAST_RUN, System.currentTimeMillis())
                        .putString(K_LAST_RESULT, result);
                // On ne marque le départ comme traité que si l'ADB a répondu, pour réessayer sinon.
                if (!result.startsWith("ERREUR") && due != null && due.compareTo(last) > 0) {
                    e.putString(K_LAST_END, due);
                }
                e.apply();
            } catch (Throwable t) {
                Log.w(TAG, "check failed", t);
            } finally {
                running = false;
                if (onDone != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(onDone);
            }
        }, "rental-reset").start();
    }

    /** Séjour en cours : prénoms affichés, dates d'arrivée et de départ (yyyy-MM-dd). */
    static final class Stay { String name = "", start, end; }

    /**
     * Séjour en cours, ou null : arrivée <= aujourd'hui, et départ après aujourd'hui (ou aujourd'hui
     * avant l'heure de check-out). Le jour d'un changement, le client qui part reste jusqu'au check-out.
     * Nom : champ « welcome » préparé par un serveur, ou 1er champ de l'enregistrement.
     */
    static Stay currentStay(Context c) {
        String json = prefs(c).getString(K_CACHE, null);
        if (json == null) return null;
        try {
            JSONObject root = new JSONObject(json);
            JSONArray data = root.optJSONArray("data");
            Calendar now = Calendar.getInstance();
            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now.getTime());
            boolean beforeCheckout = now.get(Calendar.HOUR_OF_DAY) < getCheckoutHour(c);
            JSONObject best = null;
            Stay stay = null;
            for (int i = 0; data != null && i < data.length(); i++) {
                JSONObject r = data.optJSONObject(i);
                if (r == null) continue;
                String s = r.optString("startDate", ""), e = r.optString("endDate", "");
                if (s.length() < 10 || e.length() < 10 || "CANCELLED".equals(r.optString("status"))) continue;
                s = s.substring(0, 10);
                e = e.substring(0, 10);
                boolean current = s.compareTo(today) <= 0
                        && (e.compareTo(today) > 0 || (e.equals(today) && beforeCheckout));
                if (current && (stay == null || s.compareTo(stay.start) < 0)) {
                    best = r;
                    stay = new Stay();
                    stay.start = s;
                    stay.end = e;
                }
            }
            if (stay == null) return null;
            stay.name = best.optString("nom", "").trim();
            java.util.Iterator<String> keys = best.keys();
            if (stay.name.isEmpty() && !best.has("nom") && keys.hasNext()) {
                String k = keys.next();
                Object v = best.opt(k);
                if (v instanceof String && !isTechnicalKey(k)) stay.name = ((String) v).trim();
            }
            if (stay.name.isEmpty()) stay.name = root.optString("welcome", "").trim();
            return stay;
        } catch (Exception e) {
            return null;
        }
    }

    static String currentGuest(Context c) {
        Stay s = currentStay(c);
        return s == null ? "" : s.name;
    }

    /**
     * Salutation selon le moment du séjour : « Welcome, » le jour d'arrivée et le lendemain,
     * « Thank you for staying with us, » le jour du départ, sinon bonjour selon l'heure.
     */
    static String greeting(Context c, Stay s) {
        Calendar now = Calendar.getInstance();
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        String today = f.format(now.getTime());
        Calendar next = Calendar.getInstance();
        try { next.setTime(f.parse(s.start)); } catch (Exception ignored) { }
        next.add(Calendar.DAY_OF_MONTH, 1);
        if (today.equals(s.end)) return "Thank you for staying with us,";
        if (today.equals(s.start) || today.equals(f.format(next.getTime()))) return "Welcome,";
        int h = now.get(Calendar.HOUR_OF_DAY);
        if (h >= 5 && h < 12) return "Good morning,";
        if (h >= 12 && h < 18) return "Good afternoon,";
        return "Good evening,";
    }

    /** Jour d'arrivée ou lendemain (mêmes jours que « Welcome, ») : on affiche le Wi-Fi. */
    static boolean isWelcomeDays(Stay s) {
        if (s == null || s.start == null) return false;
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        String today = f.format(new java.util.Date());
        Calendar next = Calendar.getInstance();
        try { next.setTime(f.parse(s.start)); } catch (Exception e) { return false; }
        next.add(Calendar.DAY_OF_MONTH, 1);
        return today.equals(s.start) || today.equals(f.format(next.getTime()));
    }

    private static boolean isTechnicalKey(String k) {
        switch (k) {
            case "startDate": case "endDate": case "status": case "ID": case "villaId":
            case "agencyId": case "bedrooms": case "uid": return true;
            default: return false;
        }
    }

    static String getFetchStatus(Context c) { return prefs(c).getString(K_FETCH_STATUS, "pas encore lu"); }

    /** Relit le calendrier en arrière-plan (sans rien déconnecter), puis onDone sur le fil principal. */
    static void refreshCalendar(Context ctx, Runnable onDone) {
        final Context c = ctx.getApplicationContext();
        new Thread(() -> {
            latestDueDeparture(c);
            if (onDone != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(onDone);
        }, "rental-calendar").start();
    }

    /** Les n prochains départs (jj/mm/aaaa) d'après la dernière copie du calendrier. */
    static List<String> upcomingDepartures(Context c, int n) {
        List<String> ends = new ArrayList<>();
        String json = prefs(c).getString(K_CACHE, null);
        if (json == null) return ends;
        try {
            JSONArray data = new JSONObject(json).optJSONArray("data");
            if (data == null) return ends;
            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            for (int i = 0; i < data.length(); i++) {
                JSONObject r = data.optJSONObject(i);
                if (r == null) continue;
                String end = r.optString("endDate", "").trim();
                if (end.length() >= 10 && end.substring(0, 10).compareTo(today) >= 0) ends.add(end.substring(0, 10));
            }
        } catch (Exception ignored) {
        }
        java.util.Collections.sort(ends);
        List<String> out = new ArrayList<>();
        for (String e : ends) {
            if (out.size() >= n) break;
            String fr = e.substring(8, 10) + "/" + e.substring(5, 7) + "/" + e.substring(0, 4);
            if (!out.contains(fr)) out.add(fr);
        }
        return out;
    }

    /** Date (yyyy-MM-dd) du départ le plus récent dont l'heure de check-out est passée, ou null. */
    static String latestDueDeparture(Context c) {
        VillaFolder.refreshBlocking(c);
        String url = getUrl(c);
        if (!url.equals(prefs(c).getString("cal_src", ""))) {
            // Nouveau calendrier : on repart d'une référence neuve (le prochain départ passé ne déclenche rien).
            prefs(c).edit().putString("cal_src", url).remove(K_CACHE).remove(K_LAST_END).apply();
        }
        if (url.isEmpty()) {
            prefs(c).edit().putString(K_FETCH_STATUS, "aucun calendrier").apply();
            return null;
        }
        String json = fetch(url);
        String when = new SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE).format(new Date());
        if (json != null) {
            int n = 0;
            try { JSONArray d = new JSONObject(json).optJSONArray("data"); n = d == null ? 0 : d.length(); } catch (Exception ignored) { }
            prefs(c).edit().putString(K_CACHE, json)
                    .putString(K_FETCH_STATUS, "✓ lu le " + when + " (" + n + " réservations)").apply();
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                MainActivity m = MainActivity.activeInstance;
                if (m != null) m.refreshProfileTitle();
            });
        } else {
            prefs(c).edit().putString(K_FETCH_STATUS, "✗ " + lastFetchError + " — " + when).apply();
            json = prefs(c).getString(K_CACHE, null);
        }
        if (json == null) return null;
        try {
            JSONArray data = new JSONObject(json).optJSONArray("data");
            if (data == null) return null;
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            fmt.setLenient(false);
            int hour = getCheckoutHour(c);
            long now = System.currentTimeMillis();
            String best = null;
            for (int i = 0; i < data.length(); i++) {
                JSONObject r = data.optJSONObject(i);
                if (r == null) continue;
                String end = r.optString("endDate", "").trim();
                if (end.length() < 10) continue;
                end = end.substring(0, 10);
                Date d;
                try { d = fmt.parse(end); } catch (Exception ex) { continue; }
                Calendar cal = Calendar.getInstance();
                cal.setTime(d);
                cal.set(Calendar.HOUR_OF_DAY, hour);
                if (cal.getTimeInMillis() > now) continue;
                if (best == null || end.compareTo(best) > 0) best = end;
            }
            return best;
        } catch (Exception e) {
            Log.w(TAG, "bad json", e);
            return null;
        }
    }

    private static volatile String lastFetchError = "";

    private static String fetch(String url) {
        HttpURLConnection conn = null;
        try {
            // Suit les redirections nous-mêmes (Android ne suit pas https -> http, ni certains 301).
            String target = url;
            int code = 0;
            for (int hop = 0; hop < 6; hop++) {
                conn = (HttpURLConnection) new URL(target).openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(15_000);
                code = conn.getResponseCode();
                if (code < 300 || code >= 400) break;
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                conn = null;
                if (loc == null || loc.isEmpty()) break;
                target = new URL(new URL(target), loc).toString();
            }
            if (conn == null || code != 200) { lastFetchError = "erreur HTTP " + code; return null; }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append('\n');
            }
            String s = sb.toString();
            if (s.trim().startsWith("BEGIN:VCALENDAR")) return icsToJson(s);
            new JSONObject(s); // valide avant de remplacer le cache
            return s;
        } catch (org.json.JSONException j) {
            lastFetchError = "réponse non JSON";
            return null;
        } catch (Throwable t) {
            lastFetchError = "injoignable (" + t.getClass().getSimpleName() + ")";
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Convertit un calendrier ICS en {"data":[{"startDate","endDate"}]} (DTSTART = arrivée, DTEND = départ). */
    static String icsToJson(String ics) throws org.json.JSONException {
        String unfolded = ics.replace("\r\n", "\n").replace("\r", "\n").replaceAll("\n[ \t]", "")
                // Flux sans retours à la ligne (certains exports) : on recoupe avant chaque propriété utile.
                .replaceAll("[ \t]+(?=(BEGIN|END|DTSTART|DTEND|STATUS|SUMMARY|UID|DESCRIPTION)[;:])", "\n");
        JSONArray data = new JSONArray();
        String start = null, end = null, status = "", summary = "";
        boolean in = false;
        for (String line : unfolded.split("\n")) {
            String l = line.trim();
            if (l.equals("BEGIN:VEVENT")) { in = true; start = end = null; status = ""; summary = ""; continue; }
            if (l.equals("END:VEVENT")) {
                in = false;
                // Les options (non confirmées) et les séjours annulés ne comptent pas.
                if (start != null && !status.equalsIgnoreCase("CANCELLED")
                        && !summary.toLowerCase(Locale.ROOT).startsWith("option")) {
                    if (end == null) end = start;
                    JSONObject o = new JSONObject();
                    o.put("startDate", start);
                    o.put("endDate", end);
                    data.put(o);
                }
                continue;
            }
            if (!in) continue;
            int colon = l.indexOf(':');
            if (colon < 0) continue;
            String name = l.substring(0, colon).split(";")[0].toUpperCase(Locale.ROOT);
            String val = l.substring(colon + 1).trim();
            if (name.equals("DTSTART")) start = icsDate(val);
            else if (name.equals("DTEND")) end = icsDate(val);
            else if (name.equals("STATUS")) status = val;
            else if (name.equals("SUMMARY")) summary = val;
        }
        JSONObject root = new JSONObject();
        root.put("data", data);
        return root.toString();
    }

    private static String icsDate(String v) {
        String d = v.replaceAll("[^0-9]", "");
        if (d.length() < 8) return null;
        return d.substring(0, 4) + "-" + d.substring(4, 6) + "-" + d.substring(6, 8);
    }

    /** Paquets jamais déconnectés, quoi qu'il arrive (ITVSTREAM / Next). */
    private static final String[] NEVER_CLEAR = { "com.itvstream" };

    /** Port ADB fermé : on tente de réactiver le débogage (droit accordé à l'installation), puis on réessaie. */
    private static String adbClear(Context c, String pkg) throws Exception {
        try {
            return LocalAdb.execShellOutput(c, "pm clear " + pkg);
        } catch (Exception e) {
            if (!"PORT_CLOSED".equals(e.getMessage()) || !LocalAdb.hasWriteSecureSettings(c)) throw e;
            android.provider.Settings.Global.putInt(c.getContentResolver(),
                    android.provider.Settings.Global.ADB_ENABLED, 1);
            Thread.sleep(2500L);
            return LocalAdb.execShellOutput(c, "pm clear " + pkg);
        }
    }

    private static String clearApps(Context c) {
        PackageManager pm = c.getPackageManager();
        List<String> done = new ArrayList<>();
        outer:
        for (String pkg : PACKAGES) {
            for (String keep : NEVER_CLEAR) if (keep.equals(pkg)) continue outer;
            try { pm.getPackageInfo(pkg, 0); } catch (Exception notInstalled) { continue; }
            try {
                String out = adbClear(c, pkg);
                if (out != null && out.contains("Success")) done.add(pkg);
            } catch (Exception e) {
                return "ERREUR ADB (" + e.getMessage() + ")";
            }
        }
        String when = new SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE).format(new Date());
        return when + " — " + done.size() + " appli(s) déconnectée(s)";
    }
}
