package com.techgenius.tvoslauncher;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/**
 * Message temporaire de l'accueil (« 🎂 Happy birthday Debby! »), saisi sur la page de la villa :
 * « messages: [{"e":"🎂","t":"Happy birthday {nom}!","du":"2026-12-20","au":"2026-12-20","fin":"21"}] ».
 * Affiché du premier au dernier jour inclus ; « fin » = heure à laquelle il disparaît le dernier jour.
 */
final class VillaMessage {
    private VillaMessage() { }

    static final String K_MESSAGES = "messages";

    /**
     * Messages à afficher maintenant (emoji + texte), 2 au plus : l'événement d'un seul jour d'abord,
     * puis celui de plusieurs jours. Liste vide s'il n'y en a pas.
     */
    static java.util.List<String> current(Context c, RentalReset.Stay stay) {
        java.util.List<String> unJour = new java.util.ArrayList<>(), plusieurs = new java.util.ArrayList<>();
        String raw = VillaFolder.get(c, K_MESSAGES);
        if (raw.isEmpty()) return unJour;
        try {
            JSONArray a = new JSONArray(raw);
            Calendar now = Calendar.getInstance();
            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now.getTime());
            int hour = now.get(Calendar.HOUR_OF_DAY);
            for (int i = 0; i < a.length(); i++) {
                JSONObject m = a.optJSONObject(i);
                if (m == null) continue;
                String du = m.optString("du", ""), au = m.optString("au", du), fin = m.optString("fin", "");
                if (du.compareTo(today) > 0 || au.compareTo(today) < 0) continue;
                if (!fin.isEmpty() && au.equals(today)) {
                    try { if (hour >= Integer.parseInt(fin)) continue; } catch (NumberFormatException ignored) { }
                }
                String nom = stay != null && stay.name != null && !stay.name.isEmpty() ? GuestName.format(stay.name) : "";
                String t = m.optString("t", "").replace("{nom}", nom).replaceAll("\\s+", " ").trim();
                if (t.isEmpty()) continue;
                String e = m.optString("e", "").trim();
                (du.equals(au) ? unJour : plusieurs).add(e.isEmpty() ? t : e + "  " + t);
            }
        } catch (Exception ignored) { }
        unJour.addAll(plusieurs);
        return unJour.size() > 2 ? unJour.subList(0, 2) : unJour;
    }
}
