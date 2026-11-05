package localchat;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.util.*;
import java.util.concurrent.*;
import javax.swing.*;

/** Official-contract fixtures only. No real tokens, account or inference requests. */
public final class ChatGptAccountTest {
 static int checks;
 static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 interface Action{void run()throws Exception;}
 static void fails(Action a,String why)throws Exception{try{a.run();throw new AssertionError(why);}catch(IOException expected){checks++;}}
 static String b64(byte[] b){return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
 static String jwt(KeyPair pair,Object claims)throws Exception{String unsigned=b64(Json.write(Json.obj("alg","RS256","kid","fixture")).getBytes(StandardCharsets.UTF_8))+"."+b64(Json.write(claims).getBytes(StandardCharsets.UTF_8));Signature s=Signature.getInstance("SHA256withRSA");s.initSign(pair.getPrivate());s.update(unsigned.getBytes(StandardCharsets.US_ASCII));return unsigned+"."+b64(s.sign());}
 static String event(String type,Object... kv){var m=Json.obj(kv);m.put("type",type);return "data: "+Json.write(m)+"\n\n";}
 static ChatClient.Reply reply(ChatGptResponses wire,String body,List<TokenUsage> usage)throws Exception{return wire.read(new BufferedReader(new StringReader(body)),s->{},usage::add,()->false);}
 public static void main(String[] args)throws Exception{
  Path home=Files.createTempDirectory(Files.createDirectories(Path.of(System.getProperty("localchat.home"))),"chatgpt-");System.setProperty("localchat.test.accountHome",home.resolve("accounts").toString());
  identity();callback();transport();storage(home);ui(home);
  System.out.println("PASS: "+checks+" ChatGPT account checks; signed identity fixtures, callback/PKCE, stream completion, tool continuation, isolated credentials, UI, six languages; no real account or external API");System.exit(0);
 }
 static void identity()throws Exception{
  var g=KeyPairGenerator.getInstance("RSA");g.initialize(2048);var pair=g.generateKeyPair();var pub=(RSAPublicKey)pair.getPublic();Object keys=Json.obj("keys",List.of(Json.obj("kid","fixture","kty","RSA","n",b64(unsigned(pub.getModulus().toByteArray())),"e",b64(unsigned(pub.getPublicExponent().toByteArray())))));
  long now=System.currentTimeMillis()/1000;var claims=Json.obj("iss",ChatGptAccount.ISSUER,"aud",List.of("oaiapp_fixture"),"sub","fixture-subject","nonce","nonce","exp",now+120,"iat",now);
  check("fixture-subject".equals(Json.str(ChatGptAccount.verify(jwt(pair,claims),"oaiapp_fixture","nonce",keys),"sub")),"verified signed identity");
  fails(()->ChatGptAccount.verify(jwt(pair,claims),"other","nonce",keys),"audience");fails(()->ChatGptAccount.verify(jwt(pair,claims),"oaiapp_fixture","wrong",keys),"nonce");
  for(String key:List.of("iss","sub","exp","iat")){var bad=new LinkedHashMap<>(claims);bad.put(key,key.equals("exp")?now-1:key.equals("iat")?now+1000:"");fails(()->ChatGptAccount.verify(jwt(pair,bad),"oaiapp_fixture","nonce",keys),key);}
  var changed=Json.obj("keys",List.of());fails(()->ChatGptAccount.verify(jwt(pair,claims),"oaiapp_fixture","nonce",changed),"unknown signing key");
  fails(()->ChatGptAccount.query("code=x&code=y"),"duplicate callback");
  for(String url:List.of("https://evil.test/v1/responses","http://api.openai.com/v1/models","https://api.openai.com:443/v1/models","https://user@api.openai.com/v1/models","https://api.openai.com/v1/models?token=x","https://api.openai.com/backend-api/messages"))fails(()->ChatGptAccount.checkEndpoint(url),"pinned endpoint");
  ChatGptAccount.checkEndpoint(ChatGptAccount.API+"/responses");ChatGptAccount.checkEndpoint(ChatGptAccount.API+"/models");checks+=2;
 }
 static byte[] unsigned(byte[] b){return b.length>1&&b[0]==0?Arrays.copyOfRange(b,1,b.length):b;}
 static void callback()throws Exception{
  var address=new CompletableFuture<URI>();var pool=Executors.newSingleThreadExecutor();try{
   Future<?> task=pool.submit(()->{try{ChatGptAccount.login(address::complete,"");throw new AssertionError("access denied must fail");}catch(IOException e){if(!e.getMessage().equals(I18n.text("Conectarea ChatGPT a fost anulata.")))throw new RuntimeException(e);}catch(Exception e){throw new RuntimeException(e);}});
   URI uri=address.get(5,TimeUnit.SECONDS);Map<String,String> q=ChatGptAccount.query(uri.getRawQuery());
   check(uri.getHost().equals("auth.openai.com")&&uri.getPath().equals("/api/accounts/authorize"),"official browser origin");check(q.get("client_id").equals("dynamic_agent_client"),"first registration");check(q.get("agent_name_hint").equals("LocalChat"),"honest app identity");check(q.get("ext_agent_host_id").equals(ChatGptAccount.host()),"stable host");
   check(q.get("scope").contains("chatgpt.tokens.use.direct")&&q.get("scope").contains("offline_access"),"explicit plan consent");check(q.get("code_challenge_method").equals("S256")&&q.get("code_challenge").length()==43,"PKCE");check(!q.get("nonce").equals(q.get("state")),"independent nonce/state");URI redirect=URI.create(q.get("redirect_uri"));check(redirect.getHost().equals("127.0.0.1")&&redirect.getPath().equals("/auth/callback"),"loopback callback");
   HttpURLConnection wrong=(HttpURLConnection)URI.create(redirect+"?state=wrong&error=access_denied").toURL().openConnection();wrong.setReadTimeout(3000);check(wrong.getResponseCode()==400,"wrong state rejected without consuming transaction");wrong.disconnect();
   HttpURLConnection deny=(HttpURLConnection)URI.create(redirect+"?state="+q.get("state")+"&error=access_denied").toURL().openConnection();deny.setReadTimeout(3000);check(deny.getResponseCode()==200,"valid denial consumed");deny.disconnect();task.get(5,TimeUnit.SECONDS);
  }finally{pool.shutdownNow();}
 }
 static void transport()throws Exception{
  var wire=new ChatGptResponses();var user=ChatClient.Message.user("exact user request");var spec=new ChatClient.ToolSpec("read_file","Read a file",Json.obj("type","object","properties",Json.obj("path",Json.obj("type","string"))));
  var body=wire.body("account-model","system",List.of(user),List.of(spec),2048);check(Boolean.FALSE.equals(body.get("store"))&&Boolean.TRUE.equals(body.get("stream")),"required request flags");check(body.get("max_output_tokens").equals(2048),"output limit");check(Json.str(body,"tools",0,"name").equals("read_file"),"LocalChat tools routed through Responses");check(!Json.write(body).contains("access_token"),"token never in payload");
  List<TokenUsage> usage=new ArrayList<>();Object reason=Json.obj("type","reasoning","id","rs_fixture","encrypted_content","ciphertext-fixture","summary",List.of());Object call=Json.obj("type","function_call","call_id","call_fixture","name","read_file","arguments","{\"path\":\"file.txt\"}");
  Object complete=Json.obj("status","completed","output",List.of(reason,call),"usage",Json.obj("input_tokens",100,"output_tokens",20,"input_tokens_details",Json.obj("cached_tokens",30),"output_tokens_details",Json.obj("reasoning_tokens",5)));
  var result=reply(wire,event("response.completed","response",complete),usage);check(result.calls.size()==1&&result.calls.get(0).name.equals("read_file"),"verified final tool call");check(usage.get(0).input()==100&&usage.get(0).output()==20&&usage.get(0).cached()==30&&usage.get(0).reasoning()==5,"actual usage fields");
  var assistant=ChatClient.Message.assistant("",result.calls,"ChatGPT");var tool=ChatClient.Message.toolResult("call_fixture","verified data",false);var continued=wire.body("m","",List.of(user,assistant,tool),List.of(spec),0);String json=Json.write(continued);check(json.contains("function_call_output")&&json.contains("ciphertext-fixture"),"request-local reasoning and tool output retained for continuation");
  var restored=new ChatGptResponses().body("m","",List.of(user,assistant,tool),List.of(spec),0);check(!Json.write(restored).contains("function_call_output")&&Json.write(restored).contains("Historical tool result"),"restored old traces do not invent missing reasoning items");
  fails(()->reply(wire,event("response.output_item.done","item",call),new ArrayList<>()),"no execution on incomplete stream");fails(()->reply(wire,event("response.failed","response",Json.obj("error",Json.obj("code","subscription_sharing_usage_limit_exceeded"))),new ArrayList<>()),"usage limit is failure");
  var shortReply=reply(wire,event("response.incomplete","response",Json.obj("usage",Json.obj("input_tokens",10,"output_tokens",10))),new ArrayList<>());check(shortReply.truncated()&&shortReply.calls.isEmpty(),"truncation never executes tools");
  String output=event("response.output_text.delta","delta","Hello")+event("response.completed","response",Json.obj("status","completed","output",List.of(Json.obj("type","message","content",List.of(Json.obj("type","output_text","text","Hello"))))));check(reply(wire,output,new ArrayList<>()).text.equals("Hello"),"streamed response");
  fails(()->wire.read(new BufferedReader(new StringReader(output)),s->{},u->{},()->true),"Stop");
  check(!Json.write(assistant.toJson()).contains("ciphertext"),"encrypted reasoning is not archived");
 }
 static void storage(Path home)throws Exception{
  String first=UUID.randomUUID().toString(),second=UUID.randomUUID().toString();ChatGptAccount.save(ChatGptAccount.file(first),Json.obj("access_token","fixture-one","expires_at",System.currentTimeMillis()/1000+1000));ChatGptAccount.save(ChatGptAccount.file(second),Json.obj("access_token","fixture-two","expires_at",System.currentTimeMillis()/1000+1000));check(ChatGptAccount.token(first).equals("fixture-one")&&ChatGptAccount.token(second).equals("fixture-two"),"accounts isolated");
  fails(()->ChatGptAccount.file("../../auth.json"),"path traversal");var record=Json.obj("scope",ChatGptAccount.SCOPE);ChatGptAccount.update(record,Json.obj("access_token","fixture","token_type","Bearer","expires_in",3600,"refresh_token","rotated"));check(record.get("refresh_token").equals("rotated"),"rotating token persisted");fails(()->ChatGptAccount.update(new HashMap<>(),Json.obj("access_token","x","scope","openid","token_type","Bearer","expires_in",3600)),"identity alone does not permit inference");
  Properties config=new Properties();var p=SavedProviders.save(config,"Fixture account",Provider.CHATGPT,ChatGptAccount.API,first,"");check(!config.toString().contains("fixture-one")&&config.getProperty(p.prefix()+".key").equals(first),"settings contain only opaque reference");
  Tools t=new Tools(new Tools.Context(home,home.resolve("skills"),home.resolve("rules.md"),home.resolve("settings.properties"),Map.of(),null,new Mcp(),PathMap.none(),true,EnumSet.noneOf(Tools.Access.class),(a,b)->Tools.Decision.YES),List.of());check(t.execute(new ChatClient.ToolCall("read","read_file",Json.obj("path",ChatGptAccount.file(first).toString()) )).isError,"tools cannot read OAuth credentials");
 }
 static void ui(Path home)throws Exception{
  System.setProperty("localchat.home",home.resolve("ui").toString());var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();SwingUtilities.invokeAndWait(()->{LocalChat app=null;try{var ctor=LocalChat.class.getDeclaredConstructor();ctor.setAccessible(true);app=ctor.newInstance();var show=LocalChat.class.getDeclaredMethod("showProvider",Object.class);show.setAccessible(true);show.invoke(app,Provider.CHATGPT);for(String name:List.of("chatGptLogin","chatGptLogout")){var f=LocalChat.class.getDeclaredField(name);f.setAccessible(true);check(((JButton)f.get(app)).isVisible(),"account control "+name);}var k=LocalChat.class.getDeclaredField("keyField");k.setAccessible(true);check(!((JPasswordField)k.get(app)).isVisible(),"no API key required");show.invoke(app,Provider.CUSTOM);check(((JPasswordField)k.get(app)).isVisible(),"other providers unchanged");for(String code:I18n.CODES){I18n.setLanguage(code);check(!I18n.text("Continua cu ChatGPT").isBlank(),"localized sign-in "+code);}}catch(Throwable e){failure.set(e);}finally{if(app!=null)app.dispose();}});if(failure.get()!=null)throw new Exception(failure.get());
 }
}
