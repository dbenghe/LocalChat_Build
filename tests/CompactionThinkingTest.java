package localchat;
import java.io.*;
import java.util.*;

/** 1.28.7: compaction asks a custom server not to think and works on bounded fragments (synthetic HTTP fixtures only). */
public final class CompactionThinkingTest {
    static int checks;
    static void check(boolean ok,String name){checks++;if(!ok)throw new AssertionError(name);}
    static boolean thinkingOff(Object body){return ThinkingControl.OFF_VALUE.equals(Json.get(body,ThinkingControl.PARAMETER));}
    public static void main(String[] args)throws Exception{
        String memory="Project 505417. Verified PDF: C:/project/RAPPORT.pdf. Pending: comparison. No new permissions.";
        check(ContextGuard.COMPACTION_FRAGMENT>0&&ContextGuard.COMPACTION_FRAGMENT<=8000,"compaction fragment is at most 8000 estimated tokens");
        // Direct reducer (manual /compact and automatic compaction both go through it): the client default is AUTO, compaction still sends none.
        try(var server=new FeatureTest.Server(FeatureTest.json(FeatureTest.answer(memory,100,40)))){
            String result=MemoryReducer.reduce(new ChatClient(),Provider.CUSTOM,server.url(),"","fixture-think","Summarize",List.of(ChatClient.Message.user("source")),256,32768,1.25,new MemoryReducerTest.Hooks());
            check(result.equals(memory)&&server.requests.size()==1,"compaction answered");
            check(thinkingOff(server.requests.get(0)),"compaction request carries reasoning_effort=none");
        }
        // Non-custom providers never receive the custom-server parameter.
        try(var server=new FeatureTest.Server(FeatureTest.json(FeatureTest.answer(memory,100,40)))){
            MemoryReducer.reduce(new ChatClient(),Provider.OPENAI,server.url(),"k","gpt","Summarize",List.of(ChatClient.Message.user("source")),256,32768,1.25,new MemoryReducerTest.Hooks());
            check(Json.get(server.requests.get(0),ThinkingControl.PARAMETER)==null,"official providers do not get reasoning_effort=none from compaction");
        }
        // A server that rejects the parameter: retried once without it, remembered, compaction still completes.
        ThinkingControl.forget();
        try(var server=new FeatureTest.Server(new FeatureTest.Response(400,"application/json","{\"error\":\"unknown parameter reasoning_effort\"}"),FeatureTest.json(FeatureTest.answer(memory,100,40)))){
            String result=MemoryReducer.reduce(new ChatClient(),Provider.CUSTOM,server.url(),"","fixture-reject","Summarize",List.of(ChatClient.Message.user("source")),256,32768,1.25,new MemoryReducerTest.Hooks());
            check(result.equals(memory)&&server.requests.size()==2,"rejected thinking parameter falls back inside compaction");
            check(thinkingOff(server.requests.get(0))&&Json.get(server.requests.get(1),ThinkingControl.PARAMETER)==null&&ThinkingControl.rejected(server.url(),"fixture-reject"),"fallback request omits the parameter and the rejection is remembered");
        }
        ThinkingControl.forget();
        // Automatic compaction of an oversized history: many bounded fragments, none asks for thinking, none exceeds the fragment budget.
        try(var server=new ContextGuardTest.Server(null)){
            var original=List.of(ChatClient.Message.user("old evidence ".repeat(25000)),ChatClient.Message.assistant("prior answer",List.of(),"fixture"),ChatClient.Message.user("Exact new question: verify 505417."));
            var guard=new ContextGuard(32768,4096,true,70,1.25);guard.prepare(Provider.CUSTOM,server.url(),"","fixture-auto","system",original,List.of(),0,new ContextGuardTest.Ui());
            check(server.summaries.get()>1,"oversized history is split into several fragments");
            long largest=0;boolean allOff=true;for(Object body:server.requests){largest=Math.max(largest,Json.write(body).length());allOff&=thinkingOff(body);}
            check(allOff,"every automatic compaction request thinks off");
            long cap=ContextGuard.COMPACTION_FRAGMENT*5L;
            check(largest<=cap,"each compaction request stays within the bounded fragment ("+largest+" > "+cap+" chars)");
        }
        ThinkingControl.forget();
        System.out.println("PASS: "+checks+" compaction thinking/fragment checks");System.exit(0);
    }
}
