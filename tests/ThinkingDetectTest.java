package localchat;

import java.util.*;

/** 1.28.8: automatic per-model thinking detection: metadata, name rules, streamed probe, cache, notice, chunk-list content (fake servers only). */
public final class ThinkingDetectTest {
    static int checks;
    static void check(boolean ok, String name) {
        checks++;
        if (!ok) throw new AssertionError(name);
    }

    static Object row(String json) {
        return Json.parse(json);
    }

    static String ev(Object obj) {
        return FeatureTest.event(obj);
    }

    static Object delta(Object fields) {
        return Json.obj("choices", List.of(Json.obj("delta", fields)));
    }

    static final String DONE = "data: [DONE]\n\n";

    static FeatureTest.Response thinkingStream() {
        return FeatureTest.sse(ev(delta(Json.obj("reasoning_content", "hmm")))
                + ev(delta(Json.obj("content", "391"))) + DONE);
    }

    static FeatureTest.Response plainStream() {
        return FeatureTest.sse(ev(delta(Json.obj("content", "391"))) + DONE);
    }

    static ThinkingProbe.Result probe(FeatureTest.Server server, ThinkingControl.Style style) {
        return ThinkingProbe.run(Provider.CUSTOM, server.url(), "", "probe-model", style);
    }

    public static void main(String[] args) throws Exception {
        ThinkingControl.forget();
        final String E = ThinkingControl.Style.EFFORT_NONE.id, OE = ThinkingControl.Style.OR_EFFORT_NONE.id, OD = ThinkingControl.Style.OR_DISABLED.id, T = ThinkingControl.Style.TEMPLATE.id, N = ThinkingKnowledge.NO_OFF;

        // ---- metadata: OpenRouter reasoning object -----------------------------------------------------------------
        var mandatory = ThinkingKnowledge.fromRow(row("{\"id\":\"a\",\"reasoning\":{\"mandatory\":true,\"default_enabled\":true,\"supported_efforts\":[\"low\",\"high\"]}}"));
        check(Boolean.TRUE.equals(mandatory.thinks()) && N.equals(mandatory.off()) && mandatory.confirmed() && !mandatory.canOff(), "mandatory reasoning can never be switched off");
        var withNone = ThinkingKnowledge.fromRow(row("{\"reasoning\":{\"mandatory\":false,\"default_enabled\":true,\"supported_efforts\":[\"none\",\"low\"]}}"));
        check(Boolean.TRUE.equals(withNone.thinks()) && OE.equals(withNone.off()) && withNone.canOff(), "optional reasoning with effort none uses reasoning {effort:none}");
        var withoutNone = ThinkingKnowledge.fromRow(row("{\"reasoning\":{\"mandatory\":false,\"default_enabled\":true,\"supported_efforts\":[\"low\",\"high\"]}}"));
        check(OD.equals(withoutNone.off()) && withoutNone.canOff(), "optional reasoning without effort none uses reasoning {enabled:false}");
        var offByDefault = ThinkingKnowledge.fromRow(row("{\"reasoning\":{\"mandatory\":false,\"default_enabled\":false}}"));
        check(Boolean.FALSE.equals(offByDefault.thinks()) && offByDefault.confirmed(), "reasoning that is off by default needs no off parameter");
        check(!ThinkingKnowledge.fromRow(row("{\"reasoning\":{}}")).informative(), "an empty reasoning object tells nothing");
        // ---- metadata: LM Studio native capabilities ----------------------------------------------------------------
        var lm = ThinkingKnowledge.fromRow(row("{\"key\":\"q\",\"capabilities\":{\"reasoning\":{\"allowed_options\":[\"off\",\"on\"],\"default\":\"on\"}}}"));
        check(Boolean.TRUE.equals(lm.thinks()) && E.equals(lm.off()), "LM Studio: default on, off allowed -> reasoning_effort none");
        var lmFixed = ThinkingKnowledge.fromRow(row("{\"capabilities\":{\"reasoning\":{\"allowed_options\":[\"on\"],\"default\":\"on\"}}}"));
        check(Boolean.TRUE.equals(lmFixed.thinks()) && N.equals(lmFixed.off()), "LM Studio: only on is allowed -> cannot be switched off");
        var lmOff = ThinkingKnowledge.fromRow(row("{\"capabilities\":{\"reasoning\":{\"allowed_options\":[\"off\",\"on\"],\"default\":\"off\"}}}"));
        check(Boolean.FALSE.equals(lmOff.thinks()), "LM Studio: default off");
        check(!ThinkingKnowledge.fromRow(row("{\"capabilities\":{\"vision\":true}}")).informative(), "LM Studio row without reasoning capability is unknown, not 'does not think'");
        // supported_parameters is authoritative for 'cannot reason' only when it is present
        check(Boolean.FALSE.equals(ThinkingKnowledge.fromRow(row("{\"supported_parameters\":[\"temperature\",\"tools\"]}")).thinks()), "no reasoning parameter -> does not think");
        check(!ThinkingKnowledge.fromRow(row("{\"supported_parameters\":[\"temperature\",\"reasoning\"]}")).informative(), "reasoning parameter alone does not say it thinks");
        // the catalogue parser registers metadata and flags object-shaped reasoning
        String catalog = "{\"data\":[{\"id\":\"or/thinker\",\"reasoning\":{\"mandatory\":false,\"default_enabled\":true,\"supported_efforts\":[\"none\",\"low\"]}},{\"id\":\"or/forced\",\"reasoning\":{\"mandatory\":true}},{\"id\":\"or/plain\",\"supported_parameters\":[\"temperature\"]}]}";
        List<ModelCatalog.Entry> entries = ModelCatalog.parse(Json.parse(catalog), "https://openrouter.ai/api/v1");
        ModelCatalog.Entry thinker = entries.stream().filter(e -> e.id().equals("or/thinker")).findFirst().orElseThrow();
        check(Boolean.TRUE.equals(thinker.reasoning()), "object-shaped reasoning marks the model as reasoning-capable");
        check(ThinkingControl.defaultOff("https://openrouter.ai/api/v1", "or/thinker") && OE.equals(ThinkingKnowledge.lookup("https://openrouter.ai/api/v1/", "or/thinker").off()), "catalogue metadata is registered per base|model");
        check(!ThinkingControl.defaultOff("https://openrouter.ai/api/v1", "or/forced") && ThinkingControl.style("https://openrouter.ai/api/v1", "or/forced") == null && ThinkingControl.detected("https://openrouter.ai/api/v1", "or/forced") == ThinkingControl.Detected.NO_OFF, "mandatory model: never off, never sends");
        check(ThinkingControl.detected("https://openrouter.ai/api/v1", "or/plain") == ThinkingControl.Detected.NOT_THINKING, "model without reasoning parameter is detected as not thinking");

        // ---- name rules ---------------------------------------------------------------------------------------------
        check(E.equals(ThinkingKnowledge.rule("https://api.mistral.ai/v1/", "mistral-small-latest").off()) && ThinkingKnowledge.rule("https://api.mistral.ai/v1", "mistral-small-latest").thinks() == null, "Mistral small: effort none candidate, thinking unknown");
        check(E.equals(ThinkingKnowledge.rule("https://api.mistral.ai/v1", "mistral-medium-3-5-2604").off()) && E.equals(ThinkingKnowledge.rule("https://api.mistral.ai/v1", "mistral-large-4-0").off()), "Mistral medium 3.5 / large 4");
        check(N.equals(ThinkingKnowledge.rule("https://api.mistral.ai/v1", "magistral-medium-latest").off()) && N.equals(ThinkingKnowledge.rule("https://api.mistral.ai/v1", "zai-glm-4.6").off()), "Magistral and GLM on Mistral cannot be switched off");
        check(ThinkingKnowledge.rule("https://api.mistral.ai/v1", "codestral-latest") == null, "other Mistral models: no rule");
        check(E.equals(ThinkingKnowledge.rule("https://integrate.api.nvidia.com/v1", "stepfun-ai/step-5-preview").off()), "NVIDIA step-5-preview: reasoning_effort none (verified live)");
        check(T.equals(ThinkingKnowledge.rule("https://integrate.api.nvidia.com/v1", "nvidia/llama-3.3-nemotron-super-49b-v1").off()) && T.equals(ThinkingKnowledge.rule("https://integrate.api.nvidia.com/v1", "qwen/qwen3-235b-a22b").off()), "NVIDIA nemotron/qwen3: chat_template_kwargs");
        check(ThinkingKnowledge.rule("https://integrate.api.nvidia.com/v1", "nvidia/llama-3.1-nemoguard-8b-content-safety") == null, "NVIDIA guard models are not thinkers");
        check(N.equals(ThinkingKnowledge.rule("https://integrate.api.nvidia.com/v1", "openai/gpt-oss-120b").off()), "gpt-oss cannot be switched off");
        check(N.equals(ThinkingKnowledge.rule("https://api.gonkarouter.io/v1", "MiniMax-M2").off()), "gonkarouter MiniMax: never controllable");
        check(Boolean.FALSE.equals(ThinkingKnowledge.rule("https://api.gonkarouter.io/v1", "DeepSeek-V4-Flash").thinks()), "gonkarouter DeepSeek-V4-Flash: off by default");
        check(ThinkingKnowledge.rule("https://api.tileward.com/v1", "whatever") == null, "Tileward: unknown, no rule");
        check(ThinkingKnowledge.rule("not a url", "x") == null, "bad url never throws");

        // ---- off styles reach the request ---------------------------------------------------------------------------
        String[][] styles = {{E, "reasoning_effort"}, {OE, "reasoning"}, {OD, "reasoning"}, {T, "chat_template_kwargs"}};
        for (String[] style : styles) {
            try (var server = new FeatureTest.Server(FeatureTest.json(FeatureTest.answer("ok", 5, 2)))) {
                String model = "style-" + style[0];
                ThinkingKnowledge.recordProbe(server.url(), model, new ThinkingKnowledge.Info(Boolean.TRUE, style[0], 1));
                ChatClient client = new ChatClient();
                client.thinking(ThinkingControl.Mode.OFF);
                client.chat(Provider.CUSTOM, server.url(), "", model, "s", List.of(ChatClient.Message.user("hi")), List.of(), false, x -> {});
                Object body = server.requests.get(0);
                boolean right = switch (style[0]) {
                    case "effort_none" -> "none".equals(Json.get(body, "reasoning_effort")) && Json.get(body, "reasoning") == null;
                    case "or_effort_none" -> "none".equals(Json.get(body, "reasoning", "effort")) && Json.get(body, "reasoning", "enabled") == null && Json.get(body, "reasoning_effort") == null;
                    case "or_disabled" -> Boolean.FALSE.equals(Json.get(body, "reasoning", "enabled")) && Json.get(body, "reasoning", "effort") == null && Json.get(body, "reasoning", "max_tokens") == null;
                    default -> Boolean.FALSE.equals(Json.get(body, "chat_template_kwargs", "enable_thinking")) && Json.get(body, "reasoning_effort") == null;
                };
                check(right, "request body for style " + style[0]);
            }
        }
        try (var server = new FeatureTest.Server(FeatureTest.json(FeatureTest.answer("ok", 5, 2)))) {
            ThinkingKnowledge.recordProbe(server.url(), "no-off", new ThinkingKnowledge.Info(Boolean.TRUE, N, 1));
            ChatClient client = new ChatClient();
            client.thinking(ThinkingControl.Mode.OFF);
            client.chat(Provider.CUSTOM, server.url(), "", "no-off", "s", List.of(ChatClient.Message.user("hi")), List.of(), false, x -> {});
            Object body = server.requests.get(0);
            check(Json.get(body, "reasoning_effort") == null && Json.get(body, "reasoning") == null && Json.get(body, "chat_template_kwargs") == null, "a model that cannot be switched off never gets an off parameter, even when Off is chosen");
        }
        try (var server = new FeatureTest.Server(FeatureTest.json(FeatureTest.answer("ok", 5, 2)))) {
            ChatClient client = new ChatClient();
            client.thinking(ThinkingControl.Mode.OFF);
            client.chat(Provider.CUSTOM, server.url(), "", "never-seen", "s", List.of(ChatClient.Message.user("hi")), List.of(), false, x -> {});
            check("none".equals(Json.get(server.requests.get(0), "reasoning_effort")), "explicit Off on an unknown model tries reasoning_effort=none (LM Studio verified)");
        }

        // ---- probe: classification table ------------------------------------------------------------------------------
        var ThinkYes = new ThinkingProbe.Observation(true, 200, null);
        var ThinkNo = new ThinkingProbe.Observation(false, 200, null);
        ThinkingControl.Style effort = ThinkingControl.Style.EFFORT_NONE;
        check(ThinkingProbe.classify(ThinkYes, ThinkNo, effort).outcome() == ThinkingProbe.Outcome.OFF_OK && ThinkingProbe.classify(ThinkYes, ThinkNo, effort).info().canOff(), "classify: thinks, off removes it -> off works");
        check(ThinkingProbe.classify(ThinkYes, ThinkYes, effort).outcome() == ThinkingProbe.Outcome.OFF_IGNORED && !ThinkingProbe.classify(ThinkYes, ThinkYes, effort).info().canOff(), "classify: silent no-op -> not controllable");
        check(ThinkingProbe.classify(ThinkYes, new ThinkingProbe.Observation(false, 400, "bad"), effort).outcome() == ThinkingProbe.Outcome.OFF_REJECTED && ThinkingProbe.classify(ThinkYes, new ThinkingProbe.Observation(false, 422, "bad"), effort).outcome() == ThinkingProbe.Outcome.OFF_REJECTED, "classify: 400/422 -> rejected");
        check(ThinkingProbe.classify(ThinkNo, null, effort).outcome() == ThinkingProbe.Outcome.NO_REASONING && Boolean.FALSE.equals(ThinkingProbe.classify(ThinkNo, null, effort).info().thinks()), "classify: no reasoning at all");
        check(ThinkingProbe.classify(new ThinkingProbe.Observation(false, 401, "no"), null, effort).outcome() == ThinkingProbe.Outcome.ERROR && ThinkingProbe.classify(new ThinkingProbe.Observation(false, 401, "no"), null, effort).info() == null, "classify: errors learn nothing");
        check(ThinkingProbe.classify(ThinkYes, new ThinkingProbe.Observation(false, 500, "boom"), effort).outcome() == ThinkingProbe.Outcome.ERROR, "classify: a failing second request learns nothing");

        // ---- probe against a fake SSE server ------------------------------------------------------------------------
        try (var server = new FeatureTest.Server(thinkingStream(), plainStream())) {
            var result = probe(server, null);
            check(result.outcome() == ThinkingProbe.Outcome.OFF_OK && E.equals(result.info().off()), "probe: thinks, effort none works");
            check(server.requests.size() == 2, "probe sends exactly two requests");
            Object first = server.requests.get(0), second = server.requests.get(1);
            check(Json.get(first, "reasoning_effort") == null && Boolean.TRUE.equals(Json.get(first, "stream")) && ((Number) Json.get(first, "max_tokens")).intValue() == 32, "probe request 1: no off parameter, streamed, max_tokens 32");
            check("none".equals(Json.get(second, "reasoning_effort")) && ((Number) Json.get(second, "max_tokens")).intValue() == 32, "probe request 2 carries reasoning_effort=none");
            ThinkingKnowledge.recordProbe(server.url(), "probe-model", result.info());
            check(ThinkingControl.defaultOff(server.url(), "probe-model") && ThinkingControl.detected(server.url(), "probe-model") == ThinkingControl.Detected.OFF_WORKS, "probe result switches the default to off");
        }
        try (var server = new FeatureTest.Server(thinkingStream(), thinkingStream())) {
            var result = probe(server, null);
            check(result.outcome() == ThinkingProbe.Outcome.OFF_IGNORED && !result.info().canOff(), "probe: parameter accepted but ignored");
            ThinkingKnowledge.recordProbe(server.url(), "probe-model", result.info());
            check(!ThinkingControl.defaultOff(server.url(), "probe-model") && ThinkingControl.style(server.url(), "probe-model") == null && ThinkingControl.detected(server.url(), "probe-model") == ThinkingControl.Detected.NO_OFF, "no-op: stays automatic and sends nothing");
        }
        try (var server = new FeatureTest.Server(thinkingStream(), new FeatureTest.Response(400, "application/json", "{\"error\":{\"message\":\"Unrecognized request argument supplied: reasoning_effort\"}}"))) {
            var result = probe(server, null);
            check(result.outcome() == ThinkingProbe.Outcome.OFF_REJECTED && server.requests.size() == 2, "probe: 400 means rejected and is not retried without the parameter");
            check(!ThinkingControl.rejected(server.url(), "probe-model"), "probe never records a rejection in the live rejection list");
        }
        try (var server = new FeatureTest.Server(plainStream())) {
            var result = probe(server, null);
            check(result.outcome() == ThinkingProbe.Outcome.NO_REASONING && server.requests.size() == 1, "probe: a model that does not think costs one request");
            ThinkingKnowledge.recordProbe(server.url(), "probe-model", result.info());
            check(!ThinkingControl.defaultOff(server.url(), "probe-model") && ThinkingControl.detected(server.url(), "probe-model") == ThinkingControl.Detected.NOT_THINKING, "non-thinker stays automatic");
        }
        String usageOnly = ev(delta(Json.obj("content", "391"))) + ev(Json.obj("choices", List.of(), "usage", Json.obj("prompt_tokens", 20, "completion_tokens", 25, "completion_tokens_details", Json.obj("reasoning_tokens", 20)))) + DONE;
        try (var server = new FeatureTest.Server(FeatureTest.sse(usageOnly), plainStream())) {
            check(probe(server, null).outcome() == ThinkingProbe.Outcome.OFF_OK, "probe: reasoning tokens in usage count as thinking");
        }
        String inline = ev(delta(Json.obj("content", "<think>hmm</think>391"))) + DONE;
        try (var server = new FeatureTest.Server(FeatureTest.sse(inline), plainStream())) {
            check(probe(server, null).outcome() == ThinkingProbe.Outcome.OFF_OK, "probe: inline think tag counts as thinking");
        }
        String chunks = ev(delta(Json.obj("content", List.of(Json.obj("type", "thinking", "thinking", List.of(Json.obj("type", "text", "text", "hmm"))))))) + ev(delta(Json.obj("content", List.of(Json.obj("type", "text", "text", "391"))))) + DONE;
        try (var server = new FeatureTest.Server(FeatureTest.sse(chunks), plainStream())) {
            check(probe(server, null).outcome() == ThinkingProbe.Outcome.OFF_OK, "probe: Mistral thinking chunks count as thinking");
        }
        try (var server = new FeatureTest.Server(FeatureTest.sse(ev(delta(Json.obj("reasoning", "hmm"))) + ev(delta(Json.obj("content", "391"))) + DONE), plainStream())) {
            var result = probe(server, ThinkingControl.Style.TEMPLATE);
            check(result.outcome() == ThinkingProbe.Outcome.OFF_OK && T.equals(result.info().off()), "probe: reasoning delta field and a template candidate");
            check(Boolean.FALSE.equals(Json.get(server.requests.get(1), "chat_template_kwargs", "enable_thinking")) && Json.get(server.requests.get(1), "reasoning_effort") == null, "probe uses the candidate style for the second request");
        }
        try (var server = new FeatureTest.Server(new FeatureTest.Response(401, "application/json", "{\"error\":{\"message\":\"bad key\"}}"))) {
            var result = probe(server, null);
            check(result.outcome() == ThinkingProbe.Outcome.ERROR && result.info() == null && server.requests.size() == 1, "probe: auth failure is an error that learns nothing");
        }

        // ---- cache -------------------------------------------------------------------------------------------------
        Properties saved = new Properties();
        ThinkingKnowledge.reset();
        ThinkingKnowledge.onStore(saved::setProperty);
        ThinkingKnowledge.recordProbe("http://lan:1234/v1/", "Qwen", new ThinkingKnowledge.Info(Boolean.TRUE, E, 77));
        ThinkingKnowledge.recordMetadata("http://lan:1234/v1", "meta-model", new ThinkingKnowledge.Info(Boolean.TRUE, OD, 0));
        ThinkingKnowledge.recordMetadata("http://lan:1234/v1", "empty", ThinkingKnowledge.UNKNOWN);
        check(saved.size() == 2 && saved.keySet().stream().allMatch(k -> k.toString().startsWith("thinking.probe.") || k.toString().startsWith("thinking.meta.")), "only informative results are persisted, under thinking.probe/meta.<hash>");
        check(saved.values().stream().noneMatch(v -> v.toString().contains("lan:1234") || v.toString().contains("Qwen")), "persisted values do not contain the url or model name");
        ThinkingKnowledge.recordMetadata("http://lan:1234/v1", "meta-model", new ThinkingKnowledge.Info(Boolean.TRUE, OD, 0));
        check(saved.size() == 2, "unchanged metadata is not rewritten");
        ThinkingKnowledge.reset();
        Properties reloaded = new Properties();
        java.io.StringWriter text = new java.io.StringWriter();
        saved.store(text, "");
        reloaded.load(new java.io.StringReader(text.toString()));
        ThinkingKnowledge.load(reloaded);
        check(ThinkingKnowledge.probed("http://LAN:1234/v1", "Qwen") && ThinkingKnowledge.lookup("http://lan:1234/v1", "Qwen").canOff() && E.equals(ThinkingKnowledge.lookup("http://lan:1234/v1", "Qwen").off()), "probe result survives a settings reload (url case and trailing slash ignored)");
        check(OD.equals(ThinkingKnowledge.lookup("http://lan:1234/v1", "meta-model").off()) && !ThinkingKnowledge.probed("http://lan:1234/v1", "meta-model"), "metadata survives a reload without counting as a probe");
        check(!ThinkingKnowledge.probed("http://lan:1234/v1", "Qwen2"), "another model is not cached");
        ThinkingKnowledge.recordProbe("http://lan:1234/v1", "meta-model", new ThinkingKnowledge.Info(Boolean.TRUE, N, 99));
        check(!ThinkingKnowledge.lookup("http://lan:1234/v1", "meta-model").canOff(), "a probe overrides metadata");
        check(ThinkingKnowledge.Info.parse(new ThinkingKnowledge.Info(Boolean.FALSE, null, 5).serialize()).equals(new ThinkingKnowledge.Info(Boolean.FALSE, null, 5)) && !ThinkingKnowledge.Info.parse("garbage;;=").informative(), "info round trip and tolerant parsing");
        ThinkingKnowledge.reset();
        var merged = ThinkingKnowledge.lookup("https://api.mistral.ai/v1", "mistral-small-latest");
        ThinkingKnowledge.recordMetadata("https://api.mistral.ai/v1", "mistral-small-latest", new ThinkingKnowledge.Info(Boolean.TRUE, null, 0));
        var mergedWithMeta = ThinkingKnowledge.lookup("https://api.mistral.ai/v1", "mistral-small-latest");
        check(merged.thinks() == null && Boolean.TRUE.equals(mergedWithMeta.thinks()) && E.equals(mergedWithMeta.off()) && mergedWithMeta.canOff(), "metadata and name rule complete each other field by field");

        // ---- default, explicit choice, notice -----------------------------------------------------------------------
        ThinkingControl.forget();
        String url = "http://lan:1234/v1", model = "thinker", profile = "custom|thinker";
        Properties cfg = new Properties();
        check(ThinkingControl.effective(cfg, profile, Provider.CUSTOM, url, model) == ThinkingControl.Mode.AUTO && !ThinkingControl.defaultedOff(cfg, profile, Provider.CUSTOM, url, model) && ThinkingControl.detected(url, model) == ThinkingControl.Detected.UNKNOWN, "unknown model: automatic, no notice");
        ThinkingKnowledge.recordProbe(url, model, new ThinkingKnowledge.Info(Boolean.TRUE, E, 1));
        check(ThinkingControl.effective(cfg, profile, Provider.CUSTOM, url, model) == ThinkingControl.Mode.OFF && ThinkingControl.defaultedOff(cfg, profile, Provider.CUSTOM, url, model), "confirmed thinker defaults to off and counts as defaulted");
        ThinkingControl.choose(cfg, profile, ThinkingControl.Mode.AUTO);
        check(ThinkingControl.effective(cfg, profile, Provider.CUSTOM, url, model) == ThinkingControl.Mode.AUTO && !ThinkingControl.defaultedOff(cfg, profile, Provider.CUSTOM, url, model), "explicit Automatic wins over the detected default");
        check(ThinkingControl.effective(new Properties(), profile, Provider.OPENAI, url, model) == ThinkingControl.Mode.AUTO && !ThinkingControl.defaultedOff(new Properties(), profile, Provider.OPENAI, url, model), "built-in providers are never switched");
        ThinkingControl.reject(url, model);
        check(ThinkingControl.effective(new Properties(), profile, Provider.CUSTOM, url, model) == ThinkingControl.Mode.AUTO && ThinkingControl.detected(url, model) == ThinkingControl.Detected.NO_OFF, "a server that rejected the parameter falls back to automatic");
        ThinkingControl.forget();
        ThinkingKnowledge.recordProbe(url, model, new ThinkingKnowledge.Info(Boolean.TRUE, E, 1));
        Properties notice = new Properties();
        check(ThinkingControl.noticeDue(notice, url, model), "notice is due the first time");
        ThinkingControl.markNoticed(notice, url, model);
        check(!ThinkingControl.noticeDue(notice, url, model + "") && !ThinkingControl.noticeDue(notice, url + "/", model) && ThinkingControl.noticeDue(notice, url, "other-model"), "notice shows once per model");
        Properties reloadedNotice = new Properties();
        java.io.StringWriter noticeText = new java.io.StringWriter();
        notice.store(noticeText, "");
        reloadedNotice.load(new java.io.StringReader(noticeText.toString()));
        check(!ThinkingControl.noticeDue(reloadedNotice, url, model), "the notice is not repeated after a restart");
        check(ThinkingControl.Detected.values().length == 5 && Arrays.stream(ThinkingControl.Detected.values()).allMatch(d -> d.label.startsWith("Detectat: ")), "five detected states with labels");

        // ---- Mistral chunk-list content -----------------------------------------------------------------------------
        String plainList = Json.write(Json.obj("choices", List.of(Json.obj("message", Json.obj("content", List.of(
                Json.obj("type", "thinking", "thinking", List.of(Json.obj("type", "text", "text", "let me think"))),
                Json.obj("type", "text", "text", "The answer"), Json.obj("type", "text", "text", " is 42"))))), "usage", Json.obj("prompt_tokens", 5, "completion_tokens", 9)));
        try (var server = new FeatureTest.Server(FeatureTest.json(plainList))) {
            ChatClient.Reply reply = new ChatClient().chat(Provider.CUSTOM, server.url(), "", "m", "s", List.of(ChatClient.Message.user("hi")), List.of(), false, x -> {});
            check(reply.text.equals("The answer is 42") && reply.reasoning.equals("let me think"), "non-streamed chunk list: text and thinking separated");
        }
        String streamList = ev(delta(Json.obj("content", List.of(Json.obj("type", "thinking", "thinking", List.of(Json.obj("type", "text", "text", "pondering")))))))
                + ev(delta(Json.obj("content", List.of(Json.obj("type", "text", "text", "Hello"))))) + ev(delta(Json.obj("content", " world"))) + DONE;
        try (var server = new FeatureTest.Server(FeatureTest.sse(streamList))) {
            StringBuilder visible = new StringBuilder(), thought = new StringBuilder();
            ChatClient.Reply reply = new ChatClient().chat(Provider.CUSTOM, server.url(), "", "m", "s", List.of(ChatClient.Message.user("hi")), List.of(), true, new ChatClient.Listener() {
                public void onText(String d) {
                    visible.append(d);
                }

                public void onReasoning(String d) {
                    thought.append(d);
                }
            });
            check(reply.text.equals("Hello world") && visible.toString().equals("Hello world") && thought.toString().equals("pondering") && reply.reasoning.equals("pondering"), "streamed chunk list: thinking is reasoning, text is the answer, plain strings still work");
        }
        check(ChatClient.contentText("plain").equals("plain") && ChatClient.contentText(null).isEmpty() && ChatClient.contentText(List.of("a", Json.obj("type", "text", "text", "b"), Json.obj("type", "image_url"))).equals("ab"), "contentText handles strings, nulls and mixed lists");

        ThinkingControl.forget();
        System.out.println("PASS: " + checks + " thinking detection checks");
        System.exit(0);
    }
}
