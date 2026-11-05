package localchat;

import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.security.spec.*;
import java.math.BigInteger;
import java.util.*;
import java.util.function.Consumer;

/** Official public-client OAuth. Never reads Codex, browser or VS Code credentials. */
final class ChatGptAccount {
    static final String ISSUER="https://auth.openai.com", API="https://api.openai.com/v1";
    static final String SCOPE="openid profile email offline_access resource.invoke chatgpt.tokens.use.direct";
    static Path directory(){if(Boolean.getBoolean("localchat.test")&&System.getProperty("localchat.test.accountHome")!=null)return Path.of(System.getProperty("localchat.test.accountHome"));return Path.of(System.getProperty("user.home"),".localchat-chatgpt");}
    static Path file(String id)throws IOException {if(!id.matches("[a-f0-9-]{36}"))throw new IOException(I18n.text("Conecteaza contul ChatGPT mai intai."));return directory().resolve(id+".json");}
    static String random(){byte[] b=new byte[32];new SecureRandom().nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
    static String text(Object o,String k){return Objects.toString(Json.str(o,k),"");}
    static void protect(Path p)throws IOException {
        var acl=Files.getFileAttributeView(p,AclFileAttributeView.class);
        if(acl!=null){var owner=Files.getOwner(p);var perms=EnumSet.allOf(AclEntryPermission.class);var entry=AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner).setPermissions(perms);if(Files.isDirectory(p))entry.setFlags(AclEntryFlag.DIRECTORY_INHERIT,AclEntryFlag.FILE_INHERIT);acl.setAcl(List.of(entry.build()));}
        else Files.setPosixFilePermissions(p,PosixFilePermissions.fromString(Files.isDirectory(p)?"rwx------":"rw-------"));
    }
    static void save(Path path,Object value)throws IOException {
        Files.createDirectories(path.getParent());protect(path.getParent());Path temp=Files.createTempFile(path.getParent(),"oauth-",".tmp");
        try{protect(temp);Files.writeString(temp,Json.write(value),StandardCharsets.UTF_8);Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temp);}
    }
    @SuppressWarnings("unchecked") static Map<String,Object> read(Path p)throws IOException {return new LinkedHashMap<>((Map<String,Object>)Json.parse(Files.readString(p,StandardCharsets.UTF_8)));}
    static String form(Map<String,String> values){return values.entrySet().stream().map(e->URLEncoder.encode(e.getKey(),StandardCharsets.UTF_8)+"="+URLEncoder.encode(e.getValue(),StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));}
    static Object http(String address,Map<String,String> data)throws IOException {
        if(!address.startsWith(ISSUER+"/"))throw new IOException("OAuth origin");
        HttpURLConnection c=(HttpURLConnection)URI.create(address).toURL().openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(15000);c.setReadTimeout(30000);
        try{if(data!=null){c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/x-www-form-urlencoded");try(var out=c.getOutputStream()){out.write(form(data).getBytes(StandardCharsets.UTF_8));}}
            int code=c.getResponseCode();if(code!=200)throw new IOException(I18n.text("Conectarea ChatGPT a esuat (HTTP {0}).",code));
            try(var in=c.getInputStream()){String body=new String(in.readNBytes(2_000_000),StandardCharsets.UTF_8);return body.isBlank()?Json.obj():Json.parse(body);}
        }finally{c.disconnect();}
    }
    static Object verify(String jwt,String client,String nonce,Object jwks)throws Exception {
        String[] parts=jwt.split("\\.");if(parts.length!=3)throw new IOException("ID token");var b64=Base64.getUrlDecoder();Object h=Json.parse(new String(b64.decode(parts[0]),StandardCharsets.UTF_8));
        if(!"RS256".equals(Json.str(h,"alg")))throw new IOException("ID token algorithm");
        Object keys=Json.get(jwks,"keys");boolean valid=false;
        if(keys instanceof List<?> list)for(Object key:list)if(text(h,"kid").equals(text(key,"kid"))&&"RSA".equals(text(key,"kty"))){
            PublicKey pub=KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(new BigInteger(1,b64.decode(text(key,"n"))),new BigInteger(1,b64.decode(text(key,"e")))));
            Signature sig=Signature.getInstance("SHA256withRSA");sig.initVerify(pub);sig.update((parts[0]+"."+parts[1]).getBytes(StandardCharsets.US_ASCII));valid=sig.verify(b64.decode(parts[2]));if(valid)break;
        }
        Object claims=Json.parse(new String(b64.decode(parts[1]),StandardCharsets.UTF_8));Object aud=Json.get(claims,"aud");long now=System.currentTimeMillis()/1000;
        if(!valid||!ISSUER.equals(text(claims,"iss"))||!(client.equals(aud)||aud instanceof List<?> a&&a.contains(client))||TokenUsage.number(claims,"exp",0)<=now||TokenUsage.number(claims,"iat",Long.MAX_VALUE)>now+5||text(claims,"sub").isBlank()||nonce!=null&&!nonce.equals(text(claims,"nonce")))throw new IOException("ID token validation");
        return claims;
    }
    static Map<String,String> query(String raw)throws IOException {Map<String,String> q=new HashMap<>();for(String p:raw.split("&")){String[] kv=p.split("=",2);String k=URLDecoder.decode(kv[0],StandardCharsets.UTF_8);if(q.put(k,kv.length==2?URLDecoder.decode(kv[1],StandardCharsets.UTF_8):"")!=null)throw new IOException("Duplicate callback parameter");}return q;}
    static boolean protectedPath(Path p){Path dir=directory().toAbsolutePath().normalize();if(p.toAbsolutePath().normalize().startsWith(dir))return true;try{return Files.exists(p)&&Files.exists(dir)&&p.toRealPath().startsWith(dir.toRealPath());}catch(IOException error){return true;}}
    static synchronized String host()throws IOException {
        Files.createDirectories(directory());protect(directory());Path lockPath=directory().resolve("host.lock");
        try(FileChannel channel=FileChannel.open(lockPath,StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock lock=channel.lock()){
            protect(lockPath);Path p=directory().resolve("host.json");if(Files.exists(p))return text(read(p),"host");String h="urn:uuid:"+UUID.randomUUID();save(p,Json.obj("host",h));return h;
        }
    }
    /** Blocking worker operation; listener is bound before the browser opens, expires after five minutes. */
    static String login(Consumer<URI> browser,String existing)throws Exception {
        Map<String,Object> prior=existing.matches("[a-f0-9-]{36}")&&Files.exists(file(existing))?read(file(existing)):Map.of();String savedClient=text(prior,"client_id");
        String state=random(),nonce=random(),verifier=random(),host=host();String challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        try(ServerSocket server=new ServerSocket(0,4,InetAddress.getByName("127.0.0.1"))){server.setSoTimeout(1000);String redirect="http://127.0.0.1:"+server.getLocalPort()+"/auth/callback";
            Map<String,String> params=new LinkedHashMap<>(Map.of("client_id","dynamic_agent_client","agent_name_hint","LocalChat","ext_agent_host_id",host,"response_type","code","redirect_uri",redirect,"scope",SCOPE,"resource",API,"state",state,"nonce",nonce));if(!savedClient.isBlank()){params.put("client_id",savedClient);params.remove("agent_name_hint");if(!text(prior,"id_token").isBlank())params.put("id_token_hint",text(prior,"id_token"));}
            params.put("code_challenge_method","S256");params.put("code_challenge",challenge);
            browser.accept(URI.create(ISSUER+"/api/accounts/authorize?"+form(params)));long deadline=System.nanoTime()+300_000_000_000L;
            while(System.nanoTime()<deadline&&!Thread.currentThread().isInterrupted()){
                boolean accepted=false;try(Socket socket=server.accept()){socket.setSoTimeout(3000);var reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));String line=reader.readLine();if(line==null||line.length()>16384)continue;String[] request=line.split(" ");if(request.length!=3||!request[0].equals("GET"))continue;URI target=URI.create(request[1]);
                    boolean path="/auth/callback".equals(target.getPath());Map<String,String> q=path?query(Objects.toString(target.getRawQuery(),"")):Map.of();boolean match=path&&MessageDigest.isEqual(state.getBytes(StandardCharsets.US_ASCII),q.getOrDefault("state","").getBytes(StandardCharsets.US_ASCII));
                    byte[] reply=(match?"Return to LocalChat. You may close this tab.":"Invalid callback.").getBytes(StandardCharsets.UTF_8);socket.getOutputStream().write(((match?"HTTP/1.1 200 OK":"HTTP/1.1 400 Bad Request")+"\r\nContent-Type: text/plain; charset=utf-8\r\nCache-Control: no-store\r\nContent-Length: "+reply.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(reply);if(!match)continue;accepted=true;
                    if(q.containsKey("error"))throw new IOException(I18n.text("Conectarea ChatGPT a fost anulata."));String client=q.getOrDefault("client_id",savedClient);if(!savedClient.isBlank()&&!savedClient.equals(client))throw new IOException("OAuth client mismatch");if(client.isBlank()||client.equals("dynamic_agent_client")||q.getOrDefault("code","").isBlank())throw new IOException("Incomplete OAuth registration");
                    Object token=http(ISSUER+"/api/accounts/oauth/token",Map.of("grant_type","authorization_code","client_id",client,"code",q.get("code"),"code_verifier",verifier,"redirect_uri",redirect,"resource",API));
                    Object claims=verify(text(token,"id_token"),client,nonce,http(ISSUER+"/.well-known/jwks.json",null));if(!prior.isEmpty()&&!text(prior,"subject").equals(text(claims,"sub")))throw new IOException("OAuth account mismatch");String id=prior.isEmpty()?UUID.randomUUID().toString():existing;Map<String,Object> record=new LinkedHashMap<>();record.put("client_id",client);record.put("subject",text(claims,"sub"));record.put("email",text(claims,"email"));record.put("host",host);update(record,token);save(file(id),record);return id;
                }catch(SocketTimeoutException timeout){if(accepted)throw new IOException(I18n.text("Conectarea ChatGPT a expirat. Incearca din nou."),timeout);}
            }throw new IOException(I18n.text("Conectarea ChatGPT a expirat. Incearca din nou."));
        }
    }
    static void update(Map<String,Object> record,Object token)throws IOException {
        String scope=text(token,"scope");if(scope.isBlank())scope=text(record,"scope");
        if(!Arrays.asList(scope.split(" ")).contains("chatgpt.tokens.use.direct")||text(token,"access_token").isBlank()||!"Bearer".equalsIgnoreCase(text(token,"token_type")))throw new IOException(I18n.text("Contul nu a autorizat utilizarea planului ChatGPT."));
        for(String k:List.of("access_token","refresh_token","id_token"))if(!text(token,k).isBlank())record.put(k,text(token,k));record.put("scope",scope);long seconds=TokenUsage.number(token,"expires_in",0);if(seconds<=0)throw new IOException("Token expiry");record.put("expires_at",System.currentTimeMillis()/1000+seconds);
    }
    static synchronized String token(String id)throws IOException {
        Path p=file(id);try(FileChannel channel=FileChannel.open(directory().resolve(id+".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock lock=channel.lock()){
            protect(directory().resolve(id+".lock"));Map<String,Object> record=read(p);if(text(record,"access_token").isBlank())throw new IOException(I18n.text("Conecteaza contul ChatGPT mai intai."));
            if(TokenUsage.number(record,"expires_at",0)<System.currentTimeMillis()/1000+60){Object renewed=http(ISSUER+"/api/accounts/oauth/token",Map.of("grant_type","refresh_token","client_id",text(record,"client_id"),"refresh_token",text(record,"refresh_token"),"resource",API));if(!text(renewed,"id_token").isBlank()){try{Object identity=verify(text(renewed,"id_token"),text(record,"client_id"),null,http(ISSUER+"/.well-known/jwks.json",null));if(!text(identity,"sub").equals(text(record,"subject")))throw new IOException("OAuth identity mismatch");}catch(Exception failure){throw new IOException("OAuth identity validation",failure);}}update(record,renewed);save(p,record);}
            return text(record,"access_token");
        }
    }
    static synchronized boolean logout(String id)throws IOException {
        Path path=file(id);try(FileChannel channel=FileChannel.open(directory().resolve(id+".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock lock=channel.lock()){Map<String,Object> r=read(path);boolean revoked=false;try{Object discovery=http(ISSUER+"/.well-known/openid-configuration",null);String endpoint=text(discovery,"revocation_endpoint");http(endpoint,Map.of("token",text(r,"refresh_token"),"token_type_hint","refresh_token","client_id",text(r,"client_id")));revoked=true;}catch(IOException ignored){}for(String k:List.of("access_token","refresh_token","id_token","scope","expires_at"))r.remove(k);save(path,r);return revoked;}
    }
    static String label(String id)throws IOException {Map<String,Object> r=read(file(id));String email=text(r,"email");return email.substring(0,Math.min(45,email.length()))+" · "+id.substring(0,8);}
    static void checkEndpoint(String address)throws IOException {URI u=URI.create(address);if(!"https".equals(u.getScheme())||!"api.openai.com".equals(u.getHost())||u.getPort()!=-1||u.getUserInfo()!=null||u.getQuery()!=null||!Set.of("/v1/models","/v1/responses").contains(u.getPath()))throw new IOException("ChatGPT endpoint");}
}
