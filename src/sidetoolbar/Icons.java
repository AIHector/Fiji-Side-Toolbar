package sidetoolbar;

import ij.IJ;
import ij.gui.Roi;
import ij.gui.Toolbar;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Paints button icons. ImageJ's built-in tool icons and its icon language ("C037T0b11G", used by macro tools)
 * are redrawn as vectors in ImageJ's own 30 x 30 button coordinates, then scaled, so they stay sharp at any size.
 *
 * Icon specifications: "" (automatic), "text:Ab" (letters or emoji), "ij:CODE" (ImageJ icon code),
 * "file:/path/to/image.png", "tool:NAME" (a built-in tool's picture, e.g. tool:zoom).
 */
final class Icons {

	private Icons() { }

	static final Color TOOL_COLOR = new Color(0, 25, 45);
	static final Color TRIANGLE = new Color(150, 0, 0);
	private static final Map<String, BufferedImage> images = new HashMap<String, BufferedImage>();

	/** Paints the icon of an item centred in the square (x, y, s, s). */
	static void paint(Graphics2D g, Item it, int x, int y, int s) {
		Graphics2D g2 = (Graphics2D) g.create();
		try {
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
			g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			String spec = it.icon;
			if (spec.length() == 0) spec = automatic(it);
			paintSpec(g2, spec, it, x, y, s);
		} catch (Throwable t) {
			// a broken icon must not break the toolbar: draw letters instead
			paintLetters(g, initials(it.title()), colorOf(it), x, y, s, true);
		} finally {
			g2.dispose();
		}
		if (it.kind == Item.Kind.GROUP) groupMark(g, x, y, s);
	}

	static String automatic(Item it) {
		switch (it.kind) {
			case TOOL: return "tool:" + it.target;
			case SLOT: {
				int slot = Bridge.slotIndex(it.target);
				String[] icons = Bridge.icons();
				if (slot >= 0 && slot < icons.length && icons[slot] != null) return "ij:" + icons[slot];
				return "";
			}
			case MORE: return "ij:C900T0d18>T6d18>";
			default: return "";
		}
	}

	static void paintSpec(Graphics2D g, String spec, Item it, int x, int y, int s) {
		if (spec.startsWith("tool:")) {
			virtual(g, x, y, s);
			builtin(g, spec.substring(5), it != null && it.kind == Item.Kind.TOOL);
		} else if (spec.startsWith("ij:")) {
			virtual(g, x, y, s);
			int slot = it != null && it.kind == Item.Kind.SLOT ? Bridge.slotIndex(it.target) : -1;
			code(g, spec.substring(3), 8, 8, Bridge.isMenuTool(slot));
		} else if (spec.startsWith("file:")) {
			BufferedImage img = image(spec.substring(5));
			if (img == null) throw new IllegalStateException("no image");
			int m = Math.max(2, s / 8), box = s - 2 * m;
			double f = Math.min((double) box / img.getWidth(), (double) box / img.getHeight());
			int w = (int) Math.round(img.getWidth() * f), h = (int) Math.round(img.getHeight() * f);
			g.drawImage(img, x + (s - w) / 2, y + (s - h) / 2, w, h, null);
		} else if (spec.startsWith("text:")) {
			paintLetters(g, spec.substring(5), it != null ? textColor(it) : TOOL_COLOR, x, y, s, false);
		} else {
			paintLetters(g, it != null ? initials(it.title()) : "?", it != null ? colorOf(it) : Color.gray, x, y, s, true);
		}
	}

	/** Maps ImageJ's 30 x 30 button space onto the square. */
	private static void virtual(Graphics2D g, int x, int y, int s) {
		g.translate(x, y);
		g.scale(s / 30.0, s / 30.0);
		g.setStroke(new BasicStroke(s <= 30 ? 1f : 1.15f));
	}

	// ---------------------------------------------------------------- letters

	/** Letters or emoji; "badge" draws them white on a rounded coloured square (the automatic icon). */
	static void paintLetters(Graphics2D g0, String text, Color c, int x, int y, int s, boolean badge) {
		Graphics2D g = (Graphics2D) g0.create();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		if (text.length() == 0) text = "?";
		int m = Math.max(3, s / 6);
		if (badge) {
			g.setColor(c);
			g.fillRoundRect(x + m, y + m, s - 2 * m, s - 2 * m, s / 4, s / 4);
			c = Color.white;
		}
		int n = text.codePointCount(0, text.length());
		float size = s * (n <= 1 ? 0.55f : n == 2 ? 0.42f : 0.32f) * (badge ? 0.9f : 1f);
		Font f = new Font("SansSerif", Font.BOLD, 10).deriveFont(size);
		g.setFont(f);
		FontMetrics fm = g.getFontMetrics();
		int w = fm.stringWidth(text);
		if (w > s - 2 * m) {
			g.setFont(f.deriveFont(size * (s - 2 * m) / (float) w));
			fm = g.getFontMetrics();
			w = fm.stringWidth(text);
		}
		g.setColor(c);
		g.drawString(text, x + (s - w) / 2f, y + (s - fm.getHeight()) / 2f + fm.getAscent());
		g.dispose();
	}

	static String initials(String title) {
		String t = title.replace("...", "").trim();
		StringBuilder b = new StringBuilder();
		for (String w : t.split("[\\s_\\-/()]+")) {
			if (w.length() == 0) continue;
			int cp = w.codePointAt(0);
			if (Character.isLetterOrDigit(cp)) b.appendCodePoint(b.length() == 0 ? Character.toUpperCase(cp) : cp);
			if (b.length() >= 2) break;
		}
		return b.length() > 0 ? b.toString() : t.length() > 0 ? t.substring(0, 1) : "?";
	}

	static Color parseColor(String s) {
		try {
			if (s != null && s.startsWith("#") && s.length() == 7) return new Color(Integer.parseInt(s.substring(1), 16));
		} catch (NumberFormatException ignored) { }
		return null;
	}

	static String hex(Color c) { return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue()); }

	/** A calm colour derived from the title, so automatic badges differ but stay stable. */
	static Color colorOf(Item it) {
		Color c = parseColor(it.color);
		if (c != null) return c;
		float hue = (it.title().hashCode() & 0xffff) / 65536f;
		return Color.getHSBColor(hue, 0.45f, 0.62f);
	}

	static Color textColor(Item it) {
		Color c = parseColor(it.color);
		return c != null ? c : TOOL_COLOR;
	}

	static void groupMark(Graphics2D g, int x, int y, int s) {
		int[] xs = {x + s - 3, x + s - 3, x + s - 8};
		int[] ys = {y + s - 8, y + s - 3, y + s - 3};
		g.setColor(TRIANGLE);
		g.fillPolygon(xs, ys, 3);
	}

	static BufferedImage image(String path) {
		if (images.containsKey(path)) return images.get(path);
		BufferedImage img = null;
		try {
			File f = new File(path);
			if (!f.isAbsolute()) f = new File(IJ.getDir("macros") + "toolsets/icons/", path);
			img = ImageIO.read(f);
		} catch (Throwable ignored) { }
		images.put(path, img);
		return img;
	}

	// ---------------------------------------------------------------- ImageJ icon language (Toolbar.drawIcon)

	private static String icon;
	private static int pc;

	private static int v() {
		if (pc >= icon.length()) return 0;
		char c = icon.charAt(pc++);
		if (c >= '0' && c <= '9') return c - '0';
		if (c >= 'a' && c <= 'n') return c - 'a' + 10;
		return 0;
	}

	/** Draws an ImageJ icon code with its origin at (x, y) in button space; menu tools get the red triangle. */
	static synchronized void code(Graphics2D g, String code, int x, int y, boolean menuTriangle) {
		g.setColor(TOOL_COLOR);
		if (code.trim().startsWith("icon:")) {
			BufferedImage bi = image(code.trim().substring(5));
			if (bi != null) g.drawImage(bi, x - 5, y - 5, null);
			return;
		}
		icon = code;
		pc = 0;
		Color fg = Toolbar.getForegroundColor();
		while (pc < icon.length()) {
			char command = icon.charAt(pc++);
			if (pc >= icon.length() && command != 'T') break;
			switch (command) {
				case 'B': x += v(); y += v(); break;
				case 'N': x -= v(); y -= v(); break;
				case 'R': g.drawRect(x + v(), y + v(), v(), v()); break;
				case 'F': g.fillRect(x + v(), y + v(), v(), v()); break;
				case 'O': g.drawOval(x + v(), y + v(), v(), v()); break;
				case 'V': case 'o': g.fillOval(x + v(), y + v(), v(), v()); break;
				case 'C': {
					int v1 = v(), v2 = v(), v3 = v();
					int r = Math.min(255, v1 * 16), gr = Math.min(255, v2 * 16), b = Math.min(255, v3 * 16);
					g.setColor(v1 == 1 && v2 == 2 && v3 == 3 ? fg : new Color(r, gr, b));
					break;
				}
				case 'L': g.drawLine(x + v(), y + v(), x + v(), y + v()); break;
				case 'D': g.fillRect(x + v(), y + v(), 1, 1); break;
				case 'P': {
					Polygon p = new Polygon();
					p.addPoint(x + v(), y + v());
					while (true) {
						int x2 = v(); if (x2 == 0) break;
						int y2 = v(); if (y2 == 0) break;
						p.addPoint(x + x2, y + y2);
					}
					g.drawPolyline(p.xpoints, p.ypoints, p.npoints);
					break;
				}
				case 'G': case 'H': {
					Polygon p = new Polygon();
					p.addPoint(x + v(), y + v());
					while (pc < icon.length()) {
						int x2 = v(), y2 = v();
						if (x2 == 0 && y2 == 0 && p.npoints > 2) break;
						p.addPoint(x + x2, y + y2);
					}
					if (command == 'G') g.drawPolygon(p); else g.fillPolygon(p);
					break;
				}
				case 'T': {
					int x2 = x + v() - 2, y2 = y + v();
					int size = v() * 10 + v() + 1;
					char c = pc < icon.length() ? icon.charAt(pc++) : 'e';
					g.setFont(new Font("SansSerif", Font.PLAIN, size));
					g.drawString(String.valueOf(c), x2, y2);
					break;
				}
				default: break;
			}
		}
		if (menuTriangle) triangle(g, x + 15, y + 16);
	}

	// ---------------------------------------------------------------- built-in tools (Toolbar.drawButton)

	private static Graphics2D bg;
	private static int xo, yo, px, py;

	private static void m(int x, int y) { px = xo + x; py = yo + y; }
	private static void d(int x, int y) { x += xo; y += yo; bg.drawLine(px, py, x, y); px = x; py = y; }
	private static void dot(int x, int y) { bg.fillRect(xo + x, yo + y, 1, 1); }
	private static void drawDot(int x, int y) { bg.fillRect(xo + x, yo + y, 2, 2); }

	private static void polyline(int... v) {
		Polygon p = new Polygon();
		for (int i = 0; i + 1 < v.length; i += 2) p.addPoint(v[i] + xo, v[i + 1] + yo);
		bg.drawPolyline(p.xpoints, p.ypoints, p.npoints);
	}

	private static void polygon(int... v) {
		Polygon p = new Polygon();
		for (int i = 0; i + 1 < v.length; i += 2) p.addPoint(v[i] + xo, v[i + 1] + yo);
		bg.fillPolygon(p);
	}

	private static void tri(int x, int y) { triangle(bg, xo + x, yo + y); }

	static void triangle(Graphics2D g, int x, int y) {
		Color c = g.getColor();
		g.setColor(TRIANGLE);
		g.fillPolygon(new int[] {x, x + 5, x + 2}, new int[] {y, y, y + 3}, 3);
		g.setColor(c);
	}

	private static void drawPoint(int x, int y) {
		bg.setColor(TOOL_COLOR);
		m(x - 3, y); d(x + 3, y);
		m(x, y - 3); d(x, y + 3);
		bg.setColor(Roi.getColor());
		dot(x, y); dot(x - 1, y - 1);
	}

	/**
	 * Draws a built-in tool as ImageJ does, reflecting its current state (rectangle type, line type, multi-point,
	 * colours). "live" adds the red sub-menu triangle; a picture used as an icon for something else omits it.
	 */
	static synchronized void builtin(Graphics2D g, String name, boolean live) {
		bg = g;
		int x = 7, y = 7;
		g.setColor(TOOL_COLOR);
		int rectType = Toolbar.getRectToolType(), ovalType = Toolbar.getOvalToolType();
		int lineType = Bridge.tb() != null ? Bridge.lineType() : Toolbar.LINE;
		boolean arrow = Bridge.tb() != null && Bridge.arrowMode();
		if (name.equals("rectangle")) {
			xo = x; yo = y;
			if (rectType == Toolbar.ROUNDED_RECT_ROI) g.drawRoundRect(x - 1, y + 1, 17, 13, 8, 8);
			else if (rectType == Toolbar.ROTATED_RECT_ROI) polyline(0, 10, 7, 0, 15, 6, 8, 16, 0, 10);
			else g.drawRect(x - 1, y + 1, 17, 13);
			if (live) tri(16, 16);
		} else if (name.equals("oval")) {
			xo = x; yo = y;
			if (ovalType == Toolbar.BRUSH_ROI) {
				yo = y - 1;
				polyline(6, 4, 8, 2, 12, 1, 15, 2, 16, 4, 15, 7, 12, 8, 9, 11, 9, 14, 6, 16, 2, 16, 0, 13, 1, 10, 4, 9, 6, 7, 6, 4);
			} else if (ovalType == Toolbar.ELLIPSE_ROI) {
				xo = x - 1; yo = y + 1;
				polyline(11, 0, 13, 0, 14, 1, 15, 1, 16, 2, 17, 3, 17, 7, 12, 12, 11, 12, 10, 13, 8, 13, 7, 14, 4, 14, 3, 13, 2, 13, 1, 12, 1, 11, 0, 10, 0, 9, 1, 8, 1, 7, 6, 2, 7, 2, 8, 1, 10, 1, 11, 0);
			} else g.drawOval(x, y + 1, 17, 13);
			xo = x; yo = y;
			if (live) tri(16, 16);
		} else if (name.equals("polygon")) {
			xo = x + 1; yo = y + 2;
			polyline(4, 0, 15, 0, 15, 1, 11, 5, 11, 6, 14, 11, 14, 12, 0, 12, 0, 4, 4, 0);
		} else if (name.equals("freehand")) {
			xo = x; yo = y + 3;
			polyline(2, 0, 5, 0, 7, 3, 10, 3, 12, 0, 15, 0, 17, 2, 17, 5, 16, 8, 13, 10, 11, 11, 6, 11, 4, 10, 1, 8, 0, 6, 0, 2, 2, 0);
		} else if (name.equals("line")) {
			xo = x; yo = y;
			if (lineType == Toolbar.POLYLINE) {
				polyline(15, 6, 11, 2, 1, 2, 1, 3, 7, 9, 2, 14);
				if (live) tri(14, 16);
			} else if (lineType == Toolbar.FREELINE) {
				polyline(16, 4, 14, 6, 12, 6, 9, 3, 8, 3, 6, 7, 2, 11, 1, 11);
				if (live) tri(14, 16);
			} else {
				if (arrow) {
					m(1, 14); d(14, 1); m(6, 5); d(14, 1); m(10, 9); d(14, 1); m(6, 5); d(10, 9);
				} else {
					xo = x - 1; yo = y - 1;
					m(1, 17); d(18, 0);
					drawDot(0, 16); drawDot(17, 0);
					xo = x; yo = y;
				}
				if (live) tri(16, 16);
			}
		} else if (name.equals("point")) {
			xo = x; yo = y;
			if (Toolbar.getMultiPointMode()) {
				drawPoint(1, 3); drawPoint(9, 0); drawPoint(15, 5);
				drawPoint(10, 11); drawPoint(2, 13);
			} else {
				m(1, 8); d(6, 8); d(6, 6); d(10, 6); d(10, 10); d(6, 10); d(6, 9);
				m(8, 1); d(8, 5); m(11, 8); d(15, 8); m(8, 11); d(8, 15);
				m(8, 8); d(8, 8);
				g.setColor(Roi.getColor());
				m(7, 7); d(9, 7);
				m(7, 8); d(9, 8);
				m(7, 9); d(9, 9);
			}
			if (live) tri(16, 16);
		} else if (name.equals("wand")) {
			xo = x + 2; yo = y + 1;
			dot(4, 0); m(2, 0); d(3, 1); d(4, 2); m(0, 0); d(1, 1);
			m(0, 2); d(1, 3); d(2, 4); dot(0, 4); m(3, 3); d(15, 15);
			g.setColor(Roi.getColor());
			m(1, 2); d(3, 2); m(2, 1); d(2, 3);
		} else if (name.equals("text")) {
			xo = x; yo = y;
			m(1, 16); d(9, 0); d(16, 16);
			m(0, 16); d(2, 16);
			m(15, 16); d(17, 16);
			m(4, 10); d(13, 10);
		} else if (name.equals("zoom")) {
			xo = x; yo = y;
			g.drawOval(x + 3, y, 13, 13);
			m(5, 12); d(-1, 18);
			if (live) tri(15, 17);
		} else if (name.equals("hand")) {
			xo = x; yo = y;
			polyline(5, 17, 5, 16, 0, 11, 0, 8, 1, 8, 5, 11, 5, 2, 8, 2, 8, 8, 8, 0, 11, 0, 11, 8, 11, 1, 14, 1, 14, 9, 14, 3, 17, 3, 17, 12, 16, 13, 16, 17);
		} else if (name.equals("dropper")) {
			g.setColor(Toolbar.getBackgroundColor());
			g.fillRect(x + 2, y + 3, 15, 16);
			g.drawRect(x, y + 2, 13, 13);
			g.setColor(Toolbar.getForegroundColor());
			g.fillRect(x, y + 2, 13, 13);
			xo = x + 3; yo = y - 4;
			g.setColor(TOOL_COLOR);
			m(12, 2); d(14, 2);
			m(11, 3); d(15, 3);
			m(11, 4); d(15, 4);
			m(8, 5); d(15, 5);
			m(9, 6); d(14, 6);
			polyline(10, 7, 12, 7, 12, 9);
			polyline(9, 6, 2, 13, 2, 15, 4, 15, 11, 8);
			g.setColor(new Color(228, 228, 228));
			polygon(9, 6, 2, 13, 2, 15, 4, 15, 11, 8);
			if (live) tri(12, 21);
		} else if (name.equals("angle")) {
			xo = x; yo = y + 3;
			m(0, 11); d(13, -1); m(0, 11); d(16, 11);
			m(10, 11); d(10, 8); m(9, 7); d(9, 6); dot(8, 5);
			drawDot(13, -2); drawDot(16, 10);
		} else {
			throw new IllegalArgumentException("unknown tool " + name);
		}
		bg = null;
	}

	/** Renders an icon into an image (previews in dialogs and the drag ghost). */
	static BufferedImage render(Item it, int s) {
		int scale = 2;
		BufferedImage img = new BufferedImage(s * scale, s * scale, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setTransform(AffineTransform.getScaleInstance(scale, scale));
		paint(g, it, 0, 0, s);
		g.dispose();
		return img;
	}
}
