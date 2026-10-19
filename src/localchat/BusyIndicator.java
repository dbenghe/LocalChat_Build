package localchat;
import javax.swing.*;
import java.awt.*;
/** Paint-only activity indication: no API activity is inferred from token counts. */
final class BusyIndicator extends JLabel {
    boolean active;int angle;
    final Timer animation=new Timer(65,e->{angle=(angle+24)%360;repaint();});
    BusyIndicator(){setPreferredSize(new Dimension(24,24));setVisible(false);}
    void active(boolean value){active=value;setVisible(value);if(value && isDisplayable())animation.start();else animation.stop();repaint();}
    public void addNotify(){super.addNotify();if(active)animation.start();}
    public void removeNotify(){animation.stop();super.removeNotify();}
    protected void paintComponent(Graphics g){if(!active)return;Graphics2D a=(Graphics2D)g.create();a.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);a.setColor(getForeground());a.setStroke(new BasicStroke(2.2f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));a.drawArc(4,4,15,15,angle,260);a.dispose();}
}
