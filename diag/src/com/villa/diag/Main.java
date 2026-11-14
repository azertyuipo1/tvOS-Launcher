package com.villa.diag;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.UUID;

/** Diagnostic hors production : lit les piles des télécommandes Bluetooth par le service Batterie (GATT 0x180F). */
public class Main extends Activity {
    static final UUID BAS = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    static final UUID LVL = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");
    TextView out;
    final Handler h = new Handler(Looper.getMainLooper());

    void log(String s) {
        Log.i("DiagPiles", s);
        h.post(() -> out.append(s + "\n"));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        out = new TextView(this);
        out.setTextSize(18);
        out.setPadding(60, 40, 60, 40);
        ScrollView sv = new ScrollView(this);
        sv.addView(out);
        setContentView(sv);
        log("Diag piles — Android " + android.os.Build.VERSION.RELEASE);
        try {
            BluetoothAdapter ad = ((BluetoothManager) getSystemService(BLUETOOTH_SERVICE)).getAdapter();
            for (BluetoothDevice d : ad.getBondedDevices()) {
                int lvl = -9;
                try { lvl = (Integer) d.getClass().getMethod("getBatteryLevel").invoke(d); } catch (Throwable t) { log("getBatteryLevel : " + t); }
                log("Appareil " + d.getName() + " type=" + d.getType() + " getBatteryLevel=" + lvl);
                gatt(d);
            }
        } catch (Throwable t) {
            log("Erreur : " + t);
        }
    }

    void gatt(BluetoothDevice d) {
        log("  connexion GATT à " + d.getName() + "…");
        d.connectGatt(this, false, new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
                log("  " + d.getName() + " : état " + state + " (statut " + status + ")");
                if (state == 2) g.discoverServices();
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt g, int status) {
                StringBuilder sb = new StringBuilder();
                for (BluetoothGattService s : g.getServices()) sb.append(s.getUuid().toString(), 4, 8).append(' ');
                log("  services : " + sb);
                BluetoothGattService s = g.getService(BAS);
                if (s == null) { log("  pas de service Batterie visible"); return; }
                BluetoothGattCharacteristic c = s.getCharacteristic(LVL);
                if (c == null) { log("  pas de niveau dans le service Batterie"); return; }
                log("  lecture du niveau : " + g.readCharacteristic(c));
            }

            @Override
            public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
                byte[] v = c.getValue();
                log("  >>> PILES " + d.getName() + " : " + (v != null && v.length > 0 ? (v[0] & 0xff) + " %" : "?") + " (statut " + status + ")");
            }
        }, BluetoothDevice.TRANSPORT_LE);
    }
}
