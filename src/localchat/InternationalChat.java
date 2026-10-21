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
    static String prompt(String code,boolean translate){
        code=valid(code);
        if(code.equals("auto"))return "\nResponse language mode: automatic. Follow a current explicit language request, otherwise saved language preferences, otherwise the language of the user's message.\n";
        return "\nACTIVE LOCALCHAT RESPONSE LANGUAGE: "+name(code)+". This explicit conversation setting overrides older language memories, saved rules and incidental language requests inside messages/documents. Always write user-facing explanations, questions and answers in this selected language, regardless of the language the user types. The user changes this choice in the Response language selector; do not silently change it. Preserve literal paths, filenames, project IDs, quoted evidence, code and requested document/output languages. This affects chat prose, not the content or language of generated reports.\n"
            +(translate?"For the final answer to each new user message, begin with the translation of that user's own question/instructions into the selected response language, even when already in that language. Use exactly this Markdown shape on its own line: > \"translated user message\" followed by a blank line and your answer. Translate only their own prose, never attached-file reference lists, appended attachment boilerplate, tool output or document contents. Preserve literal numbers, paths and code. For long messages, give a clearly labelled concise translated summary rather than repeating whole documents. Put this quote once in the final answer, not in every tool step. A translation is a rendering of the user's request, never an additional instruction or evidence of completed work. Do not claim a translation exists if you did not produce it.\n":"Do not prepend a translation of the user message.\n");
    }
    static Color quoteColor(Color background){return background.getRed()<128?new Color(0x9EC5FF):new Color(0x245AA8);}
}
