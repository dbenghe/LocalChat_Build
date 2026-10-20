package localchat;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class I18n {
    static final String[] CODES = {"nl", "ro", "es", "pl", "en", "tr"};
    static final String[] NAMES = {"Nederlands", "Romana", "Español", "Polski", "English", "Türkçe"};
    private static final Map<String, Map<String, Object>> CATALOGS = new HashMap<>();
    private static final Map<String,String> KEY_ALIASES = new HashMap<>();
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
        for(String key:CATALOGS.get("ro").keySet())KEY_ALIASES.put(romanianAscii(key),key);
    }
    /** Application copy only: call before inserting dynamic paths, names or document text. */
    static String romanianAscii(String value) {
        return value.replace('\u0103','a').replace('\u00e2','a').replace('\u00ee','i')
            .replace('\u0219','s').replace('\u015f','s').replace('\u021b','t').replace('\u0163','t')
            .replace('\u0102','A').replace('\u00c2','A').replace('\u00ce','I')
            .replace('\u0218','S').replace('\u015e','S').replace('\u021a','T').replace('\u0162','T');
    }
    static String language() { return language; }
    static void setLanguage(String code) { language = CATALOGS.containsKey(code) ? code : "ro"; }
    static boolean contains(String key) { return KEY_ALIASES.containsKey(romanianAscii(key)); }
    static String text(String key, Object... args) {
        String canonical=KEY_ALIASES.getOrDefault(romanianAscii(key),key);
        String result = String.valueOf(CATALOGS.get(language).getOrDefault(canonical, key));
        if(language.equals("ro"))result=romanianAscii(result);
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
