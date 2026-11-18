package sidetoolbar;

import ij.plugin.frame.Recorder;

import java.awt.Component;
import java.awt.Frame;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;

public final class MacroToolsTest {
    public static void main(String[] arguments) {
        try {
            org.scijava.Context context = new org.scijava.Context();
            if (context.service(net.imagej.legacy.LegacyService.class) == null)
                throw new AssertionError("Fiji's legacy service was not initialized");
            SwingUtilities.invokeAndWait(MacroToolsTest::checkTools);
            System.out.println("Macro tools: saved launchers, native editor and row selection passed");
        } catch (Throwable failure) {
            failure.printStackTrace();
            Runtime.getRuntime().halt(1);
        }
        // Fiji installs shutdown hooks for its interactive services. This isolated test owns the JVM.
        System.out.flush();
        Runtime.getRuntime().halt(0);
    }

    private static void checkTools() {
        try {
            Recorder hidden = new Recorder(false);
            ShelfView.openTool(Shelf.launcher("Recorder", "Recorder", "Rec", ""));
            check(hidden == Recorder.getInstance() && hidden.isVisible(), "Saved button reveals the hidden Recorder");
            hidden.close();
            ShelfView.openTool(Shelf.launcher("Recorder", "Recorder", "Rec", ""));
            check(Recorder.getInstance() != null && Recorder.getInstance().isVisible(), "Recorder opens independently");
            Recorder.getInstance().close();

            Item launcher = Shelf.launcher("Macro.ijm", "Macro.ijm", "Macro", "");
            ShelfView.openTool(launcher);
            Frame editor = null;
            for (Frame candidate : Frame.getFrames())
                if (candidate.isVisible() && MacroTools.matches(launcher, candidate)) editor = candidate;
            check(editor != null, "Saved Macro.ijm target opens Fiji's Script Editor");
            ShelfView.openTool(launcher);
            int editors = 0;
            for (Window window : Window.getWindows())
                if (window.getClass() == editor.getClass() && window.isVisible()) editors++;
            check(editors == 1, "Second click reuses the open editor");
            JTextComponent pane = (JTextComponent) editor.getClass().getMethod("getEditorPane").invoke(editor);
            check(pane.getClientProperty(MacroLineSelection.class) != null, "Line-number gutter has selection controls");
            pane.setText("first\nsecond\nthird\nfourth");
            editor.validate();
            JScrollPane scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, pane);
            Component numbers = null;
            for (Component child : ((java.awt.Container)scroll.getRowHeader().getView()).getComponents())
                if ("LineNumberList".equals(child.getClass().getSimpleName())) numbers = child;
            check(numbers != null, "Native line-number gutter");
            click(numbers, pane, 0, 0);
            check("first\n".equals(pane.getSelectedText()), "Click selects one entire line");
            click(numbers, pane, 2, InputEvent.SHIFT_DOWN_MASK);
            check("first\nsecond\nthird\n".equals(pane.getSelectedText()), "Shift-click selects a contiguous range");
            click(numbers, pane, 0, 0);
            int modifier = ij.IJ.isMacOSX() ? InputEvent.META_DOWN_MASK : InputEvent.CTRL_DOWN_MASK;
            click(numbers, pane, 2, modifier);
            Object delete = pane.getInputMap().get(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0));
            pane.getActionMap().get(delete).actionPerformed(new ActionEvent(pane, ActionEvent.ACTION_PERFORMED, ""));
            check("second\nfourth".equals(pane.getText()), "Command-click and Delete only remove the selected rows");
            editor.dispose();
        } catch (Exception failure) { throw new AssertionError(failure); }
    }

    private static void click(Component numbers, JTextComponent pane, int line, int modifiers) throws Exception {
        int start = pane.getDocument().getDefaultRootElement().getElement(line).getStartOffset();
        java.awt.Rectangle bounds = pane.modelToView(start);
        int y = SwingUtilities.convertPoint(pane, 0, bounds.y + bounds.height / 2, numbers).y;
        numbers.dispatchEvent(new MouseEvent(numbers, MouseEvent.MOUSE_PRESSED, 1L,
                modifiers | InputEvent.BUTTON1_DOWN_MASK, 3, y, 1, false, MouseEvent.BUTTON1));
        numbers.dispatchEvent(new MouseEvent(numbers, MouseEvent.MOUSE_RELEASED, 2L,
                modifiers, 3, y, 1, false, MouseEvent.BUTTON1));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
