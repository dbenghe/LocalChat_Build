package localchat;
import javax.swing.*;
import java.awt.*;
/** Small vector icons follow the component foreground in either theme. */
final class UiIcon implements Icon {
    final String kind;final int size;
    UiIcon(String kind,int size){this.kind=kind;this.size=size;}
    public int getIconWidth(){return size;}public int getIconHeight(){return size;}
    public void paintIcon(Component c,Graphics g,int x,int y){Graphics2D a=(Graphics2D)g.create();a.translate(x,y);a.scale(size/20.0,size/20.0);a.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);a.setColor(c.isEnabled()?c.getForeground():UIManager.getColor("Label.disabledForeground"));a.setStroke(new BasicStroke(1.7f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
        switch(kind){case "bookmark","bookmark-filled"->{var path=new java.awt.geom.Path2D.Double();path.moveTo(5,2);path.lineTo(15,2);path.quadTo(16,2,16,3);path.lineTo(16,18);path.lineTo(10,14);path.lineTo(4,18);path.lineTo(4,3);path.quadTo(4,2,5,2);path.closePath();if(kind.equals("bookmark-filled"))a.fill(path);else a.draw(path);}case "chevron-down"->a.drawPolyline(new int[]{5,10,15},new int[]{7,13,7},3);case "chevron-right"->a.drawPolyline(new int[]{7,13,7},new int[]{5,10,15},3);case "timeline"->{for(int i=4;i<=16;i+=6){a.fillOval(2,i-1,3,3);a.drawLine(8,i,18,i);}}case "more"->{for(int i=4;i<=16;i+=6)a.fillOval(i-1,9,2,2);}
        case "sliders"->{a.drawLine(3,5,17,5);a.drawLine(3,10,17,10);a.drawLine(3,15,17,15);a.setStroke(new BasicStroke(3));a.drawLine(7,3,7,7);a.drawLine(13,8,13,12);a.drawLine(8,13,8,17);}
        case "panels"->{a.drawRoundRect(2,3,16,14,2,2);a.drawLine(11,3,11,17);}
        case "expand"->{a.drawPolyline(new int[]{2,2,7},new int[]{7,2,2},3);a.drawPolyline(new int[]{13,18,18},new int[]{2,2,7},3);a.drawPolyline(new int[]{2,2,7},new int[]{13,18,18},3);a.drawPolyline(new int[]{13,18,18},new int[]{18,18,13},3);}
        case "restore"->{a.drawRect(3,7,10,10);a.drawPolyline(new int[]{7,7,17,17,13},new int[]{7,3,3,13,13},5);}
        case "pdf"->{a.setColor(new Color(0xD94646));a.fillRoundRect(3,1,14,18,3,3);a.setColor(Color.WHITE);a.setFont(new Font("SansSerif",Font.BOLD,6));a.drawString("PDF",4,14);a.drawPolyline(new int[]{12,12,17},new int[]{1,6,6},3);}
        case "download"->{a.drawLine(10,2,10,13);a.drawLine(10,13,6,9);a.drawLine(10,13,14,9);a.drawPolyline(new int[]{3,3,17,17},new int[]{13,18,18,13},4);}
        case "external"->{a.drawPolyline(new int[]{10,17,17},new int[]{3,3,10},3);a.drawLine(17,3,8,12);a.drawPolyline(new int[]{6,3,3,14,14},new int[]{5,5,17,17,14},5);}
        case "copy"->{a.drawRoundRect(6,6,12,12,2,2);a.drawPolyline(new int[]{13,2,2},new int[]{2,2,13},3);}
        case "send"-> {a.drawLine(10,16,10,4);a.drawLine(10,4,5,9);a.drawLine(10,4,15,9);}
        case "attach"->{a.rotate(-Math.PI/4,10,10);a.drawRoundRect(5,2,10,16,6,6);a.drawRoundRect(8,3,4,11,3,3);}
        case "stop"->a.fillRoundRect(5,5,10,10,2,2);
        case "new"->{a.drawRoundRect(3,3,14,14,4,4);a.drawLine(6,10,14,10);a.drawLine(10,6,10,14);}
        case "folder"->{a.drawPolyline(new int[]{2,2,8,10,18,18,2},new int[]{7,4,4,7,7,16,16},7);a.drawLine(2,7,18,7);}}
        a.dispose();}
}
