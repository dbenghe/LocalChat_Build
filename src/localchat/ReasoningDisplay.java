package localchat;
import java.util.regex.Pattern;
/** Only a leading provider think block is hidden; raw messages and offsets are preserved. */
final class ReasoningDisplay {
    private static final String OPEN="<think>";
    private static final Pattern CLOSE=Pattern.compile("</think\\s*>",Pattern.CASE_INSENSITIVE);
    // Some compatible streaming routes omit </think> but retain the translated-answer quote.
    private static int quotedAnswer(String text,int from){boolean fence=false,blank=false;int offset=from;for(String line:text.substring(from).split("\n",-1)){String trim=line.strip();if(trim.startsWith("```")||trim.startsWith("~~~"))fence=!fence;if(!fence&&blank&&(trim.startsWith("> \"")||trim.startsWith("> \u201c")||trim.startsWith("> \u201e")))return offset;blank=trim.isEmpty();offset+=line.length()+1;}return text.length();}
    static int prefixEnd(String text){int cursor=0,end=0;while(cursor<text.length()){while(cursor<text.length()&&Character.isWhitespace(text.charAt(cursor)))cursor++;int remaining=text.length()-cursor;if(remaining>0&&remaining<OPEN.length()&&text.regionMatches(true,cursor,OPEN,0,remaining))return text.length();if(!text.regionMatches(true,cursor,OPEN,0,OPEN.length()))return end;var close=CLOSE.matcher(text);if(!close.find(cursor+OPEN.length()))return quotedAnswer(text,cursor+OPEN.length());cursor=close.end();while(cursor<text.length()&&Character.isWhitespace(text.charAt(cursor)))cursor++;end=cursor;}return end;}
    static String answer(String text){return text.substring(prefixEnd(text));}
}
