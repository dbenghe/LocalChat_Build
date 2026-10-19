package localchat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Conversations saved as JSON files in the app's "conversatii" folder. */
final class Conversations {
    private static final DateTimeFormatter FILE_NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    record Saved(Path file, String title, String workDir, List<ChatClient.Message> messages) {
        @Override
        public String toString() {
            return file.getFileName().toString().replace(".json", "").replace('_', ' ') + "   " + title;
        }
    }

    private Conversations() {
    }

    static Path newFile(Path dir) {
        return dir.resolve(LocalDateTime.now().format(FILE_NAME) + ".json");
    }

    static void save(Path file, String workDir, List<ChatClient.Message> messages) throws IOException {
        List<Object> list = new ArrayList<>();
        for (ChatClient.Message m : messages) list.add(m.toJson());
        Files.createDirectories(file.getParent());
        Files.writeString(file, Json.write(Json.obj("title", title(messages), "workDir", workDir, "messages", list)), StandardCharsets.UTF_8);
    }

    static Saved load(Path file) throws IOException {
        Object root = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
        List<ChatClient.Message> messages = new ArrayList<>();
        Object list = Json.get(root, "messages");
        if (list instanceof List) for (Object m : (List<?>) list) messages.add(ChatClient.Message.fromJson(m));
        String title = Json.str(root, "title");
        return new Saved(file, title == null ? "" : title, Json.str(root, "workDir"), messages);
    }

    /** Newest first. Unreadable files are skipped. */
    static List<Saved> list(Path dir) {
        List<Saved> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(".json")).sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    out.add(load(p));
                } catch (IOException | RuntimeException ignored) {
                    // damaged file: leave it out of the list
                }
            });
        } catch (IOException ignored) {
            // folder vanished
        }
        return out;
    }

    private static String title(List<ChatClient.Message> messages) {
        for (ChatClient.Message m : messages) {
            if (m.role.equals("user")) {
                String t = m.content.replaceAll("\\s+", " ").trim();
                return t.length() > 80 ? t.substring(0, 80) + "…" : t;
            }
        }
        return "";
    }
}
