package localchat;
import java.awt.*;import javax.swing.*;
/** One scrolling discussion: transcript followed by a compact inline assistance area. */
final class ConversationBody extends JPanel implements Scrollable {
    final JTextPane transcript;final ComposerAssist assistance;
    ConversationBody(JTextPane transcript,ComposerAssist assistance){super(new BorderLayout());this.transcript=transcript;this.assistance=assistance;assistance.onPresentation=this::revealIfFollowing;setOpaque(false);add(transcript);JPanel footer=new JPanel(null){
        public Dimension getPreferredSize(){return new Dimension(600,assistance.isVisible()?assistance.getPreferredSize().height+12:0);}
        public void doLayout(){assistance.setBounds(12,0,Math.max(40,Math.min(620,getWidth()-24)),assistance.getPreferredSize().height);}
    };footer.setOpaque(false);footer.add(assistance);add(footer,BorderLayout.SOUTH);}
    private void revealIfFollowing(){SwingUtilities.invokeLater(()->{
        if(!assistance.isVisible()||Boolean.FALSE.equals(transcript.getClientProperty("localchat.followTail")))return;
        revalidate();SwingUtilities.invokeLater(()->{
            if(assistance.isVisible()&&!Boolean.FALSE.equals(transcript.getClientProperty("localchat.followTail")))assistance.scrollRectToVisible(new Rectangle(0,0,assistance.getWidth(),assistance.getHeight()));
        });
    });}
    public Dimension getPreferredScrollableViewportSize(){return new Dimension(700,400);}
    public int getScrollableUnitIncrement(Rectangle r,int o,int d){return 24;}
    public int getScrollableBlockIncrement(Rectangle r,int o,int d){return Math.max(24,r.height-24);}
    public boolean getScrollableTracksViewportWidth(){return true;}
    public boolean getScrollableTracksViewportHeight(){return getParent()!=null&&getPreferredSize().height<getParent().getHeight();}
}
