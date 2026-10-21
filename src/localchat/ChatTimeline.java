package localchat;
import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.function.IntConsumer;

/** Local presentation/navigation only: one stable archive index per user question. */
final class ChatTimeline extends JComponent {
    record Entry(int message,Position position,String title,String excerpt){}
    final List<Entry> entries=new ArrayList<>();
    private final JTextPane transcript;
    private final Document original;
    private final IntConsumer toggle;
    private Set<Integer> bookmarks=Set.of();
    private int hovered=-1,selected=-1;
    private TimelinePreview preview;
    ChatTimeline(JTextPane transcript,Document original,IntConsumer toggle){
        this.transcript=transcript;this.original=original;this.toggle=toggle;setPreferredSize(new Dimension(27,100));setFocusable(true);
        getAccessibleContext().setAccessibleName(I18n.text("Navigare in discutie"));
        addMouseMotionListener(new MouseMotionAdapter(){public void mouseMoved(MouseEvent e){int index=nearest(e.getY());if(index!=hovered){hovered=index;repaint();showPreview(index);}}});
        addMouseListener(new MouseAdapter(){public void mouseEntered(MouseEvent e){hovered=nearest(e.getY());repaint();showPreview(hovered);}public void mouseClicked(MouseEvent e){int index=nearest(e.getY());if(index>=0){if(SwingUtilities.isRightMouseButton(e))showPreview(index);else jump(index);}}public void mouseExited(MouseEvent e){hovered=-1;repaint();}});
        addMouseWheelListener(e->{var pane=(JScrollPane)SwingUtilities.getAncestorOfClass(JScrollPane.class,transcript);if(pane!=null){var bar=pane.getVerticalScrollBar();bar.setValue(bar.getValue()+e.getUnitsToScroll()*bar.getUnitIncrement());}});
        addKeyListener(new KeyAdapter(){public void keyPressed(KeyEvent e){if(entries.isEmpty())return;switch(e.getKeyCode()){case KeyEvent.VK_UP -> jump(Math.max(0,selected-1));case KeyEvent.VK_DOWN -> jump(Math.min(entries.size()-1,selected+1));case KeyEvent.VK_ENTER -> jump(Math.max(0,selected));case KeyEvent.VK_SPACE,KeyEvent.VK_B -> toggle.accept(entries.get(Math.max(0,selected)).message());default->{return;}}requestFocusInWindow();e.consume();}});
    }
    public javax.accessibility.AccessibleContext getAccessibleContext(){if(accessibleContext==null)accessibleContext=new AccessibleJComponent(){};return accessibleContext;}
    void clear(){entries.clear();selected=hovered=-1;closePreview();repaint();}
    void removeTurn(int message){entries.removeIf(e->e.message()==message);selected=hovered=-1;closePreview();repaint();}
    void bookmarks(Set<Integer> values){bookmarks=values;repaint();}
    void add(int message,int offset,String question){try{String title=question.replaceAll("\\s+"," ").strip();if(title.length()>110)title=title.substring(0,110)+"...";entries.add(new Entry(message,original.createPosition(offset),title,compact(question)));repaint();}catch(BadLocationException ignored){}}
    private static String compact(String text){String value=text.replaceAll("(?m)^\\s*[#>]+\\s*","").replace("**","").replace("`","").replaceAll("\\s+"," ").strip();return value.length()>320?value.substring(0,317)+"...":value;}
    void answer(int question,String raw){String text=ReasoningDisplay.answer(raw,false).strip();if(text.isEmpty())return;for(int i=entries.size()-1;i>=0;i--)if(entries.get(i).message()==question){var entry=entries.get(i);entries.set(i,new Entry(entry.message(),entry.position(),entry.title(),compact(text)));return;}}
    int markerY(int index){return 12+(entries.size()<2?0:(int)((getHeight()-24L)*index/(entries.size()-1)));}
    int nearest(int y){if(entries.isEmpty())return -1;if(entries.size()==1)return 0;return Math.max(0,Math.min(entries.size()-1,(int)Math.round((y-12.0)*(entries.size()-1)/Math.max(1,getHeight()-24))));}
    void jump(int index){if(index<0||index>=entries.size())return;closePreview();selected=index;transcript.putClientProperty("localchat.followTail",false);try{int at=Math.min(transcript.getDocument().getLength(),entries.get(index).position().getOffset());transcript.setCaretPosition(at);var bounds=transcript.modelToView2D(at);if(bounds!=null){Rectangle rect=bounds.getBounds();rect.height=Math.max(rect.height,transcript.getVisibleRect().height-12);transcript.scrollRectToVisible(rect);}transcript.requestFocusInWindow();}catch(BadLocationException ignored){}repaint();}
    void menu(Component owner){var menu=new JPopupMenu();for(int i=0;i<entries.size();i++){final int index=i;Entry entry=entries.get(i);var item=new JMenuItem((i+1)+". "+entry.title());item.setIcon(new UiIcon(bookmarks.contains(entry.message())?"bookmark-filled":"bookmark",14));item.putClientProperty("html.disable",true);item.addActionListener(e->jump(index));menu.add(item);}if(entries.isEmpty()){var item=new JMenuItem(I18n.text("Nicio discutie"));item.setEnabled(false);menu.add(item);}var scroll=new JScrollPane(menuList());if(entries.size()>16){menu.removeAll();scroll.setBorder(null);scroll.setPreferredSize(new Dimension(380,380));menu.add(scroll);}menu.show(owner,0,owner.getHeight());}
    private JPanel menuList(){var panel=new JPanel();panel.setLayout(new BoxLayout(panel,BoxLayout.Y_AXIS));for(int i=0;i<entries.size();i++){final int index=i;var entry=entries.get(i);var row=new JButton((i+1)+". "+entry.title());row.setIcon(new UiIcon(bookmarks.contains(entry.message())?"bookmark-filled":"bookmark",14));row.putClientProperty("html.disable",true);row.setHorizontalAlignment(SwingConstants.LEFT);row.setMaximumSize(new Dimension(Integer.MAX_VALUE,30));row.addActionListener(e->{MenuSelectionManager.defaultManager().clearSelectedPath();jump(index);});panel.add(row);}return panel;}
    void closePreview(){if(preview!=null){preview.dismiss();preview=null;}}
    private void showPreview(int index){
        closePreview();if(index<0||!isShowing())return;var entry=entries.get(index);
        preview=new TimelinePreview(entry.title(),entry.excerpt(),bookmarks.contains(entry.message()),transcript.getBackground().getRed()<128,()->jump(index),()->{toggle.accept(entry.message());repaint();return bookmarks.contains(entry.message());});
        preview.show(this,getWidth(),Math.min(Math.max(0,markerY(index)-12),Math.max(0,getHeight()-110)));
    }
    protected void paintComponent(Graphics graphics){super.paintComponent(graphics);var g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);Color fg=UIManager.getColor("Label.foreground");if(fg==null)fg=Color.GRAY;Color accent=UIManager.getColor("Component.focusColor");if(accent==null)accent=new Color(0x588BD9);for(int i=0;i<entries.size();i++){boolean mark=bookmarks.contains(entries.get(i).message());g.setColor(mark||i==selected||i==hovered?accent:new Color(fg.getRed(),fg.getGreen(),fg.getBlue(),115));int width=i==hovered?23:mark?18:11;int y=markerY(i);g.fillRoundRect(3,y,width,i==hovered?3:2,2,2);if(mark)g.fillOval(21,y-2,4,4);}g.dispose();}
}
