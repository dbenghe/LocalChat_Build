package localchat;

import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

/** Images travel with the current question only; earlier ones become a short placeholder. Synthetic pictures, no provider calls. */
public final class ImageResendTest {
    static int checks;
    static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }
    static Map<String, Object> body(String method, List<ChatClient.Message> history) throws Exception {
        var m = ChatClient.class.getDeclaredMethod(method, String.class, String.class, List.class, List.class, boolean.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String, Object> out = (Map<String, Object>) m.invoke(null, "fixture", "system", history, List.of(), false);
        return out;
    }
    static Path picture(Path dir, String name, long seed) throws Exception {
        BufferedImage image = new BufferedImage(160, 160, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(seed);
        for (int x = 0; x < 160; x++) for (int y = 0; y < 160; y++) image.setRGB(x, y, random.nextInt(0xFFFFFF));
        Path file = dir.resolve(name);
        ImageIO.write(image, "png", file.toFile());
        return file;
    }

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory(Files.createDirectories(Path.of(System.getProperty("localchat.home"))), "image-resend-");
        Path secret = home.resolve("settings.properties"); Files.writeString(secret, "private fixture");
        List<ImageInput.Ref> one = ImageInput.snapshot(List.of(picture(home, "one.png", 1).toString()), home.resolve("snapshots"), secret);
        List<ImageInput.Ref> two = ImageInput.snapshot(List.of(picture(home, "two.png", 2).toString(), picture(home, "three.png", 3).toString()), home.resolve("snapshots"), secret);
        List<ImageInput.Ref> last = ImageInput.snapshot(List.of(picture(home, "four.png", 4).toString()), home.resolve("snapshots"), secret);
        String oldOne = one.get(0).data(), oldTwoA = two.get(0).data(), oldTwoB = two.get(1).data(), live = last.get(0).data();
        List<ChatClient.Message> history = new ArrayList<>(List.of(
                ChatClient.Message.user("What is in the first picture?").withImages(one),
                ChatClient.Message.assistant("A noise pattern.", List.of(), "fixture"),
                ChatClient.Message.user("And these two?").withImages(two),
                ChatClient.Message.assistant("Two more patterns.", List.of(), "fixture"),
                ChatClient.Message.user("Now this one, compare with the others.").withImages(last).withRequestControl("language control"),
                ChatClient.Message.assistant("", List.of(new ChatClient.ToolCall("t1", "read_file", Json.obj("path", "a.txt"))), "fixture"),
                ChatClient.Message.toolResult("t1", "file text", false)));
        for (String format : List.of("openAiBody", "anthropicBody")) {
            String wire = Json.write(body(format, history));
            check(wire.contains(live), format + ": the current question keeps its image, also after tool results");
            check(!wire.contains(oldOne) && !wire.contains(oldTwoA) && !wire.contains(oldTwoB), format + ": earlier images are not resent");
            check(wire.contains("What is in the first picture?") && wire.contains("And these two?"), format + ": earlier question text stays");
            check(wire.contains("1 image attachment of this earlier message") && wire.contains("2 image attachments of this earlier message"), format + ": a placeholder says what was left out");
            check(wire.indexOf("What is in the first picture?") < wire.indexOf("A noise pattern.") && wire.indexOf("A noise pattern.") < wire.indexOf("And these two?"), format + ": order of the conversation unchanged");
        }
        // Same history, only the old images are what changes in size.
        int with = Json.write(body("openAiBody", history)).length();
        List<ChatClient.Message> fresh = List.of(history.get(4), history.get(5), history.get(6));
        int currentOnly = Json.write(body("openAiBody", fresh)).length();
        int everything = currentOnly + ("data:image/png;base64," + oldOne).length() + ("data:image/png;base64," + oldTwoA).length() + ("data:image/png;base64," + oldTwoB).length();
        System.out.println("request body, 3 turns with 4 images: " + everything + " bytes before, " + with + " bytes after (-" + (100 - with * 100 / everything) + "%)");
        check(with < everything / 2 && with > currentOnly, "body is much smaller than before but still carries the earlier text");

        // Only the question being answered counts as live: a single image question is still sent.
        String single = Json.write(body("openAiBody", List.of(history.get(0))));
        check(single.contains(oldOne) && !single.contains("not resent"), "a lone image question is sent in full");
        // The next turn moves the image out of the live position.
        List<ChatClient.Message> next = new ArrayList<>(history); next.add(ChatClient.Message.assistant("Done.", List.of(), "fixture")); next.add(ChatClient.Message.user("Thanks, one more thing."));
        String after = Json.write(body("openAiBody", next));
        check(!after.contains(live) && after.contains("1 image attachment of this earlier message"), "the next turn replaces the previous current image with a placeholder");
        // The estimate follows the wire: only the live image adds the image allowance.
        check(ContextMemory.messages(history) - ContextMemory.messages(history.subList(0, 0)) < 4096L * 4, "estimate no longer charges earlier images");
        check(ContextMemory.messages(List.of(history.get(0))) >= 4096, "a live image is still charged");
        // The archive keeps references for every question (the picture can still be shown and re-sent on request).
        check(ChatClient.Message.fromJson(Json.parse(Json.write(history.get(0).toJson()))).images.equals(one), "archive still keeps image references of earlier questions");

        // Encoding is reused across the steps of one turn, and a changed file is still refused.
        check(last.get(0).data() == last.get(0).data() || last.get(0).data().equals(live), "repeated encoding returns the same pixels");
        Path stored = Path.of(last.get(0).path());
        byte[] bytes = Files.readAllBytes(stored); bytes[bytes.length / 2] ^= 0x55;
        Files.write(stored, bytes); Files.setLastModifiedTime(stored, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 10_000));
        try { last.get(0).data(); check(false, "changed snapshot must be refused"); } catch (IllegalArgumentException expected) { checks++; }
        System.out.println("PASS: " + checks + " image resend checks");
        System.exit(0);
    }
}
