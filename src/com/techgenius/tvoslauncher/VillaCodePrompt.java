package com.techgenius.tvoslauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.widget.FrameLayout;

/** Saisie du code villa (nom court, puis code à 6 chiffres vérifié par la page de la villa). */
final class VillaCodePrompt {
    private VillaCodePrompt() { }

    /** changed : la TV est désormais rattachée à une autre villa. */
    interface Saved { void run(boolean changed); }

    private static final Handler H = new Handler(Looper.getMainLooper());

    private static AlertDialog.Builder dialog(Activity a, String title) {
        return new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(title);
    }

    static void start(Activity a, Saved saved) {
        android.widget.EditText in = new android.widget.EditText(a);
        in.setSingleLine(true);
        in.setHint("azu");
        // Code de la villa : 3 lettres ou chiffres, rien d'autre (plus d'adresse externe).
        in.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        in.setFilters(new android.text.InputFilter[]{ new android.text.InputFilter.LengthFilter(3),
                (src, st, en, dst, ds, de) -> {
                    StringBuilder ok = new StringBuilder();
                    for (int i = st; i < en; i++) if (Character.isLetterOrDigit(src.charAt(i)) && src.charAt(i) < 128) ok.append(src.charAt(i));
                    return ok.length() == en - st ? null : ok;
                } });
        String cur = VillaFolder.getBase(a);
        in.setText(VillaFolder.isCode(cur) ? cur : "");
        int pad = (int) (14 * a.getResources().getDisplayMetrics().density);
        FrameLayout box = new FrameLayout(a);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(in);
        // Le clavier peut envoyer « OK » deux fois (appui + relâchement) : on ne valide qu'une fois.
        final boolean[] done = {false};
        AlertDialog dlg = dialog(a, "Code villa")
                .setMessage("Code de la villa en 3 lettres (ex. « azu »).")
                .setView(box)
                .setPositiveButton("Suivant", (d, w) -> {
                    if (done[0]) return;
                    done[0] = true;
                    folderEntered(a, in.getText().toString(), saved);
                })
                .setNegativeButton("Annuler", null)
                .show();
        Ui.setupDialogEditText(in, dlg);
        // « OK / Terminé » du clavier valide directement.
        in.setOnEditorActionListener((v, actionId, ev) -> {
            if (done[0]) return true;
            done[0] = true;
            dlg.dismiss();
            folderEntered(a, in.getText().toString(), saved);
            return true;
        });
        in.requestFocus();
    }

    /** Code à 3 lettres → code à 6 chiffres demandé ; même villa → rien ne change. */
    private static void folderEntered(Activity a, String input, Saved saved) {
        String v = input == null ? "" : input.trim();
        if (v.isEmpty()) return;
        if (!VillaFolder.isCode(v)) {
            android.widget.Toast.makeText(a, "Le code villa comporte 3 lettres (ex. « azu »).", android.widget.Toast.LENGTH_LONG).show();
            H.postDelayed(() -> { if (!a.isFinishing()) start(a, saved); }, 450L);
            return;
        }
        if (v.equalsIgnoreCase(VillaFolder.getBase(a))) {
            VillaFolder.setBase(a, v);
            saved.run(false);
            return;
        }
        // Petit délai : le relâchement de « OK » ne doit pas arriver dans la fenêtre du code.
        H.postDelayed(() -> { if (!a.isFinishing()) promptVillaCode(a, v, null, saved); }, 450L);
    }

    private static void promptVillaCode(Activity a, String shortName, String error, Saved saved) {
        android.widget.EditText in = new android.widget.EditText(a);
        in.setSingleLine(true);
        in.setHint("123456");
        in.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        in.setFilters(new android.text.InputFilter[]{ new android.text.InputFilter.LengthFilter(6) });
        int pad = (int) (14 * a.getResources().getDisplayMetrics().density);
        FrameLayout box = new FrameLayout(a);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(in);
        String msg = (error == null ? "" : "✗ " + error + "\n\n")
                + "Le code à 6 chiffres est affiché en haut de la page de configuration de la villa.";
        final boolean[] done = {false};
        AlertDialog dlg = dialog(a, "Code de la villa « " + shortName.toUpperCase(java.util.Locale.ROOT) + " »")
                .setMessage(msg)
                .setView(box)
                .setPositiveButton("Valider", (d, w) -> {
                    if (done[0]) return;
                    done[0] = true;
                    checkVillaCode(a, shortName, in.getText().toString(), saved);
                })
                .setNegativeButton("Annuler", null)
                .show();
        Ui.setupDialogEditText(in, dlg);
        in.setOnEditorActionListener((v, actionId, ev) -> {
            if (done[0]) return true;
            done[0] = true;
            dlg.dismiss();
            checkVillaCode(a, shortName, in.getText().toString(), saved);
            return true;
        });
        in.requestFocus();
    }

    private static void checkVillaCode(Activity a, String shortName, String code, Saved saved) {
        String c = code == null ? "" : code.replaceAll("\\D", "");
        if (c.length() != 6) { promptVillaCode(a, shortName, "Le code comporte 6 chiffres.", saved); return; }
        final boolean[] over = {false};
        AlertDialog wait = dialog(a, "Code de la villa").setMessage("Vérification du code…")
                .setNegativeButton("Annuler", (d, w) -> over[0] = true).setCancelable(false).show();
        // Sans réponse au bout de 40 s : on le dit au lieu d'attendre indéfiniment.
        H.postDelayed(() -> {
            if (over[0] || a.isFinishing()) return;
            over[0] = true;
            try { wait.dismiss(); } catch (Throwable ignored) { }
            promptVillaCode(a, shortName, "Pas de réponse du serveur de la villa (la TV n'arrive pas à le joindre).", saved);
        }, 40_000L);
        new Thread(() -> {
            VillaSetup.Check r = VillaSetup.verifyCode(shortName, c);
            a.runOnUiThread(() -> {
                if (over[0] || a.isFinishing()) return;
                over[0] = true;
                try { wait.dismiss(); } catch (Throwable ignored) { }
                if (r.base == null) {
                    H.postDelayed(() -> { if (!a.isFinishing()) promptVillaCode(a, shortName, r.message(), saved); }, 300L);
                    return;
                }
                String before = VillaFolder.getBase(a);
                VillaFolder.setBase(a, shortName);
                VillaFolder.setResolved(a, r.base);
                android.widget.Toast.makeText(a, "✓ Villa configurée : " + VillaFolder.getBase(a),
                        android.widget.Toast.LENGTH_LONG).show();
                saved.run(!before.equals(VillaFolder.getBase(a)));
            });
        }, "villa-code").start();
    }
}
