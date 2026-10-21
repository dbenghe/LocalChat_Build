package localchat;
import java.net.HttpURLConnection;
/** Provider context must not silently merge locally isolated chats and worker agents. */
final class ProviderHeaders {
 static void apply(HttpURLConnection connection){
  String host=connection.getURL().getHost();
  if("api.tileward.com".equalsIgnoreCase(host))connection.setRequestProperty("X-Tileward-Conversation","off");
  if("api.gonkarouter.io".equalsIgnoreCase(host))connection.setRequestProperty("X-Gonka-No-Fallback","true");
 }
}
