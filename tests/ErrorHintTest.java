package localchat;

/** HTTP 429 capacity text must not be mistaken for an SSE transport failure. */
public final class ErrorHintTest {
    public static void main(String[] args) {
        String saturated = "http 429: upstream capacity is currently saturated. please retry shortly, or switch to another model.";
        if (LocalChat.streamingHint(saturated)) throw new AssertionError("upstream matched the streaming hint");
        if (!LocalChat.streamingHint("the stream closed before completion")) throw new AssertionError("stream transport lost its hint");
        if (!LocalChat.streamingHint("response was not text/event-stream")) throw new AssertionError("event-stream lost its hint");
        if (LocalChat.streamingHint("connection reset")) throw new AssertionError("unrelated transport gained a streaming hint");
        // 1.28.9: 429/503 = provider saturated, never context; hint is explicit and in every language.
        String key = LocalChat.CAPACITY_HINT;
        if (!I18n.contains(key)) throw new AssertionError("capacity hint missing from catalogs");
        if (LocalChat.capacityStatus("http 429: upstream capacity") != 429 || LocalChat.capacityStatus("http 503: busy") != 503) throw new AssertionError("429/503 not detected");
        if (LocalChat.capacityStatus("http 5030") != 0 || LocalChat.capacityStatus("http 524 (proxy)") != 0 || LocalChat.capacityStatus("http 502: bad gateway") != 0 || LocalChat.capacityStatus(null) != 0) throw new AssertionError("unrelated status gained the capacity hint");
        for (String code : I18n.CODES) {
            I18n.setLanguage(code);
            String text = I18n.text(key);
            if (text.isBlank() || text.equals(key) && !code.equals("ro")) throw new AssertionError("untranslated capacity hint: " + code);
            if (!text.contains("{0}")) throw new AssertionError("capacity hint lost its status placeholder: " + code);
            if (code.equals("ro") && !text.equals(I18n.romanianAscii(text))) throw new AssertionError("Romanian capacity hint has diacritics");
        }
        // 1.28.7: the 524 hint says where thinking is switched off and no longer sends the user to the Streaming box.
        if (!LocalChat.proxyTimeoutHint("http 524 (proxy timeout ~100 s)")) throw new AssertionError("524 lost its hint");
        if (LocalChat.proxyTimeoutHint("http 502: bad gateway") || LocalChat.proxyTimeoutHint("http 429: slow down")) throw new AssertionError("unrelated HTTP error gained the 524 hint");
        if (!I18n.contains(LocalChat.PROXY_TIMEOUT_HINT)) throw new AssertionError("524 hint missing from catalogs");
        if (LocalChat.PROXY_TIMEOUT_HINT.toLowerCase().contains("bifeaza streaming")) throw new AssertionError("524 hint still points to Streaming");
        for (String code : I18n.CODES) {
            I18n.setLanguage(code);
            String text = I18n.text(LocalChat.PROXY_TIMEOUT_HINT);
            if (text.equals(LocalChat.PROXY_TIMEOUT_HINT) && !code.equals("ro")) throw new AssertionError("untranslated 524 hint: " + code);
            if (!text.contains("524")) throw new AssertionError("524 hint lost its status: " + code);
            String menu = I18n.text("Setari rapide"), thinking = I18n.text("Gandire");
            if (!text.contains(menu) || !text.contains(thinking)) throw new AssertionError("524 hint does not name the menu path (" + menu + " > " + thinking + "): " + code);
            if (code.equals("ro") && !text.equals(I18n.romanianAscii(text))) throw new AssertionError("Romanian 524 hint has diacritics");
        }
        I18n.setLanguage("ro");
        if (!I18n.keys("en").equals(I18n.keys("ro"))) throw new AssertionError("catalog keys diverged");
        System.out.println("PASS: HTTP 429 capacity hint stays separate from SSE streaming");
        System.exit(0);
    }
}
