package localchat;
import java.io.*;
import java.util.*;

/** Retry only explicit gateway failures before a reply, never replay tools or interrupted streams. */
final class InferenceRetry {
    interface Hooks {
        void before(String system,List<ChatClient.Message> request)throws IOException;
        void usage(TokenUsage usage);
        boolean cancelled();
        default void retry(int next,int total,int status){}
    }
    /** Base backoff before retrying a proxy timeout (522/523/524); the origin may still be busy with the abandoned request. Tests shorten it. */
    static volatile long proxyBackoffMillis=4000;
    /** Base backoff before retrying a provider-capacity 429 (1.28.9): a saturated upstream often recovers within seconds; only before any reply, never replays a tool. Tests shorten it. */
    static volatile long capacityBackoffMillis=3000;
    static boolean retryable(int status){return status==429||status==502||status==503||status==504||status==522||status==523||status==524;}
    static ChatClient.Reply chat(ChatClient client,Provider provider,String url,String key,String model,String system,
            List<ChatClient.Message> request,List<ChatClient.ToolSpec> tools,boolean stream,ChatClient.Listener listener,Hooks hooks)throws IOException{
        for(int attempt=1;;attempt++){
            long base=500;
            if(hooks.cancelled())throw new InterruptedIOException("Cancelled");
            client.checkBudget(); // An expired local budget is not an API attempt or billable usage.
            hooks.before(system,request);
            try{return client.chat(provider,url,key,model,system,request,tools,stream,listener);}
            catch(ChatClient.HttpFailure failure){
                if(hooks.cancelled())throw new InterruptedIOException("Cancelled");
                if(attempt>=3 || !retryable(failure.status))throw failure;
                hooks.retry(attempt+1,3,failure.status);
                base=failure.status>=522&&failure.status<=524?proxyBackoffMillis:failure.status==429?capacityBackoffMillis:500;
            }finally{hooks.usage(client.usage());}
            long until=System.nanoTime()+attempt*base*1_000_000L;
            while(System.nanoTime()<until){if(hooks.cancelled())throw new InterruptedIOException("Cancelled");try{Thread.sleep(25);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException("Cancelled");}}
        }
    }
}
