package sidetoolbar;

import ij.CommandListener;
import ij.Executer;
import ij.IJ;
import ij.IJEventListener;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.dnd.DropTargetEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;

/**
 * The vertical toolbar. Buttons are painted, not Swing components, so they can flow like water: dragging one
 * opens a gap where it would land and the others slide aside; dragging it off the bar removes it (undoable).
 * The window never takes the keyboard focus, so clicking a tool leaves the image active, as in ImageJ.
 */
final class Dock extends JComponent implements MouseListener, MouseMotionListener, IJEventListener, CommandListener {

	static Dock instance;

	static final int PAD = 4, GAP = 2, SEP_H = 9, LABEL_W = 136, MAX_COLUMNS = 6;
	static final Color BG = new Color(238, 238, 238), EDGE = new Color(196, 196, 196);
	static final Color HOVER = new Color(222, 224, 228), PRESSED = new Color(204, 208, 214);
	static final Color CURRENT = new Color(206, 218, 234), CURRENT_EDGE = new Color(122, 150, 190);
	static final Color ACCENT = new Color(45, 110, 200), TEXT = new Color(30, 30, 30);
	private static final Object GAP_MARK = new Object();

	final JWindow window;
	private int cols = 1;
	private final LinkedHashMap<Object, Rectangle> target = new LinkedHashMap<Object, Rectangle>();
	private final Map<Object, double[]> shown = new HashMap<Object, double[]>();
	private Rectangle plusR = new Rectangle(), menuR = new Rectangle();
	private final Timer anim, poll, longPress, tipTimer;
	private int pollHash;

	// interaction state
	private Item hover, pressed;
	private Point pressPoint;
	private boolean pressedFooter, menuShowing;
	private Item dragItem;                // an entry being moved (from the bar)
	private List<Item> incoming;          // entries being dropped from outside (Add dialog, Finder)
	private boolean openHint;             // images being dragged over the bar: they will open, not become buttons
	private Point dragOffset = new Point();
	private int dropIndex = -1;           // index in visible() where the moved entry would land
	private Item dropGroup;               // a group the entry would go into
	private boolean dropOutside;          // released here, the entry is removed
	private Ghost ghost;
	private final Tip tip = new Tip();

	static final LinkedList<String> recent = new LinkedList<String>();

	Dock(java.awt.Frame owner) {
		window = new JWindow(owner);
		window.setFocusableWindowState(false);
		window.setAutoRequestFocus(false);
		window.setName("Side Toolbar");
		window.getContentPane().add(this);
		setOpaque(true);
		setFont(new Font("SansSerif", Font.PLAIN, 12));
		addMouseListener(this);
		addMouseMotionListener(this);
		new DropTarget(this, DnDConstants.ACTION_COPY_OR_MOVE, new Drops(), true);
		anim = new Timer(15, e -> step());
		poll = new Timer(700, e -> pollToolbar());
		longPress = new Timer(550, e -> longPressed());
		longPress.setRepeats(false);
		tipTimer = new Timer(650, e -> showTip());
		tipTimer.setRepeats(false);
		IJ.addEventListener(this);
		Executer.addCommandListener(this);
		poll.start();
		instance = this;
	}

	void dispose() {
		poll.stop();
		anim.stop();
		tip.hide();
		IJ.removeEventListener(this);
		Executer.removeCommandListener(this);
		window.dispose();
		if (instance == this) instance = null;
	}

	// ---------------------------------------------------------------- layout

	int buttonSize() { return Store.buttonSize(); }

	int cellWidth() { return Store.labels() ? buttonSize() + LABEL_W : buttonSize(); }

	int widthFor(int c) { return 2 * PAD + c * cellWidth() + (c - 1) * GAP; }

	int preferredWidth() { return widthFor(cols); }

	int footerHeight() { return Math.max(18, (int) Math.round(buttonSize() * 0.62)); }

	/** The + and ••• buttons: two compact rows, + above •••. */
	int footerRow() { return Math.max(14, (int) Math.round(buttonSize() * 0.5)); }

	/** Height kept free at the bottom for the + and ••• buttons. */
	int footerReserve(int c) { return 2 * footerRow() + GAP + PAD; }

	/** Entries shown now: toolbar tools that ImageJ has not loaded are kept but not shown. */
	List<Item> visible() {
		List<Item> l = new ArrayList<Item>();
		for (Item i : Store.items) if (i.kind != Item.Kind.SLOT || Bridge.slotIndex(i.target) >= 0) l.add(i);
		return l;
	}

	/** Chooses the number of columns so that everything fits the height (more columns only when needed). */
	void chooseColumns(int height) {
		int c = Store.columns() == 0 ? 1 : Store.columns();
		List<Object> entries = new ArrayList<Object>(visible());
		while (c < MAX_COLUMNS && place(entries, c, height, null) > height - footerReserve(c)) c++;
		cols = c;
	}

	/** Lays the entries out in c columns; fills "into" (if not null) and returns the height they need. */
	int place(List<Object> entries, int c, int height, Map<Object, Rectangle> into) {
		int s = buttonSize(), cw = cellWidth(), w = widthFor(c);
		int y = PAD, col = 0;
		List<Object> springs = new ArrayList<Object>();
		Map<Object, Rectangle> out = new LinkedHashMap<Object, Rectangle>();
		for (Object o : entries) {
			Item it = o instanceof Item ? (Item) o : null;
			if (it != null && it.kind == Item.Kind.SEPARATOR) {
				if (col > 0) { y += s + GAP; col = 0; }
				out.put(o, new Rectangle(PAD, y, w - 2 * PAD, SEP_H));
				y += SEP_H;
			} else if (it != null && it.kind == Item.Kind.SPRING) {
				if (col > 0) { y += s + GAP; col = 0; }
				out.put(o, new Rectangle(PAD, y, w - 2 * PAD, 8));
				springs.add(o);
				y += 8;
			} else {
				out.put(o, new Rectangle(PAD + col * (cw + GAP), y, cw, s));
				if (++col == c) { col = 0; y += s + GAP; }
			}
		}
		if (col > 0) y += s + GAP;
		int used = y;
		if (into != null) {
			int free = height - footerReserve(c) - used;
			if (free > 0 && !springs.isEmpty()) {
				// springs share the free height; everything after a spring moves down
				int shift = 0, k = 0;
				for (Map.Entry<Object, Rectangle> e : out.entrySet()) {
					Rectangle r = e.getValue();
					r.y += shift;
					if (springs.contains(e.getKey())) {
						int add = free / springs.size() + (k++ < free % springs.size() ? 1 : 0);
						r.height += add;
						shift += add;
					}
				}
			}
			into.putAll(out);
		}
		return used;
	}

	/** Recomputes the target positions; the animation moves every button there. */
	void relayout() {
		List<Object> entries = new ArrayList<Object>();
		List<Item> vis = visible();
		for (Item i : vis) if (i != dragItem) entries.add(i);
		if ((dragItem != null || incoming != null) && dropGroup == null && !dropOutside && dropIndex >= 0)
			entries.add(Math.min(dropIndex, entries.size()), GAP_MARK);
		target.clear();
		place(entries, cols, getHeight(), target);
		// + (add buttons) above ••• (the toolbar's menu), each a compact row
		int fh = footerRow();
		menuR = new Rectangle(PAD, getHeight() - fh - PAD, getWidth() - 2 * PAD, fh);
		plusR = new Rectangle(PAD, menuR.y - GAP - fh, getWidth() - 2 * PAD, fh);
		for (Map.Entry<Object, Rectangle> e : target.entrySet())
			if (!shown.containsKey(e.getKey())) shown.put(e.getKey(), new double[] {e.getValue().x, e.getValue().y});
		shown.keySet().retainAll(target.keySet());
		if (!anim.isRunning()) anim.start();
		repaint();
	}

	/** One animation frame: every button moves a third of the way to its target. */
	private void step() {
		boolean moving = false;
		for (Map.Entry<Object, Rectangle> e : target.entrySet()) {
			double[] p = shown.get(e.getKey());
			if (p == null) continue;
			Rectangle r = e.getValue();
			double dx = r.x - p[0], dy = r.y - p[1];
			if (Math.abs(dx) < 0.6 && Math.abs(dy) < 0.6) { p[0] = r.x; p[1] = r.y; continue; }
			p[0] += dx * 0.35;
			p[1] += dy * 0.35;
			moving = true;
		}
		repaint();
		if (!moving) anim.stop();
	}

	Rectangle shownRect(Object key) {
		Rectangle r = target.get(key);
		double[] p = shown.get(key);
		if (r == null) return null;
		return p == null ? r : new Rectangle((int) Math.round(p[0]), (int) Math.round(p[1]), r.width, r.height);
	}

	// ---------------------------------------------------------------- painting

	@Override
	protected void paintComponent(Graphics g0) {
		Graphics2D g = (Graphics2D) g0;
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setColor(BG);
		g.fillRect(0, 0, getWidth(), getHeight());
		g.setColor(EDGE);
		if (Store.side().equals("right")) g.drawLine(0, 0, 0, getHeight());
		else g.drawLine(getWidth() - 1, 0, getWidth() - 1, getHeight());
		boolean moving = dragItem != null || incoming != null;
		for (Object key : target.keySet()) {
			Rectangle r = shownRect(key);
			if (key == GAP_MARK) {
				g.setColor(ACCENT);
				g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[] {3f, 3f}, 0f));
				g.drawRoundRect(r.x + 1, r.y + 1, r.width - 3, r.height - 3, 8, 8);
				g.setStroke(new BasicStroke(1f));
				continue;
			}
			Item it = (Item) key;
			if (it.kind == Item.Kind.SEPARATOR) {
				g.setColor(new Color(186, 186, 186));
				g.drawLine(r.x + 3, r.y + r.height / 2, r.x + r.width - 4, r.y + r.height / 2);
				continue;
			}
			if (it.kind == Item.Kind.SPRING) {
				if (moving || it == hover) {
					g.setColor(new Color(170, 170, 170));
					g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[] {2f, 3f}, 0f));
					g.drawRoundRect(r.x + 2, r.y + 1, r.width - 5, r.height - 3, 6, 6);
					g.setStroke(new BasicStroke(1f));
				}
				continue;
			}
			paintButton(g, it, r);
		}
		paintFooterButton(g, plusR, "+", pressedFooter && pressPoint != null && plusR.contains(pressPoint));
		paintFooterButton(g, menuR, "\u2022\u2022\u2022", pressedFooter && pressPoint != null && menuR.contains(pressPoint));
		if (openHint) {
			g.setColor(new Color(45, 110, 200, 40));
			g.fillRect(0, 0, getWidth(), getHeight());
			g.setColor(ACCENT);
			g.setStroke(new BasicStroke(2f));
			g.drawRect(1, 1, getWidth() - 3, getHeight() - 3);
			g.setStroke(new BasicStroke(1f));
		}
	}

	private void paintButton(Graphics2D g, Item it, Rectangle r) {
		int s = buttonSize();
		Rectangle b = new Rectangle(r.x, r.y, Store.labels() ? r.width : s, s);
		Color fill = null, edge = null;
		if (it == dropGroup) { fill = new Color(214, 228, 248); edge = ACCENT; }
		else if (Bridge.isCurrent(it)) { fill = CURRENT; edge = CURRENT_EDGE; }
		else if (it == pressed && dragItem == null) fill = PRESSED;
		else if (it == hover) fill = HOVER;
		if (fill != null) {
			g.setColor(fill);
			g.fillRoundRect(b.x, b.y, b.width, b.height, 8, 8);
		}
		if (edge != null) {
			g.setColor(edge);
			g.drawRoundRect(b.x, b.y, b.width - 1, b.height - 1, 8, 8);
		}
		boolean ok = Bridge.available(it);
		java.awt.Composite c = g.getComposite();
		if (!ok) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.35f));
		Icons.paint(g, it, r.x, r.y, s);
		if (Store.labels()) {
			g.setColor(TEXT);
			g.setFont(getFont());
			FontMetrics fm = g.getFontMetrics();
			String t = ellipsize(it.shortTitle(), fm, r.width - s - 8);
			g.drawString(t, r.x + s + 4, r.y + (s - fm.getHeight()) / 2 + fm.getAscent());
		}
		g.setComposite(c);
	}

	static String ellipsize(String t, FontMetrics fm, int w) {
		if (fm.stringWidth(t) <= w) return t;
		while (t.length() > 1 && fm.stringWidth(t + "…") > w) t = t.substring(0, t.length() - 1);
		return t + "…";
	}

	private void paintFooterButton(Graphics2D g, Rectangle r, String text, boolean down) {
		if (down || (hover == null && r.contains(mouse))) {
			g.setColor(down ? PRESSED : HOVER);
			g.fillRoundRect(r.x, r.y, r.width, r.height, 8, 8);
		}
		g.setColor(new Color(90, 90, 90));
		Font f = getFont().deriveFont(Font.BOLD, text.equals("+") ? r.height * 0.8f : Math.min(r.height * 0.42f, r.width * 0.3f));
		g.setFont(f);
		FontMetrics fm = g.getFontMetrics();
		g.drawString(text, r.x + (r.width - fm.stringWidth(text)) / 2, r.y + (r.height - fm.getHeight()) / 2 + fm.getAscent());
	}

	// ---------------------------------------------------------------- hit testing

	private Point mouse = new Point(-100, -100);

	Item itemAt(Point p) {
		for (Object key : target.keySet()) {
			if (!(key instanceof Item) || key == dragItem) continue;
			Rectangle r = shownRect(key);
			if (r != null && r.contains(p)) return (Item) key;
		}
		return null;
	}

	/** Visible index where an entry dropped at p lands: the gap position whose button would be closest to p. */
	int dropIndexAt(Point p) {
		List<Object> base = new ArrayList<Object>();
		for (Item i : visible()) if (i != dragItem) base.add(i);
		int best = base.size(), bestD = Integer.MAX_VALUE;
		for (int i = 0; i <= base.size(); i++) {
			List<Object> l = new ArrayList<Object>(base);
			l.add(i, GAP_MARK);
			Map<Object, Rectangle> m = new LinkedHashMap<Object, Rectangle>();
			place(l, cols, getHeight(), m);
			Rectangle r = m.get(GAP_MARK);
			int dx = r.x + r.width / 2 - p.x, dy = r.y + r.height / 2 - p.y;
			int d = dx * dx + 4 * dy * dy;
			if (d < bestD) { bestD = d; best = i; }
		}
		return best;
	}

	/** A group whose middle is under p: dropping there puts the entry inside it. */
	Item groupAt(Point p) {
		Item it = itemAt(p);
		if (it == null || it.kind != Item.Kind.GROUP || it == dragItem) return null;
		Rectangle r = shownRect(it);
		int s = buttonSize();
		Rectangle inner = new Rectangle(r.x + s / 4, r.y + s / 4, s / 2, s / 2);
		return inner.contains(p) ? it : null;
	}

	/** Converts an index in visible() to an index in the full list (hidden tools keep their place). */
	static int modelIndex(List<Item> vis, int i) {
		if (i >= vis.size()) {
			if (vis.isEmpty()) return Store.items.size();
			return Store.items.indexOf(vis.get(vis.size() - 1)) + 1;
		}
		return Store.items.indexOf(vis.get(i));
	}

	// ---------------------------------------------------------------- mouse

	public void mousePressed(MouseEvent e) {
		tip.hide();
		tipTimer.stop();
		pressPoint = e.getPoint();
		if (e.isPopupTrigger()) { contextMenu(e); return; }
		if (!SwingUtilities.isLeftMouseButton(e)) return;
		if (plusR.contains(e.getPoint()) || menuR.contains(e.getPoint())) { pressedFooter = true; repaint(); return; }
		pressed = itemAt(e.getPoint());
		if (pressed == null) return;
		// tools are selected on press, as in ImageJ; menus and commands act on release, so a drag can move them
		if (pressed.isTool() && !actsOnClick(pressed)) Bridge.press(pressed, retarget(e, pressed));
		if (Bridge.hasSubtypes(pressed)) longPress.restart();
		repaint();
	}

	public void mouseDragged(MouseEvent e) {
		mouse = e.getPoint();
		if (pressed == null || pressPoint == null) return;
		if (dragItem == null) {
			if (pressPoint.distance(e.getPoint()) < 5) return;
			longPress.stop();
			startDrag(pressed, e);
		}
		updateDrag(e);
	}

	public void mouseReleased(MouseEvent e) {
		longPress.stop();
		if (dragItem != null) { finishDrag(); return; }
		if (e.isPopupTrigger()) { contextMenu(e); return; }
		Item it = pressed;
		pressed = null;
		boolean footer = pressedFooter;
		pressedFooter = false;
		repaint();
		if (menuShowing) { menuShowing = false; return; }
		if (footer) {
			if (plusR.contains(e.getPoint())) addMenu(e, -1);
			else if (menuR.contains(e.getPoint())) mainMenu(e);
			return;
		}
		if (it == null || it != itemAt(e.getPoint()) || !SwingUtilities.isLeftMouseButton(e)) return;
		click(it, e);
	}

	public void mouseMoved(MouseEvent e) {
		mouse = e.getPoint();
		Item h = itemAt(e.getPoint());
		if (h != hover) {
			hover = h;
			tip.hide();
			if (h != null) {
				if (h.isButton()) {
					if (h.isTool() || h.kind == Item.Kind.MORE) Bridge.showStatus(h);
					else IJ.showStatus(h.title() + detail(h));
					tipTimer.restart();
				}
			}
		}
		repaint();
	}

	public void mouseExited(MouseEvent e) {
		mouse = new Point(-100, -100);
		hover = null;
		tipTimer.stop();
		tip.hide();
		repaint();
	}

	public void mouseEntered(MouseEvent e) { }

	public void mouseClicked(MouseEvent e) { }

	/** A copy of the event placed on the button, for tools that open their own menu at the click. */
	private MouseEvent retarget(MouseEvent e, Item it) {
		return new MouseEvent(this, e.getID(), e.getWhen(), e.getModifiers(), e.getX(), e.getY(), e.getClickCount(), e.isPopupTrigger(), e.getButton());
	}

	/** Menu tools and action tools act when the button is released, so that they can be dragged without acting. */
	static boolean actsOnClick(Item it) {
		if (it.kind != Item.Kind.SLOT) return false;
		int slot = Bridge.slotIndex(it.target);
		return Bridge.isMenuTool(slot) || Bridge.isActionTool(slot);
	}

	private void longPressed() {
		if (pressed == null || dragItem != null) return;
		JPopupMenu m = new JPopupMenu();
		if (Bridge.addToolEntries(m, pressed, null)) {
			Rectangle r = shownRect(pressed);
			showMenu(m, r.x + r.width, r.y);
		}
	}

	private void click(Item it, MouseEvent e) {
		switch (it.kind) {
			case TOOL:
				break; // handled on press
			case SLOT: {
				int slot = Bridge.slotIndex(it.target);
				if (Bridge.isMenuTool(slot)) {
					JPopupMenu m = new JPopupMenu();
					Bridge.addMirroredMenu(m, slot);
					Rectangle r = shownRect(it);
					showMenu(m, r.x + r.width, r.y);
				} else if (Bridge.isActionTool(slot)) Bridge.select(slot, retarget(e, it));
				break;
			}
			case MORE: {
				JPopupMenu m = new JPopupMenu();
				Bridge.addMoreEntries(m);
				Rectangle r = shownRect(it);
				showMenu(m, r.x + r.width, r.y);
				break;
			}
			case GROUP: {
				JPopupMenu m = new JPopupMenu();
				fillGroupMenu(m, it, e);
				m.addSeparator();
				m.add(Bridge.item("Edit Group...", () -> Editor.edit(it)));
				Rectangle r = shownRect(it);
				showMenu(m, r.x + r.width, r.y);
				break;
			}
			case COMMAND: case MACRO: case SCRIPT:
				Runner.run(it);
				break;
			default:
		}
	}

	private void fillGroupMenu(javax.swing.JComponent m, Item group, MouseEvent e) {
		for (final Item c : group.children) {
			if (c.kind == Item.Kind.SEPARATOR) {
				if (m instanceof JPopupMenu) ((JPopupMenu) m).addSeparator();
				else ((JMenu) m).addSeparator();
				continue;
			}
			if (c.kind == Item.Kind.SPRING) continue;
			JMenuItem mi;
			if (c.kind == Item.Kind.GROUP) {
				JMenu sub = new JMenu(c.title());
				fillGroupMenu(sub, c, e);
				mi = sub;
			} else {
				mi = Bridge.item(c.title(), () -> {
					int id = Bridge.toolId(c);
					if (c.isTool()) { if (id >= 0) Bridge.select(id, retarget(e, c)); }
					else if (c.kind == Item.Kind.MORE) {
						JPopupMenu mm = new JPopupMenu();
						Bridge.addMoreEntries(mm);
						showMenu(mm, getWidth(), 0);
					} else Runner.run(c);
				});
				mi.setEnabled(Bridge.available(c));
			}
			mi.setIcon(new ImageIcon(Icons.render(c, 20).getScaledInstance(20, 20, java.awt.Image.SCALE_SMOOTH)));
			m.add(mi);
		}
	}

	static String detail(Item it) {
		switch (it.kind) {
			case COMMAND: return it.args.length() > 0 ? "  [" + it.target + ": " + it.args + "]" : (it.label.length() > 0 ? "  [" + it.target + "]" : "");
			case SCRIPT: return "  [" + it.target + "]";
			case GROUP: return "  (" + it.children.size() + " entries)";
			default: return "";
		}
	}

	void showMenu(JPopupMenu m, int x, int y) {
		menuShowing = false;
		if (m.getComponentCount() == 0) return;
		// beside the bar, on the screen side
		m.show(this, Store.side().equals("right") ? -m.getPreferredSize().width : x, y);
	}

	// ---------------------------------------------------------------- dragging inside the bar

	private String beforeDrag;

	private void startDrag(Item it, MouseEvent e) {
		dragItem = it;
		beforeDrag = Store.serialize();
		Rectangle r = shownRect(it);
		dragOffset = new Point(e.getX() - r.x, e.getY() - r.y);
		ghost = new Ghost(it, buttonSize());
		tip.hide();
		setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
	}

	private void updateDrag(MouseEvent e) {
		Point p = e.getPoint();
		Point scr = new Point(p);
		SwingUtilities.convertPointToScreen(scr, this);
		ghost.setLocation(scr.x - dragOffset.x, scr.y - dragOffset.y);
		Rectangle zone = new Rectangle(-24, -24, getWidth() + 48, getHeight() + 48);
		boolean out = !zone.contains(p);
		Item g = out ? null : groupAt(p);
		int idx = out || g != null ? -1 : dropIndexAt(p);
		ghost.setRemoving(out);
		if (out != dropOutside || g != dropGroup || idx != dropIndex) {
			dropOutside = out;
			dropGroup = g;
			dropIndex = idx;
			relayout();
		}
	}

	private void finishDrag() {
		Item it = dragItem;
		List<Item> vis = visible();
		vis.remove(it);
		String what;
		if (dropOutside) {
			what = "Remove " + it.title();
			Store.remove(it);
			toastUndo("Removed \u201c" + it.title() + "\u201d");
		} else if (dropGroup != null) {
			what = "Move " + it.title() + " into " + dropGroup.title();
			Store.items.remove(it);
			dropGroup.children.add(it);
			IJ.showStatus("Side Toolbar: moved into " + dropGroup.title());
		} else {
			what = "Move " + it.title();
			if (dropIndex >= 0) {
				int to = modelIndex(vis, dropIndex);
				int from = Store.items.indexOf(it);
				Store.items.remove(it);
				if (from >= 0 && from < to) to--;
				Store.items.add(Math.max(0, Math.min(to, Store.items.size())), it);
			}
			// the moved button starts where it was dropped and settles into place
			Point gp = ghost.getLocation();
			SwingUtilities.convertPointFromScreen(gp, this);
			shown.put(it, new double[] {gp.x, gp.y});
		}
		if (!Store.serialize().equals(beforeDrag)) {
			Store.pushUndo(what, beforeDrag);
			Store.save();
		}
		endDrag();
		changed();
	}

	private void endDrag() {
		if (ghost != null) ghost.dispose();
		ghost = null;
		dragItem = null;
		incoming = null;
		dropGroup = null;
		dropIndex = -1;
		dropOutside = false;
		pressed = null;
		setCursor(Cursor.getDefaultCursor());
	}

	// ---------------------------------------------------------------- drops from outside

	static final String FLAVOR_PREFIX = "sidetoolbar-items\n";

	private class Drops extends DropTargetAdapter {
		public void dragEnter(DropTargetDragEvent e) { dragOver(e); }

		public void dragOver(DropTargetDragEvent e) {
			if (dragItem != null) { e.rejectDrag(); return; }
			if (!e.isDataFlavorSupported(DataFlavor.stringFlavor) && !e.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
				e.rejectDrag();
				return;
			}
			e.acceptDrag(DnDConstants.ACTION_COPY);
			boolean open = onlyFilesToOpen(e);
			if (open != openHint) { openHint = open; repaint(); }
			if (open) return; // images open in Fiji: no gap
			if (incoming == null) incoming = new ArrayList<Item>();
			Point p = e.getLocation();
			Item g = groupAt(p);
			int idx = g != null ? -1 : dropIndexAt(p);
			if (g != dropGroup || idx != dropIndex) {
				dropGroup = g;
				dropIndex = idx;
				relayout();
			}
		}

		public void dragExit(DropTargetEvent e) {
			openHint = false;
			incoming = null;
			dropGroup = null;
			dropIndex = -1;
			relayout();
		}

		@SuppressWarnings("unchecked")
		public void drop(DropTargetDropEvent e) {
			List<Item> add = new ArrayList<Item>();
			final List<Object> open = new ArrayList<Object>(); // files and URLs to open in Fiji
			try {
				e.acceptDrop(DnDConstants.ACTION_COPY);
				Transferable t = e.getTransferable();
				if (t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
					for (File f : (List<File>) t.getTransferData(DataFlavor.javaFileListFlavor)) {
						if (isScript(f)) add.add(Editor.itemForFile(f));
						else open.add(f);
					}
				} else {
					String s = (String) t.getTransferData(DataFlavor.stringFlavor);
					File f = new File(s.trim());
					if (s.startsWith(FLAVOR_PREFIX)) add.addAll(Item.parse(s.substring(FLAVOR_PREFIX.length())));
					else if (f.isFile() && isScript(f)) add.add(Editor.itemForFile(f));
					else if (f.exists()) open.add(f);
					else if (s.trim().matches("(?i)https?:/.*")) open.add(s.trim());
				}
				e.dropComplete(!add.isEmpty() || !open.isEmpty());
			} catch (Exception ex) {
				e.dropComplete(false);
			}
			if (!add.isEmpty()) insert(add, dropGroup, dropIndex);
			if (!open.isEmpty()) openInFiji(open);
			openHint = false;
			incoming = null;
			dropGroup = null;
			dropIndex = -1;
			relayout();
		}
	}

	static final String[] SCRIPT_EXTENSIONS = {".ijm", ".py", ".groovy", ".js", ".bsh", ".rb", ".clj", ".scala", ".r"};

	/** Macro and script files become buttons; everything else dropped on the bar opens in Fiji. */
	static boolean isScript(File f) {
		if (!f.isFile()) return false;
		String n = f.getName().toLowerCase(java.util.Locale.US);
		for (String x : SCRIPT_EXTENSIONS) if (n.endsWith(x)) return true;
		return false;
	}

	/** True while the files dragged over the bar are all ones that will open (images, folders), not become buttons. */
	@SuppressWarnings("unchecked")
	static boolean onlyFilesToOpen(DropTargetDragEvent e) {
		try {
			if (!e.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return false;
			List<File> files = (List<File>) e.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
			if (files == null || files.isEmpty()) return false;
			for (File f : files) if (isScript(f)) return false;
			return true;
		} catch (Throwable t) {
			return false; // contents not readable before the drop: show the gap
		}
	}

	/** Opens files, folders and URLs as ImageJ does when they are dropped on its own window. */
	static void openInFiji(final List<Object> what) {
		new Thread(() -> {
			ij.plugin.DragAndDrop dnd = new ij.plugin.DragAndDrop();
			for (Object o : what) {
				if (o instanceof File) dnd.openFile((File) o);
				else IJ.open((String) o);
			}
		}, "Side Toolbar: open").start();
	}

	/** Inserts entries into a group, or at a visible index (-1 = before the ">>" button, or at the end). */
	void insert(List<Item> add, Item group, int visibleIndex) {
		if (add.isEmpty()) return;
		Store.checkpoint("Add " + add.get(0).title());
		if (group != null) group.children.addAll(add);
		else {
			int at;
			if (visibleIndex >= 0) at = modelIndex(visible(), visibleIndex);
			else {
				at = Store.items.size();
				for (int i = 0; i < Store.items.size(); i++) if (Store.items.get(i).kind == Item.Kind.MORE) { at = i; break; }
			}
			Store.items.addAll(Math.max(0, Math.min(at, Store.items.size())), add);
		}
		for (Item i : add) if (i.kind == Item.Kind.SLOT) Store.dismissed.remove(i.target);
		Store.save();
		changed();
		IJ.showStatus("Side Toolbar: added " + add.get(0).title() + (add.size() > 1 ? " and " + (add.size() - 1) + " more" : ""));
	}

	// ---------------------------------------------------------------- menus

	private void contextMenu(MouseEvent e) {
		longPress.stop();
		final Item it = itemAt(e.getPoint());
		pressed = null;
		JPopupMenu m = new JPopupMenu();
		if (it != null) {
			if (it.isTool() || it.kind == Item.Kind.MORE) {
				if (it.isTool() && !actsOnClick(it)) {
					int id = Bridge.toolId(it);
					if (id >= 0 && Bridge.hasSubtypes(it) && !it.target.equals("zoom") && !it.target.equals("dropper"))
						Bridge.select(id, retarget(e, it)); // ImageJ selects the tool before showing its menu
				}
				if (Bridge.addToolEntries(m, it, retarget(e, it))) m.addSeparator();
			}
			JLabel head = new JLabel(it.isButton() ? it.title() : it.kind == Item.Kind.SPRING ? "Spring (pushes the next entries down)" : "Separator");
			head.setFont(head.getFont().deriveFont(Font.BOLD, 11f));
			head.setForeground(new Color(110, 110, 110));
			head.setBorder(new EmptyBorder(2, 10, 2, 10));
			m.add(head);
			if (it.isButton()) m.add(Bridge.item(it.kind == Item.Kind.GROUP ? "Edit Group..." : "Edit Button...", () -> Editor.edit(it)));
			m.add(Bridge.item("Duplicate", () -> {
				Store.checkpoint("Duplicate " + it.title());
				List<Item> p = Store.parentOf(it);
				p.add(p.indexOf(it) + 1, it.copy());
				Store.save();
				changed();
			}));
			m.add(Bridge.item("Remove", () -> {
				Store.checkpoint("Remove " + it.title());
				Store.remove(it);
				Store.save();
				changed();
				toastUndo("Removed “" + it.title() + "”");
			}));
			final int after = visible().indexOf(it) + 1;
			JMenu ins = new JMenu("Insert Below");
			ins.add(Bridge.item("Button...", () -> Editor.showAdd(after)));
			ins.add(Bridge.item("Separator", () -> insert(single(new Item(Item.Kind.SEPARATOR, "")), null, after)));
			ins.add(Bridge.item("Spring (push the rest down)", () -> insert(single(new Item(Item.Kind.SPRING, "")), null, after)));
			ins.add(Bridge.item("Group...", () -> Editor.newGroup(after)));
			m.add(ins);
			m.addSeparator();
		}
		addCommonEntries(m);
		Point p = e.getPoint();
		showMenu(m, p.x, p.y);
		menuShowing = true;
	}

	static List<Item> single(Item i) {
		List<Item> l = new ArrayList<Item>();
		l.add(i);
		return l;
	}

	private void addCommonEntries(JPopupMenu m) {
		final String u = Store.undoDescription();
		JMenuItem undo = Bridge.item(u != null ? "Undo: " + u : "Undo", () -> undo());
		undo.setEnabled(u != null);
		m.add(undo);
		m.add(Bridge.item("Add Buttons...", () -> Editor.showAdd(-1)));
		m.add(Bridge.item("Side Toolbar Settings...", () -> Side_Toolbar.settings()));
	}

	private void addMenu(MouseEvent e, final int at) {
		JPopupMenu m = new JPopupMenu();
		m.add(Bridge.item("Add Buttons... (search all commands and tools)", () -> Editor.showAdd(at)));
		if (!recent.isEmpty()) {
			JMenu r = new JMenu("Recent Commands");
			for (final String c : recent) {
				Item ci = new Item(Item.Kind.COMMAND, c);
				JMenuItem mi = Bridge.item(c, () -> insert(single(new Item(Item.Kind.COMMAND, c)), null, at));
				mi.setIcon(new ImageIcon(Icons.render(ci, 18).getScaledInstance(18, 18, java.awt.Image.SCALE_SMOOTH)));
				r.add(mi);
			}
			m.add(r);
		}
		m.addSeparator();
		m.add(Bridge.item("Macro Button...", () -> Editor.newOfKind(Item.Kind.MACRO, at)));
		m.add(Bridge.item("Script or Macro File...", () -> Editor.newScript(at)));
		m.add(Bridge.item("Group...", () -> Editor.newGroup(at)));
		m.add(Bridge.item("Separator", () -> insert(single(new Item(Item.Kind.SEPARATOR, "")), null, at)));
		m.add(Bridge.item("Spring (push the rest down)", () -> insert(single(new Item(Item.Kind.SPRING, "")), null, at)));
		showMenu(m, plusR.x + plusR.width, plusR.y);
	}

	/** The ••• button: the toolbar's own menu. */
	private void mainMenu(MouseEvent e) {
		JPopupMenu m = new JPopupMenu();
		final String u = Store.undoDescription();
		JMenuItem undo = Bridge.item(u != null ? "Undo: " + u : "Undo", () -> undo());
		undo.setEnabled(u != null);
		m.add(undo);
		m.add(Bridge.item("Side Toolbar Settings...", () -> Side_Toolbar.settings()));
		m.add(Bridge.item("Export Toolbar...", () -> Side_Toolbar.exportConfig()));
		m.add(Bridge.item("Import Toolbar...", () -> Side_Toolbar.importConfig()));
		m.add(Bridge.item("Reset Toolbar...", () -> Side_Toolbar.reset()));
		m.addSeparator();
		m.add(Bridge.item("Show Classic Toolbar", () -> Side_Toolbar.stop()));
		m.add(Bridge.item("Help", () -> Side_Toolbar.help()));
		showMenu(m, menuR.x + menuR.width, menuR.y);
	}

	void undo() {
		String d = Store.undoDescription();
		if (Store.undo()) {
			changed();
			IJ.showStatus("Side Toolbar: undone (" + d + ")");
		}
	}

	private void toastUndo(String text) {
		Rectangle r = new Rectangle(menuR);
		Point p = r.getLocation();
		SwingUtilities.convertPointToScreen(p, this);
		tip.show("<html>" + Tip.esc(text) + " &nbsp;<a href=''>Undo</a></html>", new Rectangle(p.x, p.y, r.width, r.height),
			Store.side().equals("right"), () -> undo(), 10000);
	}

	// ---------------------------------------------------------------- hover tips

	private void showTip() {
		if (hover == null || !hover.isButton() || dragItem != null) return;
		Rectangle r = shownRect(hover);
		if (r == null) return;
		Point p = r.getLocation();
		SwingUtilities.convertPointToScreen(p, this);
		String hint;
		if (hover.isTool()) hint = Bridge.hasSubtypes(hover) ? "Right-click or hold for more; double-click for options" : "Right-click for more";
		else if (hover.kind == Item.Kind.GROUP) hint = "Click to open; drop buttons on it to add them";
		else hint = "Drag to move, drag off the bar to remove; right-click to edit";
		String d = detail(hover).trim();
		tip.show("<html><b>" + Tip.esc(hover.title()) + "</b>" + (d.length() > 0 ? "<br>" + Tip.esc(d) : "")
				+ "<br><font color='#777777'>" + hint + "</font></html>",
			new Rectangle(p.x, p.y, r.width, r.height), Store.side().equals("right"), null, 0);
	}

	// ---------------------------------------------------------------- live updates

	/** The contents changed: lay out again, and resize the window if the column count changed. */
	void changed() {
		int before = cols;
		chooseColumns(getHeight());
		if (cols != before) {
			Chrome.apply();
			Side_Toolbar.rearrangeWindows(); // the bar got wider or narrower: windows beside it move too
		}
		relayout();
	}

	private void pollToolbar() {
		if (Bridge.tb() == null) return;
		if (Store.sync(Bridge.slotNames())) {
			Store.save();
			changed();
			return;
		}
		// repaint when ImageJ's toolbar changed under us (a toolset loaded, a tool or its icon changed)
		int h = java.util.Arrays.hashCode(Bridge.names()) * 31 + java.util.Arrays.hashCode(Bridge.icons()) + Bridge.current();
		if (h != pollHash) {
			pollHash = h;
			changed();
		}
		Chrome.checkScreen();
	}

	public void eventOccurred(int eventID) {
		SwingUtilities.invokeLater(this::repaint);
	}

	public String commandExecuting(String command) {
		if (command != null && !command.contains("Side Toolbar") && !command.startsWith("Layout ")) {
			synchronized (recent) {
				recent.remove(command);
				recent.addFirst(command);
				while (recent.size() > 15) recent.removeLast();
			}
		}
		return command;
	}

	@Override
	public Dimension getPreferredSize() { return new Dimension(preferredWidth(), 400); }

	@Override
	public void setBounds(int x, int y, int w, int h) {
		boolean resized = w != getWidth() || h != getHeight();
		super.setBounds(x, y, w, h);
		if (resized) relayout();
	}

	// ---------------------------------------------------------------- small windows

	/** The button following the mouse while it is dragged; a red cross shows it will be removed. */
	static final class Ghost extends JWindow {
		private boolean removing;

		Ghost(final Item it, final int s) {
			setFocusableWindowState(false);
			setBackground(new Color(0, 0, 0, 0));
			JComponent c = new JComponent() {
				protected void paintComponent(Graphics g0) {
					Graphics2D g = (Graphics2D) g0;
					g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
					g.setColor(new Color(255, 255, 255, 235));
					g.fillRoundRect(0, 0, s - 1, s - 1, 8, 8);
					g.setColor(removing ? new Color(200, 40, 40) : ACCENT);
					g.drawRoundRect(0, 0, s - 1, s - 1, 8, 8);
					if (it.isButton()) Icons.paint(g, it, 0, 0, s);
					else {
						g.setColor(Color.gray);
						g.drawLine(4, s / 2, s - 5, s / 2);
					}
					if (removing) {
						int r = Math.max(12, s / 3);
						g.setColor(new Color(200, 40, 40));
						g.fillOval(s - r - 1, 1, r, r);
						g.setColor(Color.white);
						g.setStroke(new BasicStroke(2f));
						int m = r / 4;
						g.drawLine(s - r - 1 + m, 1 + m, s - 2 - m, r - m);
						g.drawLine(s - 2 - m, 1 + m, s - r - 1 + m, r - m);
					}
				}
			};
			c.setOpaque(false);
			setContentPane(c);
			setSize(s, s);
			setAlwaysOnTop(true);
			setVisible(true);
		}

		void setRemoving(boolean r) {
			if (r != removing) {
				removing = r;
				repaint();
			}
		}
	}

	/** A tooltip beside the bar (Swing's own tooltips do not show in a window without focus); can be clickable. */
	static final class Tip {
		private JWindow w;
		private JLabel label;
		private Runnable action;
		private final Timer hideTimer = new Timer(6000, e -> hide());

		static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

		void show(String html, Rectangle anchor, boolean leftOfAnchor, Runnable click, int ms) {
			show(html, anchor, leftOfAnchor ? "left" : "right", click, ms);
		}

		/**
		 * Shows the tip beside the anchor on the given side ("above", "below", "left", "right"), on the other side
		 * when there is no room, and always entirely on the anchor's screen.
		 */
		void show(String html, Rectangle anchor, String side, Runnable click, int ms) {
			hide();
			action = click;
			w = new JWindow();
			w.setFocusableWindowState(false);
			label = new JLabel(html);
			label.setFont(new Font("SansSerif", Font.PLAIN, 12));
			label.setOpaque(true);
			label.setBackground(new Color(255, 255, 238));
			label.setBorder(javax.swing.BorderFactory.createCompoundBorder(
				javax.swing.BorderFactory.createLineBorder(new Color(170, 170, 150)), new EmptyBorder(4, 7, 4, 7)));
			if (click != null) {
				label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
				label.addMouseListener(new java.awt.event.MouseAdapter() {
					public void mousePressed(MouseEvent e) {
						Runnable a = action;
						hide();
						if (a != null) a.run();
					}
				});
			}
			w.getContentPane().add(label);
			w.pack();
			w.setLocation(place(anchor, side, w.getWidth(), w.getHeight()));
			w.setAlwaysOnTop(true);
			w.setVisible(true);
			if (ms > 0) {
				hideTimer.setInitialDelay(ms);
				hideTimer.setRepeats(false);
				hideTimer.restart();
			}
		}

		static java.awt.Point place(Rectangle a, String side, int W, int H) {
			Rectangle scr = screenOf(new java.awt.Point(a.x + a.width / 2, a.y + a.height / 2));
			int x, y;
			if (side.equals("above") || side.equals("below")) {
				x = a.x + (a.width - W) / 2;
				y = side.equals("above") ? a.y - H - 6 : a.y + a.height + 6;
				if (y < scr.y) y = a.y + a.height + 6;                  // no room above: below
				if (y + H > scr.y + scr.height) y = a.y - H - 6;         // no room below: above
			} else {
				y = a.y + (a.height - H) / 2;
				x = side.equals("left") ? a.x - W - 6 : a.x + a.width + 6;
				if (x < scr.x) x = a.x + a.width + 6;
				if (x + W > scr.x + scr.width) x = a.x - W - 6;
			}
			x = Math.max(scr.x + 2, Math.min(x, scr.x + scr.width - W - 2));
			y = Math.max(scr.y + 2, Math.min(y, scr.y + scr.height - H - 2));
			return new java.awt.Point(x, y);
		}

		/** The usable area (without menu bar and Dock) of the screen holding the point. */
		static Rectangle screenOf(java.awt.Point p) {
			for (java.awt.GraphicsDevice d : java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
				java.awt.GraphicsConfiguration gc = d.getDefaultConfiguration();
				if (gc.getBounds().contains(p)) return Chrome.usable(gc);
			}
			return Chrome.usable(Chrome.screen());
		}

		void hide() {
			hideTimer.stop();
			if (w != null) w.dispose();
			w = null;
			action = null;
		}

		boolean showing() { return w != null; }
	}

	static Window windowOf(java.awt.Component c) { return SwingUtilities.getWindowAncestor(c); }
}
