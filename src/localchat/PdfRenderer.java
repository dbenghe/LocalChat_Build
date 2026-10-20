package localchat;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/** Isolated local rasterization; no model, network, shell, or permanent preview files. */
final class PdfRenderer {
    record Info(int pages){}
    private volatile boolean cancelled;
    private final AtomicReference<Process> active=new AtomicReference<>();
    private static final String SCRIPT="""
import sys,json,math
import pymupdf as pdf
pdf.TOOLS.mupdf_display_errors(False)
pdf.TOOLS.mupdf_display_warnings(False)
try:
 with pdf.open(sys.argv[2]) as doc:
  if doc.needs_pass: raise ValueError('Encrypted PDF')
  if len(doc)<1: raise ValueError('Empty PDF')
  if sys.argv[1]=='info':
   print(json.dumps({'pages':len(doc)}))
  else:
   number=int(sys.argv[3])-1
   if not 0<=number<len(doc): raise ValueError('Invalid page')
   page=doc[number]; width=float(sys.argv[4]); zoom=float(sys.argv[5])
   scale=width/page.rect.width if zoom==0 else zoom/100*96/72
   if not all(math.isfinite(v) and v>0 for v in (page.rect.width,page.rect.height,page.rect.width*page.rect.height)): raise ValueError('Invalid page geometry')
   # Leave room for the rasterizer rounding each edge up to a whole pixel.
   scale=min(max(.000001,scale),4095/max(page.rect.width,page.rect.height),math.sqrt(9900000/(page.rect.width*page.rect.height)))
   pix=page.get_pixmap(matrix=pdf.Matrix(scale,scale),alpha=False)
   sys.stdout.buffer.write(pix.tobytes('png'))
except Exception:
 sys.exit(7)
""";
    Info info(Path file,String python)throws IOException{
        Object value=Json.parse(new String(run(python,"info",file.toString()),StandardCharsets.UTF_8));Object count=Json.get(value,"pages");
        if(!(count instanceof Number)||((Number)count).intValue()<1)throw new IOException("Invalid PDF metadata");return new Info(((Number)count).intValue());
    }
    BufferedImage render(Path file,String python,int page,int width,int zoom)throws IOException{
        BufferedImage image=ImageIO.read(new ByteArrayInputStream(run(python,"render",file.toString(),Integer.toString(page),Integer.toString(width),Integer.toString(zoom))));
        if(image==null)throw new IOException("Invalid preview image");return image;
    }
    private byte[] run(String python,String...args)throws IOException{
        if(cancelled||Thread.currentThread().isInterrupted())throw new InterruptedIOException("Cancelled");
        if(python==null||python.isBlank())throw new IOException("PDF runtime unavailable");
        var command=new java.util.ArrayList<String>();command.add(python);command.add("-I");command.add("-c");command.add(SCRIPT);command.addAll(java.util.List.of(args));
        Process process=new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();active.set(process);if(cancelled){process.destroyForcibly();throw new InterruptedIOException("Cancelled");}
        ScheduledExecutorService timeout=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"pdf-preview-timeout");t.setDaemon(true);return t;});
        var deadline=timeout.schedule(()->process.destroyForcibly(),30,TimeUnit.SECONDS);
        try(var input=process.getInputStream()){
            byte[] data=input.readNBytes(20*1024*1024+1);if(data.length>20*1024*1024)throw new IOException("Preview too large");
            if(!process.waitFor(2,TimeUnit.SECONDS)||process.exitValue()!=0)throw new IOException("PDF preview unavailable");return data;
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException("Cancelled");}
        finally{deadline.cancel(false);timeout.shutdownNow();active.compareAndSet(process,null);process.destroyForcibly();}
    }
    void cancel(){cancelled=true;Process p=active.getAndSet(null);if(p!=null)p.destroyForcibly();}
}
