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
        java.util.List<String> dispo = enCache(c);
        if (dispo.isEmpty()) return "";
        String pris = p.getString("dernier", "");
        if (dispo.size() > 1) dispo.remove(pris);            // éviter deux fois le même d'affilée
        String n = dispo.get(new java.util.Random().nextInt(dispo.size()));
        if (!s.isEmpty()) p.edit().putString("choix_" + s, n).putString("dernier", n).apply();
        return n;
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
        if (mode.isEmpty()) return "désactivée dans les réglages de la villa";
        int liste = liste(c).size(), surTv = enCache(c).size();
        if (liste == 0) return "aucun morceau proposé";
        String e = prefs(c).getString("etat", "");
        String box = "";
        try {
            android.media.AudioManager am = c.getSystemService(android.media.AudioManager.class);
            box = " · box " + am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                    + "/" + am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
        } catch (Throwable ignored) { }
        return surTv + "/" + liste + " · " + prefs(c).getString("vol", vol(c) + "%") + box
                + (e.startsWith("✗") ? " · " + e : "");
    }

    /** Volume réglé pour la musique, en part du volume maximum : 20, 30 ou 40 %. */
    private static int vol(Context c) {
        try {
            int v = Integer.parseInt(VillaFolder.get(c, VillaFolder.K_MUSIC_VOL).trim());
            if (v == 35) v = 30; else if (v == 50) v = 40;      // anciens réglages doux/moyen/fort
            return v == 20 || v == 30 || v == 40 ? v : 30;
        } catch (Throwable t) {
            return 30;
        }
    }

    private static int volumeAvant = -1;        // volume de la box à remettre après la musique

    /** Amène le volume de la box jusqu'à <code>cible</code>. Beaucoup de boîtiers (HDMI-CEC,
     *  barre de son) ignorent un réglage absolu et ne comprennent que « monter / baisser » :
     *  on avance donc pas à pas, comme avec la télécommande, et on s'arrête dès que ça ne
     *  bouge plus. À appeler hors du fil principal. Renvoie le niveau atteint. */
    private static int regler(Context c, android.media.AudioManager am, int cible) {
        int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
        int lu = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
        StringBuilder j = new StringBuilder("max " + max + " · cible " + cible + " · " + lu);
        String fin = "atteint";
        try {   // d'abord le plus simple : le réglage absolu
            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, cible, 0);
            try { Thread.sleep(150); } catch (InterruptedException ignored) { }
            lu = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
            j.append("→set ").append(lu);
        } catch (Throwable t) {
            j.append("→set refusé(").append(t.getClass().getSimpleName()).append(")");
        }
        int sens = 0;
        for (int i = 0; i < 80; i++) {
            lu = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
            if (lu == cible) break;
            int s2 = lu < cible ? 1 : -1;
            if (sens != 0 && s2 != sens) { fin = "pas trop gros"; break; }
            sens = s2;
            try {
                am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                        s2 > 0 ? android.media.AudioManager.ADJUST_RAISE
                               : android.media.AudioManager.ADJUST_LOWER, 0);
            } catch (Throwable t) {
                fin = "pas refusé(" + t.getClass().getSimpleName() + ")";
                j.append("→").append(lu);
                break;
            }
            try { Thread.sleep(120); } catch (InterruptedException e) { break; }
            int apres = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
            j.append("→").append(apres);
            if (apres == lu) { fin = "ne bouge plus"; lu = apres; break; }
            lu = apres;
        }
        if (lu != cible && fin.equals("atteint")) fin = "arrêt à " + lu;
        prefs(c).edit().putString("journal",
                j + " · " + fin + (am.isVolumeFixed() ? " · figé" : "") + " · " + sorties(am)).apply();
        return lu;
    }

    /** Sorties audio branchées, pour comprendre qui pilote vraiment le volume. */
    private static String sorties(android.media.AudioManager am) {
        StringBuilder b = new StringBuilder();
        try {
            for (android.media.AudioDeviceInfo d
                    : am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)) {
                String n;
                switch (d.getType()) {
                    case android.media.AudioDeviceInfo.TYPE_HDMI: n = "hdmi"; break;
                    case android.media.AudioDeviceInfo.TYPE_HDMI_ARC: n = "arc"; break;
                    case android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: n = "hp"; break;
                    case android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: n = "bt"; break;
                    case android.media.AudioDeviceInfo.TYPE_AUX_LINE: n = "jack"; break;
                    default: n = String.valueOf(d.getType());
                }
                if (b.indexOf(n) < 0) b.append(b.length() > 0 ? "+" : "").append(n);
            }
        } catch (Throwable ignored) { }
        return b.length() > 0 ? b.toString() : "?";
    }

    static String journal(Context c) {
        String j = prefs(c).getString("journal", "");
        return j.isEmpty() ? "pas encore de réglage de volume" : j;
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
    static synchronized void maybeStart(Context ctx) {
        final Context c = ctx.getApplicationContext();
        if (isPlaying() || !aJouer(c)) return;
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
        if (nom.isEmpty()) { ensureFile(c); return; }
        File f = file(c, nom);
        if (f.length() <= 0) { ensureFile(c); return; }
        final int vol = vol(c);
        prefs(c).edit().putString("vol", vol + "% · réglage…").apply();
        // Le volume est réglé pour la musique, puis remis comme il était quand elle s'arrête.
        new Thread(() -> {
            String note = "";
            try {
                android.media.AudioManager am = c.getSystemService(android.media.AudioManager.class);
                if (am.isVolumeFixed()) {
                    note = " · volume figé";            // sortie optique/HDMI : rien à régler
                } else {
                    int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
                    int cible = Math.max(1, Math.round(max * vol / 100f));
                    volumeAvant = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
                    int atteint = regler(c, am, cible);
                    if (atteint != cible) note = " · box bloquée à " + atteint + "/" + max;
                }
            } catch (Throwable ignored) { }
            prefs(c).edit().putString("vol", vol + "%" + note).apply();
        }).start();
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
        }
    }

    private static boolean test;        // lancée par « Tester la musique » : on garde le volume réglé

    /** Arrête la musique. fini = ne plus la rejouer pour ce séjour (touche pressée). */
    static synchronized void stop(Context ctx, boolean fini) {
        Context c = ctx.getApplicationContext();
        MediaPlayer p = player;
        player = null;
        if (p != null) {
            try { p.stop(); } catch (Throwable ignored) { }
            try { p.release(); } catch (Throwable ignored) { }
            final int avant = test ? -1 : volumeAvant;
            volumeAvant = -1;
            test = false;
            if (avant >= 0) new Thread(() -> {     // la box retrouve le volume laissé par les clients
                try { regler(c, c.getSystemService(android.media.AudioManager.class), avant); }
                catch (Throwable ignored) { }
            }).start();
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
