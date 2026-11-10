package com.techgenius.tvoslauncher;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Dossier web de la villa : une seule adresse à saisir sur la TV. On y lit :
 *   <dossier>/background.jpg  l'arrière-plan de l'accueil
 *   <dossier>/config.txt      les réglages forcés, une ligne « clé: valeur » :
 *       nom_villa: Villa l'Enclos
 *       heure_checkout: 12
 *       calendrier: https://…   (JSON personnalisé ou lien ICS)
 * Une valeur vide ou absente dans config.txt laisse la main au réglage fait sur la TV.
 */
final class VillaFolder {
    static final String CONFIG_FILE = "config.txt";
    static final String BACKGROUND_FILE = "background.jpg";
    static final String K_NAME = "nom_villa";
    static final String K_CHECKOUT = "heure_checkout";
    static final String K_CALENDAR = "calendrier";
    static final String K_WIFI_SSID = "reseau_wifi";   // « Réseau WiFi: … »
    static final String K_WIFI_PASS = "mot_de_passe";  // « Mot de passe: … »
    static final String K_APPS = "applis";             // « applis: ITVSTREAM, Netflix, … » (ordre de l'accueil)
    static final String K_UPDATE = "mise_a_jour";      // « mise_a_jour: oui » : mise à jour seule des TV
    static final String K_WIFI_SHOW = "affichage_wifi"; // « affichage_wifi: 2 | tout | toujours »
    static final String K_TZ = "fuseau_horaire";
    static final String K_CLOCK = "affichage_heure";  // « affichage_heure: oui | non » (heure et date de l'accueil)        // « fuseau_horaire: America/La_Paz » (GMT-4 par défaut)

    /** Hébergement des dossiers saisis par un simple nom (non affiché à l'écran). */
    private static final String SHORT_HOST = new String(android.util.Base64.decode("aHR0cHM6Ly93d3cuc2JoLWhvc3QuZnIv", 0), StandardCharsets.UTF_8);
    private static final String P_RESOLVED = "folder_resolved";
    private static final String PREFS = "villa";
    private static final String P_BASE = "folder_url";
    private static final String P_CONFIG = "folder_config";
    private static final String P_STATUS = "folder_status";
    private static final String P_MIGRATED = "folder_migrated";

    private VillaFolder() { }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Mise à jour depuis une version sans dossier : on reprend le dossier de l'ancienne image
     * d'arrière-plan et l'ancien calendrier, pour que la TV continue de fonctionner sans rien saisir.
     */
    static void migrate(Context c) {
        SharedPreferences p = prefs(c);
        if (p.getBoolean(P_MIGRATED, false)) return;
        SharedPreferences.Editor e = p.edit().putBoolean(P_MIGRATED, true);
        if (p.contains("bg_status") && p.getString(P_BASE, "").isEmpty()) {
            String old = p.getString("bg_url", "");
            if (old.isEmpty()) old = SHORT_HOST + "clo/tv/" + BACKGROUND_FILE;
            if (old.endsWith("/" + BACKGROUND_FILE)) {
                String b = old.substring(0, old.length() - BACKGROUND_FILE.length() - 1);
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("^" + java.util.regex.Pattern.quote(SHORT_HOST) + "(?:villas/)?([a-z0-9_-]+)/tv$").matcher(b);
                e.putString(P_BASE, m.matches() ? m.group(1) : b);
            }
            RentalReset.keepLegacyCalendar(c);
        }
        e.apply();
    }

    static String getBase(Context c) {
        migrate(c);
        String b = prefs(c).getString(P_BASE, "");
        if (b.isEmpty()) {
            // Profil de 3 lettres (ex. « CLO ») : c'est aussi le nom du dossier, pas besoin de le ressaisir.
            String n = Profiles.current(c).name;
            if (n != null && n.trim().matches("[A-Za-z0-9]{3}")) {
                b = n.trim().toLowerCase(Locale.ROOT);
                prefs(c).edit().putString(P_BASE, b).apply();
            }
        }
        return b;
    }

    static boolean isConfigured(Context c) { return !getBase(c).isEmpty(); }

    /** Simple nom de dossier (ex. « clo ») plutôt qu'une adresse complète. */
    static boolean isShortName(String b) { return b.matches("[a-z0-9_-]+"); }

    /**
     * Saisie : soit un simple nom (« clo »), cherché dans villas/clo/tv puis clo/tv sur l'hébergement
     * par défaut, soit une adresse complète https://…
     */
    static void setBase(Context c, String url) {
        String u = url == null ? "" : url.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.matches("[A-Za-z0-9_-]+")) u = u.toLowerCase(Locale.ROOT);
        else if (!u.isEmpty() && !u.matches("(?i)^https?://.*")) u = "https://" + u;
        String old = prefs(c).getString(P_BASE, "");
        prefs(c).edit().putString(P_BASE, u).remove(P_CONFIG).remove(P_STATUS).remove(P_RESOLVED)
                .remove("bg_etag").remove("bg_lastmod").apply();
        if (!u.equals(old)) {
            // Nouvelle villa : rien ne doit rester de l'ancienne (clients, calendrier, photo, nom).
            RentalReset.forgetVilla(c);
            try { RemoteWallpaper.file(c).delete(); } catch (Throwable ignored) { }
            prefs(c).edit().remove("bg_status").apply();
            try {
                Profiles.Profile p = Profiles.current(c);
                p.name = isShortName(u) ? u.toUpperCase(Locale.ROOT) : "";
                Profiles.update(c, p);
            } catch (Throwable ignored) { }
        }
    }

    /** Dossiers possibles, dans l'ordre d'essai (le dernier trouvé en premier). */
    private static java.util.List<String> candidates(Context c) {
        java.util.List<String> l = new java.util.ArrayList<>();
        String b = getBase(c);
        if (b.isEmpty()) return l;
        if (!isShortName(b)) { l.add(b); return l; }
        String r = prefs(c).getString(P_RESOLVED, "");
        if (!r.isEmpty()) l.add(r);
        // « clo » → …/clo/tv ; autre code (ex. « ads ») → …/villas/tv/ads (variante …/villas/ads/tv acceptée).
        for (String x : new String[]{ SHORT_HOST + "villas/tv/" + b, SHORT_HOST + "villas/" + b + "/tv", SHORT_HOST + b + "/tv" }) {
            if (!l.contains(x)) l.add(x);
        }
        return l;
    }

    /** Adresse d'un fichier du dossier ; "" tant qu'un dossier saisi par son nom n'a pas été trouvé. */
    static String fileUrl(Context c, String name) {
        String b = getBase(c);
        if (b.isEmpty()) return "";
        if (isShortName(b)) b = prefs(c).getString(P_RESOLVED, "");
        return b.isEmpty() ? "" : b + "/" + name;
    }

    static String getStatus(Context c) {
        if (!isConfigured(c)) return "aucun dossier";
        return prefs(c).getString(P_STATUS, "pas encore lu");
    }

    /** Valeur forcée par config.txt (dernière lecture), ou "" si absente ou vide. */
    static String get(Context c, String key) {
        migrate(c);
        String v = parse(prefs(c).getString(P_CONFIG, "")).get(key);
        return v == null ? "" : v;
    }

    static boolean isForced(Context c, String key) { return !get(c, key).isEmpty(); }

    /** Fuseau de la villa (config.txt, sinon America/La_Paz = GMT-4). */
    static String timeZoneId(Context c) {
        String tz = get(c, K_TZ);
        return tz.isEmpty() ? "America/La_Paz" : tz;
    }

    /**
     * Le launcher calcule et affiche toujours l'heure dans le fuseau de la villa, même si la TV n'a pas
     * (encore) accepté le changement de fuseau.
     */
    static void applyProcessTimeZone(Context c) {
        try {
            java.util.TimeZone tz = java.util.TimeZone.getTimeZone(timeZoneId(c));
            if (!tz.getID().equals(java.util.TimeZone.getDefault().getID())) java.util.TimeZone.setDefault(tz);
        } catch (Throwable ignored) { }
    }

    /** Nom affiché : config.txt, sinon le nom saisi sur la TV (profil). */
    static String villaName(Context c) {
        String n = get(c, K_NAME);
        if (!n.isEmpty()) return n;
        // Nom repris d'une autre villa (ancien dossier) : on ne l'affiche pas, on affiche le code de la villa.
        String src = prefs(c).getString("name_src", "");
        if (!src.isEmpty() && !src.equals(getBase(c))) {
            String b = getBase(c);
            return isShortName(b) ? b.toUpperCase(Locale.ROOT) : "";
        }
        n = Profiles.current(c).name;
        return n == null ? "" : n;
    }

    /** « Réseau WiFi » → reseau_wifi, « Mot de passe » → mot_de_passe (accents, majuscules, espaces ignorés). */
    static String normKey(String k) {
        String s = java.text.Normalizer.normalize(k.trim(), java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        s = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        switch (s) {
            case "wifi": case "wifi_nom": case "nom_wifi": case "reseau": case "wifi_reseau": return K_WIFI_SSID;
            case "wifi_mdp": case "mdp": case "mot_de_passe_wifi": case "wifi_mot_de_passe": return K_WIFI_PASS;
            case "apps": case "applications": return K_APPS;
            case "mise_a_jour_auto": case "maj": return K_UPDATE;
            case "fuseau": case "timezone": return K_TZ;
            case "heure": case "affichage_date": case "heure_et_date": case "horloge": return K_CLOCK;
            case "wifi_affichage": case "qr_wifi": case "affichage_qr": case "qr_code_wifi": return K_WIFI_SHOW;
            default: return s;
        }
    }

    /**
     * Applis de l'accueil écrites une par ligne (« Netflix: oui », « YouTube: non »), dans l'ordre du fichier.
     * Renvoie les noms marqués oui ; liste vide si le fichier n'utilise pas ce format.
     */
    static java.util.List<String> appLines(String txt) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (txt == null) return out;
        java.util.Set<String> settings = new java.util.HashSet<>(java.util.Arrays.asList(
                K_NAME, K_CHECKOUT, K_CALENDAR, K_WIFI_SSID, K_WIFI_PASS, K_APPS, K_UPDATE, K_WIFI_SHOW, K_TZ, K_CLOCK));
        for (String line : txt.split("\\r?\\n")) {
            // Commentaires (#) ignorés ; tiret ou puce en début de ligne accepté (« - Netflix: oui »).
            String l = line.replace("\uFEFF", "").trim().replaceFirst("^[-\u2013\u2014\u2022*]+\\s*", "");
            if (l.isEmpty() || l.startsWith("#")) continue;
            int i = l.indexOf(':');
            if (i <= 0) continue;
            String name = l.substring(0, i).trim();
            String v = l.substring(i + 1).trim().toLowerCase(Locale.ROOT);
            if (settings.contains(normKey(name)) || !v.matches("oui|non|yes|no|1|0|on|off")) continue;
            if (v.matches("oui|yes|1|on")) out.add(name);
        }
        return out;
    }

    static java.util.List<String> appLines(Context c) {
        migrate(c);
        return appLines(prefs(c).getString(P_CONFIG, ""));
    }

    static Map<String, String> parse(String txt) {
        Map<String, String> m = new HashMap<>();
        if (txt == null) return m;
        for (String line : txt.split("\\r?\\n")) {
            // Commentaires (#) ignorés ; tiret ou puce en début de ligne accepté (« - Netflix: oui »).
            String l = line.replace("\uFEFF", "").trim().replaceFirst("^[-\u2013\u2014\u2022*]+\\s*", "");
            if (l.isEmpty() || l.startsWith("#")) continue;
            int i = l.indexOf(':');
            if (i <= 0) continue;
            String k = normKey(l.substring(0, i));
            String v = l.substring(i + 1).trim();
            if (K_CALENDAR.equals(k)) v = v.replaceAll("\\s+", "");
            m.put(k, v);
        }
        return m;
    }

    /** Relit config.txt (à appeler hors du fil principal). Sans réseau, la dernière lecture est gardée. */
    static void refreshBlocking(Context c) {
        java.util.List<String> cands = candidates(c);
        if (cands.isEmpty()) return;
        String when = new SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE).format(new Date());
        try {
            for (String base : cands) {
                Object[] r = get(base + "/" + CONFIG_FILE);
                int code = (Integer) r[0];
                if (code == 200) {
                    String txt = (String) r[1];
                    int n = 0;
                    for (String v : parse(txt).values()) if (!v.isEmpty()) n++;
                    Map<String, String> cfg = parse(txt);
                    String oldApps = get(c, K_APPS) + appLines(c);
                    prefs(c).edit().putString(P_CONFIG, txt).apply();
                    applyProcessTimeZone(c);
                    PowerService.applyVillaTimeZone(c);
                    String name = cfg.get(K_NAME);
                    if (name != null && !name.isEmpty()) syncProfileName(c, name);
                    String newApps = (cfg.get(K_APPS) == null ? "" : cfg.get(K_APPS)) + appLines(txt);
                    String upd = cfg.get(K_UPDATE) == null ? "" : cfg.get(K_UPDATE).toLowerCase(Locale.ROOT);
                    if (upd.matches("oui|yes|true|1|on")) AppUpdater.autoFromConfig();
                    if (!oldApps.equals(newApps)) {
                        // Liste ou ordre des applis changé : l'accueil se reconstruit.
                        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                            MainActivity m = MainActivity.activeInstance;
                            if (m != null) m.reload(null);
                        });
                    }
                    prefs(c).edit().putString(P_RESOLVED, base).putString(P_CONFIG, txt)
                            .putString(P_STATUS, "✓ lu le " + when + " (" + n + " réglage" + (n > 1 ? "s" : "") + " imposé" + (n > 1 ? "s" : "") + ")")
                            .apply();
                    return;
                }
                if (code == 404 && (Integer) get(base + "/" + BACKGROUND_FILE)[0] == 200) {
                    prefs(c).edit().putString(P_RESOLVED, base).putString(P_CONFIG, "")
                            .putString(P_STATUS, "config.txt absent — réglages de la TV utilisés (" + when + ")").apply();
                    return;
                }
                if (code != 404) {
                    prefs(c).edit().putString(P_STATUS, "✗ config.txt : erreur HTTP " + code + " — " + when).apply();
                    return;
                }
            }
            prefs(c).edit().putString(P_STATUS, "✗ dossier introuvable — " + when).apply();
        } catch (Throwable t) {
            prefs(c).edit().putString(P_STATUS, "✗ dossier injoignable (" + t.getClass().getSimpleName() + ") — " + when).apply();
        }
    }

    /** Le nom imposé par config.txt remplace le nom du profil saisi sur la TV (partout dans le launcher). */
    private static void syncProfileName(Context c, String name) {
        try {
            prefs(c).edit().putString("name_src", getBase(c)).apply();
            Profiles.Profile p = Profiles.current(c);
            if (name.equals(p.name)) return;
            p.name = name;
            Profiles.update(c, p);
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                MainActivity m = MainActivity.activeInstance;
                if (m != null) m.refreshProfileTitle();
            });
        } catch (Throwable ignored) { }
    }

    /** {code HTTP, texte} ; suit les redirections. */
    private static Object[] get(String url) throws Exception {
        HttpURLConnection conn = null;
        try {
            String target = url;
            int code = 0;
            for (int hop = 0; hop < 6; hop++) {
                conn = (HttpURLConnection) new URL(target).openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(15_000);
                conn.setUseCaches(false);
                code = conn.getResponseCode();
                if (code < 300 || code >= 400) break;
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                conn = null;
                if (loc == null || loc.isEmpty()) break;
                target = new URL(new URL(target), loc).toString();
            }
            if (conn == null || code != 200) return new Object[]{ code, "" };
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null && sb.length() < 64_000) sb.append(line).append('\n');
            }
            return new Object[]{ code, sb.toString() };
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
