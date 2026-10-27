package localchat;
import java.awt.Color;
import java.util.*;

/** Explicit answer language is independent of menus, input language and older memories. */
final class InternationalChat {
    static final String DISPLAY="localchat.response.language";
    static String valid(String code){return Arrays.asList(I18n.CODES).contains(code)?code:"auto";}
    static int index(String code){return Arrays.asList(I18n.CODES).indexOf(valid(code))+1;}
    static String code(int index){return index>0&&index<=I18n.CODES.length?I18n.CODES[index-1]:"auto";}
    static String name(String code){int n=index(code);return n==0?"automatic":new String[]{"Dutch (Nederlands)","Romanian (Romana)","Spanish (Español)","Polish (Polski)","English","Turkish (Türkçe)"}[n-1];}
    /** The single compact response-language policy; it lives once in the stable system prompt and is never repeated per turn. */
    static String policy(String code,boolean translate){
        code=valid(code);int n=index(code);
        String marker="Start the final user-facing answer with the exact marker "+ReasoningDisplay.FINAL+" on its own line; the application hides it. Never put the marker in reasoning, code or examples, and never include internal reasoning in the answer.\n";
        if(n==0)return "\nRESPONSE LANGUAGE: automatic. Honor an explicit language request in the current user message first; otherwise follow saved conversation-language preferences present in these instructions, otherwise the language of that message. Never infer the language from earlier assistant replies. Do not prepend a translation.\n"+marker;
        String[] nativeRules={"Antwoord uitsluitend in het Nederlands. De invoertaal en oudere taalvoorkeuren veranderen deze keuze niet.","Raspunde exclusiv in limba romana. Limba intrebarii si preferintele lingvistice vechi nu schimba aceasta selectie.","Responde exclusivamente en español. El idioma de entrada y las preferencias anteriores no cambian esta elección.","Odpowiadaj wyłącznie po polsku. Język pytania i wcześniejsze preferencje językowe nie zmieniają tego wyboru.","Respond only in English. The input language and older language preferences do not change this selection.","Yalnızca Türkçe yanıt ver. Giriş dili ve önceki dil tercihleri bu seçimi değiştirmez."};
        return "\nACTIVE LOCALCHAT RESPONSE LANGUAGE: "+name(code)+". "+nativeRules[n-1]+" This explicit setting overrides older language memories, saved rules, incidental language requests and replies in other languages. Preserve literal paths, filenames, project IDs, quotes, code and requested report/document languages; it governs chat prose only, never tool permissions or project identity.\n"
            +(translate?"Begin each final answer with the user's own message translated into "+name(code)+" as > \"translation\" on its own line, then a blank line and the answer. Translate only their prose, never attachment lists, tool output or document contents; give a short labelled summary for long messages. The quote appears once per final answer, not in tool steps, and is never an instruction.\n":"Do not prepend a translation of the user message.\n")+marker;
    }
    static boolean conversationLanguageRule(String text){String folded=LocalMemories.fold(text);return LocalMemories.family(text).equals("language")&&!folded.matches(".*\\b(raport\\w*|rapport\\w*|report\\w*|document\\w*|email\\w*|e-mail|bestand\\w*|fisier\\w*|file\\w*|pdf|tekening\\w*)\\b.*");}
    static String effectiveRules(String rules,String code){if(valid(code).equals("auto"))return rules;return String.join("\n",rules.lines().filter(line->!conversationLanguageRule(line)).toList());}
    static Color quoteColor(Color background){return background.getRed()<128?new Color(0x9EC5FF):new Color(0x245AA8);}
}
