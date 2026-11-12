package com.techgenius.tvoslauncher;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Test des touches (Réglages › À propos) : affiche chaque touche reçue — nom, code, scancode, télécommande —
 * et ce qu'elle fait en mode client. Les touches sont captées par le service d'accessibilité (Accueil, volume…
 * compris) et ne font rien pendant le test, sauf l'alimentation. Retour deux fois de suite pour quitter.
 */
public class KeyTestActivity extends Activity {
    static volatile KeyTestActivity instance;
    private static long lastEventTime = -1;
    private static int lastCode = -1;

    private LinearLayout list;
    private long lastBackAt = 0;
    private int count = 0;

    /** Appelée par le service d'accessibilité : true = touche avalée par le test. */
    static boolean capture(KeyEvent e) {
        KeyTestActivity a = instance;
        if (a == null) return false;
        String n = KeyEvent.keyCodeToString(e.getKeyCode());
        if (n.contains("POWER") || n.contains("SLEEP") || n.contains("WAKEUP")) {
            a.runOnUiThread(() -> a.record(e));
            return false; // l'alimentation reste utilisable
        }
        a.runOnUiThread(() -> a.record(e));
        return true;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int u = Ui.px(this, 1);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF121212);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(56 * u, 36 * u, 56 * u, 24 * u);
        root.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView t = new TextView(this);
        t.setText("Test des touches");
        t.setTextColor(Color.WHITE);
        t.setTypeface(Ui.font(this, "semibold"));
        Ui.textSize(t, 22);
        col.addView(t);
        TextView h = new TextView(this);
        h.setText("Appuyez sur chaque touche de la télécommande, l'une après l'autre. Pendant le test, les touches "
                + "ne font rien (sauf l'alimentation). Appuyez deux fois sur Retour pour quitter.");
        h.setTextColor(0xA6FFFFFF);
        h.setTypeface(Ui.font(this, "regular"));
        Ui.textSize(h, 11.5f);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = 6 * u;
        hp.bottomMargin = 18 * u;
        col.addView(h, hp);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        col.addView(list);
        setContentView(root);
    }

    /**
     * Volume : sur un boîtier, les touches de volume sont souvent traitées par le système (HDMI-CEC) avant
     * d'arriver ici, ou envoyées directement à la TV en infrarouge. On affiche donc aussi tout changement du
     * volume du boîtier.
     */
    private final android.content.BroadcastReceiver volume = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context c, android.content.Intent i) {
            if (i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) != android.media.AudioManager.STREAM_MUSIC) return;
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            addLine("Volume du boîtier modifié : " + am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                    + " / " + am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                    + (am.isStreamMute(android.media.AudioManager.STREAM_MUSIC) ? " (muet)" : ""),
                    "      (la touche n'est pas passée par le launcher, le système l'a traitée directement)");
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        instance = this;
        try { registerReceiver(volume, new android.content.IntentFilter("android.media.VOLUME_CHANGED_ACTION")); } catch (Throwable ignored) { }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (instance == this) instance = null;
        try { unregisterReceiver(volume); } catch (Throwable ignored) { }
    }

    /** Sans service d'accessibilité, les touches arrivent ici (sauf Accueil et volume). */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        record(e);
        return true;
    }

    private void record(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return;
        // Même touche vue par le service puis par l'écran : une seule ligne.
        if (e.getEventTime() == lastEventTime && e.getKeyCode() == lastCode) return;
        lastEventTime = e.getEventTime();
        lastCode = e.getKeyCode();
        int code = e.getKeyCode();
        if (e.getRepeatCount() > 0) {
            if (e.getRepeatCount() == 1 && list.getChildCount() > 0) {
                TextView first = (TextView) list.getChildAt(0);
                first.append("  · maintenue");
            }
            return;
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            long now = SystemClock.uptimeMillis();
            if (now - lastBackAt < 1500) { finish(); return; }
            lastBackAt = now;
        }
        InputDevice d = e.getDevice();
        String dev = d == null ? "?" : d.getName();
        String name = KeyEvent.keyCodeToString(code).replace("KEYCODE_", "");
        addLine(name + "   —   code " + code + " · scan " + e.getScanCode() + " · " + dev,
                "      en mode client : " + GuestLock.describeKey(this, code));
    }

    private void addLine(String l1, String l2) {
        count++;
        TextView row = new TextView(this);
        row.setText(count + ".  " + l1 + "\n" + l2);
        row.setTextColor(Color.WHITE);
        row.setTypeface(Ui.font(this, "regular"));
        Ui.textSize(row, 12.5f);
        row.setGravity(Gravity.START);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.px(this, 9);
        list.addView(row, 0, lp);
        for (int i = 1; i < list.getChildCount(); i++) ((TextView) list.getChildAt(i)).setTextColor(0x8CFFFFFF);
        while (list.getChildCount() > 8) list.removeViewAt(list.getChildCount() - 1);
    }
}
