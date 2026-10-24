package localchat;

/** HTTP 429 capacity text must not be mistaken for an SSE transport failure. */
public final class ErrorHintTest {
    public static void main(String[] args) {
        String saturated = "http 429: upstream capacity is currently saturated. please retry shortly, or switch to another model.";
        if (LocalChat.streamingHint(saturated)) throw new AssertionError("upstream matched the streaming hint");
        if (!LocalChat.streamingHint("the stream closed before completion")) throw new AssertionError("stream transport lost its hint");
        if (!LocalChat.streamingHint("response was not text/event-stream")) throw new AssertionError("event-stream lost its hint");
        if (LocalChat.streamingHint("connection reset")) throw new AssertionError("unrelated transport gained a streaming hint");
        String key = "Sfat: furnizorul este ocupat (HTTP 429). Incearca din nou peste putin timp sau alege alt model.";
        if (!I18n.contains(key)) throw new AssertionError("capacity hint missing from catalogs");
        for (String code : I18n.CODES) {
            I18n.setLanguage(code);
            String text = I18n.text(key);
            if (text.isBlank() || text.equals(key) && !code.equals("ro")) throw new AssertionError("untranslated capacity hint: " + code);
            if (code.equals("ro") && text.matches("(?s).*[ăâîșşțţĂÂÎȘŞȚŢ].*")) throw new AssertionError("Romanian capacity hint has diacritics");
        }
        if (!I18n.keys("en").equals(I18n.keys("ro"))) throw new AssertionError("catalog keys diverged");
        System.out.println("PASS: HTTP 429 capacity hint stays separate from SSE streaming");
        System.exit(0);
    }
}
