package com.techgenius.tvoslauncher;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mise en forme du nom des clients sur l'accueil (en anglais) :
 *  « Smith » → « The Smith Family » ; « M. et Mme Smith », « Mr and Mrs Smith » → « Mr. & Mrs. Smith »
 *  (aussi Mr. & Mr., Mrs. & Mrs., Miss, Ms., Dr.) ; « Smith, John » → « John Smith » ;
 *  prénom + nom, « John & Mary Smith », « The … Family » : gardés. Majuscules remises en ordre.
 */
final class GuestName {
    private GuestName() { }

    private static final String TITLE = "(mr|mrs|mme|mlle|miss|ms|dr|m)\\.?";
    private static final Pattern TITLES = Pattern.compile(
            "^" + TITLE + "(?:\\s*(?:&|\\+|and|et)\\s*" + TITLE + ")?\\s+(.+)$", Pattern.CASE_INSENSITIVE);

    static String format(String raw) {
        if (raw == null) return "";
        String s = raw.trim().replaceAll("\\s+", " ").replaceAll("\\.+$", "");
        if (s.isEmpty()) return s;

        // « Nom, Prénom » → « Prénom Nom »
        Matcher comma = Pattern.compile("^([^,&+/]+),\\s*([^,&+/]+)$").matcher(s);
        if (comma.matches() && !TITLES.matcher(s).matches()) {
            String last = comma.group(1).trim(), first = comma.group(2).trim();
            if (last.split(" ").length <= 2 && first.split(" ").length <= 2) s = first + " " + last;
        }

        Matcher t = TITLES.matcher(s);
        if (t.matches()) {
            String rest = fixCase(t.group(3).trim());
            String a = title(t.group(1)), b = t.group(2) == null ? null : title(t.group(2));
            return (b == null ? a : a + " & " + b) + " " + rest;
        }
        if (!s.contains(" ") && !s.matches(".*[&+/].*")) return "The " + fixCase(s) + " Family";
        return fixCase(s);
    }

    private static String title(String t) {
        switch (t.toLowerCase(Locale.ROOT)) {
            case "m": case "mr": return "Mr.";
            case "mrs": case "mme": return "Mrs.";
            case "mlle": case "miss": return "Miss";
            case "ms": return "Ms.";
            default: return "Dr.";
        }
    }

    /** Mots tout en majuscules (« DUPONT ») ou nom tout en minuscules → « Dupont », « Jean-Pierre O'Neil ». */
    private static String fixCase(String s) {
        boolean allLow = s.equals(s.toLowerCase(Locale.ROOT));
        StringBuilder out = new StringBuilder();
        for (String w : s.split(" ", -1)) {
            if (out.length() > 0) out.append(' ');
            boolean up = w.length() >= 2 && w.equals(w.toUpperCase(Locale.ROOT)) && !w.equals(w.toLowerCase(Locale.ROOT));
            out.append(up || allLow ? cap(w) : w);
        }
        String r = out.toString();
        for (String w : new String[]{"And", "Et", "Of", "De", "Du", "Des", "La", "Le"}) {
            r = r.replace(" " + w + " ", " " + w.toLowerCase(Locale.ROOT) + " ");
        }
        return r;
    }

    private static String cap(String w) {
        StringBuilder sb = new StringBuilder(w.length());
        boolean up = true;
        for (char ch : w.toLowerCase(Locale.ROOT).toCharArray()) {
            sb.append(up ? Character.toUpperCase(ch) : ch);
            up = ch == '-' || ch == '\'' || ch == '\u2019';
        }
        return sb.toString();
    }
}
