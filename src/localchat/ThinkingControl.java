package localchat;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Optional "thinking" control for OpenAI-compatible custom servers (LM Studio, Qwen, Mistral, NVIDIA, OpenRouter...).
 * Verified on the real server (qwen3.6-35b-a3b behind LM Studio): only reasoning_effort="none" switches reasoning off
 * (0 reasoning tokens, ~1 s). chat_template_kwargs.enable_thinking, top-level enable_thinking, reasoning {effort},
 * thinking {type}, /no_think and every other effort level returned 200 but did NOT reduce reasoning there, so reasoning_effort=none
 * is the default off parameter. Other providers document a different one (OpenRouter reasoning object, NVIDIA chat_template_kwargs);
 * {@link Style} lists them and {@link ThinkingKnowledge} says which one applies to a model.
 * There is no verified token-budget parameter, so "budget" is not offered: the response profile reserve covers it.
 * AUTO sends nothing. Since 1.28.8 a custom model's default is decided per baseUrl|model (see effective): OFF only when it is known that
 * the model thinks AND the off parameter works (metadata, name rules or a cached probe); otherwise AUTO. An explicit user choice always wins.
 * A provider/model that rejects the parameter with 400/422 is remembered and never asked again.
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

    /** The request parameter that switches thinking off; which one works depends on the server. */
    enum Style {
        EFFORT_NONE("effort_none"), OR_EFFORT_NONE("or_effort_none"), OR_DISABLED("or_disabled"), TEMPLATE("template");

        final String id;

        Style(String id) {
            this.id = id;
        }

        static Style parse(String id) {
            for (Style style : values()) if (style.id.equals(id)) return style;
            return null;
        }

        /** OpenRouter: never combine enabled:false with effort or max_tokens, so the reasoning object holds exactly one field. */
        void apply(Map<String, Object> body) {
            switch (this) {
                case EFFORT_NONE -> body.put(PARAMETER, OFF_VALUE);
                case OR_EFFORT_NONE -> body.put("reasoning", Json.obj("effort", OFF_VALUE));
                case OR_DISABLED -> body.put("reasoning", Json.obj("enabled", false));
                case TEMPLATE -> body.put("chat_template_kwargs", Json.obj("enable_thinking", false));
            }
        }
    }

    /** What the app knows about a model, as shown in quick settings > Gandire. */
    enum Detected {
        OFF_WORKS("Detectat: gandeste, se poate opri"),
        NO_OFF("Detectat: gandeste, nu se poate opri"),
        NOT_THINKING("Detectat: nu gandeste"),
        OFF_UNCONFIRMED("Detectat: gandeste, oprirea nu este confirmata"),
        UNKNOWN("Detectat: necunoscut (netestat)");

        final String label;

        Detected(String label) {
            this.label = label;
        }
    }

    /**
     * Effective mode of a profile. An explicit choice persisted by {@link #choose} (quick settings > Gandire) always wins; a legacy
     * "thinking=off" without the marker was a deliberate choice of a pre-1.28.7 menu and is kept. Otherwise a custom model is OFF
     * only when {@link #defaultOff} (known to think and known to switch off); everything else, and every non-custom provider, stays AUTO.
     */
    static Mode effective(java.util.Properties config, String profile, Provider provider, String baseUrl, String model) {
        boolean explicit = Boolean.parseBoolean(config.getProperty(profile + ".thinking.explicit", "false"));
        Mode saved = Mode.parse(config.getProperty(profile + ".thinking", "auto"));
        if (explicit || saved == Mode.OFF || provider != Provider.CUSTOM) return saved;
        return defaultOff(baseUrl, model) ? Mode.OFF : Mode.AUTO;
    }

    static boolean explicit(java.util.Properties config, String profile) {
        return Boolean.parseBoolean(config.getProperty(profile + ".thinking.explicit", "false"))
                || Mode.parse(config.getProperty(profile + ".thinking", "auto")) == Mode.OFF;
    }

    /** Known to think by default and known to switch off, and the server has not rejected the parameter. */
    static boolean defaultOff(String baseUrl, String model) {
        return ThinkingKnowledge.lookup(baseUrl, model).canOff() && !rejected(baseUrl, model);
    }

    /** True when the default (not a user choice) switched thinking off for this model: the one-time notice applies. */
    static boolean defaultedOff(java.util.Properties config, String profile, Provider provider, String baseUrl, String model) {
        return provider == Provider.CUSTOM && !explicit(config, profile) && defaultOff(baseUrl, model);
    }

    static Detected detected(String baseUrl, String model) {
        ThinkingKnowledge.Info info = ThinkingKnowledge.lookup(baseUrl, model);
        if (Boolean.FALSE.equals(info.thinks())) return Detected.NOT_THINKING;
        if (!Boolean.TRUE.equals(info.thinks())) return Detected.UNKNOWN;
        if (info.off() == null) return Detected.OFF_UNCONFIRMED;
        return info.canOff() && !rejected(baseUrl, model) ? Detected.OFF_WORKS : Detected.NO_OFF;
    }

    private static String noticeKey(String baseUrl, String model) {
        return "thinking.notice." + ThinkingKnowledge.hash(baseUrl, model);
    }

    /** One short notice per model, never repeated. */
    static boolean noticeDue(java.util.Properties config, String baseUrl, String model) {
        return !config.containsKey(noticeKey(baseUrl, model));
    }

    static void markNoticed(java.util.Properties config, String baseUrl, String model) {
        config.setProperty(noticeKey(baseUrl, model), "1");
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

    /** The off parameter for this model: the documented/probed style, reasoning_effort=none when nothing is known, null when none works. */
    static Style style(String baseUrl, String model) {
        ThinkingKnowledge.Info info = ThinkingKnowledge.lookup(baseUrl, model);
        if (ThinkingKnowledge.NO_OFF.equals(info.off())) return null;
        Style style = Style.parse(info.off());
        return style != null ? style : Style.EFFORT_NONE;
    }

    /** Only custom OpenAI-compatible servers get the parameter; official providers have their own reasoning settings. */
    static boolean sends(Provider provider, Mode mode, String baseUrl, String model) {
        return provider == Provider.CUSTOM && mode == Mode.OFF && !REJECTED.contains(key(baseUrl, model)) && style(baseUrl, model) != null;
    }

    static void apply(Map<String, Object> body, String baseUrl, String model) {
        Style style = style(baseUrl, model);
        (style != null ? style : Style.EFFORT_NONE).apply(body);
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
        ThinkingKnowledge.reset();
    }
}
