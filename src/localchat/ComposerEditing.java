package localchat;
import javax.swing.*;
import javax.swing.event.UndoableEditListener;
import javax.swing.text.Document;
import javax.swing.undo.*;
import java.awt.event.*;

/** Draft text edits only. App-driven replacement clears the local undo/redo boundary. */
final class ComposerEditing {
    private static final String KEY="localchat.composer.editing";
    static History install(JTextArea input){if(input.getClientProperty(KEY) instanceof History history)return history;var history=new History(input);input.putClientProperty(KEY,history);return history;}
    static void edit(JTextArea input,Runnable operation){History history=install(input);history.begin();try{operation.run();}finally{history.end();}}
    static void setText(JTextArea input,String text){input.setText(text);install(input).reset();}
    static final class History {
        final JTextArea input;final UndoManager manager=new UndoManager();
        final Action undo,redo;private Document document;private CompoundEdit compound;private int depth,changes;
        private final UndoableEditListener listener=e->{if(compound!=null){compound.addEdit(e.getEdit());changes++;}else manager.addEdit(e.getEdit());refresh();};
        History(JTextArea input){this.input=input;manager.setLimit(300);
            undo=new AbstractAction(){public void actionPerformed(ActionEvent e){if(input.isEditable()&&input.isEnabled()&&manager.canUndo())try{manager.undo();}catch(CannotUndoException ignored){}refresh();}};
            redo=new AbstractAction(){public void actionPerformed(ActionEvent e){if(input.isEditable()&&input.isEnabled()&&manager.canRedo())try{manager.redo();}catch(CannotRedoException ignored){}refresh();}};
            input.getActionMap().put("localchat.undo",undo);input.getActionMap().put("localchat.redo",redo);
            input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Z,InputEvent.CTRL_DOWN_MASK),"localchat.undo");input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Y,InputEvent.CTRL_DOWN_MASK),"localchat.redo");input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Z,InputEvent.CTRL_DOWN_MASK|InputEvent.SHIFT_DOWN_MASK),"localchat.redo");
            bind();input.addPropertyChangeListener("document",e->{bind();reset();});input.addPropertyChangeListener("editable",e->refresh());input.addPropertyChangeListener("enabled",e->refresh());refresh();
        }
        private void bind(){if(document!=null)document.removeUndoableEditListener(listener);document=input.getDocument();document.addUndoableEditListener(listener);}
        private void begin(){if(depth++==0){compound=new CompoundEdit();changes=0;}}
        private void end(){if(--depth==0){compound.end();if(changes>0)manager.addEdit(compound);compound=null;refresh();}}
        void reset(){manager.discardAllEdits();refresh();}
        void refresh(){if(undo==null||redo==null)return;undo.setEnabled(input.isEnabled()&&input.isEditable()&&manager.canUndo());redo.setEnabled(input.isEnabled()&&input.isEditable()&&manager.canRedo());}
    }
}
