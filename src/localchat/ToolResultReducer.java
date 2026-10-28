package localchat;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Deterministic reduction of OLD tool results, applied to the request view only (1.27.2).
 * The archive, the saved conversation and exports keep every byte; the model receives a stub that keeps the
 * call name and arguments, identifiers, paths and delivery/job status, so it can re-read when it needs detail.
 * The result is a pure function of the history: it is computed once at turn start, so the prefix stays stable
 * within the turn. The message count never changes (tool-call pairs stay valid, indexes stay valid).
 */
final class ToolResultReducer {
    /** The most recent tool results stay intact. */
    static final int KEEP_RECENT=6;
    /** Results shorter than this are not worth a stub. */
    static final int MIN_REDUCE=1500;
    static final int STUB_LIMIT=1100;
    static final String MARK="[Older tool result reduced for context";
    record Reduced(List<ChatClient.Message> messages,int count){}
    private static final Pattern KEY=Pattern.compile("(?i)(^id$|_id$|path|file|folder|report|artifact|project|status|state|delivery|verified|error|fail|warn|total|count|pages|sha256|next_|offset|revision|summary|^name$|^ok$)");
    private static final Pattern BULK=Pattern.compile("(?i)^(text|content|body|html|raw|stdout|stderr|output|data)$");
    private static final Pattern LOOKS_LIKE_PATH=Pattern.compile("(?i)([A-Za-z]:[\\\\/]|[\\\\/][\\w.-]+[\\\\/]|[\\w-]+\\.(pdf|docx?|xlsx?|msg|eml|txt|md|json|csv|zip|png|jpe?g)\\b)");
    private static final Pattern FACT_LINE=Pattern.compile("(?i)\\b(error|eroare|fail(ed)?|esuat|warning|avertisment|delivery_verified|job_id|status|continua cu offset|next_offset)\\b");

    private ToolResultReducer(){}

    static Reduced apply(List<ChatClient.Message> messages){
        Map<String,ChatClient.ToolCall> calls=new HashMap<>();
        for(var m:messages)if(m.role.equals("assistant"))for(var c:m.toolCalls)if(c.id!=null)calls.put(c.id,c);
        List<ChatClient.Message> out=new ArrayList<>(messages);int seen=0,count=0;
        for(int i=messages.size()-1;i>=0;i--){
            var m=messages.get(i);if(!"tool".equals(m.role))continue;
            if(++seen<=KEEP_RECENT)continue;
            String stub=stub(m,calls.get(m.toolCallId));
            if(stub!=null){out.set(i,ChatClient.Message.toolResult(m.toolCallId,stub,m.isError));count++;}
        }
        return new Reduced(out,count);
    }

    /** The stub for one result, or null when it stays as it is. */
    static String stub(ChatClient.Message m,ChatClient.ToolCall call){
        String content=m.content==null?"":m.content;
        if(content.length()<MIN_REDUCE||content.startsWith(MARK))return null;
        StringBuilder b=new StringBuilder(MARK).append(": ");
        if(call!=null)b.append("tool=").append(call.name).append(" args=").append(clip(Json.write(call.args),240)).append("; ");
        b.append(content.length()).append(" chars omitted from this request. Key facts kept below; call the tool again (with offset/limit when it has them) if the full result is needed.]\n");
        int header=b.length();
        List<String> facts=new ArrayList<>();
        String trimmed=content.stripLeading();
        boolean json=false;
        if(trimmed.startsWith("{")||trimmed.startsWith("[")){
            try{walk("",Json.parse(trimmed),facts,0);json=true;}catch(RuntimeException notJson){facts.clear();}
        }
        if(!json){
            String[] lines=content.split("\r\n|\n|\r",-1);
            facts.add(clip(content.strip(),400));
            int extra=0;
            for(int i=1;i<lines.length&&extra<6;i++){String line=lines[i].strip();if(!line.isEmpty()&&FACT_LINE.matcher(line).find()&&!facts.get(0).contains(line)){facts.add(clip(line,200));extra++;}}
            String last=lines.length==0?"":lines[lines.length-1].isBlank()&&lines.length>1?lines[lines.length-2]:lines[lines.length-1];
            if(last.contains("offset=")&&!facts.contains(clip(last.strip(),200)))facts.add(clip(last.strip(),200));
        }
        for(String fact:facts){
            if(b.length()-header+fact.length()+1>STUB_LIMIT)break;
            b.append(fact).append('\n');
        }
        String stub=b.toString().stripTrailing();
        return stub.length()*4>content.length()*3?null:stub;
    }

    @SuppressWarnings("unchecked")
    private static void walk(String path,Object node,List<String> facts,int depth){
        if(facts.size()>=40||depth>6)return;
        if(node instanceof Map<?,?> map){
            for(var entry:((Map<String,Object>)map).entrySet()){
                String key=String.valueOf(entry.getKey());walk(path.isEmpty()?key:path+"."+key,entry.getValue(),facts,depth+1);
            }
        }else if(node instanceof List<?> list){
            int shown=0;for(Object item:list){if(shown++>=5){facts.add(path+": +"+(list.size()-5)+" more items");break;}walk(path+"["+(shown-1)+"]",item,facts,depth+1);}
        }else if(node!=null){
            String leaf=path.substring(Math.max(path.lastIndexOf('.'),path.lastIndexOf(']'))+1);
            String value=String.valueOf(node);
            boolean bulk=BULK.matcher(leaf).matches();
            boolean wanted=!bulk&&(KEY.matcher(leaf).find()||(node instanceof String&&value.length()<260&&!value.contains("\n")&&LOOKS_LIKE_PATH.matcher(value).find()));
            if(wanted)facts.add(path+"="+clip(value.replace('\n',' '),200));
        }
    }

    private static String clip(String text,int limit){return text.length()<=limit?text:text.substring(0,limit)+"...";}
}