package localchat;
import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.util.*;
import java.util.regex.*;

/** Presentation only. Source offsets, saved messages and API payloads never change. No HTML execution. */
final class MarkdownChat {
    static final String MARK="localchat.markdown", HIDE="localchat.md.hide", TAB="localchat.md.tab";
    static final class Kit extends StyledEditorKit {
        private final ViewFactory factory=e->{
            if(StyleConstants.getComponent(e.getAttributes())!=null)return new ComponentView(e);
            if(e.getName().equals(AbstractDocument.ContentElementName))return new LabelView(e){
                boolean hidden(){return Boolean.TRUE.equals(getAttributes().getAttribute(HIDE));}
                public float getPreferredSpan(int axis){return hidden()&&axis==View.X_AXIS?0:super.getPreferredSpan(axis);}
                public float getMinimumSpan(int axis){return hidden()&&axis==View.X_AXIS?0:super.getMinimumSpan(axis);}
                public float getTabbedSpan(float x,TabExpander tabs){
                    if(hidden())return 0;
                    if(Boolean.TRUE.equals(getAttributes().getAttribute(TAB)) && tabs!=null)return Math.max(0,tabs.nextTabStop(x,getStartOffset())-x);
                    return super.getTabbedSpan(x,tabs);
                }
                public void paint(Graphics g,Shape a){if(!hidden()&&!Boolean.TRUE.equals(getAttributes().getAttribute(TAB)))super.paint(g,a);}
            };
            return new StyledEditorKit().getViewFactory().create(e);
        };
        public ViewFactory getViewFactory(){return factory;}
    }
    static void apply(StyledDocument doc,AttributeSet base,Color background)throws BadLocationException{apply(doc,base,background,0);}
    static void apply(StyledDocument doc,AttributeSet base,Color background,int from)throws BadLocationException{apply(doc,base,background,from,doc);}
    static void apply(StyledDocument doc,AttributeSet base,Color background,int from,Document source)throws BadLocationException{
        int begin=Math.min(from,doc.getLength());
        while(begin>0 && Boolean.TRUE.equals(doc.getCharacterElement(begin-1).getAttributes().getAttribute(MARK)))begin=doc.getCharacterElement(begin-1).getStartOffset();
        for(int i=begin;i<doc.getLength();){
            var e=doc.getCharacterElement(i);int end=Math.min(e.getEndOffset(),doc.getLength());
            if(!Boolean.TRUE.equals(e.getAttributes().getAttribute(MARK))){i=end;continue;}
            int start=i;
            while(end<doc.getLength()&&Boolean.TRUE.equals(doc.getCharacterElement(end).getAttributes().getAttribute(MARK)))end=Math.min(doc.getCharacterElement(end).getEndOffset(),doc.getLength());
            Object language=doc.getCharacterElement(start).getAttributes().getAttribute(InternationalChat.DISPLAY);var clean=new SimpleAttributeSet(base);clean.addAttribute(MARK,true);if(language!=null)clean.addAttribute(InternationalChat.DISPLAY,language);doc.setCharacterAttributes(start,end-start,clean,true);
            var paragraph=new SimpleAttributeSet();StyleConstants.setSpaceAbove(paragraph,2);StyleConstants.setSpaceBelow(paragraph,4);StyleConstants.setLeftIndent(paragraph,0);StyleConstants.setTabSet(paragraph,new TabSet(new TabStop[0]));doc.setParagraphAttributes(start,end-start,paragraph,true);
            format(doc,start,source.getText(start,end-start),background);i=end;
        }
    }
    private static void put(StyledDocument d,int s,int n,Object kind,Object v){if(n<=0)return;var a=new SimpleAttributeSet();a.addAttribute(kind,v);d.setCharacterAttributes(s,n,a,false);}
    private static void hide(StyledDocument d,int s,int n){put(d,s,n,HIDE,true);}
    private static void format(StyledDocument d,int start,String text,Color bg){
        boolean fence=false;int offset=0;boolean[] code=new boolean[text.length()];
        String[] lines=text.split("\n",-1);
        for(int row=0;row<lines.length;row++){
            String line=lines[row];String trim=line.stripLeading();int lead=line.length()-trim.length();
            if(trim.startsWith("```")||trim.startsWith("~~~")){hide(d,start+offset,line.length());fence=!fence;Arrays.fill(code,offset,Math.min(code.length,offset+line.length()),true);}
            else if(fence){Arrays.fill(code,offset,Math.min(code.length,offset+line.length()),true);put(d,start+offset,line.length(),StyleConstants.FontFamily,"Monospaced");put(d,start+offset,line.length(),StyleConstants.Background,blend(bg));}
            else{
                Matcher heading=Pattern.compile("^(#{1,6})\\s+").matcher(trim);
                if(heading.find()){hide(d,start+offset+lead,heading.end());put(d,start+offset,line.length(),StyleConstants.Bold,true);put(d,start+offset,line.length(),StyleConstants.FontSize,Math.max(15,24-heading.group(1).length()*2));}
                if(trim.matches("[-*_]{3,}\\s*")){put(d,start+offset,line.length(),StyleConstants.Foreground,Color.GRAY);}
                if(trim.startsWith("> ")){hide(d,start+offset+lead,2);put(d,start+offset,line.length(),StyleConstants.Italic,true);}
                if(trim.startsWith("> \"" )||trim.startsWith("> \u201c"))put(d,start+offset,line.length(),StyleConstants.Foreground,InternationalChat.quoteColor(bg));
                boolean table=line.contains("|") && ((row+1<lines.length&&lines[row+1].matches("\\s*\\|?\\s*:?-{3,}.*")) || (row>0&&lines[row-1].contains("|")));
                if(table){
                    put(d,start+offset,line.length(),StyleConstants.FontFamily,"Monospaced");
                    if(trim.matches("[| :\\-]+"))hide(d,start+offset,line.length());
                    else{
                        int first=row;while(first>0&&lines[first-1].contains("|"))first--;int last=row;while(last+1<lines.length&&lines[last+1].contains("|"))last++;
                        int[] widths=new int[32];for(int r=first;r<=last;r++){String[] cells=lines[r].split("\\|",-1);for(int c=1;c<Math.min(cells.length,32);c++)widths[c]=Math.max(widths[c],Math.min(50,cells[c].length()+2));}
                        java.util.List<TabStop> stops=new ArrayList<>();float x=0;for(int c=1;c<32;c++){x+=Math.max(4,widths[c])*8;stops.add(new TabStop(x));}
                        var p=new SimpleAttributeSet();StyleConstants.setTabSet(p,new TabSet(stops.toArray(TabStop[]::new)));d.setParagraphAttributes(start+offset,line.length(),p,false);
                        boolean initial=true;for(int c=0;c<line.length();c++)if(line.charAt(c)=='|'){if(initial&&line.substring(0,c).isBlank())hide(d,start+offset+c,1);else put(d,start+offset+c,1,TAB,true);initial=false;}
                        if(row+1<lines.length&&lines[row+1].matches("\\s*\\|?\\s*:?-{3,}.*"))put(d,start+offset,line.length(),StyleConstants.Bold,true);
                    }
                }
            }
            offset+=line.length()+1;
        }
        inline(d,start,text,code,Pattern.compile("`([^`\\n]+)`"),StyleConstants.FontFamily,"Monospaced",1);
        inline(d,start,text,code,Pattern.compile("\\*\\*(.+?)\\*\\*|__(.+?)__"),StyleConstants.Bold,true,2);
        inline(d,start,text,code,Pattern.compile("(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)"),StyleConstants.Italic,true,1);
        inline(d,start,text,code,Pattern.compile("~~(.+?)~~"),StyleConstants.StrikeThrough,true,2);
        inline(d,start,text,code,Pattern.compile("(?<![\\w_])_([^_\\n]+)_(?![\\w_])"),StyleConstants.Italic,true,1);
        Matcher link=Pattern.compile("(?<!!)\\[([^]\\n]+)]\\(([^\\n]+?)\\)").matcher(text);
        while(link.find()){
            boolean skip=false;for(int n=link.start();n<link.end();n++)if(code[n]){skip=true;break;}if(skip)continue;
            String href=link.group(2).trim();
            if(!(href.startsWith("https://")||href.startsWith("http://")||href.matches("[A-Za-z]:[\\\\/].*")||href.startsWith("\\\\")))continue;
            hide(d,start+link.start(),1);hide(d,start+link.end(1),link.end()-link.end(1));
            put(d,start+link.start(1),link.group(1).length(),StyleConstants.Underline,true);put(d,start+link.start(1),link.group(1).length(),"markdown.href",href);
        }
    }
    private static void inline(StyledDocument d,int start,String text,boolean[] code,Pattern pattern,Object key,Object value,int marker){
        Matcher m=pattern.matcher(text);while(m.find()){
            boolean skip=false;for(int i=m.start();i<m.end();i++)if(code[i]){skip=true;break;}if(skip)continue;
            hide(d,start+m.start(),marker);hide(d,start+m.end()-marker,marker);
            var a=new SimpleAttributeSet();a.addAttribute(key,value);d.setCharacterAttributes(start+m.start()+marker,m.end()-m.start()-2*marker,a,false);
            if(key==StyleConstants.FontFamily)Arrays.fill(code,m.start(),m.end(),true);
        }
    }
    static String visibleText(StyledDocument document,int start,int end)throws BadLocationException{
        StringBuilder text=new StringBuilder();for(int i=start;i<end;){var element=document.getCharacterElement(i);int last=Math.min(end,element.getEndOffset());var a=element.getAttributes();if(StyleConstants.getComponent(a) instanceof PdfReportCard card)text.append(card.file.getFileName());else if(!Boolean.TRUE.equals(a.getAttribute(HIDE)))text.append(Boolean.TRUE.equals(a.getAttribute(TAB))?"\t":document.getText(i,last-i));i=last;}return text.toString();
    }
    private static Color blend(Color c){return c.getRed()<128?c.brighter():new Color(238,240,243);}
}
