package com.techgenius.tvoslauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Tuile forcée d'ITVSTREAM (Next) : le logo officiel de l'appli (téléchargé une fois depuis sa fiche
 * Apple TV, jamais embarqué dans le launcher) sur fond noir, avec « Live TV » en dessous.
 */
final class ItvTile {
    static final String PKG = "com.itvstream";
    private static final String LOGO_URL = "https://is1-ssl.mzstatic.com/image/thumb/Purple211/v4/69/55/cb/"
            + "6955cb0a-c17c-ec60-542e-6b7096aa5ec5/App_Icon-marketing.lsr/800x452bb.png";
    private static volatile boolean loading = false;

    private ItvTile() { }

    private static File file(Context c) { return new File(c.getFilesDir(), "itvstream_tile_v1.png"); }

    /** Tuile prête, ou null (le téléchargement est alors lancé ; onReady sur le fil principal). */
    static Bitmap get(Context ctx, Runnable onReady) {
        File f = file(ctx);
        if (f.exists()) {
            Bitmap b = BitmapFactory.decodeFile(f.getPath());
            if (b != null) return b;
        }
        if (loading) return null;
        loading = true;
        final Context c = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                Bitmap logo = download();
                if (logo != null) {
                    Bitmap tile = compose(c, logo);
                    try (FileOutputStream out = new FileOutputStream(f)) {
                        tile.compress(Bitmap.CompressFormat.PNG, 100, out);
                    }
                    if (onReady != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(onReady);
                }
            } catch (Throwable ignored) {
            } finally {
                loading = false;
            }
        }, "itv-tile").start();
        return null;
    }

    private static Bitmap download() throws Exception {
        String target = LOGO_URL;
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(target).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(20_000);
            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null) return null;
                target = new URL(new URL(target), loc).toString();
                continue;
            }
            if (code != 200) { conn.disconnect(); return null; }
            try (InputStream in = conn.getInputStream()) {
                return BitmapFactory.decodeStream(in);
            } finally {
                conn.disconnect();
            }
        }
        return null;
    }

    /** Fond noir, logo recadré sur sa partie visible en haut, « Live TV » en dessous. */
    private static Bitmap compose(Context c, Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        int x0 = w, y0 = h, x1 = -1, y1 = -1;
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            src.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                int p = row[x];
                if (Color.red(p) + Color.green(p) + Color.blue(p) > 90) {
                    if (x < x0) x0 = x;
                    if (x > x1) x1 = x;
                    if (y < y0) y0 = y;
                    if (y > y1) y1 = y;
                }
            }
        }
        if (x1 < 0) { x0 = 0; y0 = 0; x1 = w - 1; y1 = h - 1; }
        int TW = 720, TH = 432;
        Bitmap out = Bitmap.createBitmap(TW, TH, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        cv.drawColor(Color.BLACK);
        float bw = x1 - x0 + 1, bh = y1 - y0 + 1;
        float s = Math.min(TW * 0.74f / bw, TH * 0.51f / bh);
        float dw = bw * s, dh = bh * s;
        float cy = TH * 0.39f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        cv.drawBitmap(src, new Rect(x0, y0, x1 + 1, y1 + 1),
                new RectF((TW - dw) / 2f, cy - dh / 2f, (TW + dw) / 2f, cy + dh / 2f), p);
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setColor(Color.WHITE);
        t.setTypeface(Ui.font(c, "semibold"));
        t.setTextSize(TH * 0.145f);
        t.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = t.getFontMetrics();
        float ty = TH * 0.80f - (fm.ascent + fm.descent) / 2f;
        cv.drawText("Live TV", TW / 2f, ty, t);
        return out;
    }
}
