package sidetoolbar;

import java.awt.Point;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Element;
import javax.swing.text.JTextComponent;

/** Whole-line selection in the native script editor's line-number gutter. */
final class MacroLineSelection extends MouseAdapter {
    private final JTextComponent pane;
    private final BitSet lines = new BitSet();
    private final List<Object> highlights = new ArrayList<>();
    private final int shortcut = Toolkit.getDefaultToolkit().getMenuShortcutKeyMask();
    private int anchor = -1;
    private boolean dragging;
    private boolean settingCaret;

    static void install(JTextComponent pane) {
        if (pane == null || pane.getClientProperty(MacroLineSelection.class) != null) return;
        JScrollPane scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, pane);
        if (scroll == null || scroll.getRowHeader() == null) return;
        JComponent gutter = (JComponent) scroll.getRowHeader().getView();
        if (gutter == null) return;
        for (java.awt.Component child : gutter.getComponents()) {
            if (!"LineNumberList".equals(child.getClass().getSimpleName())) continue;
            JComponent numbers = (JComponent) child;
            MacroLineSelection selection = new MacroLineSelection(pane);
            // The native gutter normally moves the caret to the beginning of the clicked line.
            if (child instanceof java.awt.event.MouseListener)
                numbers.removeMouseListener((java.awt.event.MouseListener) child);
            if (child instanceof java.awt.event.MouseMotionListener)
                numbers.removeMouseMotionListener((java.awt.event.MouseMotionListener) child);
            numbers.addMouseListener(selection);
            numbers.addMouseMotionListener(selection);
            numbers.setToolTipText("Select lines · Shift-click for a range · "
                    + (ij.IJ.isMacOSX() ? "Command" : "Ctrl") + "-click to toggle a line");
            pane.putClientProperty(MacroLineSelection.class, selection);
            return;
        }
    }

    private MacroLineSelection(JTextComponent pane) {
        this.pane = pane;
        pane.addCaretListener(event -> { if (!settingCaret) clear(); });
        pane.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) { clear(); }
        });
        pane.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent event) { clear(); }
            public void removeUpdate(DocumentEvent event) { clear(); }
            public void changedUpdate(DocumentEvent event) { clear(); }
        });
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_C, shortcut), "copyLines", false);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_X, shortcut), "cutLines", true);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "deleteLinesBackward", true);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "deleteLinesForward", true);
    }

    @Override public void mousePressed(MouseEvent event) {
        if (!SwingUtilities.isLeftMouseButton(event)) return;
        int line = lineAt(event);
        if (line < 0) return;
        boolean toggle = (event.getModifiers() & shortcut) != 0;
        dragging = !toggle;
        select(line, event.isShiftDown(), toggle);
        pane.requestFocusInWindow();
        event.consume();
    }

    @Override public void mouseDragged(MouseEvent event) {
        if (!dragging || (event.getModifiersEx() & InputEvent.BUTTON1_DOWN_MASK) == 0) return;
        int line = lineAt(event);
        if (line >= 0) select(line, true, false);
        event.consume();
    }

    @Override public void mouseReleased(MouseEvent event) { dragging = false; }

    private int lineAt(MouseEvent event) {
        Point point = SwingUtilities.convertPoint(event.getComponent(), event.getPoint(), pane);
        point.x = pane.getInsets().left;
        int offset = pane.viewToModel(point);
        return offset < 0 ? -1 : root().getElementIndex(offset);
    }

    private Element root() { return pane.getDocument().getDefaultRootElement(); }

    private int endOf(int line) {
        return Math.min(root().getElement(line).getEndOffset(), pane.getDocument().getLength());
    }

    private void select(int line, boolean extend, boolean toggle) {
        if (toggle) {
            lines.flip(line);
            anchor = line;
        } else {
            if (!extend || anchor < 0) anchor = line;
            lines.clear();
            lines.set(Math.min(anchor, line), Math.max(anchor, line) + 1);
        }

        settingCaret = true;
        try {
            pane.setCaretPosition(root().getElement(line).getStartOffset());
            render();
        } finally { settingCaret = false; }
    }

    private void render() {
        clearHighlights();
        int first = lines.nextSetBit(0);
        if (first < 0) return;
        int after = lines.nextClearBit(first);
        if (lines.nextSetBit(after) < 0) {
            pane.select(root().getElement(first).getStartOffset(), endOf(after - 1));
            return;
        }
        DefaultHighlighter.DefaultHighlightPainter painter =
                new DefaultHighlighter.DefaultHighlightPainter(pane.getSelectionColor());
        try {
            while (first >= 0) {
                after = lines.nextClearBit(first);
                highlights.add(pane.getHighlighter().addHighlight(
                        root().getElement(first).getStartOffset(), endOf(after - 1), painter));
                first = lines.nextSetBit(after);
            }
        } catch (BadLocationException exception) { clear(); }
    }

    private void clearHighlights() {
        for (Object highlight : highlights) pane.getHighlighter().removeHighlight(highlight);
        highlights.clear();
    }

    private void clear() {
        clearHighlights();
        lines.clear();
        anchor = -1;
    }

    private void bind(KeyStroke key, String name, boolean remove) {
        Object previous = pane.getInputMap().get(key);
        pane.getInputMap().put(key, name);
        pane.getActionMap().put(name, new AbstractAction() {
            public void actionPerformed(ActionEvent event) {
                if (lines.isEmpty() || (remove && !pane.isEditable())) {
                    Action action = previous == null ? null : pane.getActionMap().get(previous);
                    if (action != null) action.actionPerformed(event);
                    return;
                }
                try { apply(name.startsWith("copy") || name.startsWith("cut"), remove); }
                catch (BadLocationException exception) { Toolkit.getDefaultToolkit().beep(); }
            }
        });
    }

    private void apply(boolean copy, boolean remove) throws BadLocationException {
        List<int[]> ranges = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (int first = lines.nextSetBit(0); first >= 0; ) {
            int after = lines.nextClearBit(first);
            int start = root().getElement(first).getStartOffset(), end = endOf(after - 1);
            ranges.add(new int[]{start, end - start});
            if (copy) text.append(pane.getDocument().getText(start, end - start));
            first = lines.nextSetBit(after);
        }
        if (copy) Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text.toString()), null);
        if (!remove) return;
        for (int i = ranges.size() - 1; i >= 0; i--) {
            int[] range = ranges.get(i);
            pane.getDocument().remove(range[0], range[1]);
        }
        clear();
    }
}
