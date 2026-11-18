package localchat;
import java.nio.file.*;
import java.util.*;
/** Real Python document helper behind guarded Java tool calls. Synthetic sources only. */
public final class DocumentMarkdownTest {
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        Path packageDir=Path.of(System.getProperty("markdown.package"));String python=System.getProperty("markdown.python");
        Path home=Files.createTempDirectory("document-reader-");Path project=Files.createDirectories(home.resolve("project"));Path engine=Files.createDirectories(home.resolve("pdfcontrol"));
        Files.copy(packageDir.resolve("mbs_document_markdown.py"),engine.resolve("mbs_document_markdown.py"));
        // Vendor is supplied by a fixture symlink-free wrapper to the real owned helper.
        Files.writeString(engine.resolve("mbs_document_markdown.py"),"import runpy,sys\nsys.argv[0]="+Json.write(packageDir.resolve("mbs_document_markdown.py").toString())+"\nrunpy.run_path(sys.argv[0],run_name='__main__')\n");
        String make="import pymupdf,sys\np=pymupdf.open()\nfor n in range(3):p.new_page().insert_text((40,60),'SYNTHETIC_PAGE_'+str(n+1)+' Lengte 150 Aantal 2')\np.save(sys.argv[1])\n";
        Path source=project.resolve("source.pdf");Process proc=new ProcessBuilder(python,"-I","-c",make,source.toString()).inheritIO().start();check(proc.waitFor()==0,"create real PDF");
        byte[] before=Files.readAllBytes(source);
        Tools tools=new Tools(new Tools.Context(project,home.resolve("skills"),home.resolve("rules.md"),home.resolve("settings.properties"),Map.of(),python,new Mcp(),PathMap.none(),true,EnumSet.allOf(Tools.Access.class),(a,b)->Tools.Decision.YES),List.of());
        var first=tools.execute(new ChatClient.ToolCall("one","read_file",Json.obj("path",source.toString(),"limit",1)));
        check(!first.isError,first.text);check(first.text.contains("SYNTHETIC_PAGE_1")&&first.text.contains("offset=2"),"PDF page pagination");
        var next=tools.execute(new ChatClient.ToolCall("two","read_file",Json.obj("path",source.toString(),"offset",2,"limit",2)));
        check(!next.isError,next.text);check(next.text.contains("SYNTHETIC_PAGE_2")&&next.text.contains("SYNTHETIC_PAGE_3"),"all remaining pages");
        check(next.text.contains("source_sha256")&&next.text.contains("api_requests"),"actual provenance");check(Arrays.equals(before,Files.readAllBytes(source)),"source unchanged");
        Path eml=project.resolve("mail.eml");Files.writeString(eml,"From: fixture@example.invalid\nSubject: Definitieve tekeningen\nContent-Type: text/plain; charset=utf-8\n\nhttps://we.tl/t-synthetic-only\n");
        var email=tools.execute(new ChatClient.ToolCall("email","read_file",Json.obj("path",eml.toString())));check(!email.isError,email.text);check(email.text.contains("https://we.tl/t-synthetic-only"),"email URL remains readable");
        System.out.println("PASS: "+checks+" real Java/Markdown reader checks");System.exit(0);
    }
}
