package localchat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Application folder, Downloads folder and OS detection, extracted unchanged from LocalChat in 1.28.3. */
final class AppPaths {
    private AppPaths() {}

    /** Settings, conversations, skills and rules live next to LocalChat.jar, so the whole folder stays portable. */
    static Path appDir() {
        String override = System.getProperty("localchat.home");
        if (override != null) return Paths.get(override).toAbsolutePath();
        try {
            File location = new File(LocalChat.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return (location.isFile() ? location.getParentFile() : location).toPath();
        } catch (Exception e) {
            return Paths.get(System.getProperty("user.home"), "LocalChat");
        }
    }

    /** The real Downloads folder (it can be moved to another drive or OneDrive), from the registry. */
    static String resolveDownloads() {
        String fallback = System.getProperty("user.home") + File.separator + "Downloads";
        if (!isWindows()) return fallback;
        try {
            Process p = new ProcessBuilder("reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\User Shell Folders",
                    "/v", "{374DE290-123F-4565-9164-39C4925E467B}").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor(5, TimeUnit.SECONDS);
            Matcher m = Pattern.compile("REG_(?:EXPAND_)?SZ\\s+(.+)").matcher(out);
            if (m.find()) {
                String path = Tools.expand(m.group(1).trim());
                if (new File(path).isDirectory()) return path;
            }
        } catch (IOException e) {
            // keep the default
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return fallback;
    }

    static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
    }
}
