package localchat;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.*;

/**
 * download_url (1.28.9): plain public HTTP(S) downloads and nothing else. Outlook safelinks are unwrapped, public redirects are
 * followed by hand (every hop is checked), and only a direct file response (Content-Disposition or a non-HTML content type) is written.
 * HTML landing pages are never scraped and no private API is called: WeTransfer and similar sites are reported as "needs a browser",
 * with an optional, approved opening in the default browser. The result never claims a password, an expiry or a processing state:
 * the page is not read, so nothing about it is known.
 */
final class UrlDownload {
    static final long DEFAULT_MAX = 200L << 20, HARD_MAX = 2048L << 20;
    static final int MAX_REDIRECTS = 8;
    /** Test support: lets the tests talk to a fake server on this machine. */
    static volatile boolean allowPrivateForTests;
    /** Test support: replaces the default-browser call. */
    static volatile Consumer<URI> opener = uri -> {
        try {
            java.awt.Desktop.getDesktop().browse(uri);
        } catch (IOException | UnsupportedOperationException e) {
            throw new IllegalStateException(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), e);
        }
    };

    enum Kind { DOWNLOADED, BROWSER, HTML, FAILED }

    record Outcome(Kind kind, String url, Path file, long size, String sha256, String contentType, String detail) {}

    private UrlDownload() {}

    /** Outlook "safelinks" wrapper: https://xxx.safelinks.protection.outlook.com/?url=ENCODED&data=... The real address is the url parameter. */
    static String unwrapSafelink(String url) {
        String current = url == null ? "" : url.trim();
        for (int depth = 0; depth < 4; depth++) {
            URI uri;
            try {
                uri = new URI(current);
            } catch (URISyntaxException e) {
                return current;
            }
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (!(host.equals("safelinks.protection.outlook.com") || host.endsWith(".safelinks.protection.outlook.com"))) return current;
            String target = null;
            String query = uri.getRawQuery();
            if (query != null) for (String part : query.split("&")) {
                if (part.regionMatches(true, 0, "url=", 0, 4)) {
                    target = URLDecoder.decode(part.substring(4), StandardCharsets.UTF_8);
                    break;
                }
            }
            if (target == null || target.isBlank()) return current;
            current = target.trim();
        }
        return current;
    }

    /** Sites whose downloads need a person in a browser (script-driven pages, tokens, consent screens). They are never fetched. */
    static boolean browserOnly(String host) {
        String h = host == null ? "" : host.toLowerCase(Locale.ROOT);
        return h.equals("we.tl") || h.equals("wetransfer.com") || h.endsWith(".wetransfer.com");
    }

    static URI parse(String raw) throws IOException {
        URI uri;
        try {
            uri = new URI(raw.trim());
        } catch (URISyntaxException e) {
            throw new IOException("Invalid URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) throw new IOException("Only http and https addresses are supported");
        if (uri.getHost() == null || uri.getHost().isBlank()) throw new IOException("The address has no host");
        if (uri.getRawUserInfo() != null) throw new IOException("Addresses with embedded credentials are not used");
        return uri;
    }

    static void requirePublic(String host) throws IOException {
        if (allowPrivateForTests) return;
        InetAddress[] all;
        try {
            all = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IOException("Unknown host: " + host);
        }
        for (InetAddress a : all) {
            byte[] b = a.getAddress();
            boolean cgnat = b.length == 4 && (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64;
            boolean ula = b.length == 16 && (b[0] & 0xfe) == 0xfc;
            if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress() || cgnat || ula)
                throw new IOException("The address points to a private or local network; only public addresses are downloaded");
        }
    }

    static String describe(URI uri) {
        return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "") + (uri.getRawPath() == null ? "" : uri.getRawPath());
    }

    static void open(String url) throws IOException {
        URI uri = parse(unwrapSafelink(url));
        opener.accept(uri);
    }

    static Outcome fetch(String rawUrl, Path directory, long maxBytes, boolean overwrite, BooleanSupplier cancelled) throws IOException {
        String current = unwrapSafelink(rawUrl);
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            URI uri = parse(current);
            if (browserOnly(uri.getHost())) return new Outcome(Kind.BROWSER, describe(uri), null, 0, null, null, "browser");
            requirePublic(uri.getHost());
            HttpURLConnection c = (HttpURLConnection) uri.toURL().openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15_000);
            c.setReadTimeout(60_000);
            c.setRequestProperty("User-Agent", "LocalChat");
            c.setRequestProperty("Accept", "*/*");
            try {
                int code = c.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = c.getHeaderField("Location");
                    if (location == null || location.isBlank()) throw new IOException("Redirect without a target");
                    current = unwrapSafelink(uri.resolve(location.trim()).toString());
                    continue;
                }
                if (code >= 400) return new Outcome(Kind.FAILED, describe(uri), null, 0, null, null, "HTTP " + code);
                String type = c.getContentType() == null ? "" : c.getContentType().toLowerCase(Locale.ROOT).trim();
                String disposition = c.getHeaderField("Content-Disposition");
                boolean attachment = disposition != null && (disposition.toLowerCase(Locale.ROOT).contains("attachment") || disposition.toLowerCase(Locale.ROOT).contains("filename"));
                boolean html = type.startsWith("text/html") || type.startsWith("application/xhtml");
                if (!attachment && (type.isEmpty() || html)) return new Outcome(Kind.HTML, describe(uri), null, 0, null, type, "HTTP " + code);
                long length = c.getContentLengthLong();
                if (length > maxBytes) return new Outcome(Kind.FAILED, describe(uri), null, length, null, type, "The file is " + length + " bytes, above the limit of " + maxBytes + " bytes");
                return save(c, uri, directory, disposition, type, maxBytes, overwrite, cancelled);
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("Too many redirects (more than " + MAX_REDIRECTS + ")");
    }

    private static Outcome save(HttpURLConnection c, URI uri, Path directory, String disposition, String type, long maxBytes, boolean overwrite, BooleanSupplier cancelled) throws IOException {
        Files.createDirectories(directory);
        Path dir = directory.toRealPath();
        Path target = target(dir, fileName(disposition, uri), overwrite);
        Path part = Files.createTempFile(dir, "download-", ".part");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (InputStream in = c.getInputStream(); OutputStream out = Files.newOutputStream(part)) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    if (cancelled.getAsBoolean()) throw new IOException("Stopped by the user");
                    total += n;
                    if (total > maxBytes) return new Outcome(Kind.FAILED, describe(uri), null, total, null, type, "The download passed the limit of " + maxBytes + " bytes and was stopped; nothing was kept");
                    digest.update(buffer, 0, n);
                    out.write(buffer, 0, n);
                }
            }
            if (overwrite) Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            else Files.move(part, target);
            return new Outcome(Kind.DOWNLOADED, describe(uri), target, total, HexFormat.of().formatHex(digest.digest()), type, "");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        } finally {
            Files.deleteIfExists(part);
        }
    }

    static String fileName(String disposition, URI uri) {
        String name = null;
        if (disposition != null) {
            Matcher star = Pattern.compile("(?i)filename\\*\\s*=\\s*[\\w-]*'[^']*'([^;]+)").matcher(disposition);
            if (star.find()) name = URLDecoder.decode(star.group(1).trim().replace("+", "%2B"), StandardCharsets.UTF_8);
            else {
                Matcher plain = Pattern.compile("(?i)filename\\s*=\\s*(?:\"([^\"]*)\"|([^;]+))").matcher(disposition);
                if (plain.find()) name = plain.group(1) != null ? plain.group(1) : plain.group(2).trim();
            }
        }
        if (name == null || name.isBlank()) {
            String path = uri.getPath() == null ? "" : uri.getPath();
            name = path.substring(path.lastIndexOf('/') + 1);
        }
        return sanitize(name);
    }

    static String sanitize(String raw) {
        String name = raw == null ? "" : raw;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();
        while (name.endsWith(".") || name.endsWith(" ")) name = name.substring(0, name.length() - 1);
        if (name.length() > 150) name = name.substring(0, 150);
        if (name.isBlank() || name.equals(".") || name.equals("..")) name = "download";
        if (name.matches("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?")) name = "_" + name;
        return name;
    }

    private static Path target(Path dir, String name, boolean overwrite) throws IOException {
        Path first = dir.resolve(name).normalize();
        if (!first.startsWith(dir)) throw new IOException("Invalid file name");
        if (overwrite || !Files.exists(first)) return first;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name, extension = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            Path candidate = dir.resolve(stem + " (" + i + ")" + extension).normalize();
            if (!Files.exists(candidate)) return candidate;
        }
        throw new IOException("Too many files with this name");
    }
}