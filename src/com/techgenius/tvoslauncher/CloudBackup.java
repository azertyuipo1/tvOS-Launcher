package com.techgenius.tvoslauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Sauvegarde des réglages du launcher sur sbh-host.fr (script backup.php), une sauvegarde par villa
 * (nom du profil). Le serveur garde l'historique : une sauvegarde n'en écrase jamais une autre.
 */
final class CloudBackup {
    static final String ENDPOINT = "https://www.sbh-host.fr/clo/tv/backup.php";
    // Clé partagée avec backup.php. Elle limite l'accès aux sauvegardes (qui ne contiennent que des réglages TV).
    private static final String KEY = "E8iMPawGfrunIbQ_JfL1M9SUZEfikbsJ";

    private CloudBackup() { }

    /** Identifiant de la villa : nom du profil, simplifié (ex. « villa-l-enclos »). */
    static String villaId(Context c) {
        String n = Profiles.current(c).name;
        if (n == null || n.trim().isEmpty()) n = "villa";
        String s = java.text.Normalizer.normalize(n, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        s = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return s.isEmpty() ? "villa" : s;
    }

    static String getStatus(Context c) {
        return c.getSharedPreferences("villa", Context.MODE_PRIVATE).getString("cloud_backup_status", "jamais sauvegardé");
    }

    private static void setStatus(Context c, String s) {
        c.getSharedPreferences("villa", Context.MODE_PRIVATE).edit().putString("cloud_backup_status", s).apply();
    }

    private static String url(Context c, String action) throws Exception {
        return ENDPOINT + "?action=" + action + "&villa=" + URLEncoder.encode(villaId(c), "UTF-8")
                + "&key=" + URLEncoder.encode(KEY, "UTF-8");
    }

    private static String http(String url, String body) throws Exception {
        String target = url;
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(target).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(30_000);
            if (body != null) {
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null || loc.isEmpty()) throw new Exception("redirection HTTP " + code);
                target = new URL(new URL(target), loc).toString();
                continue;
            }
            InputStream in = code < 400 ? conn.getInputStream() : conn.getErrorStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            if (in != null) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                in.close();
            }
            conn.disconnect();
            String txt = bos.toString("UTF-8");
            if (code != 200) throw new Exception("HTTP " + code + (txt.isEmpty() ? "" : " : " + txt.trim()));
            return txt;
        }
        throw new Exception("trop de redirections");
    }

    /** Envoie la sauvegarde ; onDone(message) sur le fil principal. */
    static void upload(Activity a, java.util.function.Consumer<String> onDone) {
        new Thread(() -> {
            String when = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(new Date());
            String msg;
            try {
                String json = CustomMedia.buildSettingsBackupJson(a);
                String r = http(url(a, "save"), json);
                msg = r.startsWith("OK") ? "✓ sauvegardé le " + when + " (" + villaId(a) + ")" : "✗ " + r.trim();
            } catch (Throwable t) {
                msg = "✗ échec : " + t.getMessage();
            }
            setStatus(a, msg);
            final String m = msg;
            a.runOnUiThread(() -> onDone.accept(m));
        }, "cloud-backup").start();
    }

    /** Sauvegarde automatique, au plus une fois par jour (appelée par l'accueil). */
    static void autoDaily(Context ctx) {
        final Context c = ctx.getApplicationContext();
        android.content.SharedPreferences p = c.getSharedPreferences("villa", Context.MODE_PRIVATE);
        long last = p.getLong("cloud_backup_last", 0L);
        if (System.currentTimeMillis() - last < 24L * 3600_000L) return;
        p.edit().putLong("cloud_backup_last", System.currentTimeMillis()).apply();
        new Thread(() -> {
            String when = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(new Date());
            try {
                String r = http(url(c, "save"), CustomMedia.buildSettingsBackupJson(c));
                setStatus(c, r.startsWith("OK") ? "✓ sauvegarde auto le " + when + " (" + villaId(c) + ")" : "✗ " + r.trim());
            } catch (Throwable t) {
                setStatus(c, "✗ sauvegarde auto échouée : " + t.getMessage());
            }
        }, "cloud-backup-auto").start();
    }

    /** Restaure la dernière sauvegarde de cette villa, après confirmation. */
    static void restore(Activity a) {
        new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Restaurer depuis sbh-host.fr ?")
                .setMessage("La dernière sauvegarde de « " + villaId(a) + " » va remplacer les réglages actuels du launcher.")
                .setPositiveButton("Restaurer", (d, w) -> new Thread(() -> {
                    String err = null;
                    boolean ok = false;
                    try {
                        String json = http(url(a, "latest"), null);
                        ok = CustomMedia.applySettingsBackupJson(a, json);
                    } catch (Throwable t) {
                        err = t.getMessage();
                    }
                    final boolean success = ok;
                    final String e = err;
                    a.runOnUiThread(() -> {
                        if (a.isFinishing()) return;
                        new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                                .setTitle(success ? "Réglages restaurés" : "Échec de la restauration")
                                .setMessage(success ? "Les réglages de « " + villaId(a) + " » ont été restaurés."
                                        : (e != null ? e : "Sauvegarde illisible."))
                                .setPositiveButton("OK", (dd, ww) -> { if (success) a.recreate(); })
                                .show();
                    });
                }, "cloud-restore").start())
                .setNegativeButton("Annuler", null)
                .show();
    }
}
