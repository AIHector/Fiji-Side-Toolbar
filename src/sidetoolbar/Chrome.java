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
			boolean dockRight = Store.side().equals("right");
			int left = u.x, right = u.x + u.width, top = u.y, bottom = u.y + u.height;
			Rectangle db;
			if (dockRight) { right -= w; db = new Rectangle(right, u.y, w, u.height); }
			else { db = new Rectangle(left, u.y, w, u.height); left += w; }
			d.window.setBounds(db);
			if (!d.window.isVisible()) d.window.setVisible(true);
			java.util.List<Rectangle> reserved = new java.util.ArrayList<Rectangle>();
			reserved.add(new Rectangle(db));
			// shelves shown as columns: on the left (beside the toolbar when it is on the left) or on the right
			for (Shelf s : Shelf.all) {
				if (s.view == null || s.horizontal()) continue;
				int t = s.view.thickness();
				Rectangle r;
				if (s.place.equals("left")) { r = new Rectangle(left, u.y, t, u.height); left += t; }
				else { right -= t; r = new Rectangle(right, u.y, t, u.height); }
				show(s, r);
				reserved.add(r);
			}
			ij.pack();
			int stripH = classic ? 0 : ij.getHeight();
			boolean stripTop = !Store.status().equals("bottom");
			if (!classic && stripTop) { stripRect(ij, new Rectangle(left, top, right - left, stripH), true); top += stripH; }
			for (Shelf s : Shelf.all) {
				if (s.view == null || !s.place.equals("top")) continue;
				Rectangle r = new Rectangle(left, top, right - left, Shelf.size());
				top += r.height;
				show(s, r);
				reserved.add(r);
			}
			for (Shelf s : Shelf.all) {
				if (s.view == null || !s.place.equals("bottom")) continue;
				Rectangle r = new Rectangle(left, bottom - Shelf.size(), right - left, Shelf.size());
				bottom -= r.height;
				show(s, r);
				reserved.add(r);
			}
			if (!classic && !stripTop) stripRect(ij, new Rectangle(left, bottom - stripH, right - left, stripH), false);
			if (classic) ij.setLocation(dockRight ? db.x - ij.getWidth() : db.x + db.width, u.y);
			for (Shelf s : Shelf.all) if (s.view != null && s.place.equals("float")) s.view.placeFloating();
			System.getProperties().put(RESERVED, new Rectangle(db));
			System.getProperties().put(RESERVED_ALL, reserved);
			watch(ij);
		} finally {
			applying = false;
		}
		d.relayout();
	}

	static final String RESERVED_ALL = "sidetoolbar.reservedAll";
	/** Where the strip goes: its left edge and width, and its top (strip at the top) or bottom edge (at the bottom). */
	private static int stripLeft, stripWidth, stripTopY, stripBottomY;
	private static boolean stripAtTop = true;

	private static void show(Shelf s, Rectangle r) {
		if (!r.equals(s.view.window.getBounds())) s.view.window.setBounds(r);
		if (!s.view.window.isVisible()) s.view.window.setVisible(true);
	}

	private static void stripRect(ImageJ ij, Rectangle r, boolean atTop) {
		stripLeft = r.x;
		stripWidth = r.width;
		stripTopY = r.y;
		stripBottomY = r.y + r.height;
		stripAtTop = atTop;
		placeStrip(ij);
	}

	/** The ImageJ window as a strip, keeping the height ImageJ gives it (it grows to show search results). */
	static void placeStrip(ImageJ ij) {
		int h = ij.getHeight();
		Rectangle want = new Rectangle(stripLeft, stripAtTop ? stripTopY : stripBottomY - h, stripWidth, h);
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
					try { placeStrip(ij); }
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
			System.getProperties().remove(RESERVED_ALL);
		} finally {
			applying = false;
		}
	}
}
