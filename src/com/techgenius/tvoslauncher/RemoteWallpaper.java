package com.techgenius.tvoslauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Arrière-plan de l'accueil : background.jpg dans le dossier de la villa (voir VillaFolder).
 * Il est revérifié à chaque allumage et toutes les 30 minutes : si la photo change sur le serveur,
 * l'accueil l'affiche. Sans réseau, la dernière photo téléchargée reste affichée.
 */
final class RemoteWallpaper {
    static final String WALL_KEY = "villa:remote";
    private static final String PREFS = "villa";
    private static volatile boolean running = false;

    private RemoteWallpaper() { }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String getUrl(Context c) { return VillaFolder.fileUrl(c, VillaFolder.BACKGROUND_FILE); }

    static String getStatus(Context c) {
        if (getUrl(c).isEmpty()) return "aucun dossier";
        return prefs(c).getString("bg_status", "pas encore téléchargé");
    }

    static File file(Context c) { return new File(c.getFilesDir(), "villa_background.jpg"); }

    /** Photo téléchargée (réduite pour l'écran), ou null si aucune n'a encore été téléchargée. */
    static Bitmap load(Context c, int reqW, int reqH) {
        File f = file(c);
        if (!f.exists()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= reqW && o.outHeight / (o.inSampleSize * 2) >= reqH) o.inSampleSize *= 2;
        o.inJustDecodeBounds = false;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    /** Vérifie la photo sur le serveur en arrière-plan ; onChanged (fil principal) si elle a changé. */
    static void refresh(Context ctx, Runnable onChanged) {
        if (running || getUrl(ctx).isEmpty()) return;
        running = true;
        final Context c = ctx.getApplicationContext();
        new Thread(() -> {
            boolean changed = false;
            String when = new SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE).format(new Date());
            HttpURLConnection conn = null;
            try {
                SharedPreferences p = prefs(c);
                boolean haveFile = file(c).exists();
                String target = getUrl(c);
                int code = 0;
                for (int hop = 0; hop < 6; hop++) {
                    conn = (HttpURLConnection) new URL(target).openConnection();
                    conn.setInstanceFollowRedirects(false);
                    conn.setConnectTimeout(10_000);
                    conn.setReadTimeout(30_000);
                    if (haveFile) {
                        String etag = p.getString("bg_etag", ""), lm = p.getString("bg_lastmod", "");
                        if (!etag.isEmpty()) conn.setRequestProperty("If-None-Match", etag);
                        if (!lm.isEmpty()) conn.setRequestProperty("If-Modified-Since", lm);
                    }
                    code = conn.getResponseCode();
                    if (code < 300 || code >= 400 || code == 304) break;
                    String loc = conn.getHeaderField("Location");
                    conn.disconnect();
                    conn = null;
                    if (loc == null || loc.isEmpty()) break;
                    target = new URL(new URL(target), loc).toString();
                }
                if (code == 304) {
                    p.edit().putString("bg_status", "✓ à jour (vérifié le " + when + ")").apply();
                } else if (code == 200 && conn != null) {
                    File tmp = new File(c.getFilesDir(), "villa_background.tmp");
                    try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                        byte[] buf = new byte[16384];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(tmp.getPath(), o);
                    if (o.outWidth > 0 && o.outHeight > 0 && tmp.renameTo(file(c))) {
                        p.edit().putString("bg_etag", nz(conn.getHeaderField("ETag")))
                                .putString("bg_lastmod", nz(conn.getHeaderField("Last-Modified")))
                                .putString("bg_status", "✓ téléchargée le " + when + " (" + o.outWidth + "×" + o.outHeight + ")")
                                .apply();
                        changed = true;
                    } else {
                        tmp.delete();
                        p.edit().putString("bg_status", "✗ le fichier n'est pas une image — " + when).apply();
                    }
                } else {
                    p.edit().putString("bg_status", "✗ erreur HTTP " + code + " — " + when).apply();
                }
            } catch (Throwable t) {
                prefs(c).edit().putString("bg_status", "✗ injoignable (" + t.getClass().getSimpleName() + ") — " + when).apply();
            } finally {
                if (conn != null) conn.disconnect();
                running = false;
            }
            if (changed && onChanged != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(onChanged);
        }, "villa-wallpaper").start();
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
