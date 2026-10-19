package localchat;
import java.io.*;
import java.nio.charset.StandardCharsets;
final class AppVersion {
    static final String VALUE = load();
    private static String load() {
        try (InputStream in = AppVersion.class.getResourceAsStream("/VERSION")) {
            if (in == null) throw new IOException("VERSION resource missing");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }
}
