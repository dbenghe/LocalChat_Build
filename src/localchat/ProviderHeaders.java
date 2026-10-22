package localchat;
import java.net.HttpURLConnection;
/** Provider context must not silently merge locally isolated chats and worker agents. */
final class ProviderHeaders {
 static void validateKey(String base,String key)throws java.io.IOException{
  if("api.x.ai".equalsIgnoreCase(java.net.URI.create(base).getHost())&&(key.startsWith("crsr_")||key.startsWith("grok_")))throw new java.io.IOException(I18n.text("Aceasta este o cheie Grok/Cursor Agents. API-ul api.x.ai necesita o cheie de inferenta xAI."));
 }
 static void authenticate(HttpURLConnection c,Provider protocol,String key){
  boolean anthropic=protocol.format==Provider.Format.ANTHROPIC&&!"api.x.ai".equalsIgnoreCase(c.getURL().getHost());
  if(!key.isBlank())c.setRequestProperty(anthropic?"x-api-key":"Authorization",anthropic?key:"Bearer "+key);
  if(protocol.format==Provider.Format.ANTHROPIC)c.setRequestProperty("anthropic-version",ChatClient.ANTHROPIC_VERSION);
 }
 static java.io.IOException metadataError(HttpURLConnection c,int status){
  String message="HTTP "+status;
  try(java.io.InputStream in=c.getErrorStream()){String body=in==null?"":new String(in.readNBytes(8192),java.nio.charset.StandardCharsets.UTF_8).toLowerCase(java.util.Locale.ROOT);
   if(body.contains("incorrect api key")||body.contains("invalid api key")||body.contains("invalid_api_key"))message+=": "+I18n.text("Cheia API nu este acceptata de furnizor. Verifica cheia in configuratia acestui API.");
  }catch(java.io.IOException ignored){}
  return new java.io.IOException(message);
 }
 static void apply(HttpURLConnection connection){
  String host=connection.getURL().getHost();
  if("api.tileward.com".equalsIgnoreCase(host))connection.setRequestProperty("X-Tileward-Conversation","off");
  if("api.gonkarouter.io".equalsIgnoreCase(host))connection.setRequestProperty("X-Gonka-No-Fallback","true");
 }
}
