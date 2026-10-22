package sidetoolbar;

import ij.IJ;
import ij.Prefs;

import java.awt.Rectangle;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * A shelf is a bar of sections (open tool windows, image previews, macro buttons). It is docked along an edge of
 * the screen (bottom, top, left or right: beside the side toolbar it adds columns) or floats where it was dragged.
 * Shelves and their sections are saved to IJ_Side_Toolbar_Shelves.txt after every change.
 */
final class Shelf {

	static final String[] PLACES = {"bottom", "top", "left", "right", "float"};

	/** Kinds of section: open and pinned tool windows, image previews, macro buttons. */
	static final String WINDOWS = "windows", IMAGES = "images", MACROS = "macros";

	static final class Section {
		final String kind;
		final List<Item> items = new ArrayList<Item>(); // pinned windows (target = command, args = window title) or macros
		boolean hidden;                                  // kept (with its macros) but not shown
		Section(String kind) { this.kind = kind; }

		String title() {
			return kind.equals(WINDOWS) ? "Tools" : kind.equals(IMAGES) ? "Images" : "Macros";
		}
	}

	String place = "bottom";
	int x = 200, y = 200;               // floating shelves only
	final List<Section> sections = new ArrayList<Section>();
	ShelfView view;                      // the window showing it, while shown

	boolean horizontal() { return place.equals("bottom") || place.equals("top") || place.equals("float"); }

	boolean docked() { return !place.equals("float"); }

	/** Height of a horizontal shelf, or width of each column of a vertical one. */
	static int size() { return Math.max(36, Math.min(120, (int) Prefs.get("sidetoolbar.shelfsize", 56))); }

	static boolean enabled() { return Prefs.get("sidetoolbar.shelves", true); }

	static boolean folderMacros() { return Prefs.get("sidetoolbar.foldermacros", false); }

	// ---------------------------------------------------------------- all shelves, saved

	static final List<Shelf> all = new ArrayList<Shelf>();
	private static boolean loaded;
	private static final List<String> undo = new ArrayList<String>();

	static File file() {
		String dir = Prefs.getPrefsDir();
		return new File(dir != null ? dir : System.getProperty("user.home"), "IJ_Side_Toolbar_Shelves.txt");
	}

	/** The default: one bar at the bottom with tools on the left, images in the centre, macros on the right. */
	static List<Shelf> defaults() {
		Shelf s = new Shelf();
		s.place = "bottom";
		Section w = new Section(WINDOWS);
		w.items.add(launcher("ROI Manager...", "ROI Manager", "ROI", "#2d6ec8"));
		w.items.add(launcher("Brightness/Contrast...", "B&C", "B&C", "#5a5a5a"));
		w.items.add(launcher("Channels Tool...", "Channels", "Ch", "#8a3fb0"));
		w.items.add(launcher("results", "Results", "Res", "#2e8b57"));
		w.items.add(launcher("log", "Log", "Log", "#7a6a3a"));
		w.items.add(launcher("Undo History Window", "Undo History", "↶", "#2d6ec8"));
		s.sections.add(w);
		s.sections.add(new Section(IMAGES));
		s.sections.add(new Section(MACROS));
		List<Shelf> l = new ArrayList<Shelf>();
		l.add(s);
		return l;
	}

	static Item launcher(String command, String windowTitle, String text, String color) {
		Item it = new Item(Item.Kind.COMMAND, command);
		it.args = windowTitle;
		it.label = windowTitle;
		it.icon = "text:" + text;
		it.color = color;
		return it;
	}

	static void load() {
		if (loaded) return;
		loaded = true;
		all.clear();
		File f = file();
		if (f.exists()) {
			try { all.addAll(parse(Store.read(f))); }
			catch (Exception e) { IJ.log("Side Toolbar: could not read " + f + " (" + e + "); using the default bottom bar."); }
		}
		if (all.isEmpty()) all.addAll(defaults());
	}

	static String serialize() {
		StringBuilder b = new StringBuilder("# Fiji Side Toolbar shelves, version 1. Edit with Fiji closed.\n");
		for (Shelf s : all) {
			b.append("shelf\tplace=").append(s.place).append("\tx=").append(s.x).append("\ty=").append(s.y).append('\n');
			for (Section c : s.sections) {
				b.append("  section\tkind=").append(c.kind).append(c.hidden ? "\thidden=1" : "").append('\n');
				for (Item it : c.items) it.write(b, "    ");
				b.append("  endsection\n");
			}
			b.append("endshelf\n");
		}
		return b.toString();
	}

	static List<Shelf> parse(String text) {
		List<Shelf> out = new ArrayList<Shelf>();
		Shelf cur = null;
		Section sec = null;
		for (String line : text.split("\n")) {
			String t = line.trim();
			if (t.isEmpty() || t.startsWith("#")) continue;
			String[] p = t.split("\t");
			if (p[0].equals("shelf")) {
				cur = new Shelf();
				for (int i = 1; i < p.length; i++) {
					String[] kv = p[i].split("=", 2);
					if (kv.length < 2) continue;
					if (kv[0].equals("place")) cur.place = kv[1];
					else if (kv[0].equals("x")) cur.x = Integer.parseInt(kv[1]);
					else if (kv[0].equals("y")) cur.y = Integer.parseInt(kv[1]);
				}
				out.add(cur);
			} else if (p[0].equals("section") && cur != null) {
				String kind = p.length > 1 && p[1].startsWith("kind=") ? p[1].substring(5) : MACROS;
				sec = new Section(kind);
				sec.hidden = t.contains("\thidden=1");
				cur.sections.add(sec);
			} else if (p[0].equals("endsection")) {
				sec = null;
			} else if (p[0].equals("endshelf")) {
				cur = null;
			} else if (sec != null) {
				Item it = Item.parseLine(t);
				if (it != null) sec.items.add(it);
			}
		}
		return out;
	}

	static void save() {
		try { Store.write(file(), serialize()); }
		catch (Exception e) { IJ.log("Side Toolbar: could not save " + file() + ": " + e); }
	}

	static void checkpoint() {
		undo.add(serialize());
		if (undo.size() > 50) undo.remove(0);
	}

	static boolean canUndo() { return !undo.isEmpty(); }

	static void undo() {
		if (undo.isEmpty()) return;
		String text = undo.remove(undo.size() - 1);
		for (Shelf s : all) if (s.view != null) s.view.dispose();
		all.clear();
		all.addAll(parse(text));
		save();
		showAll();
	}

	/** The shelf docked at a place, or null. */
	static Shelf at(String place) {
		for (Shelf s : all) if (s.place.equals(place)) return s;
		return null;
	}

	/** Moves a section to the shelf at a place (made if needed); "float" always makes a new floating shelf. */
	static void moveSection(Section c, String place) {
		checkpoint();
		Shelf from = null;
		for (Shelf s : all) if (s.sections.contains(c)) from = s;
		if (from != null) from.sections.remove(c);
		Shelf to = place.equals("float") ? null : at(place);
		if (to == null) {
			to = new Shelf();
			to.place = place;
			if (from != null && from.view != null) {
				Rectangle b = from.view.window.getBounds();
				to.x = b.x + 40;
				to.y = Math.max(40, b.y - size() - 40);
			}
			all.add(to);
		}
		to.sections.add(c);
		cleanUp();
		save();
		showAll();
	}

	/** A floating bar dropped on a docked bar: its sections join that bar at the given index. */
	static void merge(Shelf from, Shelf to, int index) {
		checkpoint();
		int at = Math.max(0, Math.min(index, to.sections.size()));
		to.sections.addAll(at, from.sections);
		from.sections.clear();
		cleanUp();
		save();
		showAll();
	}

	/** Moves a whole shelf to another place. */
	static void moveShelf(Shelf s, String place) {
		checkpoint();
		Shelf there = place.equals("float") ? null : at(place);
		if (there != null && there != s) {
			there.sections.addAll(s.sections); // two bars at one edge become one
			s.sections.clear();
		} else s.place = place;
		cleanUp();
		save();
		showAll();
	}

	static void cleanUp() {
		for (int i = all.size() - 1; i >= 0; i--) {
			Shelf s = all.get(i);
			if (s.sections.isEmpty() || s.shown().isEmpty() && all.size() > 1 && !s.place.equals("bottom")) {
				// an empty bar goes away; its hidden sections move to another bar so they are not lost
				if (!s.sections.isEmpty()) for (Shelf o : all) if (o != s) { o.sections.addAll(s.sections); break; }
				if (s.view != null) s.view.dispose();
				all.remove(i);
			}
		}
	}

	/** Shows every shelf (or removes them when shelves are off) and lets Chrome place them. */
	static void showAll() {
		load();
		for (Shelf s : all) {
			if (!enabled()) { if (s.view != null) s.view.dispose(); continue; }
			if (s.view == null) s.view = new ShelfView(s);
			s.view.rebuild();
		}
		Object before = copyBands();
		Chrome.apply();
		OnTop.update();
		// windows move only when a docked bar took or gave back room
		if (!String.valueOf(before).equals(String.valueOf(copyBands()))) Side_Toolbar.rearrangeWindows();
	}

	private static Object copyBands() {
		Object o = System.getProperties().get(Chrome.RESERVED_ALL);
		return o instanceof List ? new ArrayList<Object>((List<?>) o) : null;
	}

	static void hideAll() {
		for (Shelf s : all) if (s.view != null) s.view.dispose();
	}

	/** Hidden sections, and kinds of section that no shelf has (to add them again). */
	static List<Section> hiddenSections() {
		List<String> have = new ArrayList<String>();
		List<Section> l = new ArrayList<Section>();
		for (Shelf s : all) for (Section c : s.sections) { have.add(c.kind); if (c.hidden) l.add(c); }
		for (String k : new String[] {WINDOWS, IMAGES, MACROS}) if (!have.contains(k)) l.add(new Section(k));
		return l;
	}

	List<Section> shown() {
		List<Section> l = new ArrayList<Section>();
		for (Section c : sections) if (!c.hidden) l.add(c);
		return l;
	}
}
