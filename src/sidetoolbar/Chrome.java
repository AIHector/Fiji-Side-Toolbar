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
	/** The bounds of the display everything was last placed on, and where the toolbar was put. */
	private static Rectangle lastScreen, lastDock;

	private Chrome() { }

	/**
	 * The display the ImageJ window is on, found from where the window actually is. Java's own answer
	 * (getGraphicsConfiguration) lags after a move and can stay wrong after displays sleep, wake or reconnect.
	 */
	static GraphicsConfiguration screen() {
		ImageJ ij = IJ.getInstance();
		if (ij != null) return screenAt(ij.getBounds());
		return GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
	}

	/**
	 * Where a moved strip belongs: the display under the pointer while the pointer is on the strip (the user let go
	 * of a drag by its title bar; the strip is wide, so its centre may still be on the old display), otherwise the
	 * display under the strip's centre (moved by a window-manager gesture or shortcut, or by macOS).
	 */
	static GraphicsConfiguration targetScreen(ImageJ ij) {
		Rectangle b = ij.getBounds();
		try {
			java.awt.PointerInfo pi = java.awt.MouseInfo.getPointerInfo();
			if (pi != null && b.contains(pi.getLocation())) return screenAt(new Rectangle(pi.getLocation().x, pi.getLocation().y, 1, 1));
		} catch (Exception ignored) { } // no pointer (headless) or not allowed
		return screenAt(b);
	}

	/** The display under the centre of a rectangle, else the one it overlaps most, else the main display. */
	static GraphicsConfiguration screenAt(Rectangle r) {
		GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
		java.awt.Point c = new java.awt.Point(r.x + r.width / 2, r.y + r.height / 2);
		GraphicsConfiguration best = null;
		long bestArea = 0;
		for (java.awt.GraphicsDevice d : ge.getScreenDevices()) {
			GraphicsConfiguration gc = d.getDefaultConfiguration();
			Rectangle b = gc.getBounds();
			if (b.contains(c)) return gc;
			Rectangle i = b.intersection(r);
			long area = i.isEmpty() ? 0 : (long) i.width * i.height;
			if (area > bestArea) { bestArea = area; best = gc; }
		}
		return best != null ? best : ge.getDefaultScreenDevice().getDefaultConfiguration();
	}

	/** The largest display wider than tall (else the largest display), by its size in points. */
	static GraphicsConfiguration largestLandscape() {
		GraphicsConfiguration best = null, bestAny = null;
		long area = 0, areaAny = 0;
		for (java.awt.GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
			GraphicsConfiguration gc = d.getDefaultConfiguration();
			Rectangle b = gc.getBounds();
			long a = (long) b.width * b.height;
			if (a > areaAny) { areaAny = a; bestAny = gc; }
			if (b.width >= b.height && a > area) { area = a; best = gc; }
		}
		return best != null ? best : bestAny;
	}

	/** At startup, with "Start on: the largest landscape display", moves the ImageJ window to that display. */
	static void moveToStartDisplay(ImageJ ij) {
		if (ij == null || !Store.startDisplay().equals("largest")) return;
		GraphicsConfiguration gc = largestLandscape();
		if (gc == null || gc.getBounds().equals(screen().getBounds())) return;
		Rectangle u = usable(gc);
		applying = true;
		try { ij.setLocation(u.x, u.y); }
		finally { applying = false; }
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
			GraphicsConfiguration gc = screen();
			Rectangle u = usable(gc);
			lastUsable = u;
			lastScreen = gc.getBounds();
			d.chooseColumns(u.height);
			int w = d.preferredWidth();
			boolean dockRight = Store.side().equals("right");
			int left = u.x, right = u.x + u.width, top = u.y, bottom = u.y + u.height;
			Rectangle db;
			if (dockRight) { right -= w; db = new Rectangle(right, u.y, w, u.height); }
			else { db = new Rectangle(left, u.y, w, u.height); left += w; }
			d.window.setBounds(db);
			lastDock = d.window.getBounds();
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
			int stripH = classic ? 0 : stripHeight(ij);
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

	/**
	 * The ImageJ window as a strip, keeping the height ImageJ gives it (it grows to show search results), but never
	 * taller than ImageJ's own layout needs: macOS tiling or a window manager may enlarge it while moving it.
	 */
	static void placeStrip(ImageJ ij) {
		int h = stripHeight(ij);
		Rectangle want = new Rectangle(stripLeft, stripAtTop ? stripTopY : stripBottomY - h, stripWidth, h);
		if (!want.equals(ij.getBounds())) ij.setBounds(want);
	}

	/** The strip's height: the window's height, capped at the height ImageJ's layout asks for. */
	static int stripHeight(ImageJ ij) {
		int h = ij.getHeight(), pref = ij.getPreferredSize().height;
		return pref > 0 ? Math.min(h, pref) : h;
	}

	/** Keeps the strip in place when ImageJ or Fiji re-packs its window (search results, a new tool...). */
	private static void watch(final ImageJ ij) {
		if (watcher != null) return;
		watcher = new ComponentAdapter() {
			public void componentResized(ComponentEvent e) { refit(); }
			// a move may be a drag in progress: decide once the strip has stopped moving
			public void componentMoved(ComponentEvent e) {
				if (applying || Dock.instance == null || Store.status().equals("classic")) return;
				moveSettle.restart();
			}

			private void refit() {
				if (applying || Dock.instance == null || Store.status().equals("classic")) return;
				SwingUtilities.invokeLater(() -> settle(ij));
			}
		};
		moveSettle = new javax.swing.Timer(MOVE_SETTLE_MS, e -> settle(ij));
		moveSettle.setRepeats(false);
		ij.addComponentListener(watcher);
	}

	/** How long the strip must stay still after a move before it is put back or the toolbar follows it. */
	static final int MOVE_SETTLE_MS = 450;
	private static javax.swing.Timer moveSettle;

	/**
	 * Dragged, or moved by a window-manager gesture or shortcut, or by macOS after displays reconnect, to another
	 * display: the toolbar, the strip and the shelves all go to that display. Otherwise the strip goes back in place.
	 */
	private static void settle(ImageJ ij) {
		if (applying || Dock.instance == null || Store.status().equals("classic")) return;
		if (lastScreen != null && !targetScreen(ij).getBounds().equals(lastScreen)) {
			apply();
			Side_Toolbar.rearrangeWindows();
			return;
		}
		applying = true;
		try { placeStrip(ij); }
		finally { applying = false; }
	}

	/**
	 * Called periodically: re-places everything when the screen's usable area changed (display, Dock, menu bar),
	 * or when something else moved the toolbar (macOS moves windows when displays sleep, wake or reconnect).
	 */
	static void checkScreen() {
		Dock d = Dock.instance;
		if (applying || d == null || (moveSettle != null && moveSettle.isRunning())) return; // a drag in progress
		Rectangle u = usable(screen());
		boolean dockMoved = lastDock != null && d.window.isVisible() && !d.window.getBounds().equals(lastDock);
		if (!dockMoved) dockFixes = 0;
		// a few attempts only, in case the system keeps the toolbar from its exact place
		if (!u.equals(lastUsable) || (dockMoved && dockFixes++ < 3)) apply();
	}

	private static int dockFixes;

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
