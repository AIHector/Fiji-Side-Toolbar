package sidetoolbar;

import ij.IJ;
import ij.WindowManager;
import ij.plugin.frame.Recorder;

import java.awt.Frame;
import java.awt.Window;
import java.lang.reflect.Method;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;

/** Opens Fiji's native macro tools, including launchers saved with older window-title targets. */
final class MacroTools {
    private static final String EDITOR = "org.scijava.ui.swing.script.TextEditor";
    private static Frame editor;

    static boolean isRecorder(String target) {
        return "Recorder".equals(target) || "Record...".equals(target);
    }

    static boolean isEditor(String target) {
        return "Macro Editor".equals(target) || "Macro.ijm".equals(target);
    }

    static boolean matches(Item item, Window window) {
        if (isRecorder(item.target)) return window == Recorder.getInstance();
        if (isEditor(item.target)) return window == editor;
        return item.args.equals(ShelfView.titleOf(window));
    }

    static boolean open(String target) {
        Runnable action;
        if (isRecorder(target)) action = MacroTools::showRecorder;
        else if (isEditor(target)) action = MacroTools::showEditor;
        else return false;

        if (SwingUtilities.isEventDispatchThread()) action.run();
        else SwingUtilities.invokeLater(action);
        return true;
    }

    private static void showRecorder() {
        Recorder recorder = Recorder.getInstance();
        if (recorder == null) recorder = new Recorder();
        recorder.setVisible(true);
        WindowManager.toFront(recorder);
    }

    private static void showEditor() {
        try {
            if (editor == null || !editor.isDisplayable()) {
                // Side Toolbar also runs with ImageJ core alone. Resolve optional Fiji classes only here.
                ClassLoader loader = IJ.getClassLoader();
                Class<?> legacy = Class.forName("net.imagej.legacy.LegacyService", true, loader);
                Object service = legacy.getMethod("getInstance").invoke(null);
                if (service == null) throw new IllegalStateException("Fiji has no active scripting context");
                Object context = legacy.getMethod("context").invoke(service);
                Class<?> type = Class.forName(EDITOR, true, loader);
                Frame window = (Frame) type.getConstructor(Class.forName("org.scijava.Context", true, loader))
                        .newInstance(context);
                type.getMethod("createNewDocument", String.class, String.class)
                        .invoke(window, "Macro.ijm", "");
                Method getPane = type.getMethod("getEditorPane");
                JTextComponent pane = (JTextComponent) getPane.invoke(window);
                MacroLineSelection.install(pane);
                JTabbedPane tabs = (JTabbedPane) SwingUtilities.getAncestorOfClass(JTabbedPane.class, pane);
                if (tabs != null) tabs.addChangeListener(event -> SwingUtilities.invokeLater(() -> {
                    if (!window.isDisplayable() || tabs.getSelectedIndex() < 0) return;
                    try { MacroLineSelection.install((JTextComponent) getPane.invoke(window)); }
                    catch (ReflectiveOperationException exception) { IJ.handleException(exception); }
                }));
                editor = window;
            }
            editor.setVisible(true);
            WindowManager.toFront(editor);
        } catch (ReflectiveOperationException | LinkageError | IllegalStateException exception) {
            IJ.handleException(exception);
            IJ.showStatus("Side Toolbar: could not open Fiji's Macro Editor");
        }
    }
}
