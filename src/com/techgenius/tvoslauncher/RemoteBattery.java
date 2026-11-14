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
    private static volatile boolean dumpRunning;
    private static volatile String dump = "en cours… (rouvrir À propos)";
    /** Niveaux lus par le noyau (/sys/class/power_supply/hid-<adresse>-battery), via l'ADB local. */
    private static volatile String sysfs = null;
    private static volatile boolean sysfsRunning;

    private static void refreshSysfs(Context c) {
        if (sysfsRunning) return;
        sysfsRunning = true;
        new Thread(() -> {
            try {
                String out = LocalAdb.execShellOutput(c, "for d in /sys/class/power_supply/*; do v=$(cat $d/capacity 2>/dev/null); "
                        + "[ -n \"$v\" ] && echo \"${d##*/}=$v\"; done");
                sysfs = out == null ? "" : out.trim();
            } catch (Throwable t) {
                if (sysfs == null) sysfs = "";
            }
            sysfsRunning = false;
        }, "bt-sysfs").start();
    }

    /** Ajoute les niveaux du noyau (piles des télécommandes BLE que l'API Bluetooth ne donne pas). */
    private static void addSysfs(Context c, Map<String, Integer> lv) {
        refreshSysfs(c);
        String s = sysfs;
        if (s == null || s.isEmpty()) return;
        Map<String, String> names = new java.util.HashMap<>();
        try {
            android.bluetooth.BluetoothManager bm = (android.bluetooth.BluetoothManager) c.getSystemService(Context.BLUETOOTH_SERVICE);
            android.bluetooth.BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
            if (ad != null) for (android.bluetooth.BluetoothDevice d : ad.getBondedDevices())
                names.put(d.getAddress().toLowerCase(java.util.Locale.ROOT), clean(d.getName()));
        } catch (Throwable ignored) { }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("hid-([^=\\s]*?)-battery=(\\d+)").matcher(s);
        while (m.find()) {
            String id = m.group(1).toLowerCase(java.util.Locale.ROOT);
            String name = null;
            for (Map.Entry<String, String> n : names.entrySet()) if (id.contains(n.getKey())) name = n.getValue();
            if (name == null) name = "Télécommande";
            int v = Integer.parseInt(m.group(2));
            if (v >= 0 && v <= 100 && !lv.containsKey(name)) lv.put(name, v);
        }
    }

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
        addSysfs(c, lv);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : lv.entrySet()) {
            if (sb.length() > 0) sb.append(';');
            sb.append(e.getKey()).append(':').append(e.getValue());
        }
        return sb.toString();
    }

    /** Diagnostic pour Réglages › À propos : ce que le boîtier sait des télécommandes. */
    static String debug(Context c) {
        StringBuilder sb = new StringBuilder();
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            try {
                for (int id : InputDevice.getDeviceIds()) {
                    InputDevice d = InputDevice.getDevice(id);
                    if (d == null || d.isVirtual() || (d.getSources() & InputDevice.SOURCE_KEYBOARD) == 0) continue;
                    android.hardware.BatteryState b = d.getBatteryState();
                    boolean has = b != null && b.isPresent() && !Float.isNaN(b.getCapacity());
                    sb.append(d.getName()).append(" : ").append(has ? Math.round(b.getCapacity() * 100) + " %" : "pas de niveau").append('\n');
                }
            } catch (Throwable t) { sb.append("Entrées : ").append(t.getClass().getSimpleName()).append('\n'); }
        } else {
            sb.append("Android ").append(android.os.Build.VERSION.RELEASE).append(" : niveau des entrées non disponible\n");
        }
        try {
            android.bluetooth.BluetoothManager bm = (android.bluetooth.BluetoothManager) c.getSystemService(Context.BLUETOOTH_SERVICE);
            android.bluetooth.BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
            if (ad == null) sb.append("Bluetooth : absent");
            else {
                java.util.Set<android.bluetooth.BluetoothDevice> set = ad.getBondedDevices();
                if (set.isEmpty()) sb.append("Bluetooth : aucun appareil appairé");
                for (android.bluetooth.BluetoothDevice dev : set) {
                    String r;
                    try {
                        int level = (Integer) dev.getClass().getMethod("getBatteryLevel").invoke(dev);
                        r = level >= 0 ? level + " %" : "pas de niveau (" + level + ")";
                    } catch (Throwable t) {
                        Throwable x = t.getCause() != null ? t.getCause() : t;
                        r = "erreur " + x.getClass().getSimpleName() + (x.getMessage() != null ? " : " + x.getMessage() : "");
                    }
                    sb.append("BT ").append(dev.getName()).append(" : ").append(r).append('\n');
                }
                // Ce que le système Bluetooth dit des piles (via l'ADB local).
                if (!dumpRunning) {
                    dumpRunning = true;
                    new Thread(() -> {
                        try {
                            String out = LocalAdb.execShellOutput(c, "dumpsys bluetooth_manager | grep -i -m 6 batt");
                            dump = out == null || out.trim().isEmpty() ? "rien" : out.trim();
                        } catch (Throwable t) {
                            dump = "échec : " + t.getMessage();
                        }
                        dumpRunning = false;
                    }, "bt-dump").start();
                }
                sb.append("dumpsys : ").append(dump).append('\n');
                refreshSysfs(c);
                sb.append("noyau : ").append(sysfs == null ? "en cours… (rouvrir À propos)" : sysfs.isEmpty() ? "rien" : sysfs.replace('\n', ' ')).append('\n');
            }
        } catch (SecurityException se) {
            sb.append("Bluetooth : autorisation manquante");
        } catch (Throwable t) {
            sb.append("Bluetooth : ").append(t.getClass().getSimpleName());
        }
        return sb.toString().trim();
    }

    private static String clean(String n) {
        return (n == null || n.trim().isEmpty() ? "Télécommande" : n.trim()).replaceAll("[;:]", " ");
    }
}
