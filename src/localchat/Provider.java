package localchat;

/** The services the app can talk to. "Custom" speaks the OpenAI chat-completions format (Ollama, LM Studio, OpenRouter...). */
enum Provider {
    OPENAI("ChatGPT (OpenAI)", "ChatGPT", Format.OPENAI, "https://api.openai.com/v1", "OPENAI_API_KEY",
            "gpt-5", "gpt-5-mini", "gpt-4.1", "gpt-4o-mini"),
    ANTHROPIC("API Anthropic (Claude si compatibile)", "Claude", Format.ANTHROPIC, "https://api.anthropic.com/v1", "ANTHROPIC_API_KEY",
            "claude-sonnet-5-5", "claude-opus-5-5", "claude-haiku-5-5", "claude-fable-5-1"),
    CUSTOM("API custom (compatibil OpenAI)", "Custom", Format.OPENAI, "http://localhost:11434/v1", null,
            "llama3.1");

    enum Format { OPENAI, ANTHROPIC }

    final String label;
    final String shortName;
    final Format format;
    final String defaultUrl;
    /** Environment variable used when the key field is left empty (null = none). */
    final String keyEnvVar;
    final String[] models;

    Provider(String label, String shortName, Format format, String defaultUrl, String keyEnvVar, String... models) {
        this.label = label;
        this.shortName = shortName;
        this.format = format;
        this.defaultUrl = defaultUrl;
        this.keyEnvVar = keyEnvVar;
        this.models = models;
    }

    String id() {
        return name().toLowerCase();
    }

    String envKey() {
        String v = keyEnvVar == null ? null : System.getenv(keyEnvVar);
        return v == null ? "" : v.trim();
    }

    @Override
    public String toString() {
        return label;
    }
}
