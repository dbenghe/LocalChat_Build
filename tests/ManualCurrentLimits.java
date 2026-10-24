package localchat;

import java.nio.file.*;

/** The installed manual is README.md. Current sections must match the live limits. */
public final class ManualCurrentLimits {
    public static void main(String[] args) throws Exception {
        Path readme = Path.of(System.getProperty("localchat.readme", "README.md"));
        if (!Files.isRegularFile(readme)) throw new AssertionError("README missing: " + readme.toAbsolutePath());
        String text = Files.readString(readme);
        String agents = section(text, "## Agenti la cerere");
        String automatic = section(text, "## Agenti automati");
        String compare = section(text, "## Comparare locala de importuri");
        String transport = section(text, "## Transport compactare (1.25.1)");
        if (agents.contains("180 secunde") || agents.contains("4096 tokeni de iesire pentru fiecare cerere"))
            throw new AssertionError("on-demand agent section still states a removed lot deadline or fixed child cap");
        if (!agents.contains("3 sarcini") || !agents.contains("profilul de raspuns selectat"))
            throw new AssertionError("on-demand agent section lost the current task or profile limit");
        if (automatic.contains("180"))
            throw new AssertionError("automatic agent section still states a shared deadline");
        if (!automatic.contains("profilul de raspuns selectat"))
            throw new AssertionError("automatic agent section lost the selected response profile");
        if (compare.contains("buget comun de 300") || compare.contains("120 secunde per citire"))
            throw new AssertionError("import section still states the removed compaction budget as current");
        if (!transport.startsWith("Politica de durata din aceasta sectiune este inlocuita de 1.25.2."))
            throw new AssertionError("1.25.1 duration policy is not marked as replaced");
        if (!text.contains("## Compactare fara plafon artificial (1.25.2)"))
            throw new AssertionError("current compaction section missing");
        if (AgentGroup.MAX_TASKS != 3 || AgentGroup.PARALLELISM != 2 || AgentGroup.CHILD_STEPS != 16)
            throw new AssertionError("agent constants drifted");
        if (!AgentGroup.spec().description.contains("without a shared wall-clock deadline"))
            throw new AssertionError("run_agents no longer states the absent shared deadline");
        System.out.println("PASS: manual matches current agent and compaction limits");
    }

    private static String section(String text, String heading) {
        int start = text.indexOf(heading);
        if (start < 0) throw new AssertionError("missing section " + heading);
        int body = text.indexOf('\n', start);
        int next = text.indexOf("\n## ", body + 1);
        return text.substring(body + 1, next < 0 ? text.length() : next).strip();
    }
}
