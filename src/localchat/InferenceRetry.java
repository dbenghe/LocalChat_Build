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
    static ChatClient.Reply chat(ChatClient client,Provider provider,String url,String key,String model,String system,
            List<ChatClient.Message> request,List<ChatClient.ToolSpec> tools,boolean stream,ChatClient.Listener listener,Hooks hooks)throws IOException{
        for(int attempt=1;;attempt++){
            if(hooks.cancelled())throw new InterruptedIOException("Cancelled");
            client.checkBudget(); // An expired local budget is not an API attempt or billable usage.
            hooks.before(system,request);
            try{return client.chat(provider,url,key,model,system,request,tools,stream,listener);}
            catch(ChatClient.HttpFailure failure){
                if(hooks.cancelled())throw new InterruptedIOException("Cancelled");
                if(attempt>=3 || (failure.status!=502&&failure.status!=503&&failure.status!=504))throw failure;
                hooks.retry(attempt+1,3,failure.status);
            }finally{hooks.usage(client.usage());}
            long until=System.nanoTime()+attempt*500_000_000L;
            while(System.nanoTime()<until){if(hooks.cancelled())throw new InterruptedIOException("Cancelled");try{Thread.sleep(25);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException("Cancelled");}}
        }
    }
}
