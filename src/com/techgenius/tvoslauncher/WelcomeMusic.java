package com.techgenius.tvoslauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Musique d'accueil : le jour de l'arrivée, à partir de midi, l'écran d'accueil joue en boucle le
 * morceau choisi pour la villa. Elle s'arrête à la première touche de la télécommande et ne revient
 * qu'à la réservation suivante. Le fichier est téléchargé une fois depuis villas-tv/musique/ puis
 * gardé sur la TV : plus besoin du réseau au moment de l'arrivée.
 */
final class WelcomeMusic {
    static final int HEURE = 12;                 // heure du check-in
    private static final String PREFS = "villa_musique";
    private static MediaPlayer player;
    private static volatile boolean downloading;

    private WelcomeMusic() { }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean isPlaying() {
        try {
            return player != null && player.isPlaying();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Fichier local du morceau choisi. */
    private static File file(Context c, String nom) {
        File d = new File(c.getFilesDir(), "musique");
        if (!d.exists()) d.mkdirs();
        return new File(d, nom.replaceAll("[^A-Za-z0-9._-]", "_"));
    }

    /** villas-tv/<villa>/… → villas-tv/musique/<nom> */
    private static String url(Context c, String nom) {
        String base = VillaFolder.fileUrl(c, "");
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        int i = base.lastIndexOf('/');
        if (i <= 8) return "";
        return base.substring(0, i) + "/musique/" + android.net.Uri.encode(nom);
    }

    static String status(Context c) {
        String nom = VillaFolder.get(c, VillaFolder.K_MUSIC).trim();
        if (nom.isEmpty()) return "aucune";
        String s = prefs(c).getString("etat", "pas encore téléchargée");
        return nom + " · " + s;
    }

    /** Télécharge le morceau s'il manque (en arrière-plan). */
    static void ensureFile(Context ctx) {
        final Context c = ctx.getApplicationContext();
        final String nom = VillaFolder.get(c, VillaFolder.K_MUSIC).trim();
        if (nom.isEmpty() || downloading || file(c, nom).length() > 0) return;
        final String target = url(c, nom);
        if (target.isEmpty()) return;
        downloading = true;
        VillaFolder.initUserAgent(c);
        new Thread(() -> {
            String quand = new SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE).format(new Date());
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(target).openConnection();
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(60_000);
                conn.setRequestProperty("User-Agent", VillaFolder.userAgent);
                if (conn.getResponseCode() == 200) {
                    File tmp = new File(c.getFilesDir(), "musique.tmp");
                    try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                        byte[] buf = new byte[32768];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                    if (tmp.length() > 10_000 && tmp.renameTo(file(c, nom))) {
                        prefs(c).edit().putString("etat", "✓ sur la TV (" + tmp.length() / 1024 + " ko)").apply();
                    } else {
                        tmp.delete();
                        prefs(c).edit().putString("etat", "✗ fichier vide — " + quand).apply();
                    }
                } else {
                    prefs(c).edit().putString("etat", "✗ introuvable sur le serveur — " + quand).apply();
                }
            } catch (Throwable t) {
                prefs(c).edit().putString("etat", "✗ téléchargement impossible — " + quand).apply();
            } finally {
                if (conn != null) conn.disconnect();
                downloading = false;
            }
        }, "musique-dl").start();
    }

    /** Clé du séjour en cours (date d'arrivée), "" si aucun. */
    private static String sejour(Context c) {
        RentalReset.Stay s = RentalReset.currentStay(c);
        return s == null || s.start == null ? "" : s.start;
    }

    /** Jour d'arrivée, après midi, musique pas encore écoutée pour ce séjour. */
    static boolean aJouer(Context c) {
        String nom = VillaFolder.get(c, VillaFolder.K_MUSIC).trim();
        if (nom.isEmpty()) return false;
        String s = sejour(c);
        if (s.isEmpty()) return false;
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        if (!today.equals(s)) return false;
        if (Calendar.getInstance().get(Calendar.HOUR_OF_DAY) < HEURE) return false;
        return !s.equals(prefs(c).getString("fait", ""));
    }

    /** Démarre la musique si c'est le moment ; sans effet sinon. */
    static synchronized void maybeStart(Context ctx) {
        final Context c = ctx.getApplicationContext();
        if (isPlaying() || !aJouer(c)) return;
        String nom = VillaFolder.get(c, VillaFolder.K_MUSIC).trim();
        File f = file(c, nom);
        if (f.length() <= 0) { ensureFile(c); return; }
        int vol = 40;
        try {
            vol = Math.max(5, Math.min(100, Integer.parseInt(VillaFolder.get(c, VillaFolder.K_MUSIC_VOL).trim())));
        } catch (Throwable ignored) { }
        try {
            MediaPlayer p = new MediaPlayer();
            p.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build());
            p.setDataSource(f.getPath());
            p.setLooping(true);
            float v = vol / 100f;
            p.setVolume(v, v);
            p.prepare();
            p.start();
            player = p;
        } catch (Throwable t) {
            player = null;
        }
    }

    /** Arrête la musique. fini = ne plus la rejouer pour ce séjour (touche pressée). */
    static synchronized void stop(Context ctx, boolean fini) {
        Context c = ctx.getApplicationContext();
        MediaPlayer p = player;
        player = null;
        if (p != null) {
            try { p.stop(); } catch (Throwable ignored) { }
            try { p.release(); } catch (Throwable ignored) { }
        }
        if (fini) {
            String s = sejour(c);
            if (!s.isEmpty()) prefs(c).edit().putString("fait", s).apply();
        }
    }

    /** Une touche a été pressée : la musique s'arrête pour de bon. */
    static void onKey(Context c) {
        if (player != null) stop(c, true);
    }
}
