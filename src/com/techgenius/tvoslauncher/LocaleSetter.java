package com.techgenius.tvoslauncher;

import android.content.res.Configuration;
import android.os.LocaleList;

import java.lang.reflect.Method;

/**
 * Change la langue de toute la TV. Ce n'est pas du code d'appli : le launcher le lance par ADB avec
 * app_process (droits du shell, qui peut modifier la configuration système), par exemple :
 *   CLASSPATH=<apk> app_process /system/bin com.techgenius.tvoslauncher.LocaleSetter en-US
 */
public final class LocaleSetter {
    private LocaleSetter() { }

    public static void main(String[] args) {
        String tag = args.length > 0 ? args[0] : "en-US";
        try {
            Object am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
            Configuration conf = (Configuration) am.getClass().getMethod("getConfiguration").invoke(am);
            conf.setLocales(LocaleList.forLanguageTags(tag));
            Configuration.class.getField("userSetLocale").setBoolean(conf, true);
            Method update = am.getClass().getMethod("updatePersistentConfiguration", Configuration.class);
            update.invoke(am, conf);
            System.out.println("LOCALE_OK " + tag);
        } catch (Throwable t) {
            System.out.println("LOCALE_ERR " + t);
        }
    }
}
