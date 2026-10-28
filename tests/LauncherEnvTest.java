package localchat;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.*;

/**
 * 1.28.4: Explorer can hand LocalChat a user variable such as JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStoreType=Windows-ROOT.
 * The bundled jlink runtime has no jdk.crypto.mscapi, so every HTTPS call then fails in the default SSLContext.
 * This test builds a runtime the same way Build.ps1 does, runs the real launcher with the bad variables and requires a
 * working default SSLContext; the 1.28.3 launcher (kept below as a fixture) must fail the same scenario.
 */
public final class LauncherEnvTest {
    static int checks;
    static final String BAD = "-Djavax.net.ssl.trustStoreType=Windows-ROOT";
    static final String[] VARS = {"JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"};
    /** Launcher shipped in 1.28.3 and earlier (does not touch the Java option variables). */
    static final String OLD_LAUNCHER = String.join("\r\n",
        "@echo off",
        "set \"APP=%~dp0\"",
        "if exist \"%APP%runtime\\bin\\javaw.exe\" (",
        "    start \"\" \"%APP%runtime\\bin\\javaw.exe\" -jar \"%APP%LocalChat.jar\"",
        "    exit /b 0",
        ")",
        "start \"\" javaw -jar \"%APP%LocalChat.jar\"") + "\r\n";

    static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }

    /** Runs inside the bundled-style runtime in place of LocalChat.jar. */
    public static final class Probe {
        public static void main(String[] args) throws Exception {
            Path dir = Path.of(Probe.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent();
            StringBuilder out = new StringBuilder();
            try { String protocol = javax.net.ssl.SSLContext.getDefault().getProtocol(); out.append("SSL=OK ").append(protocol); }
            catch (Throwable e) { out.append("SSL=FAIL ").append(e); }
            for (String name : new String[] {"JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"}) out.append('\n').append(name).append('=').append(System.getenv(name) == null ? "<unset>" : "<set>");
            Path tmp = dir.resolve("probe-result.tmp");
            Files.writeString(tmp, out.toString());
            Files.move(tmp, dir.resolve("probe-result.txt"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void run(List<String> command, Map<String, String> env, long seconds) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
        for (String name : VARS) pb.environment().remove(name);
        pb.environment().putAll(env);
        Process p = pb.start();
        if (!p.waitFor(seconds, TimeUnit.SECONDS)) { p.destroyForcibly(); throw new AssertionError("timeout: " + command); }
    }

    /** Result written by the probe, or "none" when the JVM never reached main. */
    static String await(Path result, long seconds) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < end) {
            if (Files.exists(result)) return Files.readString(result);
            Thread.sleep(200);
        }
        return "none";
    }

    static String launch(Path launcher, Map<String, String> env, Path result) throws Exception {
        Files.deleteIfExists(result);
        run(List.of("cmd.exe", "/c", launcher.toString()), env, 30);
        return await(result, 40);
    }

    public static void main(String[] args) throws Exception {
        if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows")) {
            System.out.println("PASS: 0 launcher checks (not Windows)");
            System.exit(0);
        }
        Path home = Path.of(System.getProperty("localchat.home", "build/launcher-env-home")).toAbsolutePath();
        Path app = home.resolve("app");
        if (Files.exists(app)) try (var walk = Files.walk(app)) { for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p); }
        Files.createDirectories(app);

        // Runtime built like Build.ps1 builds the shipped one (no jdk.crypto.mscapi).
        Path jlink = Path.of(System.getProperty("java.home"), "bin", "jlink.exe");
        check(Files.exists(jlink), "jlink not found in " + jlink);
        run(List.of(jlink.toString(), "--add-modules", "java.base,java.desktop,jdk.crypto.ec", "--strip-debug", "--no-header-files",
            "--no-man-pages", "--output", app.resolve("runtime").toString()), Map.of(), 180);
        check(Files.exists(app.resolve("runtime/bin/javaw.exe")), "runtime was not created");
        run(List.of(app.resolve("runtime/bin/java.exe").toString(), "--list-modules"), Map.of(), 30);

        // Probe jar in place of LocalChat.jar.
        Path jar = app.resolve("LocalChat.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "localchat.LauncherEnvTest$Probe");
        String entry = "localchat/LauncherEnvTest$Probe.class";
        try (var in = LauncherEnvTest.class.getClassLoader().getResourceAsStream(entry);
             JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            check(in != null, "probe class not on the classpath");
            out.putNextEntry(new JarEntry(entry));
            out.write(in.readAllBytes());
            out.closeEntry();
        }
        Path result = app.resolve("probe-result.txt");
        Map<String, String> bad = new LinkedHashMap<>();
        for (String name : VARS) bad.put(name, BAD);

        // Control: the bad variable really breaks this runtime, otherwise the test would prove nothing.
        Files.deleteIfExists(result);
        run(List.of(app.resolve("runtime/bin/java.exe").toString(), "-jar", jar.toString()), bad, 40);
        String control = await(result, 5);
        check(control.startsWith("SSL=FAIL"), "cannot reproduce: bundled-style runtime still has a working SSLContext with " + BAD + " -> " + control);
        check(control.toLowerCase(Locale.ROOT).contains("nosuchalgorithm"), "unexpected failure kind: " + control);

        // Sanity: without the variables the launcher starts the runtime with a working SSLContext.
        Path fresh = app.resolve("Porneste LocalChat.bat");
        Path launcherSource = Path.of("packaging", "Porneste LocalChat.bat").toAbsolutePath();
        check(Files.exists(launcherSource), "launcher not found: " + launcherSource);
        Files.copy(launcherSource, fresh, StandardCopyOption.REPLACE_EXISTING);
        String clean = launch(fresh, Map.of(), result);
        check(clean.startsWith("SSL=OK"), "launcher without inherited options must work: " + clean);

        // Old launcher (1.28.3 behaviour) must fail with the inherited variables.
        Path old = app.resolve("Porneste LocalChat.old.bat");
        Files.writeString(old, OLD_LAUNCHER, StandardCharsets.US_ASCII);
        String oldResult = launch(old, bad, result);
        check(oldResult.startsWith("SSL=FAIL") && oldResult.toLowerCase(Locale.ROOT).contains("nosuchalgorithm"), "old launcher should fail with NoSuchAlgorithmException under the bad variables: " + oldResult);

        // New launcher with every variable set to the bad value.
        String fixed = launch(fresh, bad, result);
        check(fixed.startsWith("SSL=OK"), "new launcher must clear the variables and give a working default SSLContext: " + fixed);
        for (String name : VARS) check(fixed.contains(name + "=<unset>"), "launcher did not clear " + name + ": " + fixed);
        // Each variable alone is enough to break the old launcher and must be cleared by the new one.
        for (String name : VARS) {
            String single = launch(fresh, Map.of(name, BAD), result);
            check(single.startsWith("SSL=OK") && single.contains(name + "=<unset>"), "new launcher failed with only " + name + ": " + single);
        }

        // Clear message instead of the raw NoSuchAlgorithmException.
        var raw = new java.io.IOException("connect failed", new java.security.NoSuchAlgorithmException(
            "Error constructing implementation (algorithm: Default, provider: SunJSSE, class: sun.security.ssl.SSLContextImpl$DefaultSSLContext)"));
        check(SslDiagnostics.isContextFailure(raw), "wrapped NoSuchAlgorithmException not recognized");
        check(SslDiagnostics.isContextFailure(new IllegalStateException("Default SSL context init failed")), "message form not recognized");
        check(!SslDiagnostics.isContextFailure(new java.io.IOException("Connection reset")), "plain IOException misclassified");
        check(!SslDiagnostics.isContextFailure(new java.net.UnknownHostException("example.invalid")), "UnknownHost misclassified");
        String key = "Java nu poate initializa SSL/HTTPS (variabile de mediu Java). Verifica JAVA_TOOL_OPTIONS, _JAVA_OPTIONS si JDK_JAVA_OPTIONS (de ex. trustStoreType=Windows-ROOT) si porneste aplicatia cu Porneste LocalChat.bat, care le goleste. Detalii: {0}";
        check(I18n.contains(key), "SSL hint missing from the catalogs");
        var describe = LocalChat.class.getDeclaredMethod("describe", Throwable.class);
        describe.setAccessible(true);
        for (String code : I18n.CODES) {
            I18n.setLanguage(code);
            String text = I18n.text(key, "detail-x");
            check(text.contains("JAVA_TOOL_OPTIONS") && text.contains("JDK_JAVA_OPTIONS") && text.contains("detail-x"), "SSL hint incomplete in " + code + ": " + text);
            check(code.equals("ro") || !text.startsWith("Java nu poate"), "SSL hint untranslated in " + code);
            check(!code.equals("ro") || text.chars().allMatch(c -> c < 128), "Romanian SSL hint has diacritics");
            String shown = (String) describe.invoke(null, raw);
            check(shown.equals(text.replace("detail-x", SslDiagnostics.detail(raw))), "describe() does not use the hint in " + code + ": " + shown);
        }
        check(I18n.keys("en").equals(I18n.keys("ro")) && I18n.keys("tr").equals(I18n.keys("ro")) && I18n.keys("pl").equals(I18n.keys("ro")), "catalog keys diverged");
        I18n.setLanguage("ro");
        System.out.println("PASS: " + checks + " launcher environment checks; real launcher + jlink runtime, bad JAVA_TOOL_OPTIONS/_JAVA_OPTIONS/JDK_JAVA_OPTIONS, old launcher fails, SSL hint in six languages");
        System.exit(0);
    }
}
