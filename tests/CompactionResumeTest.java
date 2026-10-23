package localchat;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Loopback failures and persistent checkpoints; never contacts an external provider. */
public final class CompactionResumeTest {
    static int checks;
    static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    static final String SUMMARY="Verified original evidence retained in archive. Pending user question; no new permissions or repeated writes.";
    static ContextGuard guard(Path cache,int count){ContextGuard guard=new ContextGuard(131072,8192,true,65,1.25);guard.checkpointDirectory=cache;guard.seed(86254,count);return guard;}
    static class Ui extends ContextGuardTest.Ui{int reused;public void compactReused(int fragment,int total){reused++;}}
    public static void main(String[] args)throws Exception{
        Path home=Files.createTempDirectory("compaction-resume-");
        Path cache=CompactionCheckpoint.directory(home,home.resolve("chat-a.json"),"mbs");
        check(!cache.equals(CompactionCheckpoint.directory(home,home.resolve("chat-b.json"),"mbs")),"different chats cannot share checkpoints");
        check(!cache.equals(CompactionCheckpoint.directory(home,home.resolve("chat-a.json"),"normal")),"Normal and MBS checkpoints are isolated");
        var original=List.of(ChatClient.Message.user("old question"),ChatClient.Message.assistant("evidence ".repeat(22800),List.of(),"fixture"),ChatClient.Message.user("Exact pending question, keep unchanged."));
        String archive=Json.write(original.stream().map(ChatClient.Message::toJson).toList());
        var ok=FeatureTest.json(FeatureTest.answer(SUMMARY,500,30));
        try(var server=new FeatureTest.Server(ok,new FeatureTest.Response(500,"application/json","{\"error\":\"fixture failure\"}"),ok,ok)){
            ContextGuard first=guard(cache,original.size());
            try{first.prepare(Provider.CUSTOM,server.url(),"private-test-key","model-a","system",original,List.of(),1,new Ui());throw new AssertionError("failure ignored");}
            catch(IOException expected){check(first.memory==null,"partial success never becomes active memory");}
            check(server.requests.size()==2,"failure stops before sending pending question");
            check(Files.walk(cache).filter(f->f.toString().endsWith(".json")).count()==1,"completed first fragment persisted before failure");
            ContextGuard resumed=guard(cache,original.size());Ui ui=new Ui();
            var active=resumed.prepare(Provider.CUSTOM,server.url(),"private-test-key","model-a","system",original,List.of(),1,ui);
            check(ui.reused==1&&ui.summaries==2&&server.requests.size()==4,"new guard resumes from disk and sends only remaining two fragments");
            check(active.size()==2&&active.get(1).content.equals(original.get(2).content),"latest question remains exact and separate");
            check(archive.equals(Json.write(original.stream().map(ChatClient.Message::toJson).toList())),"archive unchanged after failure and resume");
            check(resumed.predicted(ContextGuard.raw("system",active,List.of()))+resumed.reserve()+resumed.margin()<131072,"final request respects window and answer reserve");
            for(Object request:server.requests){check(Json.get(request,"tools")==null,"summary cannot call tools");String body=Json.write(request);check(!body.contains("Exact pending question"),"pending question never sent as summary material");}
            check(server.requests.size()==4,"approximately 205 KB requires three fragments rather than nine");
            ContextGuard cached=guard(cache,original.size());Ui cachedUi=new Ui();cached.prepare(Provider.CUSTOM,server.url(),"private-test-key","model-a","system",original,List.of(),1,cachedUi);
            check(cachedUi.reused==3&&cachedUi.usage==0&&server.requests.size()==4,"fully cached retry has no API usage or invented billing");
            ContextGuard stopped=guard(cache,original.size());Ui stopUi=new Ui(){public void compactReused(int f,int t){super.compactReused(f,t);stopped.cancel();}};
            try{stopped.prepare(Provider.CUSTOM,server.url(),"private-test-key","model-a","system",original,List.of(),1,stopUi);throw new AssertionError("stop ignored");}catch(InterruptedIOException expected){check(stopped.memory==null&&server.requests.size()==4,"Stop during cache recovery never applies memory or sends");}
            try(var paths=Files.walk(cache)){for(Path file:paths.filter(Files::isRegularFile).toList())check(!Files.readString(file).contains("private-test-key"),"key is never stored in checkpoint contents");}
        }
        var request=List.of(ChatClient.Message.user("exact fragment"));
        String key=CompactionCheckpoint.key(Provider.CUSTOM,"url","secret","a","system",request,256,32768,1.25);
        for(String changed:List.of(
            CompactionCheckpoint.key(Provider.CUSTOM,"other-url","secret","a","system",request,256,32768,1.25),
            CompactionCheckpoint.key(Provider.CUSTOM,"url","other-key","a","system",request,256,32768,1.25),
            CompactionCheckpoint.key(Provider.CUSTOM,"url","secret","b","system",request,256,32768,1.25),
            CompactionCheckpoint.key(Provider.CUSTOM,"url","secret","a","different instructions",request,256,32768,1.25),
            CompactionCheckpoint.key(Provider.CUSTOM,"url","secret","a","system",List.of(ChatClient.Message.user("changed evidence")),256,32768,1.25),
            CompactionCheckpoint.key(Provider.CUSTOM,"url","secret","a","system",request,256,32768,2)))check(!key.equals(changed),"changed provider/model/instructions/material/calibration invalidates checkpoint");
        CompactionCheckpoint.save(home,key,SUMMARY);check(SUMMARY.equals(CompactionCheckpoint.read(home,key,256)),"valid successful summary can be restored");
        Files.writeString(home.resolve(key+".json"),"{broken");check(CompactionCheckpoint.read(home,key,256)==null,"damaged cache is ignored");
        CompactionCheckpoint.save(home,key,SUMMARY);Files.setLastModifiedTime(home.resolve(key+".json"),java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()-31L*86400000));check(CompactionCheckpoint.read(home,key,256)==null,"expired private checkpoint is not reused");
        check(CompactionCheckpoint.read(home.resolve("missing"),key,256)==null,"missing cache leaves ordinary compaction available");
        System.out.println("PASS: "+checks+" compaction resume, privacy, bounds and cancellation checks");
    }
}
