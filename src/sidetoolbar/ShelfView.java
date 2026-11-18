package sidetoolbar;

import ij.IJ;
import ij.ImageListener;
import ij.ImagePlus;
import ij.Menus;
import ij.WindowManager;
import ij.gui.ImageWindow;
import ij.measure.ResultsTable;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dialog;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JPopupMenu;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;

/**
 * The window of one shelf. Horizontal shelves lay their sections along the bar (first section at the start,
 * last at the end, the others centred); vertical shelves show each section as a column. Tiles are painted;
 * clicks and menus act on what a tile stands for: a tool window, an image, a macro.
 */
final class ShelfView extends JComponent implements ImageListener {

	static final int PAD = 4, GAP = 4, SEP = 9;

	final Shelf shelf;
	final JWindow window;
	private final List<Tile> tiles = new ArrayList<Tile>();
	private final List<Rectangle> sectionRects = new ArrayList<Rectangle>();
	private final Map<ImagePlus, BufferedImage> thumbs = new WeakHashMap<ImagePlus, BufferedImage>();
	private javax.swing.SwingWorker<BufferedImage, Void> thumbnailJob;
	private boolean disposed;
	private final Map<ImagePlus, Long> dirty = new WeakHashMap<ImagePlus, Long>();
	private final Timer poll;
	private Tile hover, pressed;
	private Point dragFrom;
	private final Map<Shelf.Section,Integer> scrollOffsets = new java.util.HashMap<>();
	private int signature;
	private final Dock.Tip tip = new Dock.Tip();
	private Rectangle resizeFrom; // the bar's bounds when a drag of its inner edge started
	private Point resizeStart;
	private Point itemPress, itemPointer;
	private Tile dragging;
	private List<Tile> dragOriginal, dragPreview;
	private List<Rectangle> dragSlots;
	private boolean validDrop;
	private final Map<Object,double[]> shown = new java.util.HashMap<>();
	private final Timer slide = new Timer(16,e -> animateTiles());
	private final Timer dragScroll = new Timer(70,e -> autoScrollDrag());

	/** Names the shelf shows instead of image titles (the images keep their titles); for this session only. */
	private static final Map<ImagePlus, String> imageNames = java.util.Collections.synchronizedMap(new WeakHashMap<ImagePlus, String>());
	static WeakHashMap<ImagePlus,String> copyImageNames(){synchronized(imageNames){return new WeakHashMap<>(imageNames);}}
	static void restoreImageNames(Map<ImagePlus,String> names){imageNames.clear();imageNames.putAll(names);}

	static String nameOf(ImagePlus imp) {
		String n = imageNames.get(imp);
		return n == null || n.isEmpty() ? imp.getTitle() : n;
	}

	/** Asks for the name the shelf shows for an image; an empty name goes back to the image's title. */
	static void editImageName(ImagePlus imp) {
		ij.gui.GenericDialog gd = new ij.gui.GenericDialog("Name in the Shelf");
		gd.addStringField("Name:", nameOf(imp), 28);
		gd.addMessage("Only the shelf shows this name: the image keeps its title \"" + imp.getTitle() + "\".\nLeave it empty to show the title again.");
		gd.showDialog();
		if (gd.wasCanceled()) return;
		String n = gd.getNextString().trim();
		Shelf.checkpoint();
		if (n.isEmpty() || n.equals(imp.getTitle())) imageNames.remove(imp); else imageNames.put(imp, n);
		for (Shelf sh : Shelf.all) if (sh.view != null) sh.view.repaint();
	}

	/** What a tile shows. */
	static final class Tile {
		Shelf.Section section;
		String kind;     // "window" (a tool window, open or pinned), "image", "macro", "add"
		Item item;       // pinned window or macro
		Window window;   // open tool window
		ImagePlus imp;
		Rectangle r;
		boolean open() { return window != null; }
	}

	ShelfView(Shelf shelf) {
		this.shelf = shelf;
		window = new JWindow(IJ.getInstance());
		window.setFocusableWindowState(false);
		window.setAutoRequestFocus(false);
		window.setName("Side Toolbar shelf");
		window.setAlwaysOnTop(OnTop.wanted()); // a bar made while Fiji is in front stays above its windows at once
		window.getContentPane().add(this);
		setOpaque(true);
		MouseAdapter m = new MouseAdapter() {
			public void mousePressed(MouseEvent e) { pressed(e); }
			public void mouseReleased(MouseEvent e) { released(e); }
			public void mouseDragged(MouseEvent e) { dragged(e); }
			public void mouseMoved(MouseEvent e) { moved(e); }
			public void mouseExited(MouseEvent e) { hover = null; tip.hide(); repaint(); }
			public void mouseWheelMoved(MouseWheelEvent e) {
				Shelf.Section section=sectionAt(e.getPoint());if(section!=null)scrollCategory(section,e.getWheelRotation()*40);
			}
		};
		addMouseListener(m);
		addMouseMotionListener(m);
		addMouseWheelListener(m);
		new DropTarget(this, DnDConstants.ACTION_COPY, new Drops(), true);
		ImagePlus.addImageListener(this);
		poll = new Timer(600, e -> refresh());
		poll.start();
	}

	void dispose() {
		disposed = true;
		if (thumbnailJob != null) thumbnailJob.cancel(true);
		poll.stop();
		cancelItemDrag();slide.stop();
		tip.hide();
		ImagePlus.removeImageListener(this);
		window.dispose();
		shelf.view = null;
	}

	// ---------------------------------------------------------------- size and layout

	/** Thickness of the shelf: height of a horizontal one, width of a vertical one. */
	int thickness() {
		int s = Shelf.size();
		if (shelf.horizontal()) return s;
		return Math.max(1, shelf.shown().size()) * s + (shelf.shown().size() - 1) * SEP;
	}

	/** Length a floating shelf needs to show everything. */
	int naturalLength() {
		int s = Shelf.size(), len = PAD;
		for (Shelf.Section c : shelf.shown()) len += (count(c) + 1) * (tileWidth(c) + GAP) + SEP;
		return Math.max(240, len + 40);
	}

	private int count(Shelf.Section c) {
		if (c.kind.equals(Shelf.IMAGES)) return Math.max(1, tilesOf(c).size());
		if (c.kind.equals(Shelf.WINDOWS)) return Math.max(1,windowTiles(c).size());
		return c.items.size() + 1;
	}

	int tileWidth(Shelf.Section c) {
		int inner = Shelf.size() - 2 * PAD;
		return c.kind.equals(Shelf.IMAGES) ? (int) Math.round(inner * 1.35) : inner;
	}

	/** Recomputes the tiles; called when windows, images or the shelf's contents change. */
	void rebuild() {
		tiles.clear();
		sectionRects.clear();
		List<Shelf.Section> secs = shelf.shown();
		int s = Shelf.size(), inner = s - 2 * PAD;
		if (shelf.horizontal()) {
			// widths of the sections; images take what is left and scroll when they do not fit
			int w = Math.max(getWidth(), 1);
			int[] need = new int[secs.size()];
			for (int i = 0; i < secs.size(); i++) {
				Shelf.Section c = secs.get(i);
				need[i] = count(c) * (tileWidth(c) + GAP) + (c.kind.equals(Shelf.MACROS) ? 0 : 0);
			}
			// sections before the images go from the start, those after it from the end; the images take the space
			// in between with their previews centred. Without images, the last section goes to the end.
			int img = -1;
			for (int i = 0; i < secs.size(); i++) if (secs.get(i).kind.equals(Shelf.IMAGES)) img = i;
			int available=Math.max(secs.size(),w-2*PAD-Math.max(0,secs.size()-1)*SEP),total=0;
			for(int n:need)total+=n;
			if(total>available){for(int i=0;i<need.length;i++)need[i]=available/need.length+(i<available%need.length?1:0);}
			else if(need.length>0)need[img>=0?img:need.length-1]+=available-total;
			Rectangle[] areas = new Rectangle[secs.size()];int x=PAD;
			for(int i=0;i<secs.size();i++){areas[i]=new Rectangle(x,0,need[i],s);x+=need[i]+SEP;}
			for (int i = 0; i < secs.size(); i++) {
				sectionRects.add(areas[i]);
				layoutRow(secs.get(i), areas[i], inner);
			}
		} else {
			int x = 0;
			for (Shelf.Section c : secs) {
				Rectangle area = new Rectangle(x, 0, s, getHeight());
				sectionRects.add(area);
				layoutColumn(c, area, inner);
				x += s + SEP;
			}
		}
		java.util.Set<Object> present=new java.util.HashSet<>();
		for(Tile tile:tiles){Object key=ShelfOrder.identity(tile);if(key!=null){present.add(key);if(!shown.containsKey(key))shown.put(key,new double[]{tile.r.x,tile.r.y});}}
		shown.keySet().retainAll(present);slide.start();repaint();
	}

	private void animateTiles(){
		boolean moving=false;for(Tile tile:tiles){double[] p=shown.get(ShelfOrder.identity(tile));if(p==null)continue;
			if(Math.abs(p[0]-tile.r.x)<.5&&Math.abs(p[1]-tile.r.y)<.5){p[0]=tile.r.x;p[1]=tile.r.y;}
			else{p[0]+=(tile.r.x-p[0])*.4;p[1]+=(tile.r.y-p[1])*.4;moving=true;}}
		repaint();if(!moving)slide.stop();
	}
	private Rectangle paintedRect(Tile tile){double[] p=shown.get(ShelfOrder.identity(tile));return p==null?tile.r:new Rectangle((int)Math.round(p[0]),(int)Math.round(p[1]),tile.r.width,tile.r.height);}

	private void layoutRow(Shelf.Section c, Rectangle area, int inner) {
		List<Tile> l = tilesOf(c);
		int tw = tileWidth(c);
		int total = l.size() * (tw + GAP) - GAP;
		int x = area.x;
		int scroll=Math.max(0,Math.min(scrollOffsets.getOrDefault(c,0),Math.max(0,total-area.width)));scrollOffsets.put(c,scroll);
		if(total>area.width)x-=scroll;
		else if(c.kind.equals(Shelf.IMAGES))x+=(area.width-total)/2;
		else if(shelf.shown().size()>1&&shelf.shown().get(shelf.shown().size()-1)==c)x+=area.width-total;
		for (Tile t : l) {
			t.r = new Rectangle(x, PAD, tw, inner);
			x += tw + GAP;
			tiles.add(t);
		}
	}

	private void layoutColumn(Shelf.Section c, Rectangle area, int inner) {
		List<Tile> l = tilesOf(c);
		boolean img = c.kind.equals(Shelf.IMAGES);
		int th = img ? (int) Math.round(inner * 0.8) : inner;
		int total=l.size()*(th+GAP)-GAP;
		int scroll=Math.max(0,Math.min(scrollOffsets.getOrDefault(c,0),Math.max(0,total-area.height+2*PAD)));scrollOffsets.put(c,scroll);
		int y = PAD-scroll;
		for (Tile t : l) {
			t.r = new Rectangle(area.x + PAD, y, inner, th);
			y += th + GAP;
			tiles.add(t);
		}
	}

	private List<Tile> tilesOf(Shelf.Section c) {
		if(dragging!=null&&c==dragging.section&&dragPreview!=null){
			List<Tile> preview=new ArrayList<>(dragPreview);
			if(c.kind.equals(Shelf.MACROS)){Tile add=new Tile();add.kind="add";add.section=c;preview.add(add);}return preview;
		}
		List<Tile> l = new ArrayList<Tile>();
		if (c.kind.equals(Shelf.WINDOWS)) l.addAll(windowTiles(c));
		else if (c.kind.equals(Shelf.IMAGES)) {
			for (ImagePlus imp : images()) {
				if(c.hiddenImages.containsKey(imp))continue;
				Tile t = new Tile();
				t.section = c; t.kind = "image"; t.imp = imp;t.item=c.imageLooks.get(imp);
				l.add(t);
			}
		} else {
			for (Item it : c.items) {
				Tile t = new Tile();
				t.section = c; t.kind = "macro"; t.item = it;
				l.add(t);
			}
			Tile add = new Tile();
			add.section = c; add.kind = "add";
			l.add(add);
		}
		if(l.isEmpty()){Tile add=new Tile();add.section=c;add.kind="add";l.add(add);}
		ShelfOrder.sort(c,l);return l;
	}

	/** Pinned tools (open or not), then the other open tool windows. */
	private List<Tile> windowTiles(Shelf.Section c) {
		List<Tile> l = new ArrayList<Tile>();
		List<Window> open = toolWindows();
		List<Window> matchable = new ArrayList<>(open);
		for (Item it : c.items) {
			Tile t = new Tile();
			t.section = c; t.kind = "window"; t.item = it;
			for (Window w : matchable) if (MacroTools.matches(it, w)) { t.window = w; break; }
			if (t.window != null) open.remove(t.window);
			if (!c.hiddenWindows.contains(ShelfOrder.windowKey(t))) l.add(t);
		}
		for (Window w : open) {
			if(c.hiddenWindows.contains("window:"+titleOf(w)))continue;
			Tile t = new Tile();
			t.section = c; t.kind = "window"; t.window = w;
			l.add(t);
		}
		ShelfOrder.sort(c,l);return l;
	}

	static boolean isAvailable(Item it) {
		if (it.target.equals("results") || it.target.equals("log")
				|| MacroTools.isRecorder(it.target) || MacroTools.isEditor(it.target)) return true;
		return Menus.getCommands() == null || Menus.getCommands().get(it.target) != null;
	}

	static String titleOf(Window w) {
		if (w instanceof Frame) return ((Frame) w).getTitle();
		if (w instanceof Dialog) return ((Dialog) w).getTitle();
		return null;
	}

	/** Native image-backed tools are not editable data-image windows. Leave unknown subclasses as tools. */
	static boolean isImageWindow(Window window) {
		if (window == null) return false;
		Class<?> type = window.getClass();
		return type == ImageWindow.class || type == ij.gui.StackWindow.class;
	}

	/** Visible tool windows: titled, not data images, not the ImageJ window, not modal dialogs. */
	static List<Window> toolWindows() {
		List<Window> l = new ArrayList<Window>();
		for (Window w : Window.getWindows()) {
			if (!w.isVisible() || isImageWindow(w) || w == IJ.getInstance()) continue;
			if (w instanceof Dialog && ((Dialog) w).isModal()) continue;
			String t = titleOf(w);
			if (t == null || t.trim().isEmpty() || t.startsWith("Add Buttons") || t.equals("Edit Button")) continue;
			l.add(w);
		}
		return l;
	}

	static List<ImagePlus> images() {
		List<ImagePlus> l = new ArrayList<ImagePlus>();
		int[] ids = WindowManager.getIDList();
		if (ids != null) for (int id : ids) {
			ImagePlus imp = WindowManager.getImage(id);
			if (imp != null && isImageWindow(imp.getWindow()) && imp.getWindow().isVisible()) l.add(imp);
		}
		return l;
	}

	/** Every 0.6 s: re-lay out when windows or images came or went, redraw changed previews. */
	private void refresh() {
		if(dragging!=null)return;
		int sig = 17;
		for (Window w : toolWindows()) sig = (sig * 31 + System.identityHashCode(w)) * 31 + titleOf(w).hashCode();
		for (ImagePlus imp : images()) sig = sig * 31 + imp.getID() + imp.getTitle().hashCode();
		ImagePlus cur = WindowManager.getCurrentImage();
		sig = sig * 31 + (cur == null ? 0 : cur.getID());
		boolean changed = sig != signature;
		signature = sig;
		long now = System.currentTimeMillis();
		boolean previewsChanged = false;
		synchronized (dirty) {
			java.util.Iterator<Map.Entry<ImagePlus, Long>> updates = dirty.entrySet().iterator();
			while (updates.hasNext()) {
				Map.Entry<ImagePlus, Long> update = updates.next();
				if (update.getValue() >= now - 400) continue;
				previewsChanged |= thumbs.remove(update.getKey()) != null;
				updates.remove();
			}
		}
		if (changed) {
			int before = thickness();
			rebuild();
			if (!shelf.horizontal() && thickness() != before) Chrome.apply();
			if (!shelf.docked()) placeFloating();
		} else if (previewsChanged) repaint();
	}

	public void imageOpened(ImagePlus imp) { }

	public void imageClosed(ImagePlus imp) {
		synchronized (dirty) { dirty.remove(imp); }
		SwingUtilities.invokeLater(() -> thumbs.remove(imp));
	}

	/** Coalesce notifications before requesting the next visible preview. */
	public void imageUpdated(ImagePlus imp) {
		synchronized (dirty) { dirty.putIfAbsent(imp, System.currentTimeMillis()); }
	}

	void placeFloating() {
		int len = naturalLength();
		window.setBounds(shelf.x, shelf.y, len, Shelf.size());
		if (!window.isVisible()) window.setVisible(true);
		rebuild();
	}

	@Override
	public void setBounds(int x, int y, int w, int h) {
		boolean resized = w != getWidth() || h != getHeight();
		super.setBounds(x, y, w, h);
		if (resized) rebuild();
	}

	// ---------------------------------------------------------------- painting

	@Override
	protected void paintComponent(Graphics g0) {
		Graphics2D g = (Graphics2D) g0;
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.setColor(Dock.BG);
		g.fillRect(0, 0, getWidth(), getHeight());
		g.setColor(Dock.EDGE);
		if (shelf.place.equals("bottom")) g.drawLine(0, 0, getWidth(), 0);
		else if (shelf.place.equals("top")) g.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1);
		else if (shelf.place.equals("left")) g.drawLine(getWidth() - 1, 0, getWidth() - 1, getHeight());
		else if (shelf.place.equals("right")) g.drawLine(0, 0, 0, getHeight());
		else g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
		for (int i = 0; i + 1 < sectionRects.size(); i++) { // separators between sections
			Rectangle a = sectionRects.get(i);
			g.setColor(new Color(200, 200, 200));
			if (shelf.horizontal()) g.drawLine(a.x + a.width + SEP / 2, 6, a.x + a.width + SEP / 2, getHeight() - 7);
			else g.drawLine(a.x + a.width + SEP / 2, 6, a.x + a.width + SEP / 2, getHeight() - 7);
		}
		Rectangle clip = g.getClipBounds();
		for (Tile t : tiles) {
			if (ShelfOrder.same(t,dragging)) continue;
			Rectangle displayed=paintedRect(t);
			if (clip != null && !clip.intersects(displayed)) continue;
			Rectangle area = sectionRects.get(Math.max(0, shelf.shown().indexOf(t.section)));
			if (!area.intersects(displayed)) continue;
			Graphics2D gt = (Graphics2D) g.create();
			gt.clip(area);
			gt.translate(displayed.x-t.r.x,displayed.y-t.r.y);
			paintTile(gt, t);
			gt.dispose();
		}
		if(dragging!=null&&itemPointer!=null){
			g.setColor(validDrop?Dock.ACCENT:new Color(190,70,70));g.setStroke(new BasicStroke(2f));
			Rectangle gap=dragging.r;g.drawRoundRect(gap.x+1,gap.y+1,gap.width-3,gap.height-3,8,8);
			Rectangle original=dragging.r;dragging.r=new Rectangle(itemPointer.x-original.width/2,itemPointer.y-original.height/2,original.width,original.height);
			Graphics2D ghost=(Graphics2D)g.create();ghost.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,.8f));
			ghost.setColor(Dock.BG);ghost.fillRoundRect(dragging.r.x,dragging.r.y,dragging.r.width,dragging.r.height,8,8);
			try{paintTile(ghost,dragging);}finally{ghost.dispose();dragging.r=original;}
		}
		if (dropHint) {
			g.setColor(new Color(45, 110, 200, 40));
			g.fillRect(0, 0, getWidth(), getHeight());
			g.setColor(Dock.ACCENT);
			g.setStroke(new BasicStroke(2f));
			g.drawRect(1, 1, getWidth() - 3, getHeight() - 3);
		}
	}

	private void paintTile(Graphics2D g, Tile t) {
		Rectangle r = t.r;
		boolean current = t.imp != null && t.imp == WindowManager.getCurrentImage();
		if (t == pressed) { g.setColor(Dock.PRESSED); g.fillRoundRect(r.x, r.y, r.width, r.height, 8, 8); }
		else if (t == hover) { g.setColor(Dock.HOVER); g.fillRoundRect(r.x, r.y, r.width, r.height, 8, 8); }
		if (t.kind.equals("image")) {
			int labelH = Math.max(10, r.height / 5);
			Rectangle pic = new Rectangle(r.x + 2, r.y + 2, r.width - 4, r.height - labelH - 4);
			if(t.item!=null&&!t.item.icon.isEmpty()){int size=Math.min(pic.width,pic.height);Icons.paint(g,t.item,pic.x+(pic.width-size)/2,pic.y+(pic.height-size)/2,size);}
			else{BufferedImage th = thumb(t.imp, pic.width, pic.height);if (th != null) g.drawImage(th, pic.x + (pic.width - th.getWidth()) / 2, pic.y + (pic.height - th.getHeight()) / 2, null);}
			if (current) {
				g.setColor(Dock.ACCENT);
				g.setStroke(new BasicStroke(2f));
				g.drawRoundRect(r.x + 1, r.y + 1, r.width - 3, r.height - 3, 8, 8);
				g.setStroke(new BasicStroke(1f));
			}
			g.setColor(Dock.TEXT);
			g.setFont(new Font("SansSerif", Font.PLAIN, Math.max(9, labelH - 2)));
			FontMetrics fm = g.getFontMetrics();
			String name = Dock.ellipsize(nameOf(t.imp), fm, r.width - 4);
			g.drawString(name, r.x + (r.width - fm.stringWidth(name)) / 2, r.y + r.height - 3);
			return;
		}
		if (t.kind.equals("add")) {
			g.setColor(new Color(150, 150, 150));
			g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[] {3f, 3f}, 0f));
			g.drawRoundRect(r.x + 3, r.y + 3, r.width - 7, r.height - 7, 8, 8);
			g.setStroke(new BasicStroke(1f));
			Icons.paintLetters(g, "+", new Color(120, 120, 120), r.x, r.y, Math.min(r.width, r.height), false);
			return;
		}
		Item it = t.item;
		if (it == null) { // an open tool window that is not pinned: its initials
			it = new Item(Item.Kind.COMMAND, titleOf(t.window));
			it.label = titleOf(t.window);
		}
		java.awt.Composite c = g.getComposite();
		if (t.kind.equals("window") && !t.open()) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));
		int s = Math.min(r.width, r.height);
		Icons.paint(g, it, r.x + (r.width - s) / 2, r.y + (r.height - s) / 2 - (t.kind.equals("window") ? 2 : 0), s);
		g.setComposite(c);
		if (t.kind.equals("window") && t.open()) { // the open-window dot, as in the macOS Dock
			g.setColor(new Color(60, 60, 60));
			g.fillOval(r.x + r.width / 2 - 2, r.y + r.height - 4, 4, 4);
		}
	}

	/** Painting uses cached images. One background job per shelf supplies missing visible previews. */
	private BufferedImage thumb(ImagePlus imp, int w, int h) {
		double scale = Math.min(w / (double) imp.getWidth(), h / (double) imp.getHeight());
		int width = Math.max(1, (int) Math.round(imp.getWidth() * scale));
		int height = Math.max(1, (int) Math.round(imp.getHeight() * scale));
		BufferedImage cached = thumbs.get(imp);
		if (cached != null && cached.getWidth() == width && cached.getHeight() == height) return cached;
		if (thumbnailJob == null && !disposed) {
			thumbnailJob = new javax.swing.SwingWorker<BufferedImage, Void>() {
				protected BufferedImage doInBackground() {
					Image source = imp.getImage();
					if (source == null) return null;
					BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
					Graphics2D graphics = result.createGraphics();
					try {
						graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
						graphics.drawImage(source, 0, 0, width, height, null);
					} finally { graphics.dispose(); }
					return result;
				}

				protected void done() {
					thumbnailJob = null;
					if (disposed) return;
					try {
						BufferedImage result = get();
						if (result != null && imp.getWindow() != null) {
							thumbs.put(imp, result);
							repaint();
						}
					} catch (InterruptedException exception) {
						Thread.currentThread().interrupt();
					} catch (java.util.concurrent.ExecutionException | java.util.concurrent.CancellationException ignored) {
						// A closing or changing image can be retried on the next paint.
					}
				}
			};
			thumbnailJob.execute();
		}
		return cached;
	}

	// ---------------------------------------------------------------- mouse

	private Tile tileAt(Point p) {
		for (Tile t : tiles) if (paintedRect(t).contains(p)) {
			int i = shelf.shown().indexOf(t.section);
			if (i >= 0 && i < sectionRects.size() && !sectionRects.get(i).contains(p)) continue;
			return t;
		}
		return null;
	}

	private Shelf.Section sectionAt(Point p) {
		for (int i = 0; i < sectionRects.size(); i++) if (sectionRects.get(i).contains(p)) return shelf.shown().get(i);
		return null;
	}

	/** The edge of the bar that faces the screen's middle: dragging it changes the bar size. */
	private boolean onInnerEdge(Point p) {
		final int E = 4;
		switch (shelf.place) {
			case "bottom": return p.y < E;
			case "left": return p.x >= getWidth() - E;
			case "right": return p.x < E;
			default: return p.y >= getHeight() - E; // top, float
		}
	}

	private Cursor edgeCursor() {
		switch (shelf.place) {
			case "bottom": return Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR);
			case "left": return Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR);
			case "right": return Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR);
			default: return Cursor.getPredefinedCursor(Cursor.S_RESIZE_CURSOR);
		}
	}

	/** Follows a drag of the inner edge: sets the shelf size and resizes this bar from its outer edge. */
	private void resizeTo(Point screen) {
		int d;
		switch (shelf.place) {
			case "bottom": d = resizeStart.y - screen.y; break;
			case "left": d = screen.x - resizeStart.x; break;
			case "right": d = resizeStart.x - screen.x; break;
			default: d = screen.y - resizeStart.y;
		}
		Rectangle b = resizeFrom;
		int n = shelf.horizontal() ? 1 : Math.max(1, shelf.shown().size());
		int start = shelf.horizontal() ? b.height : (b.width - (n - 1) * SEP) / n;
		int size = Math.max(36, Math.min(120, start + d / n));
		if (size == Shelf.size()) return;
		ij.Prefs.set("sidetoolbar.shelfsize", size);
		int th = thickness();
		switch (shelf.place) {
			case "bottom": window.setBounds(b.x, b.y + b.height - th, b.width, th); break;
			case "left": window.setBounds(b.x, b.y, th, b.height); break;
			case "right": window.setBounds(b.x + b.width - th, b.y, th, b.height); break;
			default: window.setBounds(b.x, b.y, b.width, th);
		}
	}

	private void pressed(MouseEvent e) {
		if(dragging!=null)cancelItemDrag();
		tip.hide();
		itemPress=null;
		if (SwingUtilities.isLeftMouseButton(e) && !e.isPopupTrigger() && onInnerEdge(e.getPoint())) {
			resizeFrom = window.getBounds();
			resizeStart = e.getLocationOnScreen();
			pressed = null;
			dragFrom = null;
			return;
		}
		pressed = tileAt(e.getPoint());
		if(SwingUtilities.isLeftMouseButton(e)&&pressed!=null&&!pressed.kind.equals("add"))itemPress=e.getPoint();
		dragFrom = shelf.docked() || pressed != null || !SwingUtilities.isLeftMouseButton(e) ? null : e.getLocationOnScreen();
		if (e.isPopupTrigger()) { menu(e); pressed = null; }
		repaint();
	}

	private void dragged(MouseEvent e) {
		if (resizeFrom != null) { resizeTo(e.getLocationOnScreen()); return; }
		if(itemPress!=null&&pressed!=null){
			if(dragging==null){if(itemPress.distance(e.getPoint())<6)return;
				dragging=pressed;dragOriginal=contentTiles(dragging.section);dragSlots=new ArrayList<>();
				for(Tile t:dragOriginal)dragSlots.add(new Rectangle(t.r));
				dragPreview=new ArrayList<>(dragOriginal);hover=null;dragScroll.start();
			}updateItemDrag(e.getPoint());return;
		}
		if (dragFrom == null) return; // a floating shelf moves when dragged by its empty area
		Point now = e.getLocationOnScreen();
		Point loc = window.getLocation();
		window.setLocation(loc.x + now.x - dragFrom.x, loc.y + now.y - dragFrom.y);
		dragFrom = now;
		Shelf target = dockTarget(now);
		for (Shelf s : Shelf.all) if (s.view != null && s != shelf) s.view.setDropHint(s == target);
	}

	/** A docked bar under the pointer: dropping a floating bar there puts its sections into it. */
	private Shelf dockTarget(Point screen) {
		for (Shelf s : Shelf.all) {
			if (s == shelf || s.view == null || !s.docked()) continue;
			Rectangle b = s.view.window.getBounds();
			b.grow(12, 12);
			if (b.contains(screen)) return s;
		}
		return null;
	}

	void setDropHint(boolean on) {
		if (dropHint != on) { dropHint = on; repaint(); }
	}

	/** Where sections dropped at a screen point go in this bar: before the first section whose middle is past it. */
	int insertIndexAt(Point screen) {
		Point p = new Point(screen);
		SwingUtilities.convertPointFromScreen(p, this);
		List<Shelf.Section> shown = shelf.shown();
		for (int i = 0; i < shown.size() && i < sectionRects.size(); i++) {
			Rectangle r = sectionRects.get(i);
			boolean before = shelf.horizontal() ? p.x < r.x + r.width / 2 : p.x < r.x + r.width / 2;
			if (before) return shelf.sections.indexOf(shown.get(i));
		}
		return shelf.sections.size();
	}

	private void released(MouseEvent e) {
		if(dragging!=null){finishItemDrag(e.getPoint());return;}
		itemPress=null;
		if (resizeFrom != null) { // every bar takes the new size; windows move only if the bars' bands changed
			resizeFrom = null;
			Shelf.showAll();
			return;
		}
		if (dragFrom != null) {
			Shelf target = dockTarget(e.getLocationOnScreen());
			for (Shelf s : Shelf.all) if (s.view != null) s.view.setDropHint(false);
			dragFrom = null;
			if (target != null) { Shelf.merge(shelf, target, target.view.insertIndexAt(e.getLocationOnScreen())); return; }
			shelf.x = window.getX();
			shelf.y = window.getY();
			Shelf.save();
			return;
		}
		if (e.isPopupTrigger()) { menu(e); pressed = null; repaint(); return; }
		Tile t = pressed;
		pressed = null;
		repaint();
		if (t == null || !ShelfOrder.same(t,tileAt(e.getPoint())) || !SwingUtilities.isLeftMouseButton(e)) return;
		click(t, e);
	}

	private List<Tile> contentTiles(Shelf.Section section){
		List<Tile> result=new ArrayList<>();for(Tile t:tiles)if(t.section==section&&!t.kind.equals("add"))result.add(t);return result;
	}
	private void updateItemDrag(Point point){
		itemPointer=new Point(point);validDrop=new Rectangle(0,0,getWidth(),getHeight()).contains(point)&&sectionAt(point)==dragging.section;
		int index=0;for(int i=0;i<dragOriginal.size();i++)if(!ShelfOrder.same(dragOriginal.get(i),dragging)){
			Rectangle slot=dragSlots.get(i);if((shelf.horizontal()?point.x:point.y)>(shelf.horizontal()?slot.getCenterX():slot.getCenterY()))index++;
		}
		dragPreview=validDrop?ShelfOrder.moved(dragOriginal,dragging,index):new ArrayList<>(dragOriginal);
		setCursor(Cursor.getPredefinedCursor(validDrop?Cursor.MOVE_CURSOR:Cursor.DEFAULT_CURSOR));rebuild();
	}
	private void scrollCategory(Shelf.Section section,int delta){
		int before=scrollOffsets.getOrDefault(section,0);scrollOffsets.put(section,Math.max(0,before+delta));rebuild();
		int moved=scrollOffsets.getOrDefault(section,0)-before;
		if(dragging!=null&&dragging.section==section&&moved!=0){for(Rectangle slot:dragSlots)if(shelf.horizontal())slot.x-=moved;else slot.y-=moved;updateItemDrag(itemPointer);}
	}
	private void autoScrollDrag(){
		if(dragging==null||!validDrop||itemPointer==null)return;Rectangle area=sectionRects.get(shelf.shown().indexOf(dragging.section));
		int p=shelf.horizontal()?itemPointer.x:itemPointer.y,start=shelf.horizontal()?area.x:area.y,end=start+(shelf.horizontal()?area.width:area.height);
		if(p<start+14)scrollCategory(dragging.section,-14);else if(p>end-14)scrollCategory(dragging.section,14);
	}
	private void finishItemDrag(Point point){
		updateItemDrag(point);Tile moved=dragging;List<Tile> result=new ArrayList<>(dragPreview);boolean valid=validDrop&&!ShelfOrder.equal(dragOriginal,result);
		// Resolve current live contents before committing: a closed image or removed macro cannot be moved by a stale drag.
		dragging=null;dragPreview=null;List<Tile> current=tilesOf(moved.section);current.removeIf(t->t.kind.equals("add"));
		if(valid&&ShelfOrder.equal(current,dragOriginal)){Shelf.checkpoint();ShelfOrder.apply(moved.section,result);Shelf.save();}
		clearItemDrag();rebuild();
	}
	boolean cancelItemDrag(){boolean active=dragging!=null;clearItemDrag();if(active)rebuild();return active;}
	private void clearItemDrag(){dragScroll.stop();dragging=null;dragOriginal=null;dragPreview=null;dragSlots=null;itemPress=null;itemPointer=null;pressed=null;validDrop=false;setCursor(Cursor.getDefaultCursor());}

	private void moved(MouseEvent e) {
		if (onInnerEdge(e.getPoint())) {
			setCursor(edgeCursor());
			if (hover != null) { hover = null; repaint(); }
			tip.hide();
			return;
		}
		setCursor(Cursor.getDefaultCursor());
		Tile t = tileAt(e.getPoint());
		if (t == hover) return;
		hover = t;
		tip.hide();
		repaint();
		if (t == null) return;
		String text;
		if (t.kind.equals("image")) text = "<b>" + Dock.Tip.esc(nameOf(t.imp)) + "</b><br>"
			+ (nameOf(t.imp).equals(t.imp.getTitle()) ? "" : "Image: " + Dock.Tip.esc(t.imp.getTitle()) + "<br>") + t.imp.getWidth() + " × " + t.imp.getHeight()
			+ (t.imp.getStackSize() > 1 ? " × " + t.imp.getStackSize() : "") + "<br><font color='#777777'>Drag within Images to reorder. Right-click: appearance, place, zoom, more</font>";
		else if (t.kind.equals("add")) text = t.section.kind.equals(Shelf.WINDOWS)?"Add a tool launcher":t.section.kind.equals(Shelf.IMAGES)?"Open an image":"Add a macro (.ijm file) to this shelf, or drop .ijm files here";
		else if (t.kind.equals("macro")) text = "<b>" + Dock.Tip.esc(t.item.title()) + "</b><br>" + Dock.Tip.esc(new File(t.item.target).getName())
			+ "<br><font color='#777777'>Click: run. Drag within Macros to reorder. Right-click: edit</font>";
		else text = "<b>" + Dock.Tip.esc(t.item != null ? t.item.title() : titleOf(t.window)) + "</b><br><font color='#777777'>"
			+ (t.open() ? "Open: click to bring it to the front" : isAvailable(t.item)?"Click to open it":"Command unavailable; right-click to edit launcher") + ". Drag within Tools to reorder. Right-click: more</font>";
		Point p = t.r.getLocation();
		SwingUtilities.convertPointToScreen(p, this);
		Rectangle anchor = new Rectangle(p.x, p.y, t.r.width, t.r.height);
		String side = shelf.place.equals("bottom") || shelf.place.equals("float") ? "above" : shelf.place.equals("top") ? "below"
			: shelf.place.equals("right") ? "left" : "right";
		tip.show("<html>" + text + "</html>", anchor, side, null, 0); // away from the bar, always entirely on screen
	}

	private void click(Tile t, MouseEvent e) {
		if (t.kind.equals("image")) { bringToFront(t.imp); return; }
		if (t.kind.equals("add")) { addMenu(t.section, e); return; }
		if (t.kind.equals("macro")) { MacroShelf.run(t.item); return; }
		if (t.open()) {
			if (t.window instanceof Frame && ((Frame) t.window).getState() == Frame.ICONIFIED) ((Frame) t.window).setState(Frame.NORMAL);
			t.window.toFront();
		} else openTool(t.item);
	}

	static void bringToFront(ImagePlus imp) {
		ImageWindow w = imp.getWindow();
		if (w == null) return;
		if (w.getState() == Frame.ICONIFIED) w.setState(Frame.NORMAL);
		WindowManager.setCurrentWindow(w);
		w.toFront();
	}

	/** Opens a pinned tool that is closed. */
	static void openTool(Item it) {
		if (MacroTools.open(it.target)) return;
		if (it.target.equals("results")) { ResultsTable.getResultsTable().show("Results"); return; }
		if (it.target.equals("log")) { IJ.log(""); return; }
		Runner.run(new Item(Item.Kind.COMMAND, it.target));
	}

	// ---------------------------------------------------------------- menus

	private void menu(MouseEvent e) {
		Tile t = tileAt(e.getPoint());
		Shelf.Section sec = t != null ? t.section : sectionAt(e.getPoint());
		JPopupMenu m = new JPopupMenu();
		if (t != null && t.kind.equals("image")) {ImageMenus.fill(m, t.imp);
			m.add(Bridge.item("Name, Initials or Icon for This Tile...",()->editImageLook(t)));
			if(t.item!=null&&!t.item.icon.isEmpty())m.add(Bridge.item("Use Image Preview",()->{Shelf.checkpoint();Item look=t.item.copy();look.icon="";t.section.imageLooks.put(t.imp,look);rebuild();}));
			addMoveItems(m,t);m.add(Bridge.item("Hide from This Shelf (keep image open)",()->hideTile(t)));}
		else if (t != null && t.kind.equals("macro")) macroMenu(m, t);
		else if (t != null && t.kind.equals("window")) windowMenu(m, t);
		else if (t != null && t.kind.equals("add")) {
			if(t.section.kind.equals(Shelf.MACROS)){m.add(Bridge.item("Add Macro File (.ijm)...", () -> MacroShelf.addFile(t.section)));m.add(Bridge.item("New Macro...", () -> MacroShelf.newMacro(t.section)));}
		}
		if (m.getComponentCount() > 0) m.addSeparator();
		if (sec != null) sectionMenu(m, sec);
		shelfMenu(m);
		m.show(this, e.getX(), e.getY());
	}

	private void windowMenu(JPopupMenu m, final Tile t) {
		header(m, t.item != null ? t.item.title() : titleOf(t.window));
		if (t.open()) {
			m.add(Bridge.item("Bring to Front", () -> t.window.toFront()));
			m.add(Bridge.item("Close Window", () -> t.window.dispatchEvent(new WindowEvent(t.window, WindowEvent.WINDOW_CLOSING))));
		} else {javax.swing.JMenuItem open=Bridge.item("Open", () -> openTool(t.item));open.setEnabled(isAvailable(t.item));m.add(open);}
		if (t.item == null) {
			m.add(Bridge.item("Keep in Shelf (open it from here when it is closed)", () -> {
				Shelf.checkpoint();
				pinTile(t,launcherFor(t.window));
				Shelf.save();
				rebuild();
			}));
			m.add(Bridge.item("Rename, Initials or Icon... (keeps it in the shelf)", () -> {
				Item it = launcherFor(t.window);
				if (!editLook(it)) return; // editLook saves the undo checkpoint
				pinTile(t,it);
				Shelf.save();
				rebuild();
			}));
		} else {
			m.add(Bridge.item("Edit Launcher...", () -> editLauncher(t.section,t.item)));
			m.add(Bridge.item("Rename, Initials or Icon...", () -> { if (editLook(t.item)) { Shelf.save(); rebuild(); } }));
			m.add(Bridge.item("Remove from Shelf", () -> {
				Shelf.checkpoint();
				String old=ShelfOrder.windowKey(t);if(t.window!=null)replaceOrderKey(t.section,old,"window:"+titleOf(t.window));
				t.section.items.remove(t.item);
				Shelf.save();
				rebuild();
			}));
		}
		addMoveItems(m,t);
		m.add(Bridge.item("Hide from This Shelf (keep window open)",()->hideTile(t)));
	}

	/** A pinned launcher for an open tool window: its menu command (with or without "..."), its title, its initials. */
	private static Item launcherFor(Window w) {
		String title = titleOf(w);
		if (w instanceof ij.plugin.frame.Recorder) return Shelf.launcher("Recorder", title, "Rec", "");
		for(Shelf s:Shelf.defaults())for(Shelf.Section c:s.sections)for(Item item:c.items)if(item.args.equals(title))return item.copy();
		String cmd = Menus.getCommands() != null && Menus.getCommands().get(title + "...") != null ? title + "..." : title;
		return Shelf.launcher(cmd, title, Icons.initials(title), "");
	}

	private void macroMenu(JPopupMenu m, final Tile t) {
		header(m, t.item.title());
		m.add(Bridge.item("Run...", () -> MacroShelf.run(t.item)));
		m.add(Bridge.item("Run on Images... (choose)", () -> MacroShelf.choose(t.item)));
		m.add(Bridge.item("Edit Macro", () -> IJ.open(t.item.target)));
		m.add(Bridge.item("Edit Button and Macro File...", () -> Editor.editShelf(t.item)));
		m.add(Bridge.item("Rename, Initials or Icon...", () -> { if (editLook(t.item)) { Shelf.save(); rebuild(); } }));
		m.add(Bridge.item("Remove from Shelf (the file stays)", () -> {
			Shelf.checkpoint();
			t.section.items.remove(t.item);
			Shelf.save();
			rebuild();
		}));
		addMoveItems(m,t);
	}

	private void addMoveItems(JPopupMenu m, final Tile tile) {
		List<Tile> order=tilesOf(tile.section);order.removeIf(t->t.kind.equals("add"));int at=-1;for(int n=0;n<order.size();n++)if(ShelfOrder.same(tile,order.get(n)))at=n;
		final int i=at;
		javax.swing.JMenuItem a = Bridge.item("Move Earlier", () -> moveTile(tile,-1));
		a.setEnabled(i > 0);
		javax.swing.JMenuItem b = Bridge.item("Move Later", () -> moveTile(tile,1));
		b.setEnabled(i >= 0 && i < order.size() - 1);
		m.add(a);
		m.add(b);
	}

	private void moveTile(Tile tile,int delta){
		List<Tile> order=tilesOf(tile.section);order.removeIf(t->t.kind.equals("add"));int i=-1;
		for(int n=0;n<order.size();n++)if(ShelfOrder.same(tile,order.get(n)))i=n;
		if(i<0||i+delta<0||i+delta>=order.size())return;Shelf.checkpoint();java.util.Collections.swap(order,i,i+delta);ShelfOrder.apply(tile.section,order);Shelf.save();rebuild();
	}
	private void hideTile(Tile tile){Shelf.checkpoint();if(tile.imp!=null)tile.section.hiddenImages.put(tile.imp,true);else tile.section.hiddenWindows.add(ShelfOrder.windowKey(tile));Shelf.save();rebuild();}
	private void pinTile(Tile tile,Item item){String old=ShelfOrder.windowKey(tile);tile.section.items.add(item);Tile pinned=new Tile();pinned.section=tile.section;pinned.item=item;replaceOrderKey(tile.section,old,ShelfOrder.windowKey(pinned));}
	private static void replaceOrderKey(Shelf.Section section,String old,String replacement){for(int i=0;i<section.windowOrder.size();i++)if(section.windowOrder.get(i).equals(old))section.windowOrder.set(i,replacement);}
	private void editImageLook(Tile tile){
		Item look=tile.item==null?new Item(Item.Kind.COMMAND,""):tile.item.copy();look.label=nameOf(tile.imp);
		if(!editLook(look))return;tile.section.imageLooks.put(tile.imp,look);imageNames.put(tile.imp,look.label);rebuild();
	}
	private void editLauncher(Shelf.Section section,Item item){
		ij.gui.GenericDialog dialog=new ij.gui.GenericDialog(item==null?"Add Tool Launcher":"Edit Tool Launcher");
		dialog.addStringField("Name:",item==null?"My tool":item.label,28);
		dialog.addStringField("Command:",item==null?"ROI Manager...":item.target,28);
		dialog.addStringField("Window title:",item==null?"ROI Manager":item.args,28);
		dialog.addButton("Choose Command...",event->{String chosen=Editor.chooseCommand(dialog);if(chosen==null)return;
			((java.awt.TextField)dialog.getStringFields().get(1)).setText(chosen);
			if(item==null){String title=chosen.replaceAll("\\.\\.\\.$","");for(Shelf s:Shelf.defaults())for(Shelf.Section c:s.sections)for(Item entry:c.items)if(entry.target.equals(chosen))title=entry.args;
				((java.awt.TextField)dialog.getStringFields().get(0)).setText(title);((java.awt.TextField)dialog.getStringFields().get(2)).setText(title);}});
		dialog.addMessage("Command opens the tool when closed; Window title identifies its open window.\nRight-click the button to choose its initials, icon and colour.");
		dialog.showDialog();if(dialog.wasCanceled())return;
		String label=dialog.getNextString().trim(),command=dialog.getNextString().trim(),title=dialog.getNextString().trim();
		if(command.isEmpty()||title.isEmpty()){IJ.error("Tool Launcher","Command and window title are required.");return;}
		Shelf.checkpoint();if(item==null)section.items.add(Shelf.launcher(command,title,label.isEmpty()?title:label,""));
		else{item.label=label;item.target=command;item.args=title;}
		section.hiddenWindows.remove("window:"+title);Shelf.save();rebuild();
	}

	private void addMenu(final Shelf.Section c, MouseEvent e) {
		if(c==null)return;
		if(c.kind.equals(Shelf.WINDOWS)){editLauncher(c,null);return;}
		if(c.kind.equals(Shelf.IMAGES)){IJ.doCommand("Open...");return;}
		JPopupMenu m = new JPopupMenu();
		m.add(Bridge.item("Add Macro File (.ijm)...", () -> MacroShelf.addFile(c)));
		m.add(Bridge.item("New Macro...", () -> MacroShelf.newMacro(c)));
		m.show(this, e.getX(), e.getY());
	}

	/** Name, letters or emoji, and colour of a shelf button (or an icon from the gallery). */
	static boolean editLook(Item it) {
		ij.gui.GenericDialog gd = new ij.gui.GenericDialog("Shelf Button");
		gd.addStringField("Name:", it.label, 24);
		gd.addStringField("Letters or emoji:", it.icon.startsWith("text:") ? it.icon.substring(5) : "", 6);
		gd.addStringField("Colour (#rrggbb, empty = automatic):", it.color, 8);
		gd.addCheckbox("Choose the icon from the gallery instead", false);
		gd.addMessage("Empty letters: the initials of the name, on a coloured square.");
		gd.showDialog();
		if (gd.wasCanceled()) return false;
		Shelf.checkpoint();
		it.label = gd.getNextString().trim();
		String text = gd.getNextString().trim();
		it.icon = text.isEmpty() ? "" : "text:" + text;
		String col = gd.getNextString().trim();
		it.color = Icons.parseColor(col) != null ? col : "";
		if (gd.getNextBoolean()) {
			String spec = Gallery.choose(IJ.getInstance(), it);
			if (spec != null) it.icon = spec;
		}
		return true;
	}

	private static void header(JPopupMenu m, String text) {
		JLabel h = new JLabel(text);
		h.setFont(h.getFont().deriveFont(Font.BOLD, 11f));
		h.setForeground(new Color(110, 110, 110));
		h.setBorder(new EmptyBorder(2, 10, 2, 10));
		m.add(h);
	}

	private void sectionMenu(JPopupMenu m, final Shelf.Section c) {
		if(c.kind.equals(Shelf.WINDOWS))m.add(Bridge.item("Add Tool Launcher...",()->editLauncher(c,null)));
		if(c.kind.equals(Shelf.IMAGES))m.add(Bridge.item("Open Image...",()->IJ.doCommand("Open...")));
		if(c.kind.equals(Shelf.MACROS)){m.add(Bridge.item("Add Macro File...",()->MacroShelf.addFile(c)));m.add(Bridge.item("New Macro...",()->MacroShelf.newMacro(c)));}
		if(!c.hiddenWindows.isEmpty()||!c.hiddenImages.isEmpty())m.add(Bridge.item("Show Hidden "+c.title(),()->{Shelf.checkpoint();c.hiddenWindows.clear();c.hiddenImages.clear();Shelf.save();rebuild();}));
		JMenu move = new JMenu(c.title() + " Section");
		String[][] places = {{"bottom", "To the Bottom Bar"}, {"top", "To a Bar at the Top"}, {"left", "As a Column beside the Toolbar"},
			{"right", "As a Column at the Right Edge"}, {"float", "To a New Floating Bar"}};
		for (final String[] p : places)
			if (!p[0].equals(shelf.place) || p[0].equals("float")) {
				javax.swing.JMenuItem mi = Bridge.item("Move " + p[1], () -> Shelf.moveSection(c, p[0]));
				mi.setIcon(PlaceIcon.bar(p[0]));
				move.add(mi);
			}
		final int i = shelf.sections.indexOf(c);
		if (shelf.shown().size() > 1) {
			move.addSeparator();
			javax.swing.JMenuItem a = Bridge.item("Move Left", () -> {
				Shelf.checkpoint(); java.util.Collections.swap(shelf.sections, i, i - 1); Shelf.save(); Shelf.showAll(); });
			a.setEnabled(i > 0);
			javax.swing.JMenuItem b = Bridge.item("Move Right", () -> {
				Shelf.checkpoint(); java.util.Collections.swap(shelf.sections, i, i + 1); Shelf.save(); Shelf.showAll(); });
			b.setEnabled(i < shelf.sections.size() - 1);
			move.add(a);
			move.add(b);
		}
		move.addSeparator();
		move.add(Bridge.item("Hide " + c.title() + " Section", () -> {
			Shelf.checkpoint(); c.hidden = true; Shelf.cleanUp(); Shelf.save(); Shelf.showAll(); }));
		m.add(move);
	}

	private void shelfMenu(JPopupMenu m) {
		JMenu bar = new JMenu("This Bar");
		String[][] places = {{"bottom", "At the Bottom"}, {"top", "At the Top"}, {"left", "Beside the Toolbar (columns)"},
			{"right", "At the Right Edge (columns)"}, {"float", "Floating (drag it anywhere)"}};
		for (final String[] p : places) {
			javax.swing.JCheckBoxMenuItem ci = new javax.swing.JCheckBoxMenuItem(p[1], PlaceIcon.bar(p[0]), shelf.place.equals(p[0]));
			ci.addActionListener(ev -> Shelf.moveShelf(shelf, p[0]));
			bar.add(ci);
		}
		bar.addSeparator();
		for (final Shelf.Section c : Shelf.hiddenSections())
			bar.add(Bridge.item("Show " + c.title() + " Section", () -> {
				Shelf.checkpoint();
				if (c.hidden) c.hidden = false; else shelf.sections.add(c);
				Shelf.save(); Shelf.showAll(); }));
		bar.add(Bridge.item("Bar Size and Options...", () -> Side_Toolbar.settings()));
		javax.swing.JMenuItem u = Bridge.item("Undo Bar Change", () -> Shelf.undo());
		u.setEnabled(Shelf.canUndo());
		bar.add(u);
		m.add(bar);
	}

	// ---------------------------------------------------------------- drops: macros become buttons, images open

	private boolean dropHint;

	private class Drops extends DropTargetAdapter {
		public void dragOver(DropTargetDragEvent e) {
			if (!e.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) { e.rejectDrag(); return; }
			e.acceptDrag(DnDConstants.ACTION_COPY);
			if (!dropHint) { dropHint = true; repaint(); }
		}

		public void dragExit(java.awt.dnd.DropTargetEvent e) { dropHint = false; repaint(); }

		@SuppressWarnings("unchecked")
		public void drop(DropTargetDropEvent e) {
			dropHint = false;
			repaint();
			try {
				e.acceptDrop(DnDConstants.ACTION_COPY);
				List<File> files = (List<File>) e.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
				Shelf.Section macros = null;
				for (Shelf.Section c : shelf.shown()) if (c.kind.equals(Shelf.MACROS)) macros = c;
				List<Object> open = new ArrayList<Object>();
				boolean added = false;
				for (File f : files) {
					if (f.getName().toLowerCase().endsWith(".ijm") && macros != null) {
						if (!added) Shelf.checkpoint();
						macros.items.add(MacroShelf.itemFor(f));
						added = true;
					} else open.add(f);
				}
				e.dropComplete(true);
				if (added) { Shelf.save(); rebuild(); }
				if (!open.isEmpty()) Dock.openInFiji(open);
			} catch (Exception ex) {
				e.dropComplete(false);
			}
		}
	}
}
