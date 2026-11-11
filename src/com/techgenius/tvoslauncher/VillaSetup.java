package com.techgenius.tvoslauncher;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Vérification du code de la villa (6 chiffres) auprès de la page de son dossier (index.php). */
final class VillaSetup {
    private VillaSetup() { }

    /** Résultat d'une vérification du code de la villa. */
    static final class Check {
        String base;          // dossier trouvé (code correct), sinon null
        boolean blocked;      // trop d'essais : bloqué 15 minutes
        boolean reached;      // au moins un reglages.php a répondu
        String error = "";    // dernière erreur réseau
        String message() {
            if (blocked) return "Trop d'essais : réessayez dans 15 minutes.";
            if (!reached && !error.isEmpty()) return "Serveur de la villa injoignable (" + error + ").";
            if (!reached) return "Villa introuvable (ou index.php absent du dossier). Vérifiez le nom et la connexion.";
            return "Code de la villa incorrect.";
        }
    }

    /**
     * Demande au reglages.php des dossiers possibles si le code de la villa (6 chiffres) est le bon.
     * À appeler hors du fil principal.
     */
    static Check verifyCode(String input, String code) {
        Check r = new Check();
        for (String base : VillaFolder.candidatesFor(input)) {
            try {
                String target = base + "/index.php";
                byte[] data = ("action=verify&code=" + java.net.URLEncoder.encode(code, "UTF-8"))
                        .getBytes(StandardCharsets.UTF_8);
                for (int hop = 0; hop < 4; hop++) {
                    HttpURLConnection c = (HttpURLConnection) new URL(target).openConnection();
                    c.setInstanceFollowRedirects(false);
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    c.setRequestMethod("POST");
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                    c.setRequestProperty("User-Agent", VillaFolder.userAgent);
                    try (OutputStream o = c.getOutputStream()) { o.write(data); }
                    int status = c.getResponseCode();
                    if (status >= 300 && status < 400) {
                        String loc = c.getHeaderField("Location");
                        c.disconnect();
                        if (loc == null) break;
                        target = new URL(new URL(target), loc).toString();
                        continue;
                    }
                    if (status == 200) {
                        StringBuilder sb = new StringBuilder();
                        try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                            String l;
                            while ((l = br.readLine()) != null && sb.length() < 2048) sb.append(l);
                        }
                        c.disconnect();
                        String t = sb.toString().trim();
                        if (t.startsWith("{")) {
                            org.json.JSONObject j = new org.json.JSONObject(t);
                            r.reached = true;
                            if (j.optBoolean("ok")) { r.base = base; r.blocked = false; return r; }
                            if (j.optBoolean("bloque")) r.blocked = true;
                        }
                    } else {
                        c.disconnect();
                    }
                    break;
                }
            } catch (Throwable t) {
                r.error = t.getClass().getSimpleName();
            }
        }
        return r;
    }
}
