package localchat;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;

/** Real Edge/Chrome UI against a local synthetic transfer, never an external account. */
public final class BrowserDownloadTest {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static byte[] zip()throws IOException{var b=new ByteArrayOutputStream();try(var z=new ZipOutputStream(b)){z.putNextEntry(new ZipEntry("drawing.pdf"));z.write("synthetic drawing content".getBytes(StandardCharsets.UTF_8));z.closeEntry();}return b.toByteArray();}
    static void reply(com.sun.net.httpserver.HttpExchange x,String type,byte[] data)throws IOException{x.getResponseHeaders().set("Content-Type",type);x.sendResponseHeaders(200,data.length);try(var o=x.getResponseBody()){o.write(data);}x.close();}
    static void page(HttpServer s,String route,String html){s.createContext(route,x->reply(x,"text/html",html.getBytes(StandardCharsets.UTF_8)));}
    static void fails(String url,Path directory,long max,java.util.function.BooleanSupplier stop,String expected)throws Exception{
        try{BrowserDownload.fetch(url,directory,max,stop,n->UrlDownload.target(directory,n,false));throw new AssertionError("Expected failure: "+expected);}
        catch(IOException e){check(e.getMessage().contains(expected),"Expected "+expected+", got "+e);}
    }
    public static void main(String[] args)throws Exception{
        if(args.length==2 && args[0].equals("--real")){
            // Private URL supplied through a file, never printed or passed to the browser command line.
            Path request=Path.of(args[1]);Object r=Json.parse(Files.readString(request));Path dest=Path.of(Json.str(r,"directory"));Files.createDirectories(dest);
            var result=BrowserDownload.fetch(Json.str(r,"url"),dest,UrlDownload.HARD_MAX,()->false,new BrowserDownload.Commit(){
                public Path target(String n)throws IOException{return UrlDownload.target(dest,n,false);}
                public boolean acceptTerms(){return Boolean.TRUE.equals(Json.get(r,"terms_approved_for_this_download"));}
            });
            System.out.println(Json.write(Json.obj("file",result.file().toString(),"size",result.size(),"sha256",result.sha256(),"verification",result.detail())));return;
        }
        System.setProperty("localchat.test","true");System.setProperty("localchat.browser.headless","true");System.setProperty("localchat.browser.timeout","12");
        Path root=Files.createTempDirectory("browser-download-test-");
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);byte[] archive=zip();AtomicInteger downloads=new AtomicInteger();
        server.createContext("/package.zip",x->{downloads.incrementAndGet();x.getResponseHeaders().set("Content-Disposition","attachment; filename=package.zip");reply(x,"application/zip",archive);});
        page(server,"/transfer","<html><body><button onclick=\"this.remove();document.getElementById('d').hidden=false\">Reject all</button><button id=d hidden onclick=\"location='/package.zip'\">Download all</button></body></html>");
        page(server,"/password","<html><body><input type=password></body></html>");
        page(server,"/terms","<html><body><p>To continue, please agree to our Terms of Service, and acknowledge our Privacy Policy.</p><button onclick=\"location='/transfer'\">I agree</button></body></html>");
        page(server,"/expired","<html><body>This transfer has expired</body></html>");
        page(server,"/blocked","<html><body>Verify you are human</body></html>");
        page(server,"/nothing","<html><body>No download control</body></html>");
        server.start();String base="http://127.0.0.1:"+server.getAddress().getPort();
        try{
            UrlDownload.allowPrivateForTests=false;
            fails(base+"/transfer",root,100000,()->false,"only WeTransfer");check(downloads.get()==0,"non-WeTransfer rejected before requests");
            UrlDownload.allowPrivateForTests=true;
            fails(base+"/terms",root,100000,()->false,"terms were not accepted");
            var termsOut=BrowserDownload.fetch(base+"/terms",root,100000,()->false,new BrowserDownload.Commit(){
                public Path target(String n)throws IOException{return UrlDownload.target(root,"terms-"+n,false);}
                public boolean acceptTerms(){return true;}
            });
            check(termsOut.size()==archive.length,"explicit synthetic terms approval allows download");
            check(FileGuard.confirmOnly(BrowserDownload.TERMS_TITLE),"terms card has no permanent allowance");
            var out=BrowserDownload.fetch(base+"/transfer",root,100000,()->false,n->UrlDownload.target(root,n,false));
            check(out.kind()==UrlDownload.Kind.DOWNLOADED&&out.size()==archive.length,"real browser completed file");
            check(Arrays.equals(Files.readAllBytes(out.file()),archive),"saved bytes equal fixture archive");
            check(out.detail().contains("CRC")&&out.sha256().equals(BrowserDownload.sha256(out.file(),()->false)),"ZIP and hash/readback verified");
            var second=BrowserDownload.fetch(base+"/transfer",root,100000,()->false,n->UrlDownload.target(root,n,false));
            check(second.file().getFileName().toString().equals("package (1).zip")&&Files.size(out.file())==archive.length,"existing source preserved, collision numbered");
            Path rejected=Files.createDirectory(root.resolve("rejected"));
            fails(base+"/transfer",rejected,10,()->false,"size limit");
            fails(base+"/password",rejected,100000,()->false,"password-field-visible");
            fails(base+"/expired",rejected,100000,()->false,"expiry-message-visible");
            fails(base+"/blocked",rejected,100000,()->false,"human-verification-visible");
            System.setProperty("localchat.browser.timeout","4");
            fails(base+"/nothing",rejected,100000,()->false,"timed out");
            System.setProperty("localchat.browser.timeout","12");
            AtomicBoolean stop=new AtomicBoolean();Thread cancel=new Thread(()->{try{Thread.sleep(1800);}catch(InterruptedException ignored){}stop.set(true);});cancel.start();
            fails(base+"/nothing",rejected,100000,stop::get,"Stopped");cancel.join();
            try(var list=Files.list(rejected)){check(list.findAny().isEmpty(),"failure and cancellation leave no project file");}
            Path bad=root.resolve("bad.zip");Files.writeString(bad,"not an archive");
            try{BrowserDownload.verify(bad,"bad.zip",100000,()->false);throw new AssertionError("invalid ZIP accepted");}catch(ZipException expected){checks++;}
            Path html=root.resolve("error.zip");Files.writeString(html,"<!doctype html><html>Error</html>");
            try{BrowserDownload.verify(html,"error.zip",100000,()->false);throw new AssertionError("HTML accepted");}catch(IOException expected){check(expected.getMessage().contains("HTML"),"HTML is not a download");}
            // Actual tool permission/read-only and project directory routing, without external requests.
            BrowserDownload.Runner previous=BrowserDownload.runner;
            try{
                AtomicInteger calls=new AtomicInteger();BrowserDownload.runner=(url,dir,max,s,commit)->{calls.incrementAndGet();Path p=commit.target("result.zip");Files.write(p,archive);return new UrlDownload.Outcome(UrlDownload.Kind.DOWNLOADED,"https://we.tl/[transfer]",p,archive.length,BrowserDownload.sha256(p,s),"application/zip","fixture verified");};
                Path work=Files.createDirectory(root.resolve("work")), project=Files.createDirectory(work.resolve("123456"));
                var tool=Release1289Test.tools(work,new AtomicInteger(),Tools.Decision.YES,true);
                var result=Release1289Test.call(tool,"url","https://we.tl/test","directory","123456/Bronnen");
                check(!result.isError&&Files.exists(project.resolve("Bronnen/result.zip"))&&result.text.contains("SHA-256"),"tool saves in requested project folder");
                check(Files.readString(work.resolve("hotfolder-generated.json")).contains("result.zip"),"browser result tracked per project");
                var denied=Release1289Test.call(Release1289Test.tools(work,new AtomicInteger(),Tools.Decision.NO,false),"url","https://we.tl/test");
                var readonly=Release1289Test.call(Release1289Test.tools(work,new AtomicInteger(),Tools.Decision.YES,true).readOnly(true),"url","https://we.tl/test");
                check(denied.isError&&readonly.isError&&calls.get()==1,"denial/read-only prevent browser execution");
                var outside=Release1289Test.call(tool,"url","https://we.tl/test","directory",root.toString());
                check(outside.isError&&calls.get()==1,"outside work folder rejected before browser");
                // Legal consent must never inherit auto-approval, and a late YES after Stop is ineffective.
                AtomicInteger consent=new AtomicInteger();
                BrowserDownload.runner=(url,dir,max,s,commit)->{check(!commit.acceptTerms(),"no explicit terms consent inherited");return new UrlDownload.Outcome(UrlDownload.Kind.FAILED,"https://we.tl/[transfer]",null,0,null,null,"Terms refused");};
                var noTerms=Release1289Test.call(Release1289Test.tools(work,consent,Tools.Decision.NO,true),"url","https://we.tl/test");
                check(noTerms.isError&&consent.get()==1,"allow-all still asks exactly once for terms");
                for(String lang:I18n.CODES)check(I18n.keys(lang).contains(BrowserDownload.TERMS_TITLE),"terms title translated: "+lang);
            }finally{BrowserDownload.runner=previous;}
        }finally{UrlDownload.allowPrivateForTests=false;server.stop(0);}
        System.out.println("PASS: "+checks+" browser download checks (real browser, synthetic public UI)");
    }
}
