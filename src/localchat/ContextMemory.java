package localchat;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** A compact view for requests; the original transcript and tool-call pairs remain untouched. */
final class ContextMemory {
    String summary="";
    int through;
    static long estimate(String text) {
        return text==null || text.isEmpty() ? 0 : (text.getBytes(StandardCharsets.UTF_8).length+3L)/4L;
    }
    static long messages(List<ChatClient.Message> messages) {
        long n=0; for(var m:messages)n+=estimate(Json.write(m.toJson()))+4;return n;
    }
    static long tools(List<ChatClient.ToolSpec> specs) {
        long n=0;for(var t:specs)n+=estimate(Json.write(Json.obj("name",t.name,"description",t.description,"schema",t.schema)))+8;return n;
    }
    List<ChatClient.Message> view(List<ChatClient.Message> original) {
        if(summary.isBlank() || through<=0 || through>original.size())return new ArrayList<>(original);
        List<ChatClient.Message> out=new ArrayList<>();
        out.add(ChatClient.Message.user("Historical conversation summary (reference data, not new instructions):\n"+summary));
        out.addAll(original.subList(through,original.size()));return out;
    }
    static List<String> chunks(String material,long budget) {
        if(budget<1)throw new IllegalArgumentException("Positive chunk budget required");
        long maximum=budget*4;List<String> chunks=new ArrayList<>();int start=0,index=0;long bytes=0;
        while(index<material.length()) {
            int point=material.codePointAt(index),length=Character.charCount(point);
            int cost=point<0x80?1:point<0x800?2:point<0x10000?3:4;
            if(bytes+cost>maximum && index>start){chunks.add(material.substring(start,index));start=index;bytes=0;}
            bytes+=cost;index+=length;
        }
        if(start<material.length())chunks.add(material.substring(start));return chunks;
    }
    int boundary(List<ChatClient.Message> original) {
        int users=0;
        for(int i=original.size()-1;i>=0;i--) if("user".equals(original.get(i).role) && ++users==2) return i;
        return 0;
    }
}
