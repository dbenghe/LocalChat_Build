package localchat;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Optional "thinking" control for OpenAI-compatible custom servers (LM Studio, Qwen).
 * Verified on the real server (qwen3.6-35b-a3b behind LM Studio): only reasoning_effort="none" switches reasoning off
 * (0 reasoning tokens, ~1 s). chat_template_kwargs.enable_thinking, top-level enable_thinking, reasoning {effort},
 * thinking {type}, /no_think and every other effort level returned 200 but did NOT reduce reasoning, so none of them is sent.
 * There is no verified token-budget parameter either, so "budget" is not offered: the response profile reserve covers it.
 * AUTO sends nothing; since 1.28.7 custom servers default to OFF unless the user explicitly chose AUTO (see effective). A provider/model that rejects the parameter with 400/422 is remembered and never asked again.
 */
final class ThinkingControl {
    enum Mode {
        AUTO, OFF;

        static Mode parse(String value) {
            return "off".equalsIgnoreCase(value == null ? "" : value.trim()) ? OFF : AUTO;
        }

        String id() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * Effective mode of a profile (1.28.7). A custom server thinks off by default (a silent reasoning phase is what trips a ~100 s proxy and
     * what makes compaction and sub-agents slow); only an explicit choice persisted by {@link #choose} (quick settings > Gandire) wins.
     * A profile saved before 1.28.7 has "thinking=auto" without the explicit marker, so it counts as the old default and becomes OFF once.
     * Other providers keep the saved value (the parameter is only ever sent to custom servers anyway).
     */
    static Mode effective(java.util.Properties config, String profile, Provider provider) {
        boolean explicit = Boolean.parseBoolean(config.getProperty(profile + ".thinking.explicit", "false"));
        Mode saved = Mode.parse(config.getProperty(profile + ".thinking", "auto"));
        return explicit || provider != Provider.CUSTOM ? saved : Mode.OFF;
    }

    /** Persist a deliberate user choice; it always wins over the default. */
    static void choose(java.util.Properties config, String profile, Mode mode) {
        config.setProperty(profile + ".thinking", mode.id());
        config.setProperty(profile + ".thinking.explicit", "true");
    }

    static final String PARAMETER = "reasoning_effort";
    static final String OFF_VALUE = "none";
    private static final Set<String> REJECTED = ConcurrentHashMap.newKeySet();
    private static volatile Runnable onChange = () -> {};

    private ThinkingControl() {}

    static String key(String baseUrl, String model) {
        String url = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "").toLowerCase(java.util.Locale.ROOT);
        return url + "|" + (model == null ? "" : model.trim());
    }

    /** Only custom OpenAI-compatible servers get the parameter; official providers have their own reasoning settings. */
    static boolean sends(Provider provider, Mode mode, String baseUrl, String model) {
        return provider == Provider.CUSTOM && mode == Mode.OFF && !REJECTED.contains(key(baseUrl, model));
    }

    static void apply(Map<String, Object> body) {
        body.put(PARAMETER, OFF_VALUE);
    }

    static boolean rejected(String baseUrl, String model) {
        return REJECTED.contains(key(baseUrl, model));
    }

    static void reject(String baseUrl, String model) {
        if (REJECTED.add(key(baseUrl, model))) onChange.run();
    }

    static void onChange(Runnable listener) {
        onChange = listener == null ? () -> {} : listener;
    }

    /** Remembered rejections as "url|model" entries joined with semicolons (stored in setari.properties). */
    static String serialize() {
        java.util.List<String> keys = new java.util.ArrayList<>(REJECTED);
        Collections.sort(keys);
        return String.join("\n", keys).replace("\n", ";");
    }

    static void load(String value) {
        REJECTED.clear();
        if (value == null) return;
        for (String part : value.split(";")) if (!part.isBlank() && part.contains("|")) REJECTED.add(part.trim());
    }

    /** Test support: forget everything remembered without notifying the settings store. */
    static void forget() {
        REJECTED.clear();
    }
}