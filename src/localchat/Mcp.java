package localchat;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/** The MCP servers listed under "mcpServers" in unelte.json (same shape as Claude Desktop's config). */
final class Mcp {
    private volatile List<McpServer> servers = Collections.emptyList();
    private volatile String status = "";

    List<McpServer> servers() {
        return servers;
    }

    /** One line for the status bar, e.g. "pdfcontrol (6)". */
    String status() {
        return status;
    }

    /** Stops the running servers and starts the configured ones. Slow (starts processes): call off the UI thread. */
    synchronized void reload(Object config, UnaryOperator<String> expand) {
        closeAll();
        List<McpServer> started = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Object list = Json.get(config, "mcpServers");
        if (list instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) list).entrySet()) {
                String name = String.valueOf(e.getKey());
                Object def = e.getValue();
                if (Boolean.TRUE.equals(Json.get(def, "disabled"))) continue;
                McpServer server = null;
                try {
                    String command = Json.str(def, "command");
                    if (command == null || command.isBlank()) throw new IOException("lipsește \"command\"");
                    List<String> cmd = new ArrayList<>();
                    cmd.add(expand.apply(command));
                    Object args = Json.get(def, "args");
                    if (args instanceof List) for (Object a : (List<?>) args) cmd.add(expand.apply(String.valueOf(a)));
                    Map<String, String> env = new LinkedHashMap<>();
                    Object envDef = Json.get(def, "env");
                    if (envDef instanceof Map) {
                        for (Map.Entry<?, ?> v : ((Map<?, ?>) envDef).entrySet()) env.put(String.valueOf(v.getKey()), expand.apply(String.valueOf(v.getValue())));
                    }
                    String cwd = Json.str(def, "cwd");
                    if (!new File(cmd.get(0)).isFile() && cmd.get(0).contains(File.separator)) {
                        throw new IOException("nu găsesc " + cmd.get(0));
                    }
                    server = new McpServer(name, cmd, env, cwd == null ? null : new File(expand.apply(cwd)));
                    server.start(60_000);
                    started.add(server);
                    notes.add(name + " (" + server.tools().size() + ")");
                } catch (IOException | RuntimeException ex) {
                    String detail = server == null ? "" : server.stderrTail();
                    if (server != null) server.close();
                    notes.add(name + ": eroare – " + ex.getMessage() + (detail.isEmpty() ? "" : " [" + lastLine(detail) + "]"));
                }
            }
        }
        servers = started;
        status = String.join(", ", notes);
    }

    void closeAll() {
        for (McpServer s : servers) s.close();
        servers = Collections.emptyList();
    }

    private static String lastLine(String s) {
        String[] lines = s.trim().split("\r?\n");
        return lines[lines.length - 1];
    }
}
