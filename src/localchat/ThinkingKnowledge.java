package localchat;

import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * What is known about one model's thinking (1.28.8): does it think by default, and does an "off" parameter really work.
 * Sources, strongest first: a probe result (ThinkingProbe, cached), catalogue metadata (OpenRouter /models "reasoning" object,
 * LM Studio native /api/v1/models capabilities.reasoning) and name rules for the providers verified by hand.
 * Everything is keyed by baseUrl|model and persisted as thinking.probe.&lt;hash&gt; / thinking.meta.&lt;hash&gt; in the settings file.
 */
final class ThinkingKnowledge {
    /** Marker in {@link Info#off}: the model thinks and no off parameter works (or the server rejected it). */
    static final String NO_OFF = "none";

    /** thinks: null = unknown. off: a {@link ThinkingControl.Style} id, {@link #NO_OFF}, or null = unknown. */
    record Info(Boolean thinks, String off, long time) {
        /** Nothing more to learn: the model does not think, or it thinks and the off question is answered. */
        boolean confirmed() {
            return Boolean.FALSE.equals(thinks) || (Boolean.TRUE.equals(thinks) && off != null);
        }

        /** It thinks and a verified or documented off parameter exists. */
        boolean canOff() {
            return Boolean.TRUE.equals(thinks) && off != null && !NO_OFF.equals(off);
        }

        boolean informative() {
            return thinks != null || off != null;
        }

        String serialize() {
            return "thinks=" + (thinks == null ? "?" : thinks ? "1" : "0") + ";off=" + (off == null ? "?" : off) + ";t=" + time;
        }

        static Info parse(String text) {
            Boolean thinks = null;
            String off = null;
            long time = 0;
            if (text != null) for (String part : text.split(";")) {
                int eq = part.indexOf('=');
                if (eq < 0) continue;
                String key = part.substring(0, eq).trim(), value = part.substring(eq + 1).trim();
                switch (key) {
                    case "thinks" -> thinks = value.equals("1") ? Boolean.TRUE : value.equals("0") ? Boolean.FALSE : null;
                    case "off" -> off = value.isEmpty() || value.equals("?") ? null : value;
                    case "t" -> {
                        try {
                            time = Long.parseLong(value);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    default -> {
                    }
                }
            }
            return new Info(thinks, off, time);
        }
    }

    static final Info UNKNOWN = new Info(null, null, 0);
    static final String PROBE_PREFIX = "thinking.probe.", META_PREFIX = "thinking.meta.";
    private static final Map<String, Info> PROBE = new ConcurrentHashMap<>(), META = new ConcurrentHashMap<>();
    private static volatile BiConsumer<String, String> store = (k, v) -> {};

    private ThinkingKnowledge() {}

    static String hash(String baseUrl, String model) {
        return ModelCatalog.hash(ThinkingControl.key(baseUrl, model)).substring(0, 20);
    }

    /** Persist every change through this callback (property name, value). */
    static void onStore(BiConsumer<String, String> listener) {
        store = listener == null ? (k, v) -> {} : listener;
    }

    static void load(Properties config) {
        PROBE.clear();
        META.clear();
        for (String name : config.stringPropertyNames()) {
            if (name.startsWith(PROBE_PREFIX)) PROBE.put(name.substring(PROBE_PREFIX.length()), Info.parse(config.getProperty(name)));
            else if (name.startsWith(META_PREFIX)) META.put(name.substring(META_PREFIX.length()), Info.parse(config.getProperty(name)));
        }
    }

    /** Test support. */
    static void reset() {
        PROBE.clear();
        META.clear();
        store = (k, v) -> {};
    }

    static void recordProbe(String baseUrl, String model, Info info) {
        String h = hash(baseUrl, model);
        PROBE.put(h, info);
        store.accept(PROBE_PREFIX + h, info.serialize());
    }

    static void recordMetadata(String baseUrl, String model, Info info) {
        if (info == null || !info.informative() || model == null || model.isBlank()) return;
        String h = hash(baseUrl, model);
        Info before = META.get(h);
        if (before != null && Objects.equals(before.thinks, info.thinks) && Objects.equals(before.off, info.off)) return;
        META.put(h, info);
        store.accept(META_PREFIX + h, info.serialize());
    }

    static boolean probed(String baseUrl, String model) {
        return PROBE.containsKey(hash(baseUrl, model));
    }

    /** Probe result, else metadata completed by the name rules. */
    static Info lookup(String baseUrl, String model) {
        if (model == null || model.isBlank()) return UNKNOWN;
        Info probe = PROBE.get(hash(baseUrl, model));
        if (probe != null) return probe;
        return merge(META.get(hash(baseUrl, model)), rule(baseUrl, model));
    }

    private static Info merge(Info first, Info second) {
        if (first == null) return second == null ? UNKNOWN : second;
        if (second == null) return first;
        return new Info(first.thinks != null ? first.thinks : second.thinks, first.off != null ? first.off : second.off, Math.max(first.time, second.time));
    }

    // ---- catalogue metadata -------------------------------------------------------------------------------------

    static Boolean bool(Object value) {
        return value instanceof Boolean b ? b : null;
    }

    /** One catalogue row: OpenRouter {@code reasoning} object, LM Studio native {@code capabilities.reasoning}, or nothing. */
    static Info fromRow(Object row) {
        Object openRouter = Json.get(row, "reasoning");
        if (openRouter instanceof Map<?, ?>) {
            Boolean mandatory = bool(Json.get(openRouter, "mandatory")), enabledByDefault = bool(Json.get(openRouter, "default_enabled"));
            Object efforts = Json.get(openRouter, "supported_efforts");
            String off = null;
            if (Boolean.TRUE.equals(mandatory)) off = NO_OFF;
            else if (Boolean.FALSE.equals(mandatory))
                off = efforts instanceof List<?> list && list.stream().anyMatch(v -> "none".equals(v)) ? ThinkingControl.Style.OR_EFFORT_NONE.id : ThinkingControl.Style.OR_DISABLED.id;
            Boolean thinks = enabledByDefault != null ? enabledByDefault : Boolean.TRUE.equals(mandatory) ? Boolean.TRUE : null;
            return new Info(thinks, off, 0);
        }
        Object lmStudio = Json.get(row, "capabilities", "reasoning");
        if (lmStudio instanceof Map<?, ?>) {
            Object allowed = Json.get(lmStudio, "allowed_options");
            String fallback = Json.str(lmStudio, "default");
            String off = null;
            if (allowed instanceof List<?> list && !list.isEmpty())
                off = list.stream().anyMatch(v -> "off".equals(v) || "none".equals(v)) ? ThinkingControl.Style.EFFORT_NONE.id : NO_OFF;
            Boolean thinks = fallback == null ? null : !(fallback.equalsIgnoreCase("off") || fallback.equalsIgnoreCase("none"));
            return new Info(thinks, off, 0);
        }
        /* 1.28.9: Mistral lists a plain boolean capabilities.reasoning. false = it does not think (no probe needed); true = it can reason, whether it does by default stays unknown (probe decides). */
        Boolean mistral = bool(Json.get(row, "capabilities", "reasoning"));
        if (Boolean.FALSE.equals(mistral)) return new Info(Boolean.FALSE, null, 0);
        if (Boolean.TRUE.equals(mistral)) return UNKNOWN;
        Object parameters = Json.get(row, "supported_parameters");
        if (parameters instanceof List<?> list && !list.isEmpty() && list.stream().noneMatch(v -> "reasoning".equals(v) || "reasoning_effort".equals(v) || "include_reasoning".equals(v)))
            return new Info(Boolean.FALSE, null, 0);
        return UNKNOWN;
    }

    /** Called for every catalogue row the app parses. */
    static void learn(String baseUrl, String id, Object row) {
        if (id != null) recordMetadata(baseUrl, id, fromRow(row));
    }

    // ---- name rules for the providers checked by hand -----------------------------------------------------------

    static Info rule(String baseUrl, String model) {
        String host = "";
        try {
            host = Objects.toString(URI.create(baseUrl.trim()).getHost(), "").toLowerCase(Locale.ROOT);
        } catch (RuntimeException ignored) {
        }
        String id = model.trim().toLowerCase(Locale.ROOT);
        String plain = id.substring(id.lastIndexOf('/') + 1);
        String effortNone = ThinkingControl.Style.EFFORT_NONE.id, template = ThinkingControl.Style.TEMPLATE.id;
        switch (host) {
            case "api.mistral.ai" -> {
                if (plain.startsWith("magistral") || plain.contains("glm")) return new Info(Boolean.TRUE, NO_OFF, 0);
                if (plain.startsWith("mistral-small") || plain.startsWith("mistral-medium") || plain.startsWith("mistral-large-4"))
                    return new Info(null, effortNone, 0);
            }
            case "integrate.api.nvidia.com" -> {
                if (plain.contains("gpt-oss")) return new Info(Boolean.TRUE, NO_OFF, 0);
                if (plain.contains("step-5-preview")) return new Info(Boolean.TRUE, effortNone, 0);
                boolean auxiliary = plain.contains("safety") || plain.contains("embed") || plain.contains("guard") || plain.contains("rerank");
                if (!auxiliary && (plain.contains("nemotron") || plain.contains("qwen3"))) return new Info(Boolean.TRUE, template, 0);
            }
            case "api.gonkarouter.io" -> {
                if (plain.contains("minimax")) return new Info(Boolean.TRUE, NO_OFF, 0);
                if (plain.contains("deepseek-v4-flash")) return new Info(Boolean.FALSE, null, 0);
            }
            default -> {
            }
        }
        if (plain.contains("gpt-oss")) return new Info(Boolean.TRUE, NO_OFF, 0);
        return null;
    }
}
