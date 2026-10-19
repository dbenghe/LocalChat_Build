package localchat;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translates paths written on another computer (in skills, rules, or by the model copying them) to this one:
 * another user's profile becomes this user's, and configured aliases (e.g. C:\PDFCONTROL) point to their portable copy.
 * A path is only translated when it does not exist here, so the real folders win on the PC they were written for.
 */
final class PathMap {
    private static final Pattern MSYS = Pattern.compile("^(?:/mnt)?[/\\\\]([a-zA-Z])(?=[/\\\\]|$)");
    private static final Pattern USER = Pattern.compile("([A-Za-z]):[\\\\/]Users[\\\\/]([^\\\\/\\s\"'*?<>|]+)");

    private final Map<String, String> aliases = new LinkedHashMap<>();
    private final String home;
    private final String downloads;

    PathMap(Map<String, String> aliases, String home, String downloads) {
        // Longest source first, so C:\X\Y wins over C:\X.
        List<Map.Entry<String, String>> sorted = new ArrayList<>(aliases.entrySet());
        sorted.sort((a, b) -> b.getKey().length() - a.getKey().length());
        for (Map.Entry<String, String> e : sorted) this.aliases.put(trimSlash(e.getKey()), trimSlash(e.getValue()));
        this.home = trimSlash(home);
        this.downloads = trimSlash(downloads);
    }

    static PathMap none() {
        return new PathMap(Map.of(), System.getProperty("user.home"), System.getProperty("user.home") + File.separator + "Downloads");
    }

    /** One path argument from the model. */
    String path(String raw) {
        Matcher m = MSYS.matcher(raw);
        String s = m.find() ? m.group(1).toUpperCase(Locale.ROOT) + ":" + raw.substring(m.end()) : raw;
        for (Map.Entry<String, String> a : aliases.entrySet()) {
            if (startsWithFolder(s, a.getKey()) && !new File(a.getKey()).exists()) return a.getValue() + s.substring(a.getKey().length());
        }
        Matcher u = USER.matcher(s);
        if (u.lookingAt()) return otherUser(u, s);
        return s;
    }

    /** Every such path inside a text (skill instructions). */
    String text(String text) {
        String out = text;
        for (Map.Entry<String, String> a : aliases.entrySet()) {
            if (!new File(a.getKey()).exists()) out = replaceFolder(out, a.getKey(), a.getValue());
        }
        Matcher u = USER.matcher(out);
        StringBuilder b = new StringBuilder();
        int last = 0;
        while (u.find()) {
            String profile = u.group(1) + ":\\Users\\" + u.group(2);
            if (new File(profile).exists() || isSpecial(u.group(2))) continue;
            b.append(out, last, u.start());
            String rest = out.substring(u.end());
            if (rest.regionMatches(true, 0, "\\Downloads", 0, 10) && (rest.length() == 10 || "\\/ \n\r\t`\"'".indexOf(rest.charAt(10)) >= 0)) {
                b.append(downloads);
                last = u.end() + 10;
            } else {
                b.append(home);
                last = u.end();
            }
        }
        b.append(out.substring(last));
        return b.toString();
    }

    /** The translations that apply on this PC, to tell the model up front. */
    List<String> describe() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> a : aliases.entrySet()) {
            if (!new File(a.getKey()).exists()) out.add(a.getKey() + " → " + a.getValue());
        }
        out.add("C:\\Users\\<alt utilizator>\\Downloads → " + downloads);
        out.add("C:\\Users\\<alt utilizator> → " + home);
        return out;
    }

    private String otherUser(Matcher u, String s) {
        String profile = u.group(1) + ":\\Users\\" + u.group(2);
        if (new File(profile).exists() || isSpecial(u.group(2))) return s;
        String rest = s.substring(u.end());
        if (rest.regionMatches(true, 0, "\\Downloads", 0, 10) || rest.regionMatches(true, 0, "/Downloads", 0, 10)) {
            if (rest.length() == 10 || rest.charAt(10) == '\\' || rest.charAt(10) == '/') return downloads + rest.substring(10);
        }
        return home + rest;
    }

    private static boolean isSpecial(String user) {
        String u = user.toLowerCase(Locale.ROOT);
        return u.equals("public") || u.equals("default") || u.equals("all users") || u.startsWith("<") || u.startsWith("%");
    }

    private static boolean startsWithFolder(String s, String prefix) {
        return s.regionMatches(true, 0, prefix, 0, prefix.length())
                && (s.length() == prefix.length() || s.charAt(prefix.length()) == '\\' || s.charAt(prefix.length()) == '/');
    }

    private static String replaceFolder(String text, String from, String to) {
        Matcher m = Pattern.compile(Pattern.quote(from) + "(?=[\\\\/\\s`\"')]|$)", Pattern.CASE_INSENSITIVE).matcher(text);
        return m.replaceAll(Matcher.quoteReplacement(to));
    }

    private static String trimSlash(String s) {
        return s.endsWith("\\") || s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
