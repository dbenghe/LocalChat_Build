package localchat;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/** Minimal MCP client over stdio (newline-delimited JSON-RPC): enough to list and call a server's tools. */
final class McpServer implements AutoCloseable {
    static final class Tool {
        final String name;
        final String description;
        final Map<String, Object> schema;
        final boolean readOnly;

        Tool(String name, String description, Map<String, Object> schema, boolean readOnly) {
            this.name = name;
            this.description = description == null ? "" : description;
            this.schema = schema;
            this.readOnly = readOnly;
        }
    }

    final String name;
    private final Process process;
    private final Writer stdin;
    private final Map<Long, CompletableFuture<Object>> pending = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong();
    private final StringBuffer stderr = new StringBuffer();
    private volatile List<Tool> tools = Collections.emptyList();
    private volatile String failure;

    McpServer(String name, List<String> command, Map<String, String> env, File cwd) throws IOException {
        this.name = name;
        ProcessBuilder pb = new ProcessBuilder(command);
        if (cwd != null) pb.directory(cwd);
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        pb.environment().put("PYTHONUTF8", "1");
        pb.environment().putAll(env);
        process = pb.start();
        stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        daemon("mcp-" + name + "-out", this::readStdout);
        daemon("mcp-" + name + "-err", this::readStderr);
    }

    /** Handshake and tool discovery. */
    @SuppressWarnings("unchecked")
    void start(long timeoutMs) throws IOException {
        request("initialize", Json.obj("protocolVersion", "2025-06-18", "capabilities", Json.obj(),
                "clientInfo", Json.obj("name", "LocalChat", "version", "1.0")), timeoutMs);
        notify("notifications/initialized", Json.obj());
        List<Tool> list = new ArrayList<>();
        String cursor = null;
        do {
            Object res = request("tools/list", cursor == null ? Json.obj() : Json.obj("cursor", cursor), timeoutMs);
            Object items = Json.get(res, "tools");
            if (items instanceof List) {
                for (Object t : (List<?>) items) {
                    String n = Json.str(t, "name");
                    if (n == null) continue;
                    Object schema = Json.get(t, "inputSchema");
                    list.add(new Tool(n, Json.str(t, "description"),
                            schema instanceof Map ? (Map<String, Object>) schema : Json.obj("type", "object"),
                            Boolean.TRUE.equals(Json.get(t, "annotations", "readOnlyHint"))));
                }
            }
            cursor = Json.str(res, "nextCursor");
        } while (cursor != null && !cursor.isEmpty());
        tools = list;
    }

    List<Tool> tools() {
        return tools;
    }

    Tools.Result call(String tool, Map<String, Object> args, long timeoutMs) throws IOException {
        Object res = request("tools/call", Json.obj("name", tool, "arguments", args), timeoutMs);
        StringBuilder b = new StringBuilder();
        Object content = Json.get(res, "content");
        if (content instanceof List) {
            for (Object item : (List<?>) content) {
                String type = Json.str(item, "type");
                String part;
                if ("text".equals(type)) part = Json.str(item, "text");
                else if ("resource".equals(type)) part = Json.str(item, "resource", "text");
                else if ("resource_link".equals(type)) part = "[link: " + Json.str(item, "uri") + "]";
                else part = "[conținut " + type + " omis]";
                if (part == null) part = "[resursă: " + Json.str(item, "resource", "uri") + "]";
                if (b.length() > 0) b.append('\n');
                b.append(part);
            }
        }
        Object structured = Json.get(res, "structuredContent");
        if (b.length() == 0 && structured != null) b.append(Json.write(structured));
        return new Tools.Result(b.toString(), Boolean.TRUE.equals(Json.get(res, "isError")));
    }

    /** Fails every call in flight (Stop button); the server is told to drop them. */
    void cancelPending() {
        for (Map.Entry<Long, CompletableFuture<Object>> e : pending.entrySet()) {
            try {
                notify("notifications/cancelled", Json.obj("requestId", e.getKey(), "reason", "oprit de utilizator"));
            } catch (IOException ignored) {
                // server already gone
            }
            e.getValue().completeExceptionally(new IOException("Anulat de utilizator."));
        }
    }

    String stderrTail() {
        String s = stderr.toString().trim();
        return s.length() > 1500 ? "…" + s.substring(s.length() - 1500) : s;
    }

    @Override
    public void close() {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private Object request(String method, Object params, long timeoutMs) throws IOException {
        if (failure != null) throw new IOException(failure);
        long id = nextId.incrementAndGet();
        CompletableFuture<Object> f = new CompletableFuture<>();
        pending.put(id, f);
        try {
            send(Json.obj("jsonrpc", "2.0", "id", id, "method", method, "params", params));
            return f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            notify("notifications/cancelled", Json.obj("requestId", id, "reason", "timeout"));
            throw new IOException("Serverul MCP „" + name + "” nu a răspuns în " + timeoutMs / 1000 + " s (" + method + ").");
        } catch (ExecutionException e) {
            throw new IOException(e.getCause().getMessage(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Întrerupt.");
        } finally {
            pending.remove(id);
        }
    }

    private void notify(String method, Object params) throws IOException {
        send(Json.obj("jsonrpc", "2.0", "method", method, "params", params));
    }

    private synchronized void send(Map<String, Object> message) throws IOException {
        stdin.write(Json.write(message));
        stdin.write('\n');
        stdin.flush();
    }

    private void readStdout() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (!line.startsWith("{")) continue; // stray print() output
                Object msg;
                try {
                    msg = Json.parse(line);
                } catch (RuntimeException e) {
                    continue;
                }
                Object id = Json.get(msg, "id");
                String method = Json.str(msg, "method");
                if (method != null) {
                    if (id != null) answerServerRequest(id, method); // notifications are ignored
                } else if (id instanceof Number) {
                    CompletableFuture<Object> f = pending.get(((Number) id).longValue());
                    if (f == null) continue;
                    Object error = Json.get(msg, "error");
                    if (error == null) {
                        f.complete(Json.get(msg, "result"));
                    } else {
                        String m = Json.str(error, "message");
                        f.completeExceptionally(new IOException("MCP " + name + ": " + (m != null ? m : Json.write(error))));
                    }
                }
            }
        } catch (IOException ignored) {
            // process ended
        }
        String tail = stderrTail();
        failure = "Serverul MCP „" + name + "” s-a oprit." + (tail.isEmpty() ? "" : "\n" + tail);
        for (CompletableFuture<Object> f : pending.values()) f.completeExceptionally(new IOException(failure));
    }

    private void answerServerRequest(Object id, String method) {
        Map<String, Object> reply = Json.obj("jsonrpc", "2.0", "id", id);
        switch (method) {
            case "ping" -> reply.put("result", Json.obj());
            case "roots/list" -> reply.put("result", Json.obj("roots", new ArrayList<>()));
            default -> reply.put("error", Json.obj("code", -32601, "message", "Metodă nesuportată: " + method));
        }
        try {
            send(reply);
        } catch (IOException ignored) {
            // server gone; the reader loop will notice
        }
    }

    private void readStderr() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                stderr.append(line).append('\n');
                if (stderr.length() > 20_000) stderr.delete(0, stderr.length() - 10_000);
            }
        } catch (IOException ignored) {
            // process ended
        }
    }

    private static void daemon(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }
}
