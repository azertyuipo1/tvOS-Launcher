package com.techgenius.tvoslauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;

/**
 * Configurer la villa sans clavier sur la TV :
 *  B — « avec le téléphone » : la TV affiche un QR code ; le téléphone (même Wi-Fi) ouvre une page servie
 *      par la TV et y saisit le code ou l'adresse de la villa.
 *  C — « depuis reglages.php » : la TV affiche un code à 6 chiffres ; dans reglages.php (protégé par mot de
 *      passe), « Associer une TV » + ce code ; la TV récupère l'adresse de son dossier toute seule.
 */
final class VillaSetup {
    private VillaSetup() { }

    private static final SecureRandom RND = new SecureRandom();
    private static Handler main() { return new Handler(Looper.getMainLooper()); }

    // ------------------------------------------------------------------ commun

    private static String lanIp() {
        try {
            String fallback = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    String ip = a.getHostAddress();
                    String n = ni.getName();
                    if (n.startsWith("wlan") || n.startsWith("eth")) return ip;
                    if (fallback == null) fallback = ip;
                }
            }
            return fallback;
        } catch (Throwable t) {
            return null;
        }
    }

    private static AlertDialog showCard(Activity a, String title, Bitmap qr, String big, String text, Runnable onClose) {
        float u = Ui.unit(a);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        int pad = Math.round(16 * u);
        box.setPadding(pad, pad, pad, pad);
        if (qr != null) {
            ImageView iv = new ImageView(a);
            iv.setImageBitmap(qr);
            int s = Math.round(150 * u);
            box.addView(iv, new LinearLayout.LayoutParams(s, s));
        }
        if (big != null) {
            TextView b = new TextView(a);
            b.setText(big);
            b.setTextColor(0xFFFFFFFF);
            b.setTypeface(Ui.font(a, "display"));
            Ui.textSize(b, 30);
            b.setLetterSpacing(0.12f);
            b.setGravity(android.view.Gravity.CENTER);
            box.addView(b);
        }
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextColor(0xDDFFFFFF);
        Ui.textSize(t, 12);
        t.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = Math.round(10 * u);
        box.addView(t, tlp);
        AlertDialog d = new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title).setView(box).setNegativeButton("Fermer", null).create();
        d.setOnDismissListener(x -> onClose.run());
        d.show();
        return d;
    }

    private static void applied(Activity a, AlertDialog d, String base, Runnable onDone) {
        VillaFolder.setBase(a, base);
        if (d != null && d.isShowing()) d.dismiss();
        android.widget.Toast.makeText(a, "✓ Villa configurée : " + VillaFolder.getBase(a), android.widget.Toast.LENGTH_LONG).show();
        if (onDone != null) onDone.run();
    }

    // ------------------------------------------------------------------ B : téléphone (QR code)

    static void showPhoneSetup(Activity a, Runnable onDone) {
        String ip = lanIp();
        if (ip == null) {
            android.widget.Toast.makeText(a, "La TV n'est pas connectée au réseau.", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        final String pin = String.valueOf(1000 + RND.nextInt(9000));
        final ServerSocket[] holder = new ServerSocket[1];
        int port = 0;
        for (int p = 8181; p < 8191 && holder[0] == null; p++) {
            try { holder[0] = new ServerSocket(p); port = p; } catch (Exception ignored) { }
        }
        if (holder[0] == null) {
            android.widget.Toast.makeText(a, "Impossible de démarrer la page de configuration.", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        final String url = "http://" + ip + ":" + port + "/?k=" + pin;
        QrCode q = QrCode.encode(url);
        Bitmap qr = q == null ? null : q.toBitmap(Math.round(150 * Ui.unit(a)));
        final AlertDialog[] dlg = new AlertDialog[1];
        dlg[0] = showCard(a, "Configurer avec le téléphone", qr, null,
                "Scannez ce QR code avec un téléphone connecté au même Wi-Fi que la TV,\n"
                        + "puis saisissez le code ou l'adresse de la villa.\n\n" + url,
                () -> { try { holder[0].close(); } catch (Exception ignored) { } });
        final ServerSocket server = holder[0];
        new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket s = server.accept()) {
                    s.setSoTimeout(8000);
                    String base = handle(s, pin);
                    if (base != null) {
                        main().post(() -> applied(a, dlg[0], base, onDone));
                    }
                } catch (Exception ignored) {
                }
            }
        }, "villa-phone-setup").start();
    }

    /** Traite une requête HTTP ; renvoie l'adresse saisie si la configuration a été envoyée. */
    private static String handle(Socket s, String pin) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        String first = in.readLine();
        if (first == null) return null;
        int len = 0;
        String line;
        while ((line = in.readLine()) != null && !line.isEmpty()) {
            if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                len = Integer.parseInt(line.substring(15).trim());
            }
        }
        String[] parts = first.split(" ");
        String method = parts[0], path = parts.length > 1 ? parts[1] : "/";
        String query = path.contains("?") ? path.substring(path.indexOf('?') + 1) : "";
        String body = "";
        if ("POST".equals(method) && len > 0 && len < 4096) {
            char[] buf = new char[len];
            int r = 0;
            while (r < len) { int n = in.read(buf, r, len - r); if (n < 0) break; r += n; }
            body = new String(buf, 0, r);
        }
        String k = param("POST".equals(method) ? body : query, "k");
        OutputStream out = s.getOutputStream();
        if (!pin.equals(k)) {
            send(out, page("Lien expiré", "<p>Rescannez le QR code affiché sur la TV.</p>"));
            return null;
        }
        if ("POST".equals(method)) {
            String v = param(body, "dossier").trim();
            String pw = param(body, "mdp");
            if (v.isEmpty() || pw.isEmpty()) {
                send(out, form(pin, "Saisissez le code de la villa et son mot de passe."));
                return null;
            }
            // Le mot de passe est vérifié par le reglages.php de la villa : sans lui, impossible de configurer la TV.
            String base = verify(v, pw);
            if (base == null) {
                send(out, form(pin, "Code de villa ou mot de passe incorrect."));
                return null;
            }
            send(out, page("✓ TV configurée", "<p>La TV charge maintenant les réglages de <b>" + esc(v)
                    + "</b>. Vous pouvez fermer cette page.</p>"));
            return v.matches("[A-Za-z0-9_-]+") ? v : base; // on garde le code court (« clo ») s'il a été saisi
        }
        send(out, form(pin, null));
        return null;
    }

    /** Interroge reglages.php des dossiers possibles ; renvoie le dossier dont le mot de passe est correct. */
    private static String verify(String input, String password) {
        for (String base : VillaFolder.candidatesFor(input)) {
            try {
                String target = base + "/reglages.php";
                byte[] data = ("action=verify&password=" + java.net.URLEncoder.encode(password, "UTF-8"))
                        .getBytes(StandardCharsets.UTF_8);
                for (int hop = 0; hop < 4; hop++) {
                    HttpURLConnection c = (HttpURLConnection) new URL(target).openConnection();
                    c.setInstanceFollowRedirects(false);
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    c.setRequestMethod("POST");
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                    try (OutputStream o = c.getOutputStream()) { o.write(data); }
                    int code = c.getResponseCode();
                    if (code >= 300 && code < 400) {
                        String loc = c.getHeaderField("Location");
                        c.disconnect();
                        if (loc == null) break;
                        target = new URL(new URL(target), loc).toString();
                        continue;
                    }
                    if (code == 200) {
                        StringBuilder sb = new StringBuilder();
                        try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                            String l;
                            while ((l = br.readLine()) != null && sb.length() < 2048) sb.append(l);
                        }
                        c.disconnect();
                        if (sb.toString().trim().startsWith("{") && new org.json.JSONObject(sb.toString()).optBoolean("ok")) return base;
                    } else {
                        c.disconnect();
                    }
                    break;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String param(String qs, String name) {
        for (String kv : qs.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0 && kv.substring(0, i).equals(name)) {
                try { return URLDecoder.decode(kv.substring(i + 1), "UTF-8"); } catch (Exception e) { return ""; }
            }
        }
        return "";
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String form(String pin, String err) {
        return page("Configurer la TV",
                (err == null ? "" : "<p class=e>" + esc(err) + "</p>")
                        + "<form method=post><input type=hidden name=k value=" + pin + ">"
                        + "<label for=d>Code ou adresse de la villa</label>"
                        + "<input id=d name=dossier autocapitalize=none autocomplete=off autofocus placeholder=\"ex. clo\">"
                        + "<p class=h>Un code (ex. clo) ou une adresse complète commençant par https://</p>"
                        + "<label for=p>Mot de passe de la villa</label>"
                        + "<input id=p name=mdp type=password autocomplete=current-password>"
                        + "<p class=h>Celui de la page reglages.php de la villa.</p>"
                        + "<button>Configurer la TV</button></form>");
    }

    private static String page(String title, String content) {
        return "<!doctype html><html lang=fr><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\">"
                + "<title>" + esc(title) + "</title><style>body{font:17px/1.45 -apple-system,Segoe UI,Roboto,sans-serif;"
                + "margin:0;background:#f4f4f2;color:#1b1b1b}main{max-width:460px;margin:0 auto;padding:28px 18px}"
                + "h1{font-size:24px}label{display:block;font-weight:600;margin:16px 0 6px}"
                + "input{width:100%;box-sizing:border-box;padding:12px;font:inherit;border:1px solid #ccc;border-radius:10px}"
                + "button{margin-top:16px;width:100%;padding:14px;font:600 17px inherit;color:#fff;background:#1f6f5c;"
                + "border:0;border-radius:12px}.h{color:#6b6b6b;font-size:14px}.e{color:#b3261e}"
                + "@media(prefers-color-scheme:dark){body{background:#141414;color:#f1f1f1}input{background:#1f1f1f;color:#f1f1f1;border-color:#333}}"
                + "</style><main><h1>" + esc(title) + "</h1>" + content + "</main></html>";
    }

    private static void send(OutputStream out, String html) throws Exception {
        byte[] b = html.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + b.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(b);
        out.flush();
    }

    // ------------------------------------------------------------------ C : code d'association (reglages.php)

    static void showPairCode(Activity a, Runnable onDone) {
        final String code = String.format(java.util.Locale.ROOT, "%06d", RND.nextInt(1_000_000));
        final boolean[] open = {true};
        final AlertDialog[] dlg = new AlertDialog[1];
        dlg[0] = showCard(a, "Associer cette TV", null, code.substring(0, 3) + " " + code.substring(3),
                "Ouvrez la page reglages.php de la villa sur un téléphone ou un ordinateur,\n"
                        + "puis « Associer une TV » et saisissez ce code.\nLe code est valable 10 minutes.",
                () -> open[0] = false);
        final String url = VillaFolder.pairUrl(code);
        final long until = System.currentTimeMillis() + 10 * 60_000L;
        new Thread(() -> {
            while (open[0] && System.currentTimeMillis() < until) {
                try {
                    Thread.sleep(3000);
                    String base = fetchPair(url);
                    if (base != null && !base.isEmpty()) {
                        main().post(() -> { if (open[0]) applied(a, dlg[0], base, onDone); });
                        return;
                    }
                } catch (Throwable ignored) {
                }
            }
            main().post(() -> { if (dlg[0] != null && dlg[0].isShowing()) dlg[0].dismiss(); });
        }, "villa-pair").start();
    }

    private static String fetchPair(String url) throws Exception {
        String target = url;
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(target).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(false);
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) return null;
                target = new URL(new URL(target), loc).toString();
                continue;
            }
            if (code != 200) { c.disconnect(); return null; }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = br.readLine()) != null && sb.length() < 4096) sb.append(l);
            } finally {
                c.disconnect();
            }
            return new org.json.JSONObject(sb.toString()).optString("base", "");
        }
        return null;
    }
}
