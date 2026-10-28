package localchat;
/** Response profiles are output caps, not reasoning levels. Automatic is not unlimited. */
final class ResponseBudget {
    static final int AUTOMATIC=3,FALLBACK=16000;
    static int maximum(int profile,long apiOutput){int desired=switch(profile){case 0->1024;case 1->4096;case 2->8192;default->apiOutput>0?(int)Math.min(2000000,apiOutput):FALLBACK;};return apiOutput>0?(int)Math.max(1,Math.min(desired,apiOutput)):desired;}
    /** Most extra output tokens ever reserved for reasoning on top of the response profile. */
    static final int REASONING_CAP=8192;
    /** Smoothed reasoning tokens per reply for a provider/model: grows to the latest value, halves when a complete reply used none. */
    static long observe(long previous,long reasoning){return reasoning>0?(previous<=0?reasoning:Math.max(reasoning,(previous+reasoning)/2)):Math.max(0,previous)/2;}
    /** Extra tokens so reasoning cannot starve the visible answer: ~1.25 x observed reasoning, capped, and never beyond the API output limit. Reasoning is not resent, so this never raises the context reserve. */
    static int reasoningReserve(int budget,long observed,long apiOutput){
        if(observed<=0)return 0;
        long extra=Math.min(REASONING_CAP,(long)Math.ceil(observed*1.25));
        if(apiOutput>0)extra=Math.min(extra,Math.max(0,apiOutput-budget));
        return (int)Math.max(0,extra);
    }
    static int available(int budget,int context,long estimated){return context>0?(int)Math.max(1,Math.min(budget,context-estimated-Math.max(128,(long)Math.ceil(context*.03)))):budget;}
}
