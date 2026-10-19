package localchat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal JSON reader/writer, so the app needs nothing but the JDK. */
final class Json {
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    static Object parse(String text) {
        Json p = new Json(text);
        p.skipWhitespace();
        Object value = p.value();
        p.skipWhitespace();
        if (p.i < p.s.length()) throw p.error("text în plus după JSON");
        return value;
    }

    static String write(Object value) {
        StringBuilder b = new StringBuilder();
        write(value, b);
        return b.toString();
    }

    /** Builds an ordered JSON object from alternating key/value arguments. */
    static Map<String, Object> obj(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int k = 0; k + 1 < keyValues.length; k += 2) m.put((String) keyValues[k], keyValues[k + 1]);
        return m;
    }

    /** Walks maps (String keys) and lists (Integer indexes); returns null if anything is missing. */
    static Object get(Object node, Object... path) {
        for (Object key : path) {
            if (key instanceof String && node instanceof Map) {
                node = ((Map<?, ?>) node).get(key);
            } else if (key instanceof Integer && node instanceof List) {
                List<?> list = (List<?>) node;
                int index = (Integer) key;
                node = index < list.size() ? list.get(index) : null;
            } else {
                return null;
            }
        }
        return node;
    }

    static String str(Object node, Object... path) {
        Object v = get(node, path);
        return v instanceof String ? (String) v : null;
    }

    private Object value() {
        if (i >= s.length()) throw error("sfârșit neașteptat");
        switch (s.charAt(i)) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': literal("true"); return Boolean.TRUE;
            case 'f': literal("false"); return Boolean.FALSE;
            case 'n': literal("null"); return null;
            default: return number();
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        skipWhitespace();
        if (peek('}')) {
            i++;
            return m;
        }
        while (true) {
            skipWhitespace();
            String key = string();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            m.put(key, value());
            skipWhitespace();
            if (peek(',')) {
                i++;
                continue;
            }
            expect('}');
            return m;
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        i++;
        skipWhitespace();
        if (peek(']')) {
            i++;
            return list;
        }
        while (true) {
            skipWhitespace();
            list.add(value());
            skipWhitespace();
            if (peek(',')) {
                i++;
                continue;
            }
            expect(']');
            return list;
        }
    }

    private String string() {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (true) {
            if (i >= s.length()) throw error("șir neterminat");
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') {
                b.append(c);
                continue;
            }
            if (i >= s.length()) throw error("escape invalid");
            char e = s.charAt(i++);
            switch (e) {
                case '"': b.append('"'); break;
                case '\\': b.append('\\'); break;
                case '/': b.append('/'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'n': b.append('\n'); break;
                case 'r': b.append('\r'); break;
                case 't': b.append('\t'); break;
                case 'u':
                    if (i + 4 > s.length()) throw error("escape \\u invalid");
                    b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                    break;
                default: throw error("escape invalid");
            }
        }
    }

    private Object number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        String n = s.substring(start, i);
        if (n.isEmpty()) throw error("caracter neașteptat '" + s.charAt(start) + "'");
        try {
            if (n.indexOf('.') < 0 && n.indexOf('e') < 0 && n.indexOf('E') < 0) return Long.parseLong(n);
        } catch (NumberFormatException ignored) {
            // too large for a long, fall through to double
        }
        try {
            return Double.parseDouble(n);
        } catch (NumberFormatException e) {
            throw error("număr invalid");
        }
    }

    private void literal(String word) {
        if (!s.startsWith(word, i)) throw error("se aștepta " + word);
        i += word.length();
    }

    private boolean peek(char c) {
        return i < s.length() && s.charAt(i) == c;
    }

    private void expect(char c) {
        if (!peek(c)) throw error("se aștepta '" + c + "'");
        i++;
    }

    private void skipWhitespace() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("Răspuns JSON invalid (poziția " + i + "): " + what);
    }

    private static void write(Object v, StringBuilder b) {
        if (v == null) {
            b.append("null");
        } else if (v instanceof String) {
            quote((String) v, b);
        } else if (v instanceof Number || v instanceof Boolean) {
            b.append(v);
        } else if (v instanceof Map) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) b.append(',');
                first = false;
                quote(String.valueOf(e.getKey()), b);
                b.append(':');
                write(e.getValue(), b);
            }
            b.append('}');
        } else if (v instanceof List) {
            b.append('[');
            boolean first = true;
            for (Object item : (List<?>) v) {
                if (!first) b.append(',');
                first = false;
                write(item, b);
            }
            b.append(']');
        } else {
            throw new IllegalArgumentException("Tip JSON nesuportat: " + v.getClass());
        }
    }

    private static void quote(String s, StringBuilder b) {
        b.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        b.append('"');
    }
}
