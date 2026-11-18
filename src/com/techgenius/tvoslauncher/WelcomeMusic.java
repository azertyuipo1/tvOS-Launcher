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
public final class WelcomeMusic {
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

    private static File dossier(Context c) {
        File d = new File(c.getFilesDir(), "musique");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** Fichier local d'un morceau. */
    private static File file(Context c, String nom) {
        return new File(dossier(c), nom.replaceAll("[^A-Za-z0-9._-]", "_"));
    }

    /** Morceaux proposés par le serveur. */
    private static java.util.List<String> liste(Context c) {
        java.util.List<String> l = new java.util.ArrayList<>();
        for (String n : VillaFolder.get(c, VillaFolder.K_MUSIC_LIST).split("\\|")) {
            n = n.trim();
            if (!n.isEmpty()) l.add(n);
        }
        return l;
    }

    private static boolean auHasard(Context c) {
        return "aleatoire".equalsIgnoreCase(VillaFolder.get(c, VillaFolder.K_MUSIC).trim());
    }

    /** Morceaux déjà sur la TV, parmi ceux que le serveur propose. */
    private static java.util.List<String> enCache(Context c) {
        java.util.List<String> l = new java.util.ArrayList<>();
        for (String n : liste(c)) if (file(c, n).length() > 0) l.add(n);
        return l;
    }

    /** Morceau de ce séjour : tiré au sort une fois, puis conservé. */
    private static String choix(Context c) {
        String mode = VillaFolder.get(c, VillaFolder.K_MUSIC).trim();
        if (mode.isEmpty()) return "";
        if (!auHasard(c)) return mode;
        String s = sejour(c);
        SharedPreferences p = prefs(c);
        String dejà = s.isEmpty() ? "" : p.getString("choix_" + s, "");
        if (!dejà.isEmpty() && file(c, dejà).length() > 0) return dejà;
        String n = suivant(c);
        if (!n.isEmpty() && !s.isEmpty()) p.edit().putString("choix_" + s, n).apply();
        return n;
    }

    /** Le morceau suivant dans l'ordre de lecture : tous les morceaux passent une fois avant
     *  qu'un seul revienne. L'ordre est tiré au sort une fois, puis répété à l'identique ;
     *  il est retiré au sort si le dossier de la villa change. */
    private static synchronized String suivant(Context c) {
        java.util.List<String> dispo = enCache(c);
        if (dispo.isEmpty()) return "";
        SharedPreferences p = prefs(c);
        java.util.List<String> ordre = new java.util.ArrayList<>();
        for (String n : p.getString("ordre", "").split("\\|")) if (dispo.contains(n)) ordre.add(n);
        if (ordre.size() != dispo.size()) {                  // premier démarrage ou dossier modifié
            ordre = new java.util.ArrayList<>(dispo);
            java.util.Collections.shuffle(ordre);
            p.edit().putString("ordre", String.join("|", ordre)).putInt("pos", 0).apply();
        }
        int pos = Math.max(0, p.getInt("pos", 0)) % ordre.size();
        String n = ordre.get(pos);
        p.edit().putInt("pos", (pos + 1) % ordre.size()).apply();
        return n;
    }

    /** L'ordre de lecture, pour le débogage : « 3e sur 10 ». */
    static String ordre(Context c) {
        String[] o = prefs(c).getString("ordre", "").split("\\|");
        if (o.length <= 1 && o[0].isEmpty()) return "";
        return " · " + ((prefs(c).getInt("pos", 0) + o.length - 1) % o.length + 1) + "e sur " + o.length;
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
        String mode = VillaFolder.get(c, VillaFolder.K_MUSIC).trim();
        if (mode.isEmpty()) return "Inactive";
        int liste = liste(c).size(), surTv = enCache(c).size();
        String e = prefs(c).getString("etat", "");
        return "Active ✓ • Musiques téléchargées " + surTv + "/" + liste
                + (e.startsWith("✗") ? " • " + e : "");
    }

    /** Met la TV à jour : télécharge tous les morceaux du dossier et retire ceux qui n'y sont plus. */
    static void ensureFile(Context ctx) {
        final Context c = ctx.getApplicationContext();
        if (downloading || VillaFolder.get(c, VillaFolder.K_MUSIC).trim().isEmpty()) return;
        final java.util.List<String> tout = auHasard(c) ? liste(c)
                : java.util.Collections.singletonList(VillaFolder.get(c, VillaFolder.K_MUSIC).trim());
        final java.util.List<String> manque = new java.util.ArrayList<>();
        java.util.Set<String> attendus = new java.util.HashSet<>();
        for (String n : tout) {
            if (n.isEmpty()) continue;
            attendus.add(file(c, n).getName());
            if (file(c, n).length() <= 0) manque.add(n);
        }
        // Morceau retiré du dossier du serveur : on ne le garde pas sur la TV.
        File[] locaux = dossier(c).listFiles();
        if (locaux != null && !attendus.isEmpty()) {
            for (File f : locaux) if (!attendus.contains(f.getName())) f.delete();
        }
        if (manque.isEmpty()) return;
        downloading = true;
        VillaFolder.initUserAgent(c);
        new Thread(() -> {
            String quand = new SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE).format(new Date());
            try {
                int pris = 0;
                for (String nom : manque) {
                    String target = url(c, nom);
                    if (target.isEmpty()) break;
                    HttpURLConnection conn = null;
                    try {
                        conn = (HttpURLConnection) new URL(target).openConnection();
                        conn.setConnectTimeout(10_000);
                        conn.setReadTimeout(60_000);
                        conn.setRequestProperty("User-Agent", VillaFolder.userAgent);
                        if (conn.getResponseCode() != 200) {
                            prefs(c).edit().putString("etat", "✗ introuvable : " + nom + " — " + quand).apply();
                            continue;
                        }
                        File tmp = new File(c.getFilesDir(), "musique.tmp");
                        try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                            byte[] buf = new byte[32768];
                            int n;
                            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        }
                        if (tmp.length() > 10_000 && tmp.renameTo(file(c, nom))) pris++;
                        else { tmp.delete(); prefs(c).edit().putString("etat", "✗ fichier vide : " + nom).apply(); }
                    } catch (Throwable t) {
                        prefs(c).edit().putString("etat", "✗ téléchargement impossible — " + quand).apply();
                    } finally {
                        if (conn != null) conn.disconnect();
                    }
                }
                if (pris > 0) prefs(c).edit().putString("etat", "✓ " + pris + " morceau(x) téléchargé(s)").apply();
                else if (!manque.isEmpty()) prefs(c).edit().putString("etat", "✗ aucun morceau n'a pu être téléchargé").apply();
            } finally {
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
        if (VillaFolder.get(c, VillaFolder.K_MUSIC).trim().isEmpty()) return false;
        String s = sejour(c);
        if (s.isEmpty()) return false;
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        if (!today.equals(s)) return false;
        if (Calendar.getInstance().get(Calendar.HOUR_OF_DAY) < HEURE) return false;
        return !s.equals(prefs(c).getString("fait", ""));
    }

    /** Démarre la musique si c'est le moment ; sans effet sinon. */
    public static synchronized void maybeStart(Context ctx) {
        final Context c = ctx.getApplicationContext();
        if (isPlaying() || !aJouer(c)) return;
        // Une mise à jour en cours va relancer l'application : la musique serait coupée au milieu.
        // On la laisse s'installer, l'accueil revient de lui-même et la musique part ensuite.
        if (AppUpdater.isChecking() || AppUpdater.isDownloading()) {
            prefs(c).edit().putString("etat", "mise à jour en cours, musique en attente").apply();
            return;
        }
        jouer(c);
    }

    /** Essai depuis le mode admin : la musique démarre tout de suite, quel que soit le calendrier. */
    static void testNow(Context ctx) {
        Context c = ctx.getApplicationContext();
        stop(c, false);
        ensureFile(c);
        for (int i = 0; i < 40 && enCache(c).isEmpty() && downloading; i++) {
            try { Thread.sleep(500L); } catch (InterruptedException e) { break; }
        }
        jouer(c);
        test = true;      // le volume réglé reste en place pour qu'on puisse le vérifier
    }

    /** Vrai pendant un téléchargement. */
    static boolean enCours() { return downloading; }

    private static synchronized void jouer(Context c) {
        String nom = choix(c);
        if (nom.isEmpty()) {
            prefs(c).edit().putString("etat", "✗ aucun morceau coché sur la page").apply();
            ensureFile(c);
            return;
        }
        File f = file(c, nom);
        if (f.length() <= 0) {
            prefs(c).edit().putString("etat", "✗ pas encore téléchargé : " + nom).apply();
            ensureFile(c);
            return;
        }
        try {
            MediaPlayer p = new MediaPlayer();
            p.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build());
            p.setDataSource(f.getPath());
            p.setLooping(true);
            p.prepare();
            p.start();
            player = p;
        } catch (Throwable t) {
            player = null;
            prefs(c).edit().putString("etat",
                    "✗ lecture impossible (" + t.getClass().getSimpleName() + ") : " + nom).apply();
            return;
        }
        // Le volume n'est jamais touché : chaque TV réagit différemment aux commandes HDMI-CEC,
        // et la musique sort au niveau que les clients ont laissé.
        prefs(c).edit().putString("etat", "✓ " + nom).apply();
    }

    private static boolean test;        // lancée par « Tester la musique »

    /** Arrête la musique. fini = ne plus la rejouer pour ce séjour (touche pressée). */
    static synchronized void stop(Context ctx, boolean fini) {
        Context c = ctx.getApplicationContext();
        MediaPlayer p = player;
        player = null;
        if (p != null) {
            try { p.stop(); } catch (Throwable ignored) { }
            try { p.release(); } catch (Throwable ignored) { }
            test = false;
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
