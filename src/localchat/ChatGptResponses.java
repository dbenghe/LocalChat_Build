package localchat;

import java.io.*;
import java.util.*;
import java.util.function.Consumer;

/** Responses transport for ChatGPT-plan OAuth. No private backend endpoints. */
final class ChatGptResponses {
    final Map<String,List<Object>> reasoningByCall=new HashMap<>();
    final Map<String,Object> originalCalls=new HashMap<>();
    Map<String,Object> body(String model,String system,List<ChatClient.Message> history,List<ChatClient.ToolSpec> tools,int maximum){
        List<Object> input=new ArrayList<>();ChatClient.Message live=ChatClient.imageTurn(history);Set<String> currentCalls=new HashSet<>();Set<Object> included=new HashSet<>();
        for(var m:history){
            if(m.role.equals("tool")){
                if(currentCalls.contains(m.toolCallId))input.add(Json.obj("type","function_call_output","call_id",m.toolCallId,"output",m.content));
                else input.add(Json.obj("role","user","content","[Historical tool result "+m.toolCallId+"]\n"+m.content));
            }else if(m.role.equals("assistant")){
                if(!m.content.isBlank())input.add(Json.obj("role","assistant","content",m.content));
                for(var call:m.toolCalls){
                    if(reasoningByCall.containsKey(call.id)){
                        for(Object r:reasoningByCall.get(call.id))if(included.add(r))input.add(r);
                        input.add(originalCalls.get(call.id));currentCalls.add(call.id);
                    }else input.add(Json.obj("role","assistant","content","[Historical tool request] "+call.name+" "+Json.write(call.args)));
                }
            }else{
                Object content=m.content;
                if(m!=live&&!m.images.isEmpty())content=ChatClient.omittedImages(m);
                else if(!m.images.isEmpty()){List<Object> blocks=new ArrayList<>();blocks.add(Json.obj("type","input_text","text",m.content));for(var image:m.images)blocks.add(Json.obj("type","input_image","image_url","data:"+image.mime()+";base64,"+image.data()));content=blocks;}
                input.add(Json.obj("role","user","content",content));if(!m.requestControl.isBlank())input.add(Json.obj("role","user","content",m.requestControl));
            }
        }
        List<Object> specs=new ArrayList<>();for(var t:tools)specs.add(Json.obj("type","function","name",t.name,"description",t.description,"parameters",t.schema,"strict",false));
        Map<String,Object> b=Json.obj("model",model,"instructions",system,"input",input,"stream",true,"store",false,"include",List.of("reasoning.encrypted_content"));
        if(!specs.isEmpty())b.put("tools",List.of(Json.obj("type","namespace","name","localchat","description","LocalChat tools executed locally with application permissions.","tools",specs)));return b;
    }
    ChatClient.Reply read(BufferedReader reader,ChatClient.Listener listener,Consumer<TokenUsage> usage,java.util.function.BooleanSupplier cancelled)throws IOException {
        StringBuilder visible=new StringBuilder();List<ChatClient.ToolCall> calls=new ArrayList<>();List<Object> reasoning=new ArrayList<>();Object terminal=null;boolean completed=false;String line;StringBuilder data=new StringBuilder();
        while((line=reader.readLine())!=null){
            if(cancelled.getAsBoolean())throw new IOException("Cancelled");
            if(line.startsWith("data:")){if(data.length()>0)data.append('\n');data.append(line.substring(5).stripLeading());}
            else if(line.isEmpty()&&data.length()>0){Object e;try{e=Json.parse(data.toString());}catch(RuntimeException bad){throw new IOException("Invalid Responses event",bad);}data.setLength(0);String type=Objects.toString(Json.str(e,"type"),"");
                if(type.equals("response.output_text.delta")){String delta=Objects.toString(Json.str(e,"delta"),"");visible.append(delta);listener.onText(delta);}
                if(type.equals("response.output_item.done")){Object item=Json.get(e,"item");String kind=Json.str(item,"type");if("reasoning".equals(kind))reasoning.add(item);}
                if(type.equals("error")||type.equals("response.failed"))throw new IOException(I18n.text("Cererea ChatGPT a esuat: {0}",Objects.toString(Json.str(e,"response","error","code"),Objects.toString(Json.str(e,"code"),"unknown_error"))));
                if(type.equals("response.incomplete")){usage.accept(count(Json.get(e,"response"),false));return new ChatClient.Reply(visible.toString(),List.of(),"length");}
                if(type.equals("response.completed")){terminal=Json.get(e,"response");if(!"completed".equals(Json.str(terminal,"status")))throw new IOException("Responses completion status");completed=true;usage.accept(count(terminal,true));break;}
            }
        }
        if(!completed)throw new IOException(I18n.text("Fluxul ChatGPT s-a inchis fara raspuns complet."));
        Object output=Json.get(terminal,"output");StringBuilder full=new StringBuilder();if(output instanceof List<?> list){
            for(Object item:list)if("reasoning".equals(Json.str(item,"type"))&&!reasoning.contains(item))reasoning.add(item);
            for(Object item:list){String kind=Json.str(item,"type");if("function_call".equals(kind)){String id=Json.str(item,"call_id"),name=Json.str(item,"name");Object args;try{args=Json.parse(Objects.toString(Json.str(item,"arguments"),"{}"));}catch(RuntimeException bad){throw new IOException("Invalid tool arguments",bad);}if(!(args instanceof Map<?,?>)||id==null||name==null)throw new IOException("Invalid tool call");@SuppressWarnings("unchecked")var map=(Map<String,Object>)args;String namespace=Objects.toString(Json.str(item,"namespace"),"");if(!namespace.isEmpty()&&!namespace.equals("localchat"))throw new IOException("Unknown tool namespace");calls.add(new ChatClient.ToolCall(id,name,map));reasoningByCall.put(id,List.copyOf(reasoning));originalCalls.put(id,item);}
                if("message".equals(kind)&&Json.get(item,"content") instanceof List<?> content)for(Object part:content)if("output_text".equals(Json.str(part,"type")))full.append(Objects.toString(Json.str(part,"text"),""));
            }
        }
        if(visible.length()==0&&full.length()>0)listener.onText(full.toString());return new ChatClient.Reply(full.length()>0?full.toString():visible.toString(),calls,"stop");
    }
    static TokenUsage count(Object response,boolean complete){Object u=Json.get(response,"usage");return new TokenUsage(TokenUsage.number(u,"input_tokens",-1),TokenUsage.number(u,"output_tokens",-1),TokenUsage.number(Json.get(u,"input_tokens_details"),"cached_tokens",0),0,complete&&Json.get(u,"input_tokens")!=null&&Json.get(u,"output_tokens")!=null,TokenUsage.number(Json.get(u,"output_tokens_details"),"reasoning_tokens",0));}
}
