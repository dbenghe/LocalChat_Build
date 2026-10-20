package localchat;
import javax.swing.*;
import java.awt.*;
/** Estimates use the same denominator as the sidebar, never billed usage. */
final class ContextRing extends JButton {
    long system,tools,conversation;int maximum;
    ContextRing(){setPreferredSize(new Dimension(90,26));setContentAreaFilled(false);setBorderPainted(false);setMargin(new Insets(1,2,1,2));setFont(getFont().deriveFont(11f));setHorizontalAlignment(SwingConstants.RIGHT);}
    void update(long s,long t,long c,int max){system=s;tools=t;conversation=c;maximum=max;setText(max>0?"~"+percentage(s+t+c,max)+"%":"~?");repaint();}
    static String percentage(long tokens,int maximum){return String.format(java.util.Locale.forLanguageTag(I18n.language()),"%.2f",tokens*100.0/maximum);}
    protected void paintComponent(Graphics g){super.paintComponent(g);Graphics2D a=(Graphics2D)g.create();a.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);a.setStroke(new BasicStroke(3));a.setColor(Color.GRAY);a.drawOval(3,5,15,15);
        if(maximum>0){int angle=90;long[] values={system,tools,conversation};Color[] colors=ContextUsagePopup.COLORS;
            for(int i=0;i<3;i++){int extent=(int)Math.min(360,Math.ceil(values[i]*360.0/maximum));a.setColor(colors[i]);a.drawArc(3,5,15,15,angle,-extent);angle-=extent;}}a.dispose();}
}
