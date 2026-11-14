package com.techgenius.tvoslauncher;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.util.UUID;

/**
 * Piles des télécommandes Bluetooth (RemoteG10 des Mecool…), lues directement sur la télécommande par le
 * service Batterie du Bluetooth basse consommation (GATT 0x180F / 0x2A19). Android ne transmet pas ce niveau
 * aux applis, mais la télécommande le donne à qui le lui demande. Lecture au plus toutes les 6 h, niveau
 * mémorisé et envoyé à la page de la villa (« Nom:64;Nom 2:80 »).
 */
final class RemoteBattery {
    private RemoteBattery() { }

    private static final UUID BAS = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    private static final UUID LEVEL = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");
    private static final long EVERY_MS = 6 * 3600_000L;
    private static final long KEEP_MS = 7 * 86400_000L;
    private static boolean grantTried;

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("remote_battery", Context.MODE_PRIVATE);
    }

    /** Niveaux mémorisés (lus ces 7 derniers jours) : « Nom:64;Nom 2:80 », ou "". */
    static String summary(Context ctx) {
        Context c = ctx.getApplicationContext();
        refresh(c);
        StringBuilder sb = new StringBuilder();
        long now = System.currentTimeMillis();
        for (String k : prefs(c).getAll().keySet()) {
            if (!k.startsWith("v:")) continue;
            String name = k.substring(2);
            long at = prefs(c).getLong("t:" + name, 0L);
            if (now - at > KEEP_MS) continue;
            if (sb.length() > 0) sb.append(';');
            sb.append(name).append(':').append(prefs(c).getInt(k, 0));
        }
        return sb.toString();
    }

    /** Lance une lecture (en arrière-plan) si la dernière date de plus de 6 h. */
    static void refresh(Context c) {
        if (System.currentTimeMillis() - prefs(c).getLong("last_try", 0L) < EVERY_MS) return;
        if (android.os.Build.VERSION.SDK_INT >= 31
                && c.checkSelfPermission("android.permission.BLUETOOTH_CONNECT") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (!grantTried) {
                grantTried = true; // autorisation Bluetooth accordée via l'ADB local ; lecture au prochain passage
                new Thread(() -> {
                    try {
                        LocalAdb.execShellOutput(c, "pm grant " + c.getPackageName() + " android.permission.BLUETOOTH_CONNECT");
                    } catch (Throwable ignored) { }
                }, "bt-grant").start();
            }
            return;
        }
        prefs(c).edit().putLong("last_try", System.currentTimeMillis()).apply();
        try {
            BluetoothManager bm = (BluetoothManager) c.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
            if (ad == null || !ad.isEnabled()) return;
            for (BluetoothDevice d : ad.getBondedDevices()) {
                int type = d.getType();
                if (type == BluetoothDevice.DEVICE_TYPE_LE || type == BluetoothDevice.DEVICE_TYPE_DUAL) read(c, d);
            }
        } catch (Throwable ignored) { }
    }

    private static void read(Context c, BluetoothDevice d) {
        final Handler h = new Handler(Looper.getMainLooper());
        final String name = clean(d.getName());
        final BluetoothGatt[] ref = new BluetoothGatt[1];
        final boolean[] done = new boolean[1];
        final Runnable close = () -> {
            if (done[0]) return;
            done[0] = true;
            try { if (ref[0] != null) { ref[0].disconnect(); ref[0].close(); } } catch (Throwable ignored) { }
        };
        try {
            ref[0] = d.connectGatt(c, false, new BluetoothGattCallback() {
                @Override
                public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
                    if (state == BluetoothGatt.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) g.discoverServices();
                    else h.post(close);
                }

                @Override
                public void onServicesDiscovered(BluetoothGatt g, int status) {
                    BluetoothGattService s = g.getService(BAS);
                    BluetoothGattCharacteristic ch = s == null ? null : s.getCharacteristic(LEVEL);
                    if (ch == null || !g.readCharacteristic(ch)) h.post(close);
                }

                @Override
                public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic ch, int status) {
                    byte[] v = ch.getValue();
                    if (status == BluetoothGatt.GATT_SUCCESS && v != null && v.length > 0) {
                        int pct = v[0] & 0xff;
                        if (pct <= 100) prefs(c).edit().putInt("v:" + name, pct)
                                .putLong("t:" + name, System.currentTimeMillis()).apply();
                    }
                    h.post(close);
                }
            }, BluetoothDevice.TRANSPORT_LE);
        } catch (Throwable t) {
            close.run();
            return;
        }
        h.postDelayed(close, 45_000L); // télécommande endormie : on abandonne, nouvel essai dans 6 h
    }

    private static String clean(String n) {
        return (n == null || n.trim().isEmpty() ? "Télécommande" : n.trim()).replaceAll("[;:]", " ");
    }
}
