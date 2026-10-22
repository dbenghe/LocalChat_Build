package localchat;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Talks to OpenAI-style (/chat/completions) and Anthropic (/messages) endpoints, with streaming and tool calls. */
final class ChatClient {
    static final String ANTHROPIC_VERSION = "2023-06-01";
    static final int MAX_TOKENS = 16000;

    interface Listener {
        void onText(String delta);
    }

    /** A tool the model asked to run. */
    static final class ToolCall {
        final String id;
        final String name;
        final Map<String, Object> args;

        ToolCall(String id, String name, Map<String, Object> args) {
            this.id = id;
            this.name = name;
            this.args = args;
        }

        Map<String, Object> toJson() {
            return Json.obj("id", id, "name", name, "args", args);
        }

        @SuppressWarnings("unchecked")
        static ToolCall fromJson(Object o) {
            Object args = Json.get(o, "args");
            return new ToolCall(Json.str(o, "id"), Json.str(o, "name"),
                    args instanceof Map ? (Map<String, Object>) args : new LinkedHashMap<>());
        }
    }

    /** A tool offered to the model: name, what it does, and a JSON schema for its arguments. */
    static final class ToolSpec {
        final String name;
        final String description;
        final Map<String, Object> schema;

        ToolSpec(String name, String description, Map<String, Object> schema) {
            this.name = name;
            this.description = description;
            this.schema = schema;
        }
    }

    /** One entry of the conversation: user text, assistant text plus tool calls, or a tool result. */
    static final class Message {
        String requestControl=""; // Ephemeral wire-only application setting; never archived.
        String displayLanguage; // Local display metadata only; omitted from provider payloads.
        final String role; // "user", "assistant" or "tool"
        final String content;
        final List<ToolCall> toolCalls; // assistant only
        final String toolCallId; // tool only: the call this answers
        final boolean isError; // tool only
        final String label; // assistant only: "Claude · model", shown in the transcript

        private Message(String role, String content, List<ToolCall> toolCalls, String toolCallId, boolean isError, String label) {
            this.role = role;
            this.content = content == null ? "" : content;
            this.toolCalls = toolCalls;
            this.toolCallId = toolCallId;
            this.isError = isError;
            this.label = label;
        }

        static Message user(String text) {
            return new Message("user", text, Collections.emptyList(), null, false, null);
        }

        static Message assistant(String text, List<ToolCall> calls, String label) {
            return new Message("assistant", text, calls, null, false, label);
        }

        static Message toolResult(String callId, String text, boolean isError) {
            return new Message("tool", text, Collections.emptyList(), callId, isError, null);
        }

        Message withRequestControl(String control){Message copy=new Message(role,content,toolCalls,toolCallId,isError,label);copy.displayLanguage=displayLanguage;copy.requestControl=control;return copy;}

        Message withDisplayLanguage(String language){Message copy=new Message(role,content,toolCalls,toolCallId,isError,label);copy.displayLanguage=InternationalChat.valid(language);return copy;}

        Map<String, Object> toJson() {
            Map<String, Object> m = Json.obj("role", role, "content", content);
            if(displayLanguage!=null)m.put("displayLanguage",displayLanguage);
            if (!toolCalls.isEmpty()) {
                List<Object> calls = new ArrayList<>();
                for (ToolCall c : toolCalls) calls.add(c.toJson());
                m.put("tool_calls", calls);
            }
            if (toolCallId != null) m.put("tool_call_id", toolCallId);
            if (isError) m.put("is_error", true);
            if (label != null) m.put("label", label);
            return m;
        }

        static Message fromJson(Object o) {
            List<ToolCall> calls = new ArrayList<>();
            Object list = Json.get(o, "tool_calls");
            if (list instanceof List) for (Object c : (List<?>) list) calls.add(ToolCall.fromJson(c));
            Message m=new Message(Json.str(o, "role"), Json.str(o, "content"), calls, Json.str(o, "tool_call_id"),
                    Boolean.TRUE.equals(Json.get(o, "is_error")), Json.str(o, "label"));
            String language=Json.str(o,"displayLanguage");if(language!=null)m.displayLanguage=InternationalChat.valid(language);return m;
        }
    }

    /** What the model answered in one request. */
    static final class Reply {
        final String text;
        final List<ToolCall> calls;

        Reply(String text, List<ToolCall> calls) {
            this.text = text;
            this.calls = calls;
        }
    }

    private static final class CallBuilder {
        String id;
        String name;
        final StringBuilder args = new StringBuilder();
    }

    private int outputLimit;
    void outputLimit(int tokens) {outputLimit=tokens;}
    private boolean omitUsageOption;
    private volatile TokenUsage lastUsage = TokenUsage.UNKNOWN;
    TokenUsage usage() { return lastUsage; }

    private volatile HttpURLConnection active;
    private volatile boolean cancelled;

    void cancel() {
        cancelled = true;
        final HttpURLConnection c = active;
        if (c != null) {
            // disconnect() waits for the reader's current read() to return, so never block the caller (the UI thread).
            Thread t = new Thread(c::disconnect, "chat-cancel");
            t.setDaemon(true);
            t.start();
        }
    }

    /** Sends the conversation; reply text is also pushed to the listener as it arrives. */
    Reply chat(Provider p, String baseUrl, String apiKey, String model, String system, List<Message> history,
               List<ToolSpec> tools, boolean stream, Listener listener) throws IOException {
        lastUsage = TokenUsage.UNKNOWN;
        boolean anthropic = p.format == Provider.Format.ANTHROPIC;
        Map<String, Object> body = anthropic
                ? anthropicBody(model, system, history, tools, stream)
                : openAiBody(model, system, history, tools, stream);

        if(outputLimit>0)body.put(p==Provider.OPENAI?"max_completion_tokens":"max_tokens",outputLimit);
        if (stream && !anthropic && !omitUsageOption) body.put("stream_options", Json.obj("include_usage", true));
        HttpURLConnection conn = open(p, baseUrl + (anthropic ? "/messages" : "/chat/completions"), apiKey, "POST");
        active = conn;
        try {
            byte[] out = Json.write(body).getBytes(StandardCharsets.UTF_8);
            // No fixed-length streaming mode: with it, HttpURLConnection drops the error body of a 401.
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(out);
            }
            checkStatus(conn);
            return readReply(conn, anthropic, listener);
        } catch (IOException e) {
            if (cancelled) return new Reply("", Collections.emptyList());
            if(p==Provider.CUSTOM && stream && !omitUsageOption && e.getMessage()!=null
                    && e.getMessage().contains("400") && e.getMessage().toLowerCase(java.util.Locale.ROOT).contains("stream_options")) {
                omitUsageOption=true;conn.disconnect();return chat(p,baseUrl,apiKey,model,system,history,tools,stream,listener);
            }
            throw e;
        } finally {
            active = null;
            conn.disconnect();
        }
    }

    private static Map<String, Object> openAiBody(String model, String system, List<Message> history,
                                                  List<ToolSpec> tools, boolean stream) {
        List<Object> messages = new ArrayList<>();
        if (!system.isEmpty()) messages.add(Json.obj("role", "system", "content", system));
        for (Message m : history) {
            switch (m.role) {
                case "assistant" -> {
                    Map<String, Object> a = Json.obj("role", "assistant");
                    if (!m.content.isEmpty() || m.toolCalls.isEmpty()) a.put("content", m.content);
                    if (!m.toolCalls.isEmpty()) {
                        List<Object> calls = new ArrayList<>();
                        for (ToolCall c : m.toolCalls) {
                            calls.add(Json.obj("id", c.id, "type", "function",
                                    "function", Json.obj("name", c.name, "arguments", Json.write(c.args))));
                        }
                        a.put("tool_calls", calls);
                    }
                    messages.add(a);
                }
                case "tool" -> messages.add(Json.obj("role", "tool", "tool_call_id", m.toolCallId, "content", m.content));
                default -> {messages.add(Json.obj("role", "user", "content", m.content));if(!m.requestControl.isBlank())messages.add(Json.obj("role","user","content",m.requestControl));}
            }
        }
        Map<String, Object> body = Json.obj("model", model, "messages", messages, "stream", stream);
        if (!tools.isEmpty()) {
            List<Object> list = new ArrayList<>();
            for (ToolSpec t : tools) {
                list.add(Json.obj("type", "function", "function",
                        Json.obj("name", t.name, "description", t.description, "parameters", t.schema)));
            }
            body.put("tools", list);
        }
        return body;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> anthropicBody(String model, String system, List<Message> history,
                                                     List<ToolSpec> tools, boolean stream) {
        List<Map<String, Object>> messages = new ArrayList<>();
        for (Message m : history) {
            if (m.role.equals("assistant")) {
                List<Object> blocks = new ArrayList<>();
                if (!m.content.isEmpty()) blocks.add(Json.obj("type", "text", "text", m.content));
                for (ToolCall c : m.toolCalls) {
                    blocks.add(Json.obj("type", "tool_use", "id", anthropicId(c.id), "name", c.name, "input", c.args));
                }
                if (blocks.isEmpty()) blocks.add(Json.obj("type", "text", "text", "(fara text)"));
                messages.add(Json.obj("role", "assistant", "content", blocks));
                continue;
            }
            Map<String, Object> block;
            if (m.role.equals("tool")) {
                block = Json.obj("type", "tool_result", "tool_use_id", anthropicId(m.toolCallId),
                        "content", m.content.isEmpty() ? "(gol)" : m.content);
                if (m.isError) block.put("is_error", true);
            } else {
                block = Json.obj("type", "text", "text", m.content);
            }
            // Tool results and the user's next text share one user message; Anthropic wants strict alternation.
            Map<String, Object> last = messages.isEmpty() ? null : messages.get(messages.size() - 1);
            if (last != null && "user".equals(last.get("role"))) {
                ((List<Object>) last.get("content")).add(block);
            } else {
                List<Object> content = new ArrayList<>();
                content.add(block);
                messages.add(Json.obj("role", "user", "content", content));
            }
            if(m.role.equals("user")&&!m.requestControl.isBlank())((List<Object>)messages.get(messages.size()-1).get("content")).add(Json.obj("type","text","text",m.requestControl));
        }
        // Cache the conversation so far: an agent loop resends all of it on every step.
        if (!messages.isEmpty()) {
            List<Object> content = (List<Object>) messages.get(messages.size() - 1).get("content");
            ((Map<String, Object>) content.get(content.size() - 1)).put("cache_control", Json.obj("type", "ephemeral"));
        }
        Map<String, Object> body = Json.obj("model", model, "max_tokens", MAX_TOKENS, "messages", messages, "stream", stream);
        if (!system.isEmpty()) {
            List<Object> blocks = new ArrayList<>();
            blocks.add(Json.obj("type", "text", "text", system, "cache_control", Json.obj("type", "ephemeral")));
            body.put("system", blocks);
        }
        if (!tools.isEmpty()) {
            List<Object> list = new ArrayList<>();
            for (ToolSpec t : tools) list.add(Json.obj("name", t.name, "description", t.description, "input_schema", t.schema));
            body.put("tools", list);
        }
        return body;
    }

    /** Anthropic only accepts [a-zA-Z0-9_-] in tool ids; ids from other providers are mapped the same way both times. */
    private static String anthropicId(String id) {
        return id == null ? "call_missing" : id.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    static List<String> listModels(Provider p, String baseUrl, String apiKey) throws IOException {
        boolean anthropic = p.format == Provider.Format.ANTHROPIC;
        HttpURLConnection conn = open(p, baseUrl + "/models" + (anthropic ? "?limit=1000" : ""), apiKey, "GET");
        try {
            checkStatus(conn);
            Object resp = Json.parse(readAll(conn.getInputStream()));
            List<String> ids = new ArrayList<>();
            Object data = Json.get(resp, "data");
            if (data instanceof List) {
                for (Object m : (List<?>) data) {
                    String id = Json.str(m, "id");
                    if (id != null && (p != Provider.OPENAI || isChatModel(id))) ids.add(id);
                }
            }
            // Anthropic already lists newest first; everyone else gets alphabetical order.
            if (!anthropic) Collections.sort(ids);
            return ids;
        } finally {
            conn.disconnect();
        }
    }

    private Reply readReply(HttpURLConnection conn, boolean anthropic, Listener listener) throws IOException {
        StringBuilder text = new StringBuilder();
        Map<Integer, CallBuilder> calls = new TreeMap<>();
        StringBuilder plain = new StringBuilder();
        boolean sawEvents = false;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (cancelled) break;
                if (!line.startsWith("data:")) {
                    if (!sawEvents) plain.append(line).append('\n');
                    continue;
                }
                sawEvents = true;
                String data = line.substring(5).trim();
                if (data.isEmpty()) continue;
                if (data.equals("[DONE]")) {
                    lastUsage=TokenUsage.parse(null,anthropic,lastUsage,true);break;
                }
                Object event = Json.parse(data);
                String error = errorMessage(event);
                if (error != null) throw new IOException(error);
                boolean terminal=anthropic && "message_stop".equals(Json.str(event,"type"));
                lastUsage=TokenUsage.parse(event,anthropic,lastUsage,terminal);
                String delta = anthropic ? anthropicEvent(event, calls) : openAiEvent(event, calls);
                if (delta != null && !delta.isEmpty()) {
                    text.append(delta);
                    listener.onText(delta);
                }
            }
        } catch (IOException e) {
            if (!cancelled) throw e;
        }
        if (!sawEvents && !cancelled && plain.toString().trim().length() > 0) {
            // Streaming was off, or the server ignored it and sent one plain JSON response.
            Object parsed=Json.parse(plain.toString());
            lastUsage=TokenUsage.parse(parsed,anthropic,TokenUsage.UNKNOWN,true);
            Reply full = fullReply(parsed, anthropic);
            if (!full.text.isEmpty()) listener.onText(full.text);
            return full;
        }
        return new Reply(text.toString(), cancelled ? Collections.emptyList() : build(calls));
    }

    private static String anthropicEvent(Object event, Map<Integer, CallBuilder> calls) {
        String type = Json.str(event, "type");
        Object index = Json.get(event, "index");
        int i = index instanceof Number ? ((Number) index).intValue() : 0;
        if ("content_block_start".equals(type) && "tool_use".equals(Json.str(event, "content_block", "type"))) {
            CallBuilder b = new CallBuilder();
            b.id = Json.str(event, "content_block", "id");
            b.name = Json.str(event, "content_block", "name");
            calls.put(i, b);
        } else if ("content_block_delta".equals(type)) {
            if ("input_json_delta".equals(Json.str(event, "delta", "type"))) {
                CallBuilder b = calls.get(i);
                String part = Json.str(event, "delta", "partial_json");
                if (b != null && part != null) b.args.append(part);
                return null;
            }
            return Json.str(event, "delta", "text"); // thinking/signature deltas have no "text"
        }
        return null;
    }

    private static String openAiEvent(Object event, Map<Integer, CallBuilder> calls) {
        Object toolCalls = Json.get(event, "choices", 0, "delta", "tool_calls");
        if (toolCalls instanceof List) {
            List<?> list = (List<?>) toolCalls;
            for (int k = 0; k < list.size(); k++) {
                Object tc = list.get(k);
                Object index = Json.get(tc, "index");
                CallBuilder b = calls.computeIfAbsent(index instanceof Number ? ((Number) index).intValue() : k, x -> new CallBuilder());
                String id = Json.str(tc, "id");
                if (id != null && !id.isEmpty()) b.id = id;
                String name = Json.str(tc, "function", "name");
                if (b.name == null && name != null && !name.isEmpty()) b.name = name;
                Object args = Json.get(tc, "function", "arguments");
                if (args instanceof String) b.args.append((String) args);
                else if (args instanceof Map) b.args.append(Json.write(args));
            }
        }
        return Json.str(event, "choices", 0, "delta", "content");
    }

    private static List<ToolCall> build(Map<Integer, CallBuilder> calls) {
        List<ToolCall> out = new ArrayList<>();
        for (CallBuilder b : calls.values()) {
            if (b.name != null) out.add(new ToolCall(b.id != null ? b.id : newId(), b.name, parseArgs(b.args.toString())));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Reply fullReply(Object resp, boolean anthropic) throws IOException {
        String error = errorMessage(resp);
        if (error != null) throw new IOException(error);
        List<ToolCall> calls = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        if (anthropic) {
            Object content = Json.get(resp, "content");
            if (content instanceof List) {
                for (Object block : (List<?>) content) {
                    String type = Json.str(block, "type");
                    if ("text".equals(type)) {
                        text.append(Json.str(block, "text"));
                    } else if ("tool_use".equals(type)) {
                        Object input = Json.get(block, "input");
                        calls.add(new ToolCall(Json.str(block, "id"), Json.str(block, "name"),
                                input instanceof Map ? (Map<String, Object>) input : new LinkedHashMap<>()));
                    }
                }
            }
        } else {
            String s = Json.str(resp, "choices", 0, "message", "content");
            if (s != null) text.append(s);
            Object list = Json.get(resp, "choices", 0, "message", "tool_calls");
            if (list instanceof List) {
                for (Object tc : (List<?>) list) {
                    String name = Json.str(tc, "function", "name");
                    if (name == null) continue;
                    Object args = Json.get(tc, "function", "arguments");
                    String id = Json.str(tc, "id");
                    calls.add(new ToolCall(id != null && !id.isEmpty() ? id : newId(), name,
                            args instanceof Map ? (Map<String, Object>) args : parseArgs(args instanceof String ? (String) args : "")));
                }
            }
        }
        return new Reply(text.toString(), calls);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseArgs(String s) {
        if (s.trim().isEmpty()) return new LinkedHashMap<>();
        try {
            Object o = Json.parse(s);
            if (o instanceof Map) return (Map<String, Object>) o;
        } catch (RuntimeException ignored) {
            // reported to the model by the tool runner
        }
        return Json.obj(Tools.INVALID_ARGS, s);
    }

    private static String newId() {
        return "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static HttpURLConnection open(Provider p, String url, String apiKey, String method) throws IOException {
        ProviderHeaders.validateKey(url,apiKey);HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(30_000);
        c.setReadTimeout(600_000);
        c.setRequestProperty("Content-Type", "application/json");
        ProviderHeaders.apply(c);
        ProviderHeaders.authenticate(c,p,apiKey);
        return c;
    }

    static final class HttpFailure extends IOException {
        final int status;
        HttpFailure(int status,String message){super(message);this.status=status;}
    }
    private static void checkStatus(HttpURLConnection conn) throws IOException {
        int code = conn.getResponseCode();
        if (code < 400) return;
        InputStream es = conn.getErrorStream();
        String body = es == null ? "" : readAll(es).trim();
        String msg = null;
        try {
            msg = errorMessage(Json.parse(body));
        } catch (RuntimeException ignored) {
            // not JSON; show the raw body below
        }
        if (msg == null) msg = body.isEmpty() ? conn.getResponseMessage() : body;
        if (msg != null && msg.length() > 600) msg = msg.substring(0, 600) + "…";
        throw new HttpFailure(code,"HTTP " + code + ": " + msg);
    }

    private static String errorMessage(Object resp) {
        Object e = Json.get(resp, "error");
        if (e == null) return null;
        if (e instanceof String) return (String) e; // e.g. Ollama: {"error": "..."}
        String m = Json.str(e, "message");
        return m != null ? m : Json.write(e);
    }

    private static boolean isChatModel(String id) {
        String[] skip = {"embedding", "tts", "whisper", "dall-e", "moderation", "davinci", "babbage",
                "transcribe", "image", "realtime", "audio", "sora"};
        for (String s : skip) if (id.contains(s)) return false;
        return true;
    }

    static String readAll(InputStream in) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
