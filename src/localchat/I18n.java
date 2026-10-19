package localchat;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class I18n {
    static final String[] CODES = {"nl", "ro", "es", "pl", "en", "tr"};
    static final String[] NAMES = {"Nederlands", "Română", "Español", "Polski", "English", "Türkçe"};
    private static final Map<String, Map<String, Object>> CATALOGS = new HashMap<>();
    private static volatile String language = "ro";
    static {
        for (String code : CODES) {
            try (InputStream in = I18n.class.getResourceAsStream("i18n/" + code + ".json")) {
                if (in == null) throw new IOException("Missing catalog: " + code);
                Object value = Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                if (!(value instanceof Map)) throw new IOException("Invalid catalog: " + code);
                Map<String,Object> entries = new LinkedHashMap<>();
                ((Map<?,?>) value).forEach((key, text) -> entries.put(key.toString(), text));
                CATALOGS.put(code, Collections.unmodifiableMap(entries));
            } catch (IOException e) { throw new ExceptionInInitializerError(e); }
        }
    }
    static String language() { return language; }
    static void setLanguage(String code) { language = CATALOGS.containsKey(code) ? code : "ro"; }
    static boolean contains(String key) { return CATALOGS.get("ro").containsKey(key); }
    static String text(String key, Object... args) {
        String result = String.valueOf(CATALOGS.get(language).getOrDefault(key, key));
        // Replace placeholders in one pass: braces in filenames or server messages stay literal.
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{([0-9]+)\\}").matcher(result);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(n < args.length ? String.valueOf(args[n]) : m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }
    static Set<String> keys(String code) { return CATALOGS.get(code).keySet(); }
}
