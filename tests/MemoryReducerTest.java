package localchat;
import java.util.*;
import java.io.*;
public final class MemoryReducerTest{
    static int checks;
    static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
    static class Hooks implements MemoryReducer.Attempt{int attempts,usage;boolean stop;public void before(String s,List<ChatClient.Message> m){attempts++;}public void usage(TokenUsage u){usage++;}public boolean cancelled(){return stop;}}
    public static void main(String[] args)throws Exception{
        String tooLong="Evidence ".repeat(1500),memory="Project 505417. Verified PDF: C:/project/RAPPORT.pdf. Pending: comparison. No new permissions.";
        try(var server=new FeatureTest.Server(FeatureTest.json(FeatureTest.answer(tooLong,100,50)),FeatureTest.json(FeatureTest.answer(memory,100,40)))){
            Hooks hooks=new Hooks();String result=MemoryReducer.reduce(new ChatClient(),Provider.CUSTOM,server.url(),"","fixture","Summarize historical data",List.of(ChatClient.Message.user("source")),256,32768,2,hooks);
            check(result.equals(memory),"oversized first summary tightened without truncation");check(hooks.attempts==2&&hooks.usage==2,"each retry counted once");check(Json.get(server.requests.get(0),"tools")==null,"summary cannot execute tools");check(Json.get(server.requests.get(0),"max_tokens")==null&&Json.get(server.requests.get(1),"max_tokens")==null,"shortening does not cap reasoning generation");
        }
        try(var server=new FeatureTest.Server(FeatureTest.json(FeatureTest.answer(tooLong,100,50)),FeatureTest.json(FeatureTest.answer(tooLong,100,50)),FeatureTest.json(FeatureTest.answer(tooLong,100,50)))){
            Hooks hooks=new Hooks();try{MemoryReducer.reduce(new ChatClient(),Provider.CUSTOM,server.url(),"","fixture","Summarize",List.of(ChatClient.Message.user("source")),256,32768,1.25,hooks);throw new AssertionError("unbounded summary accepted");}catch(IOException expected){check(hooks.attempts==3&&server.requests.size()==3,"bounded retries and explicit failure");}
        }
        try(var server=new FeatureTest.Server(FeatureTest.json(FeatureTest.answer("",100,50)),FeatureTest.json(FeatureTest.answer(memory,100,40)))){
            Hooks hooks=new Hooks();check(MemoryReducer.reduce(new ChatClient(),Provider.CUSTOM,server.url(),"","fixture","Summarize",List.of(ChatClient.Message.user("source")),256,32768,1.25,hooks).equals(memory),"empty response gets a bounded retry");
            check(Json.get(server.requests.get(1),"max_tokens")==null,"empty-response repair keeps endpoint generation default");
        }
        try(var server=new FeatureTest.Server()){
            Hooks hooks=new Hooks();hooks.stop=true;try{MemoryReducer.reduce(new ChatClient(),Provider.CUSTOM,server.url(),"","fixture","Summarize",List.of(ChatClient.Message.user("source")),256,32768,1.25,hooks);throw new AssertionError("cancel ignored");}catch(InterruptedIOException expected){check(server.requests.isEmpty(),"Stop prevents retry and send");}
        }
        try(var server=new ContextGuardTest.Server(null)){
            var original=List.of(ChatClient.Message.user("old evidence ".repeat(25000)),ChatClient.Message.assistant("prior answer",List.of(),"fixture"),ChatClient.Message.user("Exact new question: verify 505417 and return PDF."));String before=Json.write(original.stream().map(ChatClient.Message::toJson).toList());
            var guard=new ContextGuard(32768,4096,true,70,1.25);var active=guard.prepare(Provider.CUSTOM,server.url(),"","fixture","system",original,List.of(),0,new ContextGuardTest.Ui());
            check(server.summaries.get()>1,"oversized history processed in multiple bounded fragments");check(active.get(1).content.equals(original.get(2).content),"new question retained exactly");check(before.equals(Json.write(original.stream().map(ChatClient.Message::toJson).toList())),"full original archive unchanged");check(guard.predicted(ContextGuard.raw("system",active,List.of()))+guard.reserve()+guard.margin()<32768,"summary plus new question and answer fit");
        }
        System.out.println("PASS: "+checks+" adaptive memory reduction checks");
    }
}
