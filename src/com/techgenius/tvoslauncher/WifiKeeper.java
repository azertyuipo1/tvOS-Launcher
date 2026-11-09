package com.techgenius.tvoslauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.widget.EditText;
import android.widget.FrameLayout;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Google TV : le Wi-Fi saisi pendant l'installation appartient à l'assistant Google (setupwraith) et
 * disparaît quand on le désactive. On le réenregistre au nom du système (oubli + reconnexion via l'ADB
 * local, comme le ferait l'utilisateur dans les Paramètres) : il suffit de taper le mot de passe.
 */
final class WifiKeeper {
    private WifiKeeper() { }

    interface Done { void run(boolean ok); }

    /** Réseau Wi-Fi actuellement connecté, ou "" (lu par l'ADB local, sans permission de localisation). */
    static String currentSsid(Activity a) {
        try {
            String s = LocalAdb.execShellOutput(a, "cmd wifi status");
            Matcher m = Pattern.compile("connected to \"(.+?)\"").matcher(s == null ? "" : s);
            if (m.find()) return m.group(1);
        } catch (Throwable ignored) { }
        try {
            String s = LocalAdb.execShellOutput(a, "dumpsys wifi | grep -m1 'mWifiInfo SSID'");
            Matcher m = Pattern.compile("SSID: \"(.+?)\"").matcher(s == null ? "" : s);
            if (m.find()) return m.group(1);
        } catch (Throwable ignored) { }
        return "";
    }

    private static String q(String s) { return "'" + s.replace("'", "'\\''") + "'"; }

    /** Oublie puis réenregistre le réseau ; null si c'est bon, sinon le message d'erreur. */
    static String resave(Activity a, String ssid, String pass) {
        try {
            String list = LocalAdb.execShellOutput(a, "cmd wifi list-networks");
            for (String line : (list == null ? "" : list).split("\\n")) {
                Matcher m = Pattern.compile("^\\s*(\\d+)\\s+(.+?)\\s+(\\S+)\\s*$").matcher(line);
                if (m.find() && m.group(2).equals(ssid)) {
                    LocalAdb.execShellOutput(a, "cmd wifi forget-network " + m.group(1));
                }
            }
            String out = LocalAdb.execShellOutput(a, "cmd wifi connect-network " + q(ssid)
                    + (pass.isEmpty() ? " open" : " wpa2 " + q(pass)));
            if (out != null && out.toLowerCase(java.util.Locale.ROOT).matches("(?s).*(unknown|invalid|error|exception).*")) {
                return out.trim();
            }
            for (int i = 0; i < 10; i++) {
                Thread.sleep(2000L);
                if (ssid.equals(currentSsid(a))) {
                    String owners = LocalAdb.execShellOutput(a, "dumpsys wifi | grep -o 'cname=[^ ]*'");
                    if (owners == null || !owners.contains("com.google.android.tungsten.setupwraith")) return null;
                }
            }
            return "le boîtier ne s'est pas reconnecté (mot de passe incorrect ?)";
        } catch (Throwable t) {
            return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        }
    }

    /** Demande le mot de passe et réenregistre le Wi-Fi ; done(true) si le Wi-Fi est désormais protégé. */
    static void ask(Activity a, Done done) {
        new Thread(() -> {
            String ssid = currentSsid(a);
            a.runOnUiThread(() -> {
                if (a.isFinishing()) return;
                if (ssid.isEmpty()) { done.run(false); return; }
                EditText in = new EditText(a);
                in.setSingleLine(true);
                in.setHint("Mot de passe du Wi-Fi");
                in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
                FrameLayout box = new FrameLayout(a);
                int pad = (int) (14 * a.getResources().getDisplayMetrics().density);
                box.setPadding(pad, pad / 2, pad, 0);
                box.addView(in);
                AlertDialog dlg = new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("Garder le Wi-Fi « " + ssid + " »")
                        .setMessage("Ce réseau a été enregistré par l'installation Google : il serait effacé quand "
                                + "l'accueil Google est désactivé. Tapez son mot de passe pour le réenregistrer "
                                + "automatiquement (laisser vide pour un réseau sans mot de passe).")
                        .setView(box)
                        .setPositiveButton("Réenregistrer", (d, w) -> {
                            String pass = in.getText().toString();
                            AlertDialog wait = new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                                    .setTitle("Wi-Fi").setMessage("Réenregistrement du Wi-Fi…").setCancelable(false).show();
                            new Thread(() -> {
                                String err = resave(a, ssid, pass);
                                a.runOnUiThread(() -> {
                                    try { wait.dismiss(); } catch (Throwable ignored) { }
                                    if (a.isFinishing()) return;
                                    if (err == null) {
                                        android.widget.Toast.makeText(a, "✓ Wi-Fi « " + ssid + " » réenregistré", android.widget.Toast.LENGTH_LONG).show();
                                        done.run(true);
                                    } else {
                                        new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                                                .setTitle("Wi-Fi non réenregistré")
                                                .setMessage(err)
                                                .setPositiveButton("Réessayer", (d2, w2) -> ask(a, done))
                                                .setNegativeButton("Autre méthode", (d2, w2) -> done.run(false))
                                                .show();
                                    }
                                });
                            }, "wifi-keeper").start();
                        })
                        .setNegativeButton("Autre méthode", (d, w) -> done.run(false))
                        .show();
                Ui.setupDialogEditText(in, dlg);
                in.requestFocus();
            });
        }, "wifi-ssid").start();
    }
}
