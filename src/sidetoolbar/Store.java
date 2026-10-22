package sidetoolbar;

import ij.IJ;
import ij.Prefs;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The toolbar's contents, saved after every change to a plain-text file next to IJ_Prefs.txt, with an undo history.
 * Settings (side, size, ...) live in IJ_Prefs.txt under "sidetoolbar.*".
 */
final class Store {

	static final String[] BUILTIN = {"rectangle", "oval", "polygon", "freehand", "line", "angle",
		"point", "wand", "text", "zoom", "hand", "dropper"};

	static final List<Item> items = new ArrayList<Item>();
	/** Names of toolbar tools the user removed: they are not added back when ImageJ loads them again. */
	static final Set<String> dismissed = new LinkedHashSet<String>();
	private static final List<String[]> undo = new ArrayList<String[]>(); // {description, saved text}
	private static boolean loaded;

	static File file() {
		String dir = Prefs.getPrefsDir();
		return new File(dir != null ? dir : System.getProperty("user.home"), "IJ_Side_Toolbar.txt");
	}

	// ---------------------------------------------------------------- settings

	static String side() { return Prefs.get("sidetoolbar.side", "left"); }
	static int buttonSize() { return Math.max(20, Math.min(64, (int) Prefs.get("sidetoolbar.size", 32))); }
	static int columns() { return Math.max(0, Math.min(4, (int) Prefs.get("sidetoolbar.columns", 1))); } // 0 = automatic
	static boolean labels() { return Prefs.get("sidetoolbar.labels", false); }
	static String status() { return Prefs.get("sidetoolbar.status", "top"); }           // top, bottom, classic
	static boolean onTop() { return Prefs.get("sidetoolbar.ontop", true); }

	static void persistPrefs() {
		try { Prefs.savePreferences(); } catch (Throwable ignored) { }
	}

	// ---------------------------------------------------------------- contents

	static List<Item> defaults() {
		List<Item> l = new ArrayList<Item>();
		for (String t : BUILTIN) l.add(Item.tool(t));
		l.add(new Item(Item.Kind.SEPARATOR, ""));
		l.add(new Item(Item.Kind.MORE, ""));
		return l;
	}

	static void load() {
		if (loaded) return;
		loaded = true;
		items.clear();
		dismissed.clear();
		File f = file();
		if (f.exists()) {
			try {
				String text = read(f);
				items.addAll(Item.parse(text));
				for (String line : text.split("\n"))
					if (line.startsWith("#dismissed\t")) dismissed.add(Item.unescape(line.substring(11)));
			} catch (Exception e) {
				IJ.log("Side Toolbar: could not read " + f + " (" + e + "); using the default toolbar.");
				items.clear();
			}
		}
		if (items.isEmpty()) items.addAll(defaults());
	}

	static String serialize() {
		StringBuilder b = new StringBuilder("# Fiji Side Toolbar, version 1. One button per line; edit with Fiji closed.\n");
		b.append(Item.write(items));
		for (String d : dismissed) b.append("#dismissed\t").append(Item.escape(d)).append('\n');
		return b.toString();
	}

	static void save() {
		try {
			write(file(), serialize());
		} catch (IOException e) {
			IJ.log("Side Toolbar: could not save " + file() + ": " + e);
		}
	}

	/** Call before every change: remembers the current contents so the change can be undone. */
	static void checkpoint(String description) {
		undo.add(new String[] {description, serialize()});
		if (undo.size() > 100) undo.remove(0);
	}

	/** Remembers contents saved before a change that is already made. */
	static void pushUndo(String description, String before) {
		undo.add(new String[] {description, before});
		if (undo.size() > 100) undo.remove(0);
	}

	static String undoDescription() { return undo.isEmpty() ? null : undo.get(undo.size() - 1)[0]; }

	static boolean undo() {
		if (undo.isEmpty()) return false;
		restore(undo.remove(undo.size() - 1)[1]);
		save();
		return true;
	}

	static void restore(String text) {
		items.clear();
		dismissed.clear();
		items.addAll(Item.parse(text));
		for (String line : text.split("\n"))
			if (line.startsWith("#dismissed\t")) dismissed.add(Item.unescape(line.substring(11)));
		if (items.isEmpty()) items.addAll(defaults());
	}

	// ---------------------------------------------------------------- tree helpers

	/** The list that directly contains it (the top level or a group's children), or null. */
	static List<Item> parentOf(Item it) { return parentOf(items, it); }

	private static List<Item> parentOf(List<Item> list, Item it) {
		for (Item i : list) {
			if (i == it) return list;
			if (i.kind == Item.Kind.GROUP) {
				List<Item> p = parentOf(i.children, it);
				if (p != null) return p;
			}
		}
		return null;
	}

	static boolean containsSlot(List<Item> list, String name) {
		for (Item i : list) {
			if (i.kind == Item.Kind.SLOT && i.target.equals(name)) return true;
			if (i.kind == Item.Kind.GROUP && containsSlot(i.children, name)) return true;
		}
		return false;
	}

	static void remove(Item it) {
		List<Item> p = parentOf(it);
		if (p != null) p.remove(it);
		if (it.kind == Item.Kind.SLOT) dismissed.add(it.target);
	}

	/**
	 * Adds the tools ImageJ's toolbar has gained (a toolset was loaded, a plugin installed its tool) and that are
	 * neither on the side toolbar nor removed by the user, just before the ">>" button. Returns true if any was added.
	 */
	static boolean sync(List<String> slotNames) {
		boolean changed = false;
		for (String n : slotNames) {
			if (n == null || containsSlot(items, n) || dismissed.contains(n)) continue;
			int at = items.size();
			for (int i = 0; i < items.size(); i++) if (items.get(i).kind == Item.Kind.MORE) { at = i; break; }
			items.add(at, new Item(Item.Kind.SLOT, n));
			changed = true;
		}
		return changed;
	}

	// ---------------------------------------------------------------- files

	static String read(File f) throws IOException {
		InputStream in = new FileInputStream(f);
		try {
			byte[] buf = new byte[(int) f.length()];
			int n = 0;
			while (n < buf.length) {
				int r = in.read(buf, n, buf.length - n);
				if (r < 0) break;
				n += r;
			}
			return new String(buf, 0, n, "UTF-8");
		} finally { in.close(); }
	}

	/** Writes through a temporary file, so a crash never leaves a half-written configuration. */
	static void write(File f, String text) throws IOException {
		if (f.getParentFile() != null) f.getParentFile().mkdirs();
		File tmp = new File(f.getPath() + ".tmp");
		OutputStream out = new FileOutputStream(tmp);
		try { out.write(text.getBytes("UTF-8")); } finally { out.close(); }
		if (!tmp.renameTo(f)) {
			f.delete();
			if (!tmp.renameTo(f)) throw new IOException("cannot replace " + f);
		}
	}
}
