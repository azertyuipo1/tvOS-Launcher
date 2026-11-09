package com.techgenius.tvoslauncher;

/**
 * Lancé via l'ADB local (app_process, utilisateur shell qui a le droit SET_TIME_ZONE) :
 * règle le fuseau horaire du système, comme le ferait Paramètres → Date et heure.
 */
public final class TimeZoneSetter {
    public static void main(String[] args) {
        try {
            String tz = args.length > 0 ? args[0] : "America/La_Paz";
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Object binder = sm.getMethod("getService", String.class).invoke(null, "alarm");
            Class<?> stub = Class.forName("android.app.IAlarmManager$Stub");
            Object am = stub.getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
            am.getClass().getMethod("setTimeZone", String.class).invoke(am, tz);
            System.out.println("TZ_OK " + tz);
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            System.out.println("TZ_ERR " + c);
        }
    }
}
