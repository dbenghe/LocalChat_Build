package localchat;
import java.awt.*;import javax.swing.*;
/** New inline decisions are visible at the live tail, without stealing historical reading position. */
public final class InlineScrollTest {
 static int checks;static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 public static void main(String[] args)throws Exception{
  JFrame[] frame={null};JTextPane[] transcript={null};ComposerAssist[] assist={null};JScrollPane[] scroll={null};
  FeatureTest.edt(()->{com.formdev.flatlaf.FlatDarkLaf.setup();frame[0]=new JFrame();transcript[0]=new JTextPane();transcript[0].setText("Historical answer line\n".repeat(200));transcript[0].putClientProperty("localchat.followTail",true);assist[0]=new ComposerAssist(new ComposerAssist.GhostInput(),s->{});scroll[0]=new JScrollPane(new ConversationBody(transcript[0],assist[0]));frame[0].add(scroll[0]);frame[0].setSize(760,520);frame[0].addNotify();frame[0].validate();assist[0].present(ComposerAssistTest.questions(),"first");});
  for(int i=0;i<4;i++)FeatureTest.edt(()->{frame[0].validate();ContextUsagePopupTest.layout(frame[0].getRootPane());});
  FeatureTest.edt(()->{check(scroll[0].getViewport().getViewPosition().y>0,"new card reveals below a long transcript");Rectangle card=SwingUtilities.convertRectangle(assist[0].getParent(),assist[0].getBounds(),scroll[0].getViewport().getView());check(scroll[0].getViewport().getViewRect().intersects(card),"inline question is in the visible discussion viewport");transcript[0].putClientProperty("localchat.followTail",false);scroll[0].getViewport().setViewPosition(new Point(0,0));assist[0].present(ComposerAssistTest.questions(),"reading-old");});
  for(int i=0;i<4;i++)FeatureTest.edt(()->frame[0].validate());
  FeatureTest.edt(()->{check(scroll[0].getViewport().getViewPosition().y==0,"reading old messages is never interrupted by a new card");check(assist[0].isVisible(),"card remains available at the discussion tail");frame[0].dispose();});
  System.out.println("PASS: "+checks+" inline scroll and historical reading checks");System.exit(0);
 }
}
