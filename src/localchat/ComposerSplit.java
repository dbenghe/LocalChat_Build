package localchat;
import java.awt.*;
import java.util.function.IntConsumer;
import javax.swing.*;

/** Keeps the editable viewport between a readable minimum and the answer viewport height. */
final class ComposerSplit extends JSplitPane {
    static final int MINIMUM=96,DEFAULT=144;
    final JScrollPane response,input;
    private final IntConsumer saved;
    private int desired=DEFAULT,topChrome,bottomChrome;
    private boolean layingOut,ready;
    ComposerSplit(Component upper,Component lower,JScrollPane response,JScrollPane input,IntConsumer saved){
        super(JSplitPane.VERTICAL_SPLIT,upper,lower);this.response=response;this.input=input;this.saved=saved;
        setBorder(null);setDividerSize(8);setResizeWeight(1);setContinuousLayout(true);upper.setMinimumSize(new Dimension(0,0));lower.setMinimumSize(new Dimension(0,0));translate();
    }
    @Override public void updateUI(){boolean prior=layingOut;layingOut=true;try{super.updateUI();}finally{layingOut=prior;}}
    void restore(String value){try{desired=Math.max(MINIMUM,Math.min(4096,Integer.parseInt(value)));}catch(RuntimeException ignored){desired=DEFAULT;}revalidate();}
    int preferredInputHeight(){return desired;}
    int available(){return Math.max(0,getHeight()-getInsets().top-getInsets().bottom-getDividerSize());}
    int maximumInputHeight(){return Math.max(0,(available()-topChrome-bottomChrome)/2);}
    private int appliedHeight(){int max=maximumInputHeight();return Math.max(Math.min(MINIMUM,max),Math.min(desired,max));}
    private int locationFor(int height){return Math.max(0,available()-bottomChrome-height)+getInsets().top;}
    void translate(){String text=I18n.text("Trage separatorul pentru a redimensiona mesajul. Maxim: aceeasi inaltime ca raspunsurile.");setToolTipText(text);getAccessibleContext().setAccessibleDescription(text);}
    @Override public void setDividerLocation(int location){
        if(!ready||layingOut||response==null||input==null){super.setDividerLocation(location);return;}
        desired=Math.max(Math.min(MINIMUM,maximumInputHeight()),Math.min(maximumInputHeight(),available()-bottomChrome-(location-getInsets().top)));
        super.setDividerLocation(locationFor(appliedHeight()));if(saved!=null)saved.accept(desired);
    }
    @Override public int getMinimumDividerLocation(){return ready?locationFor(maximumInputHeight()):super.getMinimumDividerLocation();}
    @Override public int getMaximumDividerLocation(){return ready?locationFor(Math.min(MINIMUM,maximumInputHeight())):super.getMaximumDividerLocation();}
    private static void layoutPath(Component component,Component target){if(component instanceof Container container){container.doLayout();for(Component child:container.getComponents())if(child==target||SwingUtilities.isDescendingFrom(target,child))layoutPath(child,target);}}
    private void measure(){layoutPath(getTopComponent(),response.getViewport());layoutPath(getBottomComponent(),input.getViewport());topChrome=Math.max(0,getTopComponent().getHeight()-response.getViewport().getHeight());bottomChrome=Math.max(0,getBottomComponent().getHeight()-input.getViewport().getHeight());}
    @Override public void doLayout(){
        if(layingOut){super.doLayout();return;}layingOut=true;
        try{super.doLayout();if(response==null||input==null||getHeight()<=getDividerSize())return;
            // A short settling pass includes report/attachment bars and scroll borders in the limit.
            for(int i=0;i<3;i++){measure();super.setDividerLocation(locationFor(appliedHeight()));super.doLayout();}
            measure();ready=true;
        }finally{layingOut=false;}
    }
}
