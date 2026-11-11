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
    static final String K_INTRO = "effet_allumage";  // « effet_allumage: oui | non » (apparition de l'accueil à l'allumage)
    static final String K_INTRO_HOME = "effet_accueil"; // « effet_accueil: oui | non » (apparition à chaque retour sur l'accueil)
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

    /** Fichier d'association déposé par reglages.php (« Associer une TV ») pour le code affiché sur la TV. */

    // ---- Discrétion réseau : peu de requêtes, jamais d'insistance quand le serveur ne répond pas.
    static volatile String userAgent = "VillaTV-Launcher";
    private static final long MIN_GAP_MS = 15 * 60_000L;          // au plus une relecture auto par quart d'heure
    private static final long BACKOFF_MS = 30 * 60_000L;          // serveur muet : 30 min, 1 h, puis 2 h
    private static final long BACKOFF_MAX_MS = 2 * 60 * 60_000L;

    static void initUserAgent(Context c) {
        try { userAgent = "VillaTV-Launcher/" + AppUpdater.getCurrentVersion(c); } catch (Throwable ignored) { }
    }

    /** Le serveur n'a pas répondu récemment : on attend avant de le recontacter automatiquement. */
    static boolean serverPaused(Context c) {
        return prefs(c).getInt("net_fails", 0) > 0 && System.currentTimeMillis() < prefs(c).getLong("net_next", 0L);
    }

    /** Note un échec de connexion ; renvoie le délai avant le prochain essai automatique. */
    static long noteFailure(Context c) {
        int fails = Math.min(prefs(c).getInt("net_fails", 0) + 1, 10);
        long wait = Math.min(BACKOFF_MS << Math.min(fails - 1, 4), BACKOFF_MAX_MS);
        prefs(c).edit().putInt("net_fails", fails).putLong("net_next", System.currentTimeMillis() + wait).apply();
        return wait;
    }

    static void noteSuccess(Context c) {
        prefs(c).edit().putInt("net_fails", 0).putLong("net_next", System.currentTimeMillis() + MIN_GAP_MS).apply();
    }

    /** Dossier trouvé lors de la vérification du code : inutile de le rechercher ensuite. */
    static void setResolved(Context c, String base) {
        if (base != null && !base.isEmpty()) prefs(c).edit().putString(P_RESOLVED, base).apply();
    }

    /** Simple nom de dossier (ex. « clo ») plutôt qu'une adresse complète. */
    static boolean isShortName(String b) { return b.matches("[a-z0-9_-]+"); }

    /** Saisie d'un nom court (« clo », « ADS »), quelle que soit la casse. */
    static boolean isShortInput(String in) {
        String u = in == null ? "" : in.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u.matches("[A-Za-z0-9_-]+");
    }

    /**
     * Saisie : soit un simple nom (« clo »), lu dans villas-tv/clo sur l'hébergement
     * par défaut, soit une adresse complète https://…
     */
    static void setBase(Context c, String url) {
        String u = url == null ? "" : url.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.matches("[A-Za-z0-9_-]+")) u = u.toLowerCase(Locale.ROOT);
        else if (!u.isEmpty() && !u.matches("(?i)^https?://.*")) u = "https://" + u;
        String old = prefs(c).getString(P_BASE, "");
        if (u.equals(old) && !u.isEmpty()) {
            // Même villa ressaisie : on garde le dossier trouvé et les derniers réglages lus.
            prefs(c).edit().remove("net_fails").remove("net_next").remove("bg_next").apply();
            return;
        }
        prefs(c).edit().putString(P_BASE, u).remove(P_CONFIG).remove(P_STATUS).remove(P_RESOLVED)
                .remove("bg_etag").remove("bg_lastmod").remove("net_fails").remove("net_next").remove("bg_next").apply();
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

    /** Emplacement unique des villas saisies par leur nom court : …/villas-tv/<nom>. */
    static String shortUrl(String name) { return SHORT_HOST + "villas-tv/" + name; }

    /** Dossiers possibles, dans l'ordre d'essai (le dernier trouvé en premier). */
    private static java.util.List<String> candidates(Context c) {
        java.util.List<String> l = new java.util.ArrayList<>();
        String b = getBase(c);
        if (b.isEmpty()) return l;
        if (!isShortName(b)) { l.add(b); return l; }
        // Nom court : un seul emplacement, aucune recherche.
        l.add(shortUrl(b));
        return l;
    }

    /** Dossiers possibles pour une saisie (code « clo » / « ads » ou adresse complète), sans rien enregistrer. */
    static java.util.List<String> candidatesFor(String input) {
        java.util.List<String> l = new java.util.ArrayList<>();
        String u = input == null ? "" : input.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.isEmpty()) return l;
        if (u.matches("[A-Za-z0-9_-]+")) {
            l.add(shortUrl(u.toLowerCase(Locale.ROOT)));
        } else {
            l.add(u.matches("(?i)^https?://.*") ? u : "https://" + u);
        }
        return l;
    }

    /** Adresse d'un fichier du dossier ; "" tant qu'un dossier saisi par son nom n'a pas été trouvé. */
    static String fileUrl(Context c, String name) {
        String b = getBase(c);
        if (b.isEmpty()) return "";
        if (isShortName(b)) b = shortUrl(b);
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
                K_NAME, K_CHECKOUT, K_CALENDAR, K_WIFI_SSID, K_WIFI_PASS, K_APPS, K_UPDATE, K_WIFI_SHOW, K_TZ, K_CLOCK, K_INTRO, K_INTRO_HOME));
        for (String line : txt.split("\\r?\\n")) {
            // Commentaires (#) ignorés ; tiret ou puce en début de ligne accepté (« - Netflix: oui »).
            String l = line.replace("\uFEFF", "").trim().replaceFirst("^[-\u2013\u2014\u2022*]+\\s*", "");
            if (l.isEmpty() || l.startsWith("#")) continue;
            int i = l.indexOf(':');
            if (i <= 0) continue;
            String name = l.substring(0, i).trim();
            String v = l.substring(i + 1).trim().toLowerCase(Locale.ROOT);
            if (settings.contains(normKey(name)) || !v.matches("oui|non|yes|no|1|0|on|off")) continue;
            // Un réglage inconnu (« mot_clé: oui ») n'est jamais une appli : les noms d'applis n'ont pas de « _ ».
            if (name.matches("[a-z]+(_[a-z]+)+")) continue;
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
    static void refreshBlocking(Context c) { refreshBlocking(c, false); }

    /** manual : demandé par quelqu'un (bouton, saisie) ; sinon lecture automatique, espacée et patiente. */
    static void refreshBlocking(Context c, boolean manual) {
        java.util.List<String> cands = candidates(c);
        if (cands.isEmpty()) return;
        initUserAgent(c);
        if (!manual && System.currentTimeMillis() < prefs(c).getLong("net_next", 0L)) return;
        String when = new SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE).format(new Date());
        Throwable failure = null;
        try {
            for (String base : cands) {
                Object[] r;
                try {
                    // D'abord par la page de la villa : elle renvoie config.txt et note l'état de la TV.
                    try {
                        r = postConfig(c, base);
                    } catch (Throwable pt) {
                        r = null; // la lecture par la page a échoué : on lit config.txt directement
                    }
                    if (r == null) r = manual ? getRetry(base + "/" + CONFIG_FILE) : get(base + "/" + CONFIG_FILE);
                } catch (Throwable t) {
                    failure = t; // ce dossier ne répond pas : on essaie le suivant
                    continue;
                }
                int code = (Integer) r[0];
                noteSuccess(c); // le serveur a répondu
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
            if (failure != null) throw failure;
            prefs(c).edit().putString(P_STATUS, "✗ dossier introuvable — " + when).apply();
        } catch (Throwable t) {
            // Les derniers réglages lus restent appliqués.
            boolean kept = !prefs(c).getString(P_CONFIG, "").isEmpty();
            long wait = noteFailure(c) / 60_000L;
            prefs(c).edit().putString(P_STATUS, "✗ serveur injoignable (" + t.getClass().getSimpleName() + ") — " + when
                    + (kept ? " · réglages précédents gardés" : "")
                    + " · prochain essai dans " + (wait >= 60 ? (wait / 60) + " h" : wait + " min")).apply();
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

    /** Identifiant court et stable de la TV (pour la distinguer des autres TV de la villa). */
    static String deviceId(Context c) {
        try {
            String a = android.provider.Settings.Secure.getString(c.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(((a == null ? "" : a) + "|villa-tv").getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 5; i++) sb.append(String.format(Locale.ROOT, "%02x", h[i]));
            return sb.toString();
        } catch (Throwable t) {
            return "inconnu";
        }
    }

    /**
     * Lit config.txt par la page de la villa (index.php, action=config) en lui envoyant l'état de la TV :
     * modèle, version, installation, applis manquantes, temps passé par appli. Renvoie null si la page ne
     * le gère pas (ancienne page, autre hébergement) : on lit alors config.txt directement.
     */
    private static Object[] postConfig(Context c, String base) throws Exception {
        StringBuilder body = new StringBuilder("action=config");
        java.util.List<String> miss = InstallCheck.missing(c);
        String[][] fields = {
                {"id", deviceId(c)},
                {"modele", (android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL).trim()},
                {"version", AppUpdater.getCurrentVersion(c)},
                {"installation", miss.isEmpty() ? "ok" : android.text.TextUtils.join(", ", miss)},
                {"applis_manquantes", android.text.TextUtils.join(", ", InstallCheck.missingApps(c))},
                {"usage", UsageStats.summary30(c)},
                {"usage_total", UsageStats.summaryAll(c)},
        };
        for (String[] f : fields) {
            body.append('&').append(f[0]).append('=').append(java.net.URLEncoder.encode(f[1] == null ? "" : f[1], "UTF-8"));
        }
        byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
        String target = base + "/index.php";
        for (int hop = 0; hop < 4; hop++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(target).openConnection();
            try {
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(15_000);
                conn.setReadTimeout(20_000);
                conn.setUseCaches(false);
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("User-Agent", userAgent);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                try (java.io.OutputStream o = conn.getOutputStream()) { o.write(data); }
                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = conn.getHeaderField("Location");
                    if (loc == null) return null;
                    target = new URL(new URL(target), loc).toString();
                    continue;
                }
                if (!"1".equals(conn.getHeaderField("X-Villa-Config"))) return null; // page sans cette fonction
                if (code != 200) return new Object[]{ code, "" };
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null && sb.length() < 64_000) sb.append(line).append('\n');
                }
                return new Object[]{ code, sb.toString() };
            } finally {
                conn.disconnect();
            }
        }
        return null;
    }

    /** Comme get(), avec un second essai si le serveur ou le réseau ne répond pas à temps. */
    private static Object[] getRetry(String url) throws Exception {
        try {
            return get(url);
        } catch (java.io.IOException first) {
            Thread.sleep(3000L);
            return get(url);
        }
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
                conn.setConnectTimeout(15_000);
                conn.setReadTimeout(20_000);
                conn.setUseCaches(false);
                conn.setRequestProperty("User-Agent", userAgent);
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
