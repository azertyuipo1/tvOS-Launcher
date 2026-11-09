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

    static final String SHORT_PREFIX = "https://www.sbh-host.fr/";
    static final String SHORT_SUFFIX = "/tv";
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
            if (old.isEmpty()) old = "https://www.sbh-host.fr/clo/tv/background.jpg";
            if (old.endsWith("/" + BACKGROUND_FILE)) {
                e.putString(P_BASE, old.substring(0, old.length() - BACKGROUND_FILE.length() - 1));
            }
            RentalReset.keepLegacyCalendar(c);
        }
        e.apply();
    }

    static String getBase(Context c) {
        migrate(c);
        return prefs(c).getString(P_BASE, "");
    }

    static boolean isConfigured(Context c) { return !getBase(c).isEmpty(); }

    static void setBase(Context c, String url) {
        String u = url == null ? "" : url.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        // Raccourci : un simple nom (ex. « clo ») donne https://www.sbh-host.fr/clo/tv
        if (!u.isEmpty() && u.matches("[A-Za-z0-9_-]+")) u = SHORT_PREFIX + u.toLowerCase(Locale.ROOT) + SHORT_SUFFIX;
        if (!u.isEmpty() && !u.matches("(?i)^https?://.*")) u = "https://" + u;
        prefs(c).edit().putString(P_BASE, u).remove(P_CONFIG).remove(P_STATUS)
                .remove("bg_etag").remove("bg_lastmod").apply();
    }

    static String fileUrl(Context c, String name) {
        String b = getBase(c);
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

    /** Nom affiché : config.txt, sinon le nom saisi sur la TV (profil). */
    static String villaName(Context c) {
        String n = get(c, K_NAME);
        if (!n.isEmpty()) return n;
        n = Profiles.current(c).name;
        return n == null ? "" : n;
    }

    static Map<String, String> parse(String txt) {
        Map<String, String> m = new HashMap<>();
        if (txt == null) return m;
        for (String line : txt.split("\\r?\\n")) {
            String l = line.replace("﻿", "").trim();
            if (l.isEmpty() || l.startsWith("#")) continue;
            int i = l.indexOf(':');
            if (i <= 0) continue;
            String k = l.substring(0, i).trim().toLowerCase(Locale.ROOT);
            String v = l.substring(i + 1).trim();
            if (K_CALENDAR.equals(k)) v = v.replaceAll("\\s+", "");
            m.put(k, v);
        }
        return m;
    }

    /** Relit config.txt (à appeler hors du fil principal). Sans réseau, la dernière lecture est gardée. */
    static void refreshBlocking(Context c) {
        String url = fileUrl(c, CONFIG_FILE);
        if (url.isEmpty()) return;
        String when = new SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE).format(new Date());
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
            if (code == 404) {
                prefs(c).edit().putString(P_CONFIG, "")
                        .putString(P_STATUS, "config.txt absent — réglages de la TV utilisés (" + when + ")").apply();
                return;
            }
            if (conn == null || code != 200) {
                prefs(c).edit().putString(P_STATUS, "✗ config.txt : erreur HTTP " + code + " — " + when).apply();
                return;
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null && sb.length() < 64_000) sb.append(line).append('\n');
            }
            String txt = sb.toString();
            int n = 0;
            for (String v : parse(txt).values()) if (!v.isEmpty()) n++;
            prefs(c).edit().putString(P_CONFIG, txt)
                    .putString(P_STATUS, "✓ config.txt lu le " + when + " (" + n + " réglage" + (n > 1 ? "s" : "") + " forcé" + (n > 1 ? "s" : "") + ")")
                    .apply();
        } catch (Throwable t) {
            prefs(c).edit().putString(P_STATUS, "✗ dossier injoignable (" + t.getClass().getSimpleName() + ") — " + when).apply();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
