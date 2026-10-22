package localchat;
import java.awt.*;import javax.swing.*;
/** Pure presentation: only live AgentPanel evidence may start this pulse. */
class AgentActivityButton extends JButton {
 private int running,waiting,phase;private String state="draft";
 private final Timer timer=new Timer(90,e->{phase=(phase+1)%24;refreshColor();repaint();});
 AgentActivityButton(){super(new UiIcon("agents",18));addHierarchyListener(e->{if((e.getChangeFlags()&java.awt.event.HierarchyEvent.SHOWING_CHANGED)!=0)syncTimer();});translate();}
 void activity(int running,int waiting,String state){this.running=Math.max(0,running);this.waiting=Math.max(0,waiting);this.state=state;phase=0;translate();syncTimer();}
 int running(){return running;}int waiting(){return waiting;}boolean active(){return running+waiting>0;}boolean animating(){return timer.isRunning();}
 void translate(){String text=active()?I18n.text("Panou agenti: {0} in lucru, {1} in asteptare",running,waiting):I18n.text("Panou agenti")+" — "+I18n.text(switch(state){case "done"->"Gata.";case "failed"->"Eroare";case "partial"->"Rezultat partial";case "cancelled"->"Anulat";case "unconfirmed"->"Stare neconfirmata";default->"Niciun agent pornit in aceasta conversatie.";});setToolTipText(text);getAccessibleContext().setAccessibleName(text);refreshColor();repaint();}
 private void refreshColor(){Color base=StatusColors.color(active()?(running>0?"running":"pending"):state,StatusColors.dark(this));if(active()){double strength=.72+.28*(.5+.5*Math.sin(phase*Math.PI/12));Color bg=getBackground()==null?Color.GRAY:getBackground();base=new Color((int)(base.getRed()*strength+bg.getRed()*(1-strength)),(int)(base.getGreen()*strength+bg.getGreen()*(1-strength)),(int)(base.getBlue()*strength+bg.getBlue()*(1-strength)));}setForeground(base);}
 private void syncTimer(){if(active()&&isShowing())timer.start();else timer.stop();}
 void disposeActivity(){timer.stop();}
 @Override public void removeNotify(){timer.stop();super.removeNotify();}
 @Override protected void paintComponent(Graphics graphics){super.paintComponent(graphics);if(!active())return;Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setColor(getForeground());int x=getWidth()-12,y=getHeight()-12;g.fillOval(x,y,11,11);g.setColor(StatusColors.dark(this)?new Color(0x15202D):Color.WHITE);g.setFont(getFont().deriveFont(Font.BOLD,8f));String text=Integer.toString(running>0?running:waiting);FontMetrics metrics=g.getFontMetrics();g.drawString(text,x+(11-metrics.stringWidth(text))/2,y+8);g.dispose();}
}
