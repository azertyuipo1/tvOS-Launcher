package com.techgenius.tvoslauncher;

import android.content.Context;
import android.view.InputDevice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Niveau des piles des télécommandes Bluetooth, envoyé à la page de la villa (« Télécommande : 85 % »).
 * Deux sources : la batterie des périphériques d'entrée (Android 12+) et celle des appareils Bluetooth
 * appairés (méthode cachée getBatteryLevel). Une télécommande infrarouge n'a pas de niveau : rien n'est envoyé.
 */
final class RemoteBattery {
    private RemoteBattery() { }

    private static boolean grantTried;

    /** « Nom:85;Nom 2:40 » ou "" si aucun niveau connu. */
    static String summary(Context c) {
        Map<String, Integer> lv = new LinkedHashMap<>();
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            try {
                for (int id : InputDevice.getDeviceIds()) {
                    InputDevice d = InputDevice.getDevice(id);
                    if (d == null || d.isVirtual()) continue;
                    android.hardware.BatteryState b = d.getBatteryState();
                    if (b == null || !b.isPresent()) continue;
                    float cap = b.getCapacity();
                    if (Float.isNaN(cap) || cap < 0) continue;
                    lv.put(clean(d.getName()), Math.round(cap * 100));
                }
            } catch (Throwable ignored) { }
        }
        try {
            android.bluetooth.BluetoothManager bm = (android.bluetooth.BluetoothManager) c.getSystemService(Context.BLUETOOTH_SERVICE);
            android.bluetooth.BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
            if (ad != null) {
                for (android.bluetooth.BluetoothDevice dev : ad.getBondedDevices()) {
                    int level = -1;
                    try {
                        level = (Integer) dev.getClass().getMethod("getBatteryLevel").invoke(dev);
                    } catch (Throwable ignored) { }
                    if (level < 0 || level > 100) continue;
                    String n = clean(dev.getName());
                    if (!lv.containsKey(n)) lv.put(n, level);
                }
            }
        } catch (SecurityException se) {
            // Autorisation Bluetooth pas encore accordée : on la demande une fois via l'ADB local.
            if (!grantTried) {
                grantTried = true;
                new Thread(() -> {
                    try {
                        LocalAdb.execShellOutput(c, "pm grant " + c.getPackageName() + " android.permission.BLUETOOTH_CONNECT");
                    } catch (Throwable ignored) { }
                }, "bt-grant").start();
            }
        } catch (Throwable ignored) { }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : lv.entrySet()) {
            if (sb.length() > 0) sb.append(';');
            sb.append(e.getKey()).append(':').append(e.getValue());
        }
        return sb.toString();
    }

    private static String clean(String n) {
        return (n == null || n.trim().isEmpty() ? "Télécommande" : n.trim()).replaceAll("[;:]", " ");
    }
}
