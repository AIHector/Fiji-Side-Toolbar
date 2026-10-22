package sidetoolbar;

import ij.IJ;
import ij.ImageJ;
import ij.gui.Toolbar;

import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Development-only live-test harness (NOT installed). Started by a sandbox AutoRun macro:
 * call("sidetoolbar.TestHarness.start", dir). Polls dir/cmd.ijm, runs it, writes dir/done.txt.
 */
public class TestHarness {
	private static Timer timer;
	private static String dir;

	static StringBuilder keys = new StringBuilder();

	public static String start(String d) {
		dir = d;
		java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(e -> {
			java.awt.event.KeyEvent k = (java.awt.event.KeyEvent) e;
			if (keys.length() < 400) keys.append(k.getID() == java.awt.event.KeyEvent.KEY_TYPED ? "T" : k.getID() == java.awt.event.KeyEvent.KEY_PRESSED ? "P" : "R")
				.append(k.getKeyChar() >= 32 && k.getKeyChar() < 127 ? k.getKeyChar() : '?').append(k.isConsumed() ? "c" : "").append(' ');
		}, java.awt.AWTEvent.KEY_EVENT_MASK);
		SwingUtilities.invokeLater(() -> {
			timer = new Timer(250, e -> poll());
			timer.start();
		});
		return "started";
	}

	static void poll() {
		File cmd = new File(dir, "cmd.ijm");
		if (!cmd.exists()) return;
		File run = new File(dir, "running.ijm");
		run.delete();
		if (!cmd.renameTo(run)) return;
		new Thread(() -> {
			String out;
			try { out = "OK " + IJ.runMacroFile(run.getPath()); }
			catch (Throwable t) { out = "EXC " + t; }
			write(new File(dir, "done.txt"), out + "\n");
		}, "st-test").start();
	}

	static void write(File f, String s) {
		try (FileWriter w = new FileWriter(f)) { w.write(s); } catch (IOException ignored) { }
	}

	static String r(Rectangle b) { return b == null ? "null" : b.x + "," + b.y + "," + b.width + "x" + b.height; }

	/** One line per fact: windows, dock entries with their rectangles, tool state. */
	public static String dump(String path) {
		StringBuilder sb = new StringBuilder();
		try {
			SwingUtilities.invokeAndWait(() -> {
				ImageJ ij = IJ.getInstance();
				sb.append("ij ").append(r(ij.getBounds())).append(" undecorated=").append(ij.isUndecorated())
					.append(" visible=").append(ij.isVisible()).append('\n');
				sb.append("toolbarVisible=").append(Toolbar.getInstance().isVisible())
					.append(" current=").append(Bridge.current()).append(" name=").append(Toolbar.getToolName()).append('\n');
				sb.append("usable ").append(r(Chrome.usable(Chrome.screen()))).append('\n');
				sb.append("reserved ").append(System.getProperties().get(Chrome.RESERVED)).append('\n');
				Dock d = Dock.instance;
				if (d != null) {
					sb.append("dock ").append(r(d.window.getBounds())).append(" visible=").append(d.window.isVisible())
						.append(" onTop=").append(d.window.isAlwaysOnTop()).append(" focusable=").append(d.window.getFocusableWindowState()).append('\n');
					for (Item it : d.visible()) {
						Rectangle b = d.shownRect(it);
						sb.append("item ").append(it.kind).append(" '").append(it.target).append("' ").append(r(b))
							.append(Bridge.isCurrent(it) ? " CURRENT" : "").append(Bridge.available(it) ? "" : " UNAVAILABLE").append('\n');
					}
				} else sb.append("dock none\n");
				for (String n : Bridge.slotNames()) sb.append("slot ").append(n).append('\n');
				for (Window w : Window.getWindows())
					if (w.isVisible()) sb.append("window ").append(w.getClass().getSimpleName()).append(' ').append(r(w.getBounds()))
						.append(w instanceof java.awt.Frame ? " '" + ((java.awt.Frame) w).getTitle() + "'" : "")
						.append(w instanceof java.awt.Dialog ? " '" + ((java.awt.Dialog) w).getTitle() + "'" : "").append('\n');
			});
		} catch (Exception e) { sb.append("EXC ").append(e); }
		write(new File(path), sb.toString());
		return "dumped";
	}

	/** Synthesized mouse event on the dock, at dock coordinates. kind: press, drag, release, move, rpress. */
	public static String mouse(String kind, String xs, String ys) {
		int x = (int) Double.parseDouble(xs), y = (int) Double.parseDouble(ys);
		try {
			SwingUtilities.invokeAndWait(() -> {
				Dock d = Dock.instance;
				long t = System.currentTimeMillis();
				int id; int mods; boolean popup = false; int button = MouseEvent.BUTTON1;
				switch (kind) {
					case "press": id = MouseEvent.MOUSE_PRESSED; mods = InputEvent.BUTTON1_DOWN_MASK; break;
					case "rpress": id = MouseEvent.MOUSE_PRESSED; mods = InputEvent.BUTTON3_DOWN_MASK; popup = true; button = MouseEvent.BUTTON3; break;
					case "drag": id = MouseEvent.MOUSE_DRAGGED; mods = InputEvent.BUTTON1_DOWN_MASK; break;
					case "release": id = MouseEvent.MOUSE_RELEASED; mods = 0; break;
					default: id = MouseEvent.MOUSE_MOVED; mods = 0; button = MouseEvent.NOBUTTON;
				}
				d.dispatchEvent(new MouseEvent(d, id, t, mods, x, y, 1, popup, button));
			});
		} catch (Exception e) { return "EXC " + e; }
		return "ok";
	}

	/** Centre of the n-th visible entry, as "x,y" in dock coordinates. */
	public static String center(String n) {
		final String[] out = {""};
		try {
			SwingUtilities.invokeAndWait(() -> {
				Dock d = Dock.instance;
				Rectangle b = d.shownRect(d.visible().get(Integer.parseInt(n)));
				out[0] = (b.x + b.width / 2) + "," + (b.y + b.height / 2);
			});
		} catch (Exception e) { return "EXC " + e; }
		return out[0];
	}

	static String name(Window w) {
		if (w == null) return "null";
		if (w instanceof java.awt.Frame) return "Frame:" + ((java.awt.Frame) w).getTitle();
		if (w instanceof java.awt.Dialog) return "Dialog:" + ((java.awt.Dialog) w).getTitle() + " focusable=" + w.isFocusableWindow();
		return w.getClass().getSimpleName();
	}

	static void tree(java.awt.Component c, String ind, StringBuilder b) {
		b.append(ind).append(c.getClass().getName()).append(' ').append(r(c.getBounds())).append(c.isVisible() ? "" : " HIDDEN")
			.append(" pref=").append(c.getPreferredSize().width).append('x').append(c.getPreferredSize().height).append('\n');
		if (c instanceof java.awt.Container) for (java.awt.Component k : ((java.awt.Container) c).getComponents()) tree(k, ind + "  ", b);
	}

	/** Real OS mouse events (java.awt.Robot) at screen coordinates: "click x y" or "drag x1 y1 x2 y2". */
	public static String robot(String spec) {
		try {
			java.awt.Robot r = new java.awt.Robot();
			r.setAutoDelay(15);
			String[] a = spec.split(" ");
			int x = Integer.parseInt(a[1]), y = Integer.parseInt(a[2]);
			r.mouseMove(x, y);
			r.delay(150);
			if (a[0].equals("move")) return "moved";
			r.mousePress(a[0].equals("rclick") ? InputEvent.BUTTON3_DOWN_MASK : InputEvent.BUTTON1_DOWN_MASK);
			if (a[0].equals("drag")) {
				int x2 = Integer.parseInt(a[3]), y2 = Integer.parseInt(a[4]);
				for (int k = 1; k <= 20; k++) r.mouseMove(x + (x2 - x) * k / 20, y + (y2 - y) * k / 20);
				r.delay(200);
			}
			r.mouseRelease(a[0].equals("rclick") ? InputEvent.BUTTON3_DOWN_MASK : InputEvent.BUTTON1_DOWN_MASK);
			r.delay(200);
			java.awt.Window fw = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow();
			return "focused=" + (fw instanceof java.awt.Frame ? ((java.awt.Frame) fw).getTitle() : String.valueOf(fw));
		} catch (Throwable t) { return "EXC " + t; }
	}

	public static String eval(String what) {
		final String[] out = {""};
		try {
			SwingUtilities.invokeAndWait(() -> {
				if (what.equals("undo")) Dock.instance.undo();
				else if (what.equals("serialize")) out[0] = Store.serialize();
				else if (what.startsWith("add:")) Dock.instance.insert(Item.parse(what.substring(4).replace("\\t", "\t").replace("|", "\n")), null, -1);
				else if (what.startsWith("edit:")) { Item it = Dock.instance.visible().get(Integer.parseInt(what.substring(5))); SwingUtilities.invokeLater(() -> Editor.edit(it)); }
				else if (what.startsWith("adddialog")) SwingUtilities.invokeLater(() -> Editor.showAdd(-1));
				else if (what.equals("settings")) SwingUtilities.invokeLater(() -> Side_Toolbar.settings());
				else if (what.equals("emoji")) {
					Item it = new Item(Item.Kind.MACRO, "print(\"hello\");");
					it.label = "Microscope"; it.icon = "text:\ud83d\udd2c";
					Dock.instance.insert(Dock.single(it), null, -1);
				}
				else if (what.equals("gallery")) SwingUtilities.invokeLater(() -> Gallery.choose(IJ.getInstance(), new Item(Item.Kind.COMMAND, "Invert")));
				else if (what.equals("closewindows")) {
					for (Window w : Window.getWindows()) if (w.isVisible() && (w instanceof java.awt.Dialog)) w.dispose();
					javax.swing.MenuSelectionManager.defaultManager().clearSelectedPath();
				}
				else if (what.equals("focus")) {
					java.awt.KeyboardFocusManager k = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager();
					out[0] = "focusedWindow=" + name(k.getFocusedWindow()) + " active=" + name(k.getActiveWindow()) + " owner=" + (k.getFocusOwner() == null ? null : k.getFocusOwner().getClass().getName());
				}
				else if (what.equals("fieldtext")) {
					java.awt.Component c = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
					out[0] = c instanceof javax.swing.text.JTextComponent ? "[" + ((javax.swing.text.JTextComponent) c).getText() + "] enabled=" + c.isEnabled() + " editable=" + ((javax.swing.text.JTextComponent) c).isEditable() + " keys=" + keys : "owner=" + c;
				}
				else if (what.equals("shelves")) {
					StringBuilder b = new StringBuilder();
					for (Shelf sh : Shelf.all) {
						b.append(sh.place).append(' ').append(sh.view == null ? "hidden" : r(sh.view.window.getBounds())).append(" sections=");
						for (Shelf.Section c : sh.shown()) b.append(c.kind).append('(').append(c.items.size()).append(") ");
						b.append('\n');
					}
					out[0] = b.toString();
				}
				else if (what.startsWith("shelfmove:")) {
					String[] a = what.substring(10).split(",");
					Shelf.Section sec = null;
					for (Shelf sh : Shelf.all) for (Shelf.Section c : sh.sections) if (c.kind.equals(a[0])) sec = c;
					if (sec != null) Shelf.moveSection(sec, a[1]);
				}
				else if (what.startsWith("shelfplace:")) Shelf.moveShelf(Shelf.all.get(0), what.substring(11));
				else if (what.startsWith("addmacro:")) {
					for (Shelf sh : Shelf.all) for (Shelf.Section c : sh.sections)
						if (c.kind.equals("macros")) { c.items.add(MacroShelf.itemFor(new java.io.File(what.substring(9)))); Shelf.save(); Shelf.showAll(); return; }
				}
				else if (what.equals("count")) out[0] = "" + Dock.instance.visible().size();
				else if (what.equals("front")) {
					try { java.awt.Desktop.class.getMethod("requestForeground", boolean.class).invoke(java.awt.Desktop.getDesktop(), true); }
					catch (Throwable t) { out[0] = t.toString(); }
					IJ.getInstance().toFront();
				}
				else if (what.equals("tree")) { StringBuilder b = new StringBuilder(); tree(IJ.getInstance(), "", b); out[0] = b.toString(); }
				else if (what.equals("popups")) {
					StringBuilder b = new StringBuilder();
					for (Window w : Window.getWindows()) if (w.isVisible() && w.getClass().getName().contains("Popup")) b.append(w.getClass().getName()).append(' ');
					for (javax.swing.MenuElement me : javax.swing.MenuSelectionManager.defaultManager().getSelectedPath()) { java.awt.Component c = me.getComponent();
						if (c instanceof javax.swing.JPopupMenu) {
							for (java.awt.Component mi : ((javax.swing.JPopupMenu) c).getComponents())
								if (mi instanceof javax.swing.JMenuItem) b.append("[").append(((javax.swing.JMenuItem) mi).getText()).append("] ");
						}
					}
					out[0] = b.toString();
				} else if (what.equals("closepopups")) javax.swing.MenuSelectionManager.defaultManager().clearSelectedPath();
				else if (what.startsWith("clickmenu:")) {
					String label = what.substring(10);
					for (javax.swing.MenuElement me : javax.swing.MenuSelectionManager.defaultManager().getSelectedPath()) { java.awt.Component c = me.getComponent();
						if (c instanceof javax.swing.JPopupMenu)
							for (java.awt.Component mi : ((javax.swing.JPopupMenu) c).getComponents())
								if (mi instanceof javax.swing.JMenuItem && label.equals(((javax.swing.JMenuItem) mi).getText())) {
									javax.swing.MenuSelectionManager.defaultManager().clearSelectedPath();
									((javax.swing.JMenuItem) mi).doClick(0);
									out[0] = "clicked";
									return;
								}
					}
					out[0] = "notfound";
				}
			});
		} catch (Exception e) { return "EXC " + e; }
		return out[0];
	}
}
