package localchat;
import javax.swing.*;import java.awt.*;import java.util.List;
/** Local workflow labels are user-owned, never inferred completion claims. */
final class ProjectStatus {
 static final List<String> VALUES=List.of("draft","in_progress","partial","completed","restarted");
 static String valid(String value){return value!=null&&VALUES.contains(value)?value:"draft";}
 static String label(String value){return I18n.text(switch(valid(value)){case "in_progress"->"In lucru";case "partial"->"Partial";case "completed"->"Finalizat";case "restarted"->"Repornit";default->"Draft";});}
 static Icon icon(String value){return new Icon(){public int getIconWidth(){return 14;}public int getIconHeight(){return 14;}public void paintIcon(Component c,Graphics g,int x,int y){Graphics2D a=(Graphics2D)g.create();a.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);a.setColor(c.getForeground());a.setStroke(new BasicStroke(1.4f));a.drawOval(x+2,y+2,10,10);switch(valid(value)){case "completed"->a.drawPolyline(new int[]{x+4,x+6,x+10},new int[]{y+7,y+9,y+5},3);case "in_progress"->a.fillOval(x+5,y+5,4,4);case "partial"->a.fillArc(x+3,y+3,8,8,90,180);case "restarted"->a.drawArc(x+4,y+4,6,6,20,270);default->a.drawLine(x+5,y+8,x+9,y+5);}a.dispose();}};}
}
