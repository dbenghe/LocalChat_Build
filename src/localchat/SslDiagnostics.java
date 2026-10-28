package localchat;

import java.util.Locale;

/** Recognizes a broken default SSL context, typically caused by inherited JAVA_TOOL_OPTIONS (1.28.4). */
final class SslDiagnostics {
    private SslDiagnostics() {}

    static boolean isContextFailure(Throwable error) {
        int depth = 0;
        for (Throwable t = error; t != null && depth < 8; t = t.getCause(), depth++) {
            if (t instanceof java.security.NoSuchAlgorithmException) return true;
            String message = t.getMessage();
            if (message == null) continue;
            String lower = message.toLowerCase(Locale.ROOT);
            if (lower.contains("default ssl context") || lower.contains("defaultsslcontext") || lower.contains("sslcontext")) return true;
        }
        return false;
    }

    /** Innermost cause as "Class: message", for the detail part of the hint. */
    static String detail(Throwable error) {
        Throwable root = error;
        for (int i = 0; i < 8 && root.getCause() != null; i++) root = root.getCause();
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}
