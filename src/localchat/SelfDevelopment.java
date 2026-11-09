package localchat;

import java.nio.file.*;
import java.util.*;

/** Opt-in development capability. Paths/origin come from the host, never model arguments. */
final class SelfDevelopment {
    static final String TOOL="localchat_develop";
    static final String ORIGIN="https://github.com/dbenghe/LocalChat_Build.git";
    static final Set<String> ACTIONS=Set.of("begin","status","list","read","write","edit","remove","validate","publish");
    static final Set<String> ARGUMENTS=Set.of("action","session","goal","path","content","offset","find","replace");
    static Path defaultSource(Path home){return home.toAbsolutePath().getParent().resolve("LocalChat_Build");}
    static String guidance(){return "\nAutomatic application development is enabled. If existing tools cannot fulfil the current request, use general reasoning and localchat_develop: begin, inspect source, edit exact source snippets for a focused fix and regression test, update VERSION/CHANGELOG/DEVELOPMENT_STATUS, validate, then publish the tested branch. This never changes the selected provider or the project's technical rules. Do not stop at a missing skill instruction; do not invent facts or approvals. A build remains pending installation/review while LocalChat is running.\n";}
    static ChatClient.ToolSpec spec(){return new ChatClient.ToolSpec(TOOL,
        "Develop LocalChat in a separate Git worktree when existing capabilities are insufficient. begin(goal), list/read/write/remove; edit(session,path,find,replace) changes one exact match; validate(session) runs pinned build/core tests, publish(session) requires the exact passing sources/version/docs/artifact and pushes a separate branch. status returns failure log. Host/policy files and installed/production data are outside editable paths. No arbitrary command or test-pass argument. Installation is separate.",
        Json.obj("type","object","properties",Json.obj("action",Json.obj("type","string","enum",ACTIONS.stream().sorted().toList()),"session",Json.obj("type","string"),"goal",Json.obj("type","string"),"path",Json.obj("type","string"),"content",Json.obj("type","string"),"find",Json.obj("type","string"),"replace",Json.obj("type","string"),"offset",Json.obj("type","integer","minimum",0)),"required",List.of("action"),"additionalProperties",false));}
    static void arguments(Map<String,Object> args){if(!ARGUMENTS.containsAll(args.keySet())||!ACTIONS.contains(Objects.toString(args.get("action"),"")))throw new IllegalArgumentException("Invalid development action/arguments");}
}
