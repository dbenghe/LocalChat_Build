package localchat;
/** Response profiles are output caps, not reasoning levels. Automatic is not unlimited. */
final class ResponseBudget {
    static final int AUTOMATIC=3,FALLBACK=16000;
    static int maximum(int profile,long apiOutput){int desired=switch(profile){case 0->1024;case 1->4096;case 2->8192;default->apiOutput>0?(int)Math.min(2000000,apiOutput):FALLBACK;};return apiOutput>0?(int)Math.max(1,Math.min(desired,apiOutput)):desired;}
    static int available(int budget,int context,long estimated){return context>0?(int)Math.max(1,Math.min(budget,context-estimated-Math.max(128,(long)Math.ceil(context*.03)))):budget;}
}
