package localchat;
import java.util.regex.Pattern;
/** Only a leading provider think block is hidden; raw messages and offsets are preserved. */
final class ReasoningDisplay {
    static final String FINAL="[[LOCALCHAT_FINAL]]";
    private static final String OPEN="<think>";
    private static final Pattern CLOSE=Pattern.compile("</think\\s*>",Pattern.CASE_INSENSITIVE);
    // Some compatible streaming routes omit </think> but retain the translated-answer quote.
    private static int quotedAnswer(String text,int from){boolean fence=false,blank=false;int offset=from;for(String line:text.substring(from).split("\n",-1)){String trim=line.strip();if(trim.startsWith("```")||trim.startsWith("~~~"))fence=!fence;if(!fence&&blank&&(trim.startsWith("> \"")||trim.startsWith("> \u201c")||trim.startsWith("> \u201e")))return offset;blank=trim.isEmpty();offset+=line.length()+1;}return text.length();}
    private static int finalMarker(String text,int from){boolean fence=false;int offset=from;for(String line:text.substring(from).split("\n",-1)){String trim=line.strip();if(trim.startsWith("```")||trim.startsWith("~~~"))fence=!fence;if(!fence&&trim.equals(FINAL))return Math.min(text.length(),offset+line.length()+1);offset+=line.length()+1;}return -1;}
    static int prefixEnd(String text){return prefixEnd(text,true);}
    static int prefixEnd(String text,boolean completed){int cursor=0,end=0;while(cursor<text.length()){while(cursor<text.length()&&Character.isWhitespace(text.charAt(cursor)))cursor++;int remaining=text.length()-cursor;if(remaining>0&&remaining<FINAL.length()&&text.regionMatches(cursor,FINAL,0,remaining))return text.length();if(text.startsWith(FINAL,cursor))return Math.min(text.length(),cursor+FINAL.length()+(text.length()>cursor+FINAL.length()&&text.charAt(cursor+FINAL.length())=='\n'?1:0));if(remaining>0&&remaining<OPEN.length()&&text.regionMatches(true,cursor,OPEN,0,remaining))return text.length();if(!text.regionMatches(true,cursor,OPEN,0,OPEN.length()))return end;var close=CLOSE.matcher(text);if(!close.find(cursor+OPEN.length())){int marker=finalMarker(text,cursor+OPEN.length());if(marker>=0)return marker;int quote=quotedAnswer(text,cursor+OPEN.length());return quote<text.length()?quote:completed?cursor+OPEN.length():text.length();}cursor=close.end();while(cursor<text.length()&&Character.isWhitespace(text.charAt(cursor)))cursor++;end=cursor;}return end;}
    static String answer(String text){return answer(text,true);}
    static String answer(String text,boolean completed){return text.substring(prefixEnd(text,completed));}
}
