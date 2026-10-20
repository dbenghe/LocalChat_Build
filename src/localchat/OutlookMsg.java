package localchat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Outlook .msg e-mails (OLE compound files): headers, text and attachments, without Outlook. */
final class OutlookMsg {
    private static final ZoneId AMSTERDAM = ZoneId.of("Europe/Amsterdam");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final long MAX_SIZE = 300_000_000L;
    /** The dictionary every compressed RTF body starts from (MS-OXRTFCP). */
    private static final String RTF_PREBUF = "{\\rtf1\\ansi\\mac\\deff0\\deftab720{\\fonttbl;}{\\f0\\fnil \\froman \\fswiss "
            + "\\fmodern \\fscript \\fdecor MS Sans SerifSymbolArialTimes New RomanCourier{\\colortbl\\red0\\green0\\blue0\r\n"
            + "\\par \\pard\\plain\\f0\\fs20\\b\\i\\u\\tab\\tx";

    record Attachment(String name, String mime, long size, boolean inline, byte[] data, Message embedded) {
    }

    record Message(String subject, String from, String to, String cc, String date, String body, List<Attachment> attachments) {
    }

    private OutlookMsg() {
    }

    static Message read(Path file) throws IOException {
        if (Files.size(file) > MAX_SIZE) throw new IOException("Email prea mare (" + Tools.size(Files.size(file)) + ").");
        Cfb cfb = new Cfb(Files.readAllBytes(file));
        return message(cfb, cfb.root(), true);
    }

    static String text(Path file) throws IOException {
        StringBuilder b = new StringBuilder("Email Outlook (.msg): " + file.getFileName() + "\n");
        append(b, read(file), 0);
        return b.toString();
    }

    /** Saves the attachments (embedded e-mails as .txt, their own attachments in a subfolder); reports each file. */
    static void extract(Message m, Path dir, boolean includeInline, List<String> report) throws IOException {
        Files.createDirectories(dir);
        for (Attachment a : m.attachments()) {
            if (a.inline() && !includeInline) {
                report.add("omis (imagine inline / semnatura): " + a.name());
                continue;
            }
            String name = safeName(a.name());
            if (a.embedded() != null) {
                StringBuilder b = new StringBuilder("Email atasat: " + a.name() + "\n");
                append(b, a.embedded(), 0);
                report.add(save(dir.resolve(name + ".txt"), b.toString().getBytes(StandardCharsets.UTF_8)));
                if (!a.embedded().attachments().isEmpty()) extract(a.embedded(), dir.resolve(name), includeInline, report);
            } else {
                report.add(save(dir.resolve(name), a.data()));
            }
        }
    }

    private static String save(Path target, byte[] data) throws IOException {
        if (Files.exists(target) && Arrays.equals(Files.readAllBytes(target), data)) return "exista deja, identic: " + target;
        Path free = target;
        String file = target.getFileName().toString();
        int dot = file.lastIndexOf('.');
        String base = dot > 0 ? file.substring(0, dot) : file;
        String ext = dot > 0 ? file.substring(dot) : "";
        for (int i = 2; Files.exists(free); i++) free = target.resolveSibling(base + " (" + i + ")" + ext);
        Files.write(free, data);
        return "salvat: " + free + " (" + Tools.size(data.length) + ")";
    }

    private static void append(StringBuilder b, Message m, int depth) {
        b.append("Subiect: ").append(orDash(m.subject())).append('\n')
                .append("De la: ").append(orDash(m.from())).append('\n')
                .append("Catre: ").append(orDash(m.to())).append('\n');
        if (m.cc() != null && !m.cc().isBlank()) b.append("Cc: ").append(m.cc()).append('\n');
        b.append("Data: ").append(m.date() == null ? "necunoscuta" : m.date() + " (Europe/Amsterdam)").append('\n');
        List<Attachment> atts = m.attachments();
        b.append("Atasamente: ").append(atts.isEmpty() ? "niciunul" : String.valueOf(atts.size())).append('\n');
        for (int i = 0; i < atts.size(); i++) {
            Attachment a = atts.get(i);
            b.append("  ").append(i + 1).append(". ");
            if (a.embedded() != null) {
                b.append("[email atasat] ").append(a.name());
            } else {
                b.append(a.name()).append(" (").append(Tools.size(a.size()));
                if (a.mime() != null && !a.mime().isBlank()) b.append(", ").append(a.mime());
                b.append(')');
                if (a.inline()) b.append(" [imagine in corpul emailului, de obicei semnatura]");
            }
            b.append('\n');
        }
        b.append("--- Text ---\n").append(m.body().strip()).append('\n');
        for (Attachment a : atts) {
            if (a.embedded() == null || depth > 5) continue;
            b.append("\n=== Email atasat: ").append(a.name()).append(" ===\n");
            append(b, a.embedded(), depth + 1);
        }
    }

    // ---- MAPI properties ----

    private static Message message(Cfb cfb, Cfb.Node storage, boolean top) throws IOException {
        Map<Integer, byte[]> props = properties(cfb, storage, top ? 32 : 24);
        int codepage = intProp(props, 0x3FDE, intProp(props, 0x3FFD, 1252));
        Charset cs = charset(codepage);
        String subject = str(cfb, storage, 0x0037, cs);
        String fromName = str(cfb, storage, 0x0C1A, cs);
        String fromMail = firstMail(str(cfb, storage, 0x5D01, cs), str(cfb, storage, 0x0C1F, cs), str(cfb, storage, 0x0065, cs));
        String from = fromName == null ? fromMail : fromMail == null || fromName.contains(fromMail) ? fromName : fromName + " <" + fromMail + ">";
        List<String> toMails = new ArrayList<>();
        List<String> ccMails = new ArrayList<>();
        for (Cfb.Node r : children(storage, "__RECIP_VERSION1.0_")) {
            String mail = firstMail(str(cfb, r, 0x39FE, cs), str(cfb, r, 0x3003, cs));
            if (mail == null) continue;
            (intProp(properties(cfb, r, 8), 0x0C15, 1) == 2 ? ccMails : toMails).add(mail);
        }
        String to = withMails(str(cfb, storage, 0x0E04, cs), toMails);
        String cc = withMails(str(cfb, storage, 0x0E03, cs), ccMails);
        String date = time(props, 0x0039);
        if (date == null) date = time(props, 0x0E06);
        if (date == null) date = headerDate(str(cfb, storage, 0x007D, cs));

        List<Attachment> atts = new ArrayList<>();
        for (Cfb.Node a : children(storage, "__ATTACH_VERSION1.0_")) {
            Map<Integer, byte[]> ap = properties(cfb, a, 8);
            String name = firstNonBlank(str(cfb, a, 0x3707, cs), str(cfb, a, 0x3704, cs), str(cfb, a, 0x3001, cs), "atasament");
            Cfb.Node embedded = a.children.get("__SUBSTG1.0_3701000D");
            if (embedded != null) {
                Message inner = message(cfb, embedded, false);
                atts.add(new Attachment(firstNonBlank(inner.subject(), name), "message/rfc822", 0, false, null, inner));
                continue;
            }
            Cfb.Node dataNode = a.children.get("__SUBSTG1.0_37010102");
            byte[] data = dataNode == null ? new byte[0] : cfb.read(dataNode);
            String mime = str(cfb, a, 0x370E, cs);
            String contentId = str(cfb, a, 0x3712, cs);
            boolean image = (mime != null && mime.startsWith("image/")) || name.matches("(?i).+\\.(png|jpe?g|gif|bmp|emf|wmf)$");
            boolean inline = boolProp(ap, 0x7FFE) || (image && contentId != null && !contentId.isBlank());
            atts.add(new Attachment(name, mime, data.length, inline, data, null));
        }
        return new Message(subject, from, to, cc, date, body(cfb, storage, cs), atts);
    }

    private static String body(Cfb cfb, Cfb.Node storage, Charset cs) throws IOException {
        String plain = str(cfb, storage, 0x1000, cs);
        if (plain != null && !plain.isBlank()) return tidy(plain);
        Cfb.Node html = storage.children.get("__SUBSTG1.0_10130102");
        if (html != null) return tidy(htmlToText(decode(cfb.read(html))));
        String htmlText = str(cfb, storage, 0x1013, cs);
        if (htmlText != null) return tidy(htmlToText(htmlText));
        Cfb.Node rtf = storage.children.get("__SUBSTG1.0_10090102");
        if (rtf != null) return tidy(rtfToText(new String(decompressRtf(cfb.read(rtf)), Charset.forName("windows-1252"))));
        return "";
    }

    private static Map<Integer, byte[]> properties(Cfb cfb, Cfb.Node storage, int header) throws IOException {
        Map<Integer, byte[]> out = new HashMap<>();
        Cfb.Node n = storage.children.get("__PROPERTIES_VERSION1.0");
        if (n == null) return out;
        byte[] b = cfb.read(n);
        for (int off = header; off + 16 <= b.length; off += 16) out.put(le16(b, off + 2), Arrays.copyOfRange(b, off + 8, off + 16));
        return out;
    }

    private static String str(Cfb cfb, Cfb.Node storage, int id, Charset cs) throws IOException {
        Cfb.Node n = storage.children.get(String.format("__SUBSTG1.0_%04X001F", id));
        if (n != null) return stripNul(new String(cfb.read(n), StandardCharsets.UTF_16LE));
        n = storage.children.get(String.format("__SUBSTG1.0_%04X001E", id));
        return n == null ? null : stripNul(new String(cfb.read(n), cs));
    }

    private static int intProp(Map<Integer, byte[]> props, int id, int def) {
        byte[] v = props.get(id);
        return v == null ? def : le32(v, 0);
    }

    private static boolean boolProp(Map<Integer, byte[]> props, int id) {
        byte[] v = props.get(id);
        return v != null && le16(v, 0) != 0;
    }

    private static String time(Map<Integer, byte[]> props, int id) {
        byte[] v = props.get(id);
        if (v == null) return null;
        long fileTime = le64(v, 0);
        if (fileTime <= 0) return null;
        long unix100ns = fileTime - 116_444_736_000_000_000L;
        Instant t = Instant.ofEpochSecond(Math.floorDiv(unix100ns, 10_000_000L), Math.floorMod(unix100ns, 10_000_000L) * 100);
        return t.atZone(AMSTERDAM).format(DATE);
    }

    private static String headerDate(String headers) {
        if (headers == null) return null;
        Matcher m = Pattern.compile("(?mi)^Date:\\s*(.+)$").matcher(headers);
        return m.find() ? m.group(1).trim() : null;
    }

    private static List<Cfb.Node> children(Cfb.Node storage, String prefix) {
        List<Cfb.Node> out = new ArrayList<>();
        for (Map.Entry<String, Cfb.Node> e : storage.children.entrySet()) {
            if (e.getKey().startsWith(prefix) && e.getValue().type == 1) out.add(e.getValue());
        }
        out.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    // ---- text helpers ----

    private static String firstMail(String... candidates) {
        for (String c : candidates) if (c != null && c.contains("@")) return c.trim();
        return null;
    }

    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) if (c != null && !c.isBlank()) return c.trim();
        return "";
    }

    private static String withMails(String display, List<String> mails) {
        if (mails.isEmpty()) return display;
        return (display == null || display.isBlank() ? "" : display + " ") + "[" + String.join(", ", mails) + "]";
    }

    private static String orDash(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }

    private static String stripNul(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == 0) end--;
        return s.substring(0, end);
    }

    private static String tidy(String s) {
        return s.replace("\r\n", "\n").replace('\r', '\n').replaceAll("[ \\t\\u00A0]+\\n", "\n").replaceAll("\\n{4,}", "\n\n\n");
    }

    static String safeName(String name) {
        String s = name.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1F]", "_").trim().replaceAll("^[. ]+|[. ]+$", "");
        if (s.isEmpty()) s = "atasament";
        return s.length() > 150 ? s.substring(0, 150) : s;
    }

    private static Charset charset(int codepage) {
        if (codepage == 65001) return StandardCharsets.UTF_8;
        try {
            return Charset.forName("windows-" + codepage);
        } catch (RuntimeException e) {
            try {
                return Charset.forName("cp" + codepage);
            } catch (RuntimeException e2) {
                return Charset.forName("windows-1252");
            }
        }
    }

    private static String decode(byte[] b) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b)).toString();
        } catch (CharacterCodingException e) {
            return new String(b, Charset.forName("windows-1252"));
        }
    }

    static String htmlToText(String html) {
        String s = html.replaceAll("(?is)<(script|style|head)\\b[^>]*>.*?</\\1\\s*>", " ")
                .replaceAll("(?is)<!--.*?-->", "")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|div|tr|li|h[1-6]|table|blockquote)\\s*>", "\n")
                .replaceAll("(?i)<(td|th)\\b[^>]*>", "\t")
                .replaceAll("(?s)<[^>]*>", "");
        Matcher m = Pattern.compile("&(#x[0-9a-fA-F]+|#[0-9]+|nbsp|amp|lt|gt|quot|apos|euro);").matcher(s);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            String e = m.group(1);
            String r = switch (e) {
                case "nbsp" -> " ";
                case "amp" -> "&";
                case "lt" -> "<";
                case "gt" -> ">";
                case "quot" -> "\"";
                case "apos" -> "'";
                case "euro" -> "€";
                default -> new String(Character.toChars(e.startsWith("#x") ? Integer.parseInt(e.substring(2), 16) : Integer.parseInt(e.substring(1))));
            };
            m.appendReplacement(b, Matcher.quoteReplacement(r));
        }
        m.appendTail(b);
        return b.toString();
    }

    // ---- RTF bodies (only when a message has neither plain text nor HTML) ----

    static byte[] decompressRtf(byte[] src) throws IOException {
        if (src.length < 16) throw new IOException("Corp RTF invalid.");
        int compSize = le32(src, 0);
        int rawSize = le32(src, 4);
        int magic = le32(src, 8);
        if (magic == 0x414C454D) return Arrays.copyOfRange(src, 16, Math.min(src.length, 16 + Math.max(0, rawSize))); // "MELA": stored
        if (magic != 0x75465A4C) throw new IOException("Format RTF necunoscut.");
        byte[] dict = new byte[4096];
        byte[] pre = RTF_PREBUF.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(pre, 0, dict, 0, pre.length);
        int write = pre.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(16, rawSize));
        int in = 16;
        int end = Math.min(src.length, compSize + 4);
        while (in < end) {
            int control = src[in++] & 0xFF;
            for (int bit = 0; bit < 8 && in < end; bit++) {
                if ((control & (1 << bit)) == 0) {
                    byte c = src[in++];
                    out.write(c);
                    dict[write] = c;
                    write = (write + 1) & 0xFFF;
                } else {
                    if (in + 1 >= end) return out.toByteArray();
                    int word = ((src[in] & 0xFF) << 8) | (src[in + 1] & 0xFF);
                    in += 2;
                    int offset = word >> 4;
                    int length = (word & 0xF) + 2;
                    if (offset == write) return out.toByteArray(); // end marker
                    for (int i = 0; i < length; i++) {
                        byte c = dict[(offset + i) & 0xFFF];
                        out.write(c);
                        dict[write] = c;
                        write = (write + 1) & 0xFFF;
                    }
                }
            }
        }
        return out.toByteArray();
    }

    /** Rough RTF to text: keeps the visible text, drops formatting, tables of fonts/colors and HTML-only parts. */
    static String rtfToText(String rtf) {
        StringBuilder out = new StringBuilder();
        Deque<Boolean> skipStack = new ArrayDeque<>();
        boolean skip = false;
        boolean htmlRtf = false;
        int ucSkip = 1;
        Charset cp = Charset.forName("windows-1252");
        int i = 0;
        int n = rtf.length();
        while (i < n) {
            char c = rtf.charAt(i);
            if (c == '{') {
                skipStack.push(skip);
                if (rtf.startsWith("{\\*", i)) skip = true;
                i++;
            } else if (c == '}') {
                skip = skipStack.isEmpty() ? false : skipStack.pop();
                i++;
            } else if (c == '\\' && i + 1 < n) {
                char next = rtf.charAt(i + 1);
                if (Character.isLetter(next)) {
                    int j = i + 1;
                    while (j < n && Character.isLetter(rtf.charAt(j))) j++;
                    String word = rtf.substring(i + 1, j);
                    int k = j;
                    if (k < n && (rtf.charAt(k) == '-' || Character.isDigit(rtf.charAt(k)))) {
                        k++;
                        while (k < n && Character.isDigit(rtf.charAt(k))) k++;
                    }
                    String arg = rtf.substring(j, k);
                    if (k < n && rtf.charAt(k) == ' ') k++;
                    i = k;
                    switch (word) {
                        case "par", "line", "row" -> { if (!skip && !htmlRtf) out.append('\n'); }
                        case "tab", "cell" -> { if (!skip && !htmlRtf) out.append('\t'); }
                        case "htmlrtf" -> htmlRtf = !"0".equals(arg);
                        case "uc" -> ucSkip = arg.isEmpty() ? 1 : Integer.parseInt(arg);
                        case "u" -> {
                            if (!skip && !htmlRtf && !arg.isEmpty()) out.append((char) (Integer.parseInt(arg) & 0xFFFF));
                            for (int s = 0; s < ucSkip && i < n; s++) i = rtf.charAt(i) == '\\' && i + 3 < n && rtf.charAt(i + 1) == '\'' ? i + 4 : i + 1;
                        }
                        case "fonttbl", "colortbl", "stylesheet", "info", "pict", "object", "header", "footer", "listtable", "themedata", "datastore" -> skip = true;
                        default -> { }
                    }
                } else if (next == '\'' && i + 3 < n) {
                    if (!skip && !htmlRtf) {
                        try {
                            out.append(new String(new byte[]{(byte) Integer.parseInt(rtf.substring(i + 2, i + 4), 16)}, cp));
                        } catch (NumberFormatException ignored) {
                            // malformed escape: drop it
                        }
                    }
                    i += 4;
                } else {
                    if (!skip && !htmlRtf) {
                        if (next == '~') out.append(' ');
                        else if (next == '\\' || next == '{' || next == '}') out.append(next);
                    }
                    i += 2;
                }
            } else {
                if (!skip && !htmlRtf && c != '\r' && c != '\n') out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    // ---- little-endian helpers ----

    private static int le16(byte[] b, int off) {
        return (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8;
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8 | (b[off + 2] & 0xFF) << 16 | (b[off + 3] & 0xFF) << 24;
    }

    private static long le64(byte[] b, int off) {
        return (le32(b, off) & 0xFFFFFFFFL) | ((long) le32(b, off + 4)) << 32;
    }

    /** Compound File Binary reader (MS-CFB): sectors, FAT, mini stream and the directory tree. */
    private static final class Cfb {
        private final byte[] d;
        private final int sectorSize;
        private final int miniSize;
        private final long cutoff;
        private final int[] fat;
        private final int[] miniFat;
        private final byte[] mini;
        private final List<Node> nodes = new ArrayList<>();

        static final class Node {
            final String name;
            final int type; // 1 storage, 2 stream, 5 root
            final int left;
            final int right;
            final int child;
            final int start;
            final long size;
            final Map<String, Node> children = new LinkedHashMap<>(); // by upper-case name

            Node(byte[] dir, int off, boolean v3) {
                int len = le16(dir, off + 64);
                name = len >= 2 ? new String(dir, off, Math.min(64, len) - 2, StandardCharsets.UTF_16LE) : "";
                type = dir[off + 66] & 0xFF;
                left = le32(dir, off + 68);
                right = le32(dir, off + 72);
                child = le32(dir, off + 76);
                start = le32(dir, off + 116);
                long sz = le64(dir, off + 120);
                size = v3 ? sz & 0xFFFFFFFFL : sz;
            }
        }

        Cfb(byte[] data) throws IOException {
            d = data;
            if (d.length < 512 || le32(d, 0) != 0xE011CFD0 || le32(d, 4) != 0xE11AB1A1) {
                throw new IOException("Nu e un fisier Outlook .msg valid.");
            }
            sectorSize = 1 << le16(d, 30);
            miniSize = 1 << le16(d, 32);
            if (sectorSize != 512 && sectorSize != 4096) throw new IOException("Fisier .msg deteriorat (sector " + sectorSize + ").");
            cutoff = le32(d, 56) & 0xFFFFFFFFL;
            int numFat = le32(d, 44);
            int firstDir = le32(d, 48);
            int firstMiniFat = le32(d, 60);
            int numMiniFat = le32(d, 64);
            int per = sectorSize / 4;
            List<Integer> fatSectors = new ArrayList<>();
            for (int i = 0; i < 109 && fatSectors.size() < numFat; i++) {
                int s = le32(d, 76 + 4 * i);
                if (s >= 0) fatSectors.add(s);
            }
            int difat = le32(d, 68);
            for (int guard = 0; difat >= 0 && fatSectors.size() < numFat && guard < 1_000_000; guard++) {
                int off = offset(difat);
                for (int i = 0; i < per - 1 && fatSectors.size() < numFat; i++) {
                    int s = le32(d, off + 4 * i);
                    if (s >= 0) fatSectors.add(s);
                }
                difat = le32(d, off + 4 * (per - 1));
            }
            fat = new int[fatSectors.size() * per];
            for (int k = 0; k < fatSectors.size(); k++) {
                int off = offset(fatSectors.get(k));
                for (int i = 0; i < per; i++) fat[k * per + i] = le32(d, off + 4 * i);
            }
            byte[] dir = chain(firstDir, -1);
            for (int off = 0; off + 128 <= dir.length; off += 128) nodes.add(new Node(dir, off, sectorSize == 512));
            if (nodes.isEmpty()) throw new IOException("Fisier .msg fara continut.");
            mini = chain(nodes.get(0).start, nodes.get(0).size);
            byte[] mf = numMiniFat > 0 ? chain(firstMiniFat, -1) : new byte[0];
            miniFat = new int[mf.length / 4];
            for (int i = 0; i < miniFat.length; i++) miniFat[i] = le32(mf, 4 * i);
            for (Node n : nodes) if (n.type == 1 || n.type == 5) link(n);
        }

        Node root() {
            return nodes.get(0);
        }

        byte[] read(Node n) throws IOException {
            if (n.size <= 0) return new byte[0];
            if (n.size > MAX_SIZE) throw new IOException("Parte prea mare in .msg.");
            return n.size < cutoff ? miniChain(n.start, n.size) : chain(n.start, n.size);
        }

        /** In-order walk of the storage's red-black tree of children (iterative, cycle-safe). */
        private void link(Node parent) {
            Set<Integer> seen = new HashSet<>();
            Deque<Integer> stack = new ArrayDeque<>();
            if (valid(parent.child)) stack.push(parent.child);
            while (!stack.isEmpty()) {
                int idx = stack.pop();
                if (!seen.add(idx)) continue;
                Node n = nodes.get(idx);
                parent.children.put(n.name.toUpperCase(Locale.ROOT), n);
                if (valid(n.left)) stack.push(n.left);
                if (valid(n.right)) stack.push(n.right);
            }
        }

        private boolean valid(int idx) {
            return idx >= 0 && idx < nodes.size();
        }

        private int offset(int sector) throws IOException {
            long off = (long) (sector + 1) * sectorSize;
            if (sector < 0 || off >= d.length) throw new IOException("Fisier .msg deteriorat (sector " + sector + ").");
            return (int) off;
        }

        private byte[] chain(int start, long size) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int s = start;
            for (int guard = 0; s >= 0 && guard <= fat.length; guard++) {
                int off = offset(s);
                out.write(d, off, Math.min(sectorSize, d.length - off));
                if (size >= 0 && out.size() >= size) break;
                s = s < fat.length ? fat[s] : -2;
            }
            byte[] b = out.toByteArray();
            return size >= 0 && b.length > size ? Arrays.copyOf(b, (int) size) : b;
        }

        private byte[] miniChain(int start, long size) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int s = start;
            for (int guard = 0; s >= 0 && guard <= miniFat.length && out.size() < size; guard++) {
                long off = (long) s * miniSize;
                if (off >= mini.length) break;
                out.write(mini, (int) off, (int) Math.min(miniSize, mini.length - off));
                s = s < miniFat.length ? miniFat[s] : -2;
            }
            byte[] b = out.toByteArray();
            return b.length > size ? Arrays.copyOf(b, (int) size) : b;
        }
    }
}
