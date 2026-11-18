package localchat;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.zip.*;

/** Public WeTransfer UI only. A fresh Edge/Chrome profile belongs to this operation;
 * no existing browser, cookie store, credential, transfer endpoint or private API is used. */
final class BrowserDownload {
    static final String TERMS_TITLE="Acceptarea termenilor WeTransfer";
    interface Commit {
        Path target(String filename) throws IOException;
        /** Always requires a fresh explicit user decision; automatic file approval is not consent. */
        default boolean acceptTerms() throws IOException { return false; }
    }
    interface Runner { UrlDownload.Outcome fetch(String url, Path directory, long max, BooleanSupplier stop, Commit commit) throws IOException; }
    static volatile Runner runner = BrowserDownload::fetch;
    private static final String ACTION = """
        (() => {
          const visible=e=>{const r=e.getBoundingClientRect();if(!r.width||!r.height||getComputedStyle(e).visibility==='hidden')return false;const hit=document.elementFromPoint(r.x+r.width/2,r.y+r.height/2);return !!hit&&(e===hit||e.contains(hit));};
          const buttons=[...document.querySelectorAll('button,a,[role=button],input[type=submit]')].filter(visible);
          const label=e=>(e.innerText||e.value||e.getAttribute('aria-label')||'').trim().replace(/\\s+/g,' ').toLowerCase();
          const reject=/^(reject all|reject optional cookies|decline optional cookies|alle weigeren|alles weigeren|alleen noodzakelijke cookies|alle ablehnen|refuser tout|rechazar todo|respinge toate|odrzuć wszystkie|tümünü reddet)$/;
          let b=buttons.find(e=>reject.test(label(e)));
          if(b){b.click();return 'consent-rejected';}
          const t=(document.body?.innerText||'').toLowerCase();
          if(t.includes('to continue, please agree to our terms of service')&&t.includes('acknowledge our privacy policy')&&buttons.some(e=>label(e)==='i agree'))return 'download-terms-visible';
          const download=/^(download( all| files| all files)?|downloaden|alles downloaden|bestanden downloaden|herunterladen|alle herunterladen|télécharger( tout)?|descargar( todo)?|descarca( tot)?|pobierz( wszystko)?|indir|tümünü indir|get your files|get the files|haal je bestanden op)$/;
          b=buttons.find(e=>download.test(label(e))&&!e.disabled&&e.getAttribute('aria-disabled')!=='true');
          if(b){b.click();return 'download-clicked';}
          if(document.querySelector('input[type=password]'))return 'password-field-visible';
          if(/transfer (has )?expired|transfer is verlopen|overdracht is verlopen/.test(t))return 'expiry-message-visible';
          if(/verify (that )?you are human|confirm you are human|bevestig dat je een mens/.test(t))return 'human-verification-visible';
          if(/sign in to download|log in to download|inloggen om te downloaden/.test(t))return 'login-message-visible';
          return 'waiting-for-download-control';
        })()
        """;
    private static final String ACCEPT_TERMS = """
        (()=>{const t=(document.body?.innerText||'').toLowerCase();
          if(!t.includes('to continue, please agree to our terms of service')||!t.includes('acknowledge our privacy policy'))return 'terms-page-changed';
          const b=[...document.querySelectorAll('button,[role=button]')].find(e=>e.getClientRects().length&&(e.innerText||'').trim().toLowerCase()==='i agree');
          if(b){b.click();return 'download-terms-acknowledged';}return 'terms-control-unavailable';})()
        """;

    static Path executable() throws IOException {
        // Explicit override is test-only; model arguments cannot choose an executable or profile.
        String override = Boolean.getBoolean("localchat.test") ? System.getProperty("localchat.browser.testExe") : null;
        if (override != null && Files.isRegularFile(Path.of(override))) return Path.of(override);
        for (String base : List.of("ProgramFiles(x86)", "ProgramFiles", "LOCALAPPDATA")) {
            String root = System.getenv(base);
            if (root == null) continue;
            for (String name : List.of("Microsoft/Edge/Application/msedge.exe", "Google/Chrome/Application/chrome.exe")) {
                Path p=Path.of(root).resolve(name); if(Files.isRegularFile(p)) return p;
            }
        }
        throw new IOException("Automatic browser download requires Microsoft Edge or Google Chrome installed on this computer. Nothing downloaded.");
    }

    static UrlDownload.Outcome fetch(String raw, Path directory, long max, BooleanSupplier stop, Commit commit) throws IOException {
        active(stop);
        URI source=UrlDownload.parse(UrlDownload.unwrapSafelink(raw));
        boolean fixture=Boolean.getBoolean("localchat.test") && UrlDownload.allowPrivateForTests;
        if(!UrlDownload.browserOnly(source.getHost()) && !fixture) throw new IOException("Automatic browser downloads support only WeTransfer links");
        UrlDownload.requirePublic(source.getHost());
        Path browser=executable(), temp=Files.createTempDirectory("localchat-browser-");
        Path profile=Files.createDirectory(temp.resolve("profile")), incoming=Files.createDirectory(temp.resolve("incoming"));
        Process process=null;
        try {
            List<String> command=new ArrayList<>(List.of(browser.toString(),"--user-data-dir="+profile,"--remote-debugging-port=0","--remote-debugging-address=127.0.0.1","--no-first-run","--no-default-browser-check","--disable-background-networking","--disable-extensions","--disable-sync","--window-size=1000,760","about:blank"));
            if(Boolean.getBoolean("localchat.test") && Boolean.getBoolean("localchat.browser.headless"))command.add(1,"--headless=new");
            process=new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            long startup=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);
            Path portFile=profile.resolve("DevToolsActivePort");
            while(!Files.isRegularFile(portFile)){ active(stop);if(System.nanoTime()>startup||!process.isAlive())throw new IOException("The isolated browser could not start; check browser policy. Nothing downloaded.");pause(100); }
            List<String> port=Files.readAllLines(portFile);if(port.size()<2)throw new IOException("Browser control endpoint unavailable");
            try(Cdp cdp=new Cdp(Integer.parseInt(port.get(0)),port.get(1))) {
                cdp.call("Browser.setDownloadBehavior",Json.obj("behavior","allowAndName","downloadPath",incoming.toString(),"eventsEnabled",true),null);
                String target=Json.str(cdp.call("Target.createTarget",Json.obj("url","about:blank"),null),"targetId");
                String session=Json.str(cdp.call("Target.attachToTarget",Json.obj("targetId",target,"flatten",true),null),"sessionId");
                cdp.call("Page.enable",Map.of(),session);
                cdp.call("Fetch.enable",Json.obj("patterns",List.of(Json.obj("urlPattern","*","requestStage","Request"))),session);
                cdp.send("Page.navigate",Json.obj("url",source.toString()),session);
                Set<String> checked=new HashSet<>(), frames=new HashSet<>();
                String guid=null, filename=null, state="waiting-for-download-control";
                long bytes=0, total=0, deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(timeout(180)), nextAction=0;
                boolean complete=false, trustedPage=false, termsApproved=false;
                while(!complete) {
                    active(stop); if(!process.isAlive())throw new IOException("Browser closed before a verified download completed");
                    if(System.nanoTime()>deadline)throw new IOException("Browser download timed out ("+state+"); no verified file was saved. Retry only after checking the transfer page.");
                    Map<?,?> event=cdp.events.poll();
                    if(event!=null){
                        String method=Json.str(event,"method");Object p=event.get("params");
                        if("Fetch.requestPaused".equals(method)) {
                            String id=Json.str(p,"requestId"), url=Json.str(p,"request","url");boolean allowed=false;
                            try {
                                URI u=UrlDownload.parse(url);
                                if(!checked.contains(u.getHost())){UrlDownload.requirePublic(u.getHost());checked.add(u.getHost());}
                                // A normal Download button may navigate to a public CDN attachment.
                                // Page interaction itself remains restricted to WeTransfer below.
                                allowed=true;
                            }catch(IOException ignored){}
                            cdp.send(allowed?"Fetch.continueRequest":"Fetch.failRequest",allowed?Json.obj("requestId",id):Json.obj("requestId",id,"errorReason","BlockedByClient"),session);
                        } else if("Page.frameNavigated".equals(method)){
                            frames.add(Json.str(p,"frame","id"));
                            if(Json.get(p,"frame","parentId")==null){
                                try{trustedPage=fixture||UrlDownload.browserOnly(UrlDownload.parse(Json.str(p,"frame","url")).getHost());}catch(IOException ignored){trustedPage=false;}
                            }
                        }
                        else if("Page.frameAttached".equals(method))frames.add(Json.str(p,"frameId"));
                        else if("Browser.downloadWillBegin".equals(method)){
                            if(guid!=null || !frames.contains(Json.str(p,"frameId")))throw new IOException("Unexpected additional browser download; nothing committed");
                            guid=Json.str(p,"guid");filename=UrlDownload.sanitize(Json.str(p,"suggestedFilename"));
                            deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(timeout(900));state="download-in-progress";
                        }else if("Browser.downloadProgress".equals(method)&&Objects.equals(guid,Json.str(p,"guid"))){
                            bytes=number(Json.get(p,"receivedBytes"));total=number(Json.get(p,"totalBytes"));
                            if(bytes>max||total>max){cdp.send("Browser.cancelDownload",Json.obj("guid",guid),null);throw new IOException("Download exceeds the size limit; nothing committed");}
                            String status=Json.str(p,"state");if("canceled".equals(status))throw new IOException("Browser reported a canceled download; nothing committed");
                            complete="completed".equals(status);
                        }
                    }
                    // Do not wait synchronously for page evaluation: network interception must keep flowing.
                    if(guid==null && trustedPage && System.nanoTime()>nextAction){
                        Object result=cdp.takeEvaluation();
                        if(result!=null){
                            state=String.valueOf(result);
                            if(state.equals("download-terms-visible")){
                                if(!termsApproved){termsApproved=commit.acceptTerms();active(stop);if(!termsApproved)throw new IOException("WeTransfer download terms were not accepted by the user; nothing downloaded");deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(timeout(180));}
                                cdp.evaluate(ACCEPT_TERMS,session);nextAction=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);continue;
                            }
                            if(state.endsWith("-visible"))throw new IOException("Transfer page: "+state+". User interaction is required; nothing downloaded.");
                        }
                        cdp.evaluate(ACTION,session);nextAction=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                    }
                    if(event==null)pause(25);
                }
                active(stop);
                Path downloaded=incoming.resolve(guid);
                if(!Files.isRegularFile(downloaded,LinkOption.NOFOLLOW_LINKS))throw new IOException("Browser completion has no matching local file");
                long size=Files.size(downloaded);
                if(size<=0||size>max||size!=bytes||(total>0&&size!=total))throw new IOException("Downloaded file size does not match browser completion; nothing committed");
                String hash=verify(downloaded,filename,max,stop);
                active(stop);Path dest=commit.target(filename);
                if(!dest.getParent().toRealPath().equals(directory.toRealPath()))throw new IOException("Download target escaped the approved directory");
                // Commit with exclusive creation; collisions never replace an existing source.
                boolean created=false;
                try {
                    try(OutputStream out=Files.newOutputStream(dest,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
                        created=true;
                        try(InputStream in=Files.newInputStream(downloaded)){byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1){active(stop);out.write(buffer,0,n);}}
                    }
                    if(!hash.equals(sha256(dest,stop)))throw new IOException("Saved download failed SHA-256 readback");
                }catch(IOException error){if(created)Files.deleteIfExists(dest);throw error;}
                return new UrlDownload.Outcome(UrlDownload.Kind.DOWNLOADED,"https://"+source.getHost()+"/[transfer]",dest,size,hash,"application/octet-stream","Browser completion, size and SHA-256 readback verified"+(filename.toLowerCase(Locale.ROOT).endsWith(".zip")?"; ZIP CRC verified":""));
            }
        }finally {
            if(process!=null){process.descendants().forEach(ProcessHandle::destroy);process.destroy();try{if(!process.waitFor(3,TimeUnit.SECONDS))process.destroyForcibly();}catch(InterruptedException e){Thread.currentThread().interrupt();}}
            // Only this operation's unique temporary tree; never a user browser/profile/project.
            for(int attempt=0;attempt<3;attempt++){try(var paths=Files.walk(temp)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);break;}catch(IOException e){try{Thread.sleep(200);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();break;}}}
        }
    }

    private static int timeout(int normal){return Boolean.getBoolean("localchat.test")?Integer.getInteger("localchat.browser.timeout",normal):normal;}
    private static long number(Object value){return value instanceof Number n?n.longValue():0;}
    private static void active(BooleanSupplier stop)throws IOException{if(stop.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("Stopped by the user");}
    private static void pause(long ms)throws IOException{try{Thread.sleep(ms);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Stopped by the user");}}
    static String sha256(Path path,BooleanSupplier stop)throws IOException{
        try{MessageDigest md=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(path)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){active(stop);md.update(b,0,n);}}return HexFormat.of().formatHex(md.digest());}
        catch(java.security.NoSuchAlgorithmException e){throw new IOException(e);}
    }
    static String verify(Path file,String name,long max,BooleanSupplier stop)throws IOException{
        try(InputStream in=Files.newInputStream(file)){String prefix=new String(in.readNBytes(256),StandardCharsets.US_ASCII).stripLeading().toLowerCase(Locale.ROOT);if(prefix.startsWith("<!doctype html")||prefix.startsWith("<html"))throw new IOException("Browser downloaded an HTML page instead of the transfer file");}
        if(name.toLowerCase(Locale.ROOT).endsWith(".zip")){
            long expanded=0,deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(120);int count=0;
            try(ZipFile zip=new ZipFile(file.toFile())){
                if(zip.size()==0)throw new IOException("Empty ZIP archive");
                var entries=zip.entries();byte[] buffer=new byte[65536];
                while(entries.hasMoreElements()){
                    ZipEntry entry=entries.nextElement();if(++count>100000)throw new IOException("ZIP verification entry limit exceeded");if(entry.isDirectory())continue;
                    CRC32 crc=new CRC32();long actual=0;
                    try(InputStream in=zip.getInputStream(entry)){int n;while((n=in.read(buffer))!=-1){active(stop);actual+=n;expanded+=n;if(expanded>Math.min(20L<<30,max*100)||System.nanoTime()>deadline)throw new IOException("ZIP verification resource limit exceeded; nothing committed");crc.update(buffer,0,n);}}
                    if(actual!=entry.getSize()||crc.getValue()!=entry.getCrc())throw new IOException("ZIP entry failed size/CRC verification");
                }
            }
        }
        return sha256(file,stop);
    }

    /** Minimal loopback WebSocket transport for CDP, using java.base (portable runtime unchanged).
     * Browser address comes exclusively from the fresh profile's DevToolsActivePort. */
    private static final class Cdp implements AutoCloseable {
        final Socket socket=new Socket();final InputStream in;final OutputStream out;
        final AtomicInteger sequence=new AtomicInteger();final Map<Integer,CompletableFuture<Object>> calls=new ConcurrentHashMap<>();
        final Queue<Map<?,?>> events=new ConcurrentLinkedQueue<>();volatile CompletableFuture<Object> evaluation;volatile boolean closed;
        Cdp(int port,String path)throws IOException{
            if(!path.matches("/devtools/browser/[A-Za-z0-9-]+"))throw new IOException("Invalid browser control endpoint");
            socket.connect(new InetSocketAddress("127.0.0.1",port),5000);socket.setTcpNoDelay(true);socket.setSoTimeout(10000);in=new BufferedInputStream(socket.getInputStream());out=new BufferedOutputStream(socket.getOutputStream(),65536);
            byte[] nonce=new byte[16];new java.security.SecureRandom().nextBytes(nonce);String key=Base64.getEncoder().encodeToString(nonce);
            out.write(("GET "+path+" HTTP/1.1\r\nHost: 127.0.0.1:"+port+"\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: "+key+"\r\nSec-WebSocket-Version: 13\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.flush();
            ByteArrayOutputStream header=new ByteArrayOutputStream();int value;while((value=in.read())!=-1){header.write(value);if(header.size()>16384)throw new IOException("Browser handshake too large");if(header.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n"))break;}
            String response=header.toString(StandardCharsets.US_ASCII);String accept;
            try{accept=Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));}catch(Exception e){throw new IOException(e);}
            if(!response.startsWith("HTTP/1.1 101")||!response.toLowerCase(Locale.ROOT).contains("sec-websocket-accept: "+accept.toLowerCase(Locale.ROOT)))throw new IOException("Browser control handshake failed");
            socket.setSoTimeout(0);Thread reader=new Thread(this::read,"localchat-browser-cdp");reader.setDaemon(true);reader.start();
        }
        CompletableFuture<Object> send(String method,Object params,String session)throws IOException{
            int id=sequence.incrementAndGet();var future=new CompletableFuture<Object>();calls.put(id,future);
            Map<String,Object> body=Json.obj("id",id,"method",method,"params",params);if(session!=null)body.put("sessionId",session);
            frame(1,Json.write(body).getBytes(StandardCharsets.UTF_8));return future;
        }
        Object call(String method,Object params,String session)throws IOException{
            try{return send(method,params,session).get(10,TimeUnit.SECONDS);}catch(Exception e){throw new IOException("Browser command failed: "+method,e);}
        }
        void evaluate(String code,String session)throws IOException{if(evaluation==null)evaluation=send("Runtime.evaluate",Json.obj("expression",code,"returnByValue",true,"userGesture",true),session);}
        Object takeEvaluation(){var f=evaluation;if(f==null||!f.isDone())return null;evaluation=null;try{return Json.get(f.join(),"result","value");}catch(CompletionException e){return "waiting-for-download-control";}}
        synchronized void frame(int opcode,byte[] bytes)throws IOException{
            out.write(0x80|opcode);int n=bytes.length;if(n<126)out.write(0x80|n);else if(n<=65535){out.write(0xfe);out.write(n>>>8);out.write(n);}else{out.write(0xff);for(int i=7;i>=0;i--)out.write((int)((long)n>>>(i*8)));}
            byte[] mask=new byte[4];new java.security.SecureRandom().nextBytes(mask);out.write(mask);for(int i=0;i<n;i++)out.write(bytes[i]^mask[i%4]);out.flush();
        }
        void read(){
            try{ByteArrayOutputStream message=new ByteArrayOutputStream();while(!closed){int a=in.read(),b=in.read();if(a<0||b<0)break;int op=a&15;long n=b&127;if(n==126)n=((long)in.read()<<8)|in.read();else if(n==127){n=0;for(int i=0;i<8;i++)n=(n<<8)|in.read();}if(n<0||n>8*1024*1024)throw new IOException("Browser frame limit");byte[] mask=(b&128)!=0?in.readNBytes(4):null;byte[] payload=in.readNBytes((int)n);if(payload.length!=n)throw new EOFException();if(mask!=null)for(int i=0;i<payload.length;i++)payload[i]^=mask[i%4];if(op==8)break;if(op==9){frame(10,payload);continue;}if(op!=0&&op!=1)continue;message.write(payload);if(message.size()>8*1024*1024)throw new IOException("Browser message limit");if((a&128)==0)continue;Object parsed=Json.parse(message.toString(StandardCharsets.UTF_8));message.reset();if(!(parsed instanceof Map<?,?> m))continue;Object id=m.get("id");if(id instanceof Number num){var f=calls.remove(num.intValue());if(f!=null){if(m.containsKey("error"))f.completeExceptionally(new IOException("Browser rejected command"));else f.complete(m.get("result"));}}else events.add(m);}}
            catch(Exception ignored){}finally{for(var f:calls.values())f.completeExceptionally(new IOException("Browser disconnected"));calls.clear();closed=true;}
        }
        public void close(){closed=true;try{socket.close();}catch(IOException ignored){}}
    }
}
