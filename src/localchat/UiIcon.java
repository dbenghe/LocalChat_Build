package localchat;
import javax.swing.*;
import java.awt.*;
/** Small vector icons follow the component foreground in either theme. */
final class UiIcon implements Icon {
    final String kind;final int size;
    UiIcon(String kind,int size){this.kind=kind;this.size=size;}
    public int getIconWidth(){return size;}public int getIconHeight(){return size;}
    public void paintIcon(Component c,Graphics g,int x,int y){Graphics2D a=(Graphics2D)g.create();a.translate(x,y);a.scale(size/20.0,size/20.0);a.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);a.setColor(c.isEnabled()?c.getForeground():UIManager.getColor("Label.disabledForeground"));a.setStroke(new BasicStroke(1.7f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
        switch(kind){case "send"-> {a.drawLine(10,16,10,4);a.drawLine(10,4,5,9);a.drawLine(10,4,15,9);}
        case "attach"->{a.rotate(-Math.PI/4,10,10);a.drawRoundRect(5,2,10,16,6,6);a.drawRoundRect(8,3,4,11,3,3);}
        case "stop"->a.fillRoundRect(5,5,10,10,2,2);
        case "new"->{a.drawRoundRect(3,3,14,14,4,4);a.drawLine(6,10,14,10);a.drawLine(10,6,10,14);}
        case "folder"->{a.drawPolyline(new int[]{2,2,8,10,18,18,2},new int[]{7,4,4,7,7,16,16},7);a.drawLine(2,7,18,7);}}
        a.dispose();}
}
