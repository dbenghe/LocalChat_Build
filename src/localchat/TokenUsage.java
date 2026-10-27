package localchat;
import java.util.*;

/** Server counts are never substituted with estimates. Streaming counters are cumulative snapshots. */
record TokenUsage(long input, long output, long cached, long created, boolean complete, long reasoning) {
    TokenUsage(long input, long output, long cached, long created, boolean complete) { this(input,output,cached,created,complete,0); }
    static final TokenUsage UNKNOWN = new TokenUsage(-1,-1,0,0,false);
    static long number(Object o, String key, long fallback) {
        Object v=Json.get(o,key); return v instanceof Number ? Math.max(0,((Number)v).longValue()) : fallback;
    }
    static TokenUsage parse(Object data, boolean anthropic, TokenUsage previous, boolean terminal) {
        Object u=Json.get(data,"usage");
        if (u==null && anthropic) u=Json.get(data,"message","usage");
        if (u==null) return new TokenUsage(previous.input,previous.output,previous.cached,previous.created,
                terminal && previous.input>=0 && previous.output>=0,previous.reasoning);
        long cached=anthropic ? number(u,"cache_read_input_tokens",previous.cached)
                : number(Json.get(u,"prompt_tokens_details"),"cached_tokens",previous.cached);
        long created=anthropic ? number(u,"cache_creation_input_tokens",previous.created) : 0;
        long rawPrevious=previous.input<0 ? -1 : previous.input-(anthropic ? previous.cached+previous.created : 0);
        long raw=number(u,anthropic ? "input_tokens" : "prompt_tokens",number(u,"input_tokens",rawPrevious));
        long in=raw<0 ? -1 : raw+(anthropic ? cached+created : 0);
        long out=number(u,anthropic ? "output_tokens" : "completion_tokens",number(u,"output_tokens",previous.output));
        long reasoning=anthropic ? previous.reasoning : number(Json.get(u,"completion_tokens_details"),"reasoning_tokens",previous.reasoning);
        return new TokenUsage(in,out,cached,created,terminal && in>=0 && out>=0,reasoning);
    }
    static final class Ledger {
        long input,output,cached,created,requests,unknown,reasoning;
        boolean historicalUnknown;
        void add(TokenUsage u) {
            requests++; if(u.input>=0)input+=u.input; if(u.output>=0)output+=u.output;
            cached+=u.cached;created+=u.created;reasoning+=Math.max(0,u.reasoning);
            if(!u.complete)unknown++;
        }
        void merge(Ledger other) { input+=other.input;output+=other.output;cached+=other.cached;created+=other.created;
            requests+=other.requests;unknown+=other.unknown;reasoning+=other.reasoning;historicalUnknown|=other.historicalUnknown; }
        Object toJson() { return Json.obj("input",input,"output",output,"cached",cached,"created",created,
                "requests",requests,"unknown",unknown,"reasoning",reasoning,"historicalUnknown",historicalUnknown); }
        static Ledger fromJson(Object o) { Ledger l=new Ledger();l.input=number(o,"input",0);l.output=number(o,"output",0);
            l.cached=number(o,"cached",0);l.created=number(o,"created",0);l.requests=number(o,"requests",0);
            l.unknown=number(o,"unknown",0);l.reasoning=number(o,"reasoning",0);l.historicalUnknown=Boolean.TRUE.equals(Json.get(o,"historicalUnknown"));return l; }
    }
}
