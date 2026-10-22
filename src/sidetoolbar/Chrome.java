package sidetoolbar;

import ij.IJ;
import ij.ImageJ;
import ij.gui.Toolbar;

import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import javax.swing.SwingUtilities;

/**
 * Places the side toolbar and the ImageJ window. ImageJ's horizontal toolbar is hidden (not removed: it keeps
 * working underneath), so the ImageJ window shrinks to its status line, progress bar and Fiji's search bar, and
 * becomes a strip along the top (or bottom) of the screen, beside the side toolbar.
 *
 * The rectangle taken by the side toolbar is published as the system property "sidetoolbar.reserved"
 * (a java.awt.Rectangle in screen coordinates), so window-arranging plugins can keep clear of it.
 */
final class Chrome {

	static final String RESERVED = "sidetoolbar.reserved";
	private static boolean applying;
	private static ComponentAdapter watcher;
	private static Rectangle lastUsable;

	private Chrome() { }

	static GraphicsConfiguration screen() {
		ImageJ ij = IJ.getInstance();
		if (ij != null && ij.getGraphicsConfiguration() != null) return ij.getGraphicsConfiguration();
		return GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
	}

	static Rectangle usable(GraphicsConfiguration gc) {
		Rectangle b = gc.getBounds();
		Insets i = Toolkit.getDefaultToolkit().getScreenInsets(gc);
		return new Rectangle(b.x + i.left, b.y + i.top, b.width - i.left - i.right, b.height - i.top - i.bottom);
	}

	/** Places everything for the current settings. */
	static void apply() {
		Dock d = Dock.instance;
		ImageJ ij = IJ.getInstance();
		if (d == null || ij == null) return;
		applying = true;
		try {
			boolean classic = Store.status().equals("classic");
			// a window without a title bar cannot take the keyboard on macOS (Fiji's search bar would stop working)
			if (ij.isUndecorated()) setUndecorated(ij, false);
			Toolbar tb = Toolbar.getInstance();
			if (tb != null && tb.isVisible() != classic) tb.setVisible(classic);
			// ImageJ draws a button straight onto its toolbar when the tool changes, even while hidden:
			// with no size, those drawings land nowhere instead of on the status strip
			if (tb != null && !classic) tb.setBounds(0, 0, 0, 0);
			Rectangle u = usable(screen());
			lastUsable = u;
			d.chooseColumns(u.height);
			int w = d.preferredWidth();
			Rectangle db = Store.side().equals("right") ? new Rectangle(u.x + u.width - w, u.y, w, u.height)
				: new Rectangle(u.x, u.y, w, u.height);
			d.window.setBounds(db);
			if (!d.window.isVisible()) d.window.setVisible(true);
			System.getProperties().put(RESERVED, new Rectangle(db));
			ij.pack();
			placeStrip(ij, u, db, classic);
			watch(ij);
		} finally {
			applying = false;
		}
		d.relayout();
	}

	/** The ImageJ window: a strip beside the bar, or (classic) its usual form at the top, next to the bar. */
	static void placeStrip(ImageJ ij, Rectangle u, Rectangle db, boolean classic) {
		boolean right = Store.side().equals("right");
		int x = right ? u.x : db.x + db.width;
		if (classic) {
			ij.setLocation(right ? db.x - ij.getWidth() : x, u.y);
			return;
		}
		int w = u.width - db.width, h = ij.getHeight();
		int y = Store.status().equals("bottom") ? u.y + u.height - h : u.y;
		Rectangle want = new Rectangle(x, y, w, h);
		if (!want.equals(ij.getBounds())) ij.setBounds(want);
	}

	/** Keeps the strip in place when ImageJ or Fiji re-packs its window (search results, a new tool...). */
	private static void watch(final ImageJ ij) {
		if (watcher != null) return;
		watcher = new ComponentAdapter() {
			public void componentResized(ComponentEvent e) { refit(); }
			public void componentMoved(ComponentEvent e) { refit(); }

			private void refit() {
				if (applying || Dock.instance == null || Store.status().equals("classic")) return;
				SwingUtilities.invokeLater(() -> {
					Dock d = Dock.instance;
					if (applying || d == null) return;
					applying = true;
					try { placeStrip(ij, usable(screen()), d.window.getBounds(), false); }
					finally { applying = false; }
				});
			}
		};
		ij.addComponentListener(watcher);
	}

	/** Called periodically: re-places everything when the screen's usable area changed (display, Dock, menu bar). */
	static void checkScreen() {
		if (applying || Dock.instance == null) return;
		Rectangle u = usable(screen());
		if (!u.equals(lastUsable)) apply();
	}

	/** Changing a window's decorations requires releasing its native window; it is shown again at once. */
	static void setUndecorated(ImageJ ij, boolean undecorated) {
		Rectangle b = ij.getBounds();
		boolean visible = ij.isVisible();
		ij.dispose();
		ij.setUndecorated(undecorated);
		ij.setBounds(b);
		if (visible) ij.setVisible(true);
	}

	/** Brings back ImageJ's own toolbar and window and removes the side toolbar. */
	static void restoreClassic() {
		ImageJ ij = IJ.getInstance();
		applying = true;
		try {
			if (ij != null) {
				if (watcher != null) ij.removeComponentListener(watcher);
				watcher = null;
				if (ij.isUndecorated()) setUndecorated(ij, false);
				Toolbar tb = Toolbar.getInstance();
				if (tb != null) tb.setVisible(true);
				ij.pack();
				Rectangle u = usable(screen());
				ij.setLocation(u.x, u.y);
			}
			System.getProperties().remove(RESERVED);
		} finally {
			applying = false;
		}
	}
}
