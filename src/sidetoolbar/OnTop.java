package sidetoolbar;

import java.awt.AWTEvent;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.WindowEvent;
import javax.swing.SwingUtilities;

/**
 * Keeps the side toolbar above Fiji's own windows while Fiji is the active application, and lets other
 * applications cover it when they are in front (a plain "always on top" window would float over them too).
 */
final class OnTop {

	private static AWTEventListener listener;
	private static boolean fijiActive;

	private OnTop() { }

	static void install() {
		if (listener != null) return;
		fijiActive = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow() != null;
		listener = new AWTEventListener() {
			public void eventDispatched(AWTEvent e) {
				if (!(e instanceof WindowEvent)) return;
				WindowEvent we = (WindowEvent) e;
				if (we.getID() == WindowEvent.WINDOW_GAINED_FOCUS) fijiActive = true;
				else if (we.getID() == WindowEvent.WINDOW_LOST_FOCUS && we.getOppositeWindow() == null) fijiActive = false;
				else return;
				SwingUtilities.invokeLater(OnTop::update);
			}
		};
		Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.WINDOW_FOCUS_EVENT_MASK);
	}

	static void update() {
		Dock d = Dock.instance;
		if (d == null) return;
		boolean want = Store.onTop() && fijiActive;
		if (d.window.isAlwaysOnTop() != want) d.window.setAlwaysOnTop(want);
	}
}
