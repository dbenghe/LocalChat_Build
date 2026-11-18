package localchat;

import java.text.Normalizer;
import java.util.*;

/** One factual correction when a model invents a connector failure after successful host results.
 * It never retries an operation, reconnects a tool or turns an error into success. */
final class HostResultRecovery {
    static final String INSTRUCTION="\nThe LocalChat host delivered the recent tool results successfully. No missing-result or connector-timeout error was returned for those calls. Your previous final statement about a stalled connector is unsupported. Use the confirmed results and continue the still-authorized requested work through available tools; do not restart the procedure or replay completed operations. Await every tool promise and consume its returned content in the JavaScript host. Never recommend restarting the application without a returned host error. If a concrete operation really fails, report its exact returned error and preserve permission denials, Stop and unresolved technical evidence. This is one correction, not new permission.\n";
    static boolean needed(String answer,List<ChatClient.Message> turn){
        String s=fold(answer);
        boolean claim=s.matches("(?s).*(?:conector(?:ul)?|connector|localchat|tool(?:s| calls?)?).{0,140}(?:nu (?:a |mai )*(?:return|raspuns)|did not return|didn't return|no (?:longer )?(?:return|respond)|niet.{0,25}(?:antwoord|resultaat)|keine.{0,25}(?:antwort|ergebnis)).*")
            ||s.matches("(?s).*(?:citirile|apelurile|asteptarile|reads|tool calls).{0,90}(?:nu au returnat|blocat|did not return|timed out).*")
            ||s.matches("(?s).*(?:in asteptare|pending|awaiting).{0,120}(?:fara rezultat returnat|no (?:returned )?result|not returned).*");
        if(!claim)return false;
        int successes=0;
        for(int i=turn.size()-1;i>=0;i--){var m=turn.get(i);if(!m.role.equals("tool"))continue;
            if(m.isError)return false; // genuine failed/denied/cancelled operations are never overridden
            if(++successes==3)break;
        }
        return successes>0;
    }
    private static String fold(String text){return Normalizer.normalize(Objects.toString(text,""),Normalizer.Form.NFD).replaceAll("\\p{M}","").toLowerCase(Locale.ROOT);}
}
