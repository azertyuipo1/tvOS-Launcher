package com.techgenius.tvoslauncher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.List;

/** Ce qui manque à l'installation de la TV (liste vide = installation complète). */
final class InstallCheck {
    private InstallCheck() { }

    static boolean accessibilityEnabled(Context c) {
        try {
            String on = android.provider.Settings.Secure.getString(c.getContentResolver(),
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return on != null && on.contains(c.getPackageName() + "/")
                    && android.provider.Settings.Secure.getInt(c.getContentResolver(),
                            android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1;
        } catch (Throwable t) {
            return false;
        }
    }

    static List<String> missing(Context c) {
        List<String> miss = new ArrayList<>();
        // Service activé dans les réglages mais pas encore relancé (juste après une mise à jour, au réveil) : pas un souci.
        if (PowerService.instance == null && !accessibilityEnabled(c)) miss.add("service d'accessibilité");
        if (!LocalAdb.isPaired(c)) miss.add("ADB local");
        try {
            ResolveInfo home = c.getPackageManager().resolveActivity(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
            if (home == null || home.activityInfo == null || !c.getPackageName().equals(home.activityInfo.packageName)) {
                miss.add("accueil d'origine à désactiver");
            }
        } catch (Throwable ignored) { }
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26 && !c.getPackageManager().canRequestPackageInstalls()) {
                miss.add("autorisation de mise à jour");
            }
        } catch (Throwable ignored) { }
        if (!GuestLock.isEnabled(c)) miss.add("verrou clients");
        if (!VillaFolder.isConfigured(c)) miss.add("villa non configurée");
        return miss;
    }

    /** Applis de l'accueil de la villa qui ne sont pas encore installées (noms). */
    static List<String> missingApps(Context c) {
        List<String> out = new ArrayList<>();
        try {
            MainActivity.refreshVillaApps(c);
            PackageManager pm = c.getPackageManager();
            for (String[] app : MainActivity.VILLA_APPS) {
                boolean found = false;
                for (String pkg : app[2].split("\\|")) {
                    try { pm.getPackageInfo(pkg, 0); found = true; break; } catch (Exception ignored) { }
                }
                if (!found) out.add(app[0]);
            }
        } catch (Throwable ignored) { }
        return out;
    }

    static String summary(Context c) {
        List<String> miss = missing(c);
        return miss.isEmpty() ? "✓ complète" : "✗ manque : " + android.text.TextUtils.join(", ", miss);
    }
}
