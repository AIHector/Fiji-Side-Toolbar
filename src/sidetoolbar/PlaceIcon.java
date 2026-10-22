package sidetoolbar;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import javax.swing.Icon;

/** A little screen with the area a menu entry puts something in, filled: halves, thirds, bars, columns, displays. */
final class PlaceIcon implements Icon {

	static final int W = 22, H = 15;
	private static final Color SCREEN = new Color(250, 250, 250), FRAME = new Color(120, 120, 120);
	private static final Color FILL = new Color(45, 110, 200), BAR = new Color(150, 150, 150);

	private final double x, y, w, h;
	private final String extra; // null, "toolbar" (the side toolbar drawn as a thin grey band), "float", "restore", "next", "previous"

	PlaceIcon(double x, double y, double w, double h, String extra) {
		this.x = x; this.y = y; this.w = w; this.h = h; this.extra = extra;
	}

	public int getIconWidth() { return W; }

	public int getIconHeight() { return H; }

	public void paintIcon(Component c, Graphics g0, int px, int py) {
		Graphics2D g = (Graphics2D) g0.create();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		if ("next".equals(extra) || "previous".equals(extra)) {
			boolean next = "next".equals(extra); // the filled screen is where the window goes
			paintScreen(g, px, py + 3, 8, 9, next ? 0 : 1);
			paintScreen(g, px + 13, py + 3, 8, 9, next ? 1 : 0);
			g.setColor(FILL);
			int ay = py + 7, ax0 = px + 10, ax1 = px + 12;
			g.drawLine(ax0, ay, ax1, ay);
			if (next) { g.drawLine(ax1, ay, ax1 - 1, ay - 1); g.drawLine(ax1, ay, ax1 - 1, ay + 1); }
			else { g.drawLine(ax0, ay, ax0 + 1, ay - 1); g.drawLine(ax0, ay, ax0 + 1, ay + 1); }
			g.dispose();
			return;
		}
		RoundRectangle2D s = new RoundRectangle2D.Double(px + 0.5, py + 0.5, W - 1, H - 1, 3, 3);
		g.setColor(SCREEN);
		g.fill(s);
		double ix = px + 1.5, iy = py + 1.5, iw = W - 3, ih = H - 3;
		if ("toolbar".equals(extra)) { // the side toolbar, then the column beside it
			g.setColor(BAR);
			g.fill(new Rectangle2D.Double(ix, iy, iw * 0.1, ih));
		}
		Rectangle2D zone = new Rectangle2D.Double(ix + x * iw, iy + y * ih, w * iw, h * ih);
		if ("float".equals(extra)) {
			g.setColor(new Color(0, 0, 0, 50));
			g.fill(new Rectangle2D.Double(zone.getX() + 1, zone.getY() + 1, zone.getWidth(), zone.getHeight()));
		}
		g.setColor("restore".equals(extra) ? new Color(160, 190, 230) : FILL);
		g.fill(zone);
		if ("restore".equals(extra)) { // an arrow going back
			g.setColor(FILL);
			g.setStroke(new BasicStroke(1.3f));
			g.drawArc(px + 12, py + 4, 7, 7, 90, 270);
			g.drawLine(px + 15, py + 4, px + 13, py + 2);
			g.drawLine(px + 15, py + 4, px + 13, py + 6);
		}
		g.setColor(FRAME);
		g.setStroke(new BasicStroke(1f));
		g.draw(s);
		g.dispose();
	}

	private static void paintScreen(Graphics2D g, int x, int y, int w, int h, int filled) {
		g.setColor(filled == 1 ? FILL : SCREEN);
		g.fillRoundRect(x, y, w, h, 2, 2);
		g.setColor(FRAME);
		g.drawRoundRect(x, y, w, h, 2, 2);
	}

	/** The icon for a Window Layouts snap zone. */
	static PlaceIcon zone(String z) {
		double t = 1 / 3.0;
		switch (z) {
			case "left-half": return new PlaceIcon(0, 0, 0.5, 1, null);
			case "right-half": return new PlaceIcon(0.5, 0, 0.5, 1, null);
			case "top-half": return new PlaceIcon(0, 0, 1, 0.5, null);
			case "bottom-half": return new PlaceIcon(0, 0.5, 1, 0.5, null);
			case "top-left": return new PlaceIcon(0, 0, 0.5, 0.5, null);
			case "top-right": return new PlaceIcon(0.5, 0, 0.5, 0.5, null);
			case "bottom-left": return new PlaceIcon(0, 0.5, 0.5, 0.5, null);
			case "bottom-right": return new PlaceIcon(0.5, 0.5, 0.5, 0.5, null);
			case "left-third": return new PlaceIcon(0, 0, t, 1, null);
			case "center-third": return new PlaceIcon(t, 0, t, 1, null);
			case "right-third": return new PlaceIcon(2 * t, 0, t, 1, null);
			case "left-two-thirds": return new PlaceIcon(0, 0, 2 * t, 1, null);
			case "right-two-thirds": return new PlaceIcon(t, 0, 2 * t, 1, null);
			case "center": return new PlaceIcon(0.25, 0.2, 0.5, 0.6, null);
			case "maximize": return new PlaceIcon(0, 0, 1, 1, null);
			case "restore": return new PlaceIcon(0.08, 0.2, 0.35, 0.6, "restore");
			case "next-display": return new PlaceIcon(0, 0, 0, 0, "next");
			case "previous-display": return new PlaceIcon(0, 0, 0, 0, "previous");
			default: return null;
		}
	}

	/** The icon for a bar position: bottom, top, a column beside the toolbar, at the right edge, floating. */
	static PlaceIcon bar(String place) {
		switch (place) {
			case "bottom": return new PlaceIcon(0, 0.78, 1, 0.22, null);
			case "top": return new PlaceIcon(0, 0, 1, 0.22, null);
			case "left": return new PlaceIcon(0.1, 0, 0.15, 1, "toolbar");
			case "right": return new PlaceIcon(0.85, 0, 0.15, 1, null);
			default: return new PlaceIcon(0.25, 0.38, 0.5, 0.24, "float");
		}
	}
}
