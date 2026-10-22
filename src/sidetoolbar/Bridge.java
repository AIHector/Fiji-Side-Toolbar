package sidetoolbar;

import ij.IJ;
import ij.ImagePlus;
import ij.Menus;
import ij.WindowManager;
import ij.gui.ColorChooser;
import ij.gui.ImageCanvas;
import ij.gui.Toolbar;
import ij.plugin.frame.ColorPicker;
import ij.plugin.frame.Recorder;
import ij.plugin.tool.MacroToolRunner;
import ij.plugin.tool.PlugInTool;

import java.awt.CheckboxMenuItem;
import java.awt.Color;
import java.awt.Menu;
import java.awt.MenuBar;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.MouseEvent;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;

/**
 * Drives ImageJ's own toolbar, which stays alive (only hidden), so every tool behaves exactly as it does there:
 * selection, double-click options, sub-type menus, macro and plugin tools, menu tools and the ">>" menu.
 * Private state is read by reflection; every access is guarded so an ImageJ update degrades gracefully.
 */
final class Bridge {

	private Bridge() { }

	static Toolbar tb() { return Toolbar.getInstance(); }

	// ---------------------------------------------------------------- reflection

	static Object get(String name) {
		try {
			Field f = Toolbar.class.getDeclaredField(name);
			f.setAccessible(true);
			return f.get(java.lang.reflect.Modifier.isStatic(f.getModifiers()) ? null : tb());
		} catch (Throwable t) { return null; }
	}

	static int getInt(String name, int def) {
		Object o = get(name);
		return o instanceof Integer ? (Integer) o : def;
	}

	static Object call(String name, Class<?>[] types, Object... args) {
		try {
			Method m = Toolbar.class.getDeclaredMethod(name, types);
			m.setAccessible(true);
			return m.invoke(tb(), args);
		} catch (Throwable t) {
			IJ.log("Side Toolbar: cannot call Toolbar." + name + " (" + t + ")");
			return null;
		}
	}

	static String[] names() { Object o = get("names"); return o instanceof String[] ? (String[]) o : new String[0]; }
	static String[] icons() { Object o = get("icons"); return o instanceof String[] ? (String[]) o : new String[0]; }
	static PlugInTool[] tools() { Object o = get("tools"); return o instanceof PlugInTool[] ? (PlugInTool[]) o : new PlugInTool[0]; }
	static PopupMenu[] menus() { Object o = get("menus"); return o instanceof PopupMenu[] ? (PopupMenu[]) o : new PopupMenu[0]; }
	static int current() { return getInt("current", Toolbar.getToolId()); }
	static int lineType() { return getInt("lineType", Toolbar.LINE); }
	static boolean arrowMode() { return Boolean.TRUE.equals(get("arrowMode")); }
	static int numTools() { return tb() != null ? tb().getNumTools() : 0; }

	// ---------------------------------------------------------------- built-in tools

	static int builtinId(String name) {
		if (name.equals("rectangle")) return Toolbar.RECTANGLE;
		if (name.equals("oval")) return Toolbar.OVAL;
		if (name.equals("polygon")) return Toolbar.POLYGON;
		if (name.equals("freehand")) return Toolbar.FREEROI;
		if (name.equals("line")) return Toolbar.LINE;
		if (name.equals("angle")) return Toolbar.ANGLE;
		if (name.equals("point")) return Toolbar.POINT;
		if (name.equals("wand")) return Toolbar.WAND;
		if (name.equals("text")) return Toolbar.TEXT;
		if (name.equals("zoom")) return Toolbar.MAGNIFIER;
		if (name.equals("hand")) return Toolbar.HAND;
		if (name.equals("dropper")) return Toolbar.DROPPER;
		return -1;
	}

	static String builtinShort(String name) {
		if (name.equals("dropper")) return "Color picker";
		if (name.equals("freehand")) return "Freehand";
		return name.length() > 0 ? Character.toUpperCase(name.charAt(0)) + name.substring(1) : name;
	}

	static String builtinTitle(String name) {
		if (name.equals("rectangle")) return "Rectangle, rounded or rotated rectangle";
		if (name.equals("oval")) return "Oval, elliptical or brush selections";
		if (name.equals("polygon")) return "Polygon selections";
		if (name.equals("freehand")) return "Freehand selections";
		if (name.equals("line")) return "Straight, segmented or freehand lines, or arrows";
		if (name.equals("angle")) return "Angle tool";
		if (name.equals("point")) return "Point or multi-point";
		if (name.equals("wand")) return "Wand (tracing) tool";
		if (name.equals("text")) return "Text tool";
		if (name.equals("zoom")) return "Magnifying glass";
		if (name.equals("hand")) return "Scrolling tool";
		if (name.equals("dropper")) return "Color picker";
		return name;
	}

	/** The ImageJ tool id an item stands for, or -1 (not a tool, or a toolbar tool that is not loaded now). */
	static int toolId(Item it) {
		if (tb() == null) return -1;
		if (it.kind == Item.Kind.TOOL) {
			int id = builtinId(it.target);
			return id == Toolbar.LINE ? lineType() : id;
		}
		if (it.kind == Item.Kind.SLOT) return slotIndex(it.target);
		return -1;
	}

	static int slotIndex(String name) {
		String[] n = names();
		for (int i = Toolbar.CUSTOM1; i < Math.min(n.length, numTools() - 1); i++)
			if (name.equals(n[i])) return i;
		return -1;
	}

	/** Names of the macro and plugin tools now in ImageJ's toolbar, in toolbar order. */
	static List<String> slotNames() {
		List<String> l = new ArrayList<String>();
		String[] n = names();
		for (int i = Toolbar.CUSTOM1; i < Math.min(n.length, numTools() - 1); i++)
			if (n[i] != null && !n[i].contains("Unused Tool")) l.add(n[i]);
		return l;
	}

	static boolean available(Item it) {
		switch (it.kind) {
			case SLOT: return slotIndex(it.target) >= 0;
			case COMMAND: return Menus.getCommands() == null || Menus.getCommands().get(it.target) != null;
			case SCRIPT: return new File(it.target).exists();
			default: return true;
		}
	}

	static boolean isCurrent(Item it) {
		if (!it.isTool()) return false;
		int cur = current();
		if (it.kind == Item.Kind.TOOL && it.target.equals("line"))
			return cur == Toolbar.LINE || cur == Toolbar.POLYLINE || cur == Toolbar.FREELINE;
		int id = toolId(it);
		return id >= 0 && id == cur;
	}

	static boolean isMenuTool(int slot) {
		PopupMenu[] m = menus();
		return slot >= 0 && slot < m.length && m[slot] != null && m[slot].getItemCount() > 0;
	}

	static boolean isActionTool(int slot) {
		String[] n = names();
		return slot >= 0 && slot < n.length && n[slot] != null && n[slot].contains("Action Tool");
	}

	/** ImageJ's own status-bar text for a tool, as shown when the mouse is over its button. */
	static void showStatus(Item it) {
		int id = it.kind == Item.Kind.MORE ? numTools() - 1 : toolId(it);
		if (id >= 0 && tb() != null) {
			try {
				Method m = Toolbar.class.getDeclaredMethod("showMessage", int.class);
				m.setAccessible(true);
				m.invoke(tb(), id);
				return;
			} catch (Throwable ignored) { }
		}
		IJ.showStatus(it.title());
	}

	// ---------------------------------------------------------------- pressing a tool button

	private static long lastPress;
	private static int lastPressTool = -1;

	/** Mouse press on a tool button: selects the tool, or opens its options on a double click (as ImageJ does). */
	static void press(Item it, MouseEvent e) {
		int tool = toolId(it);
		if (tool < 0) return;
		long now = System.currentTimeMillis();
		boolean doubleClick = tool == lastPressTool && tool == current() && now - lastPress <= Toolbar.DOUBLE_CLICK_THRESHOLD;
		lastPress = doubleClick ? 0 : now;
		lastPressTool = tool;
		if (doubleClick) options(tool);
		else select(tool, e);
	}

	/** Selects a tool exactly like a click on ImageJ's toolbar (macro tools get their "Selected" call, action tools run). */
	static void select(int tool, MouseEvent e) {
		try {
			tb().triggerPopupMenu(tool, e, false, false);
		} catch (Throwable t) {
			tb().setTool(tool);
		}
	}

	static void options(int tool) {
		String[] n = names();
		PlugInTool[] t = tools();
		if (tool >= Toolbar.CUSTOM1) {
			if (tool < t.length && t[tool] instanceof MacroToolRunner) {
				t[tool].runMacroTool(spaced(n[tool]) + "Options");
			} else if (tool < t.length && t[tool] != null) {
				t[tool].showOptionsDialog();
			}
			return;
		}
		ImagePlus imp = WindowManager.getCurrentImage();
		switch (tool) {
			case Toolbar.RECTANGLE:
				IJ.doCommand(Toolbar.getRectToolType() == Toolbar.ROUNDED_RECT_ROI ? "Rounded Rect Tool..." : "Roi Defaults...");
				break;
			case Toolbar.OVAL:
				call("showBrushDialog", new Class<?>[0]);
				break;
			case Toolbar.MAGNIFIER:
				if (imp != null) {
					ImageCanvas ic = imp.getCanvas();
					if (ic != null) ic.unzoom();
				}
				break;
			case Toolbar.LINE: case Toolbar.POLYLINE: case Toolbar.FREELINE:
				if (tool == Toolbar.LINE && arrowMode()) IJ.doCommand("Arrow Tool...");
				else IJ.runPlugIn("ij.plugin.frame.LineWidthAdjuster", "");
				break;
			case Toolbar.ANGLE:
				call("showAngleDialog", new Class<?>[0]);
				break;
			case Toolbar.POINT:
				IJ.doCommand("Point Tool...");
				break;
			case Toolbar.WAND:
				IJ.doCommand("Wand Tool...");
				break;
			case Toolbar.TEXT:
				IJ.run("Fonts...");
				break;
			case Toolbar.DROPPER:
				IJ.doCommand("Color Picker...");
				tb().restorePreviousTool();
				break;
			default:
		}
	}

	static String spaced(String name) { return name.endsWith(" ") ? name : name + " "; }

	static boolean hasOptions(Item it) {
		if (it.kind == Item.Kind.TOOL) {
			String t = it.target;
			return !(t.equals("polygon") || t.equals("freehand") || t.equals("hand"));
		}
		int slot = toolId(it);
		PlugInTool[] t = tools();
		if (slot < 0 || slot >= t.length || t[slot] == null) return false;
		if (t[slot] instanceof MacroToolRunner) return hasMacro(t[slot], spaced(names()[slot]) + "Options");
		return overrides(t[slot], "showOptionsDialog");
	}

	@SuppressWarnings("unchecked")
	static boolean hasMacro(PlugInTool runner, String name) {
		try {
			Field f = MacroToolRunner.class.getDeclaredField("installer");
			f.setAccessible(true);
			Object installer = f.get(runner);
			Field g = installer.getClass().getDeclaredField("macroNames");
			g.setAccessible(true);
			for (String n : (List<String>) g.get(installer)) if (n.startsWith(name)) return true;
			return false;
		} catch (Throwable t) { return true; }
	}

	static boolean overrides(Object tool, String method, Class<?>... types) {
		try {
			return tool.getClass().getMethod(method, types).getDeclaringClass() != PlugInTool.class;
		} catch (Throwable t) { return false; }
	}

	// ---------------------------------------------------------------- menus

	/** True if the tool has a menu that ImageJ opens with a right or long click (the small red triangle). */
	static boolean hasSubtypes(Item it) {
		if (it.kind != Item.Kind.TOOL) return false;
		String t = it.target;
		return t.equals("rectangle") || t.equals("oval") || t.equals("line") || t.equals("point")
			|| t.equals("zoom") || t.equals("dropper");
	}

	/** Adds the tool's own menu entries (sub-types, options); the caller shows the menu. Returns true if any was added. */
	static boolean addToolEntries(JPopupMenu m, final Item it, final MouseEvent e) {
		int before = m.getComponentCount();
		if (it.kind == Item.Kind.TOOL) {
			String t = it.target;
			if (t.equals("rectangle")) {
				int type = Toolbar.getRectToolType();
				check(m, "Rectangle", type == Toolbar.RECT_ROI, "rectItem", Toolbar.RECTANGLE);
				check(m, "Rounded Rectangle", type == Toolbar.ROUNDED_RECT_ROI, "roundRectItem", Toolbar.RECTANGLE);
				check(m, "Rotated Rectangle", type == Toolbar.ROTATED_RECT_ROI, "rotatedRectItem", Toolbar.RECTANGLE);
			} else if (t.equals("oval")) {
				int type = Toolbar.getOvalToolType();
				check(m, "Oval selections", type == Toolbar.OVAL_ROI, "ovalItem", Toolbar.OVAL);
				check(m, "Elliptical selections", type == Toolbar.ELLIPSE_ROI, "ellipseItem", Toolbar.OVAL);
				check(m, "Selection Brush Tool", type == Toolbar.BRUSH_ROI, "brushItem", Toolbar.OVAL);
			} else if (t.equals("point")) {
				boolean multi = Toolbar.getMultiPointMode();
				check(m, "Point Tool", !multi, "pointItem", Toolbar.POINT);
				check(m, "Multi-point Tool", multi, "multiPointItem", Toolbar.POINT);
			} else if (t.equals("line")) {
				int lt = lineType();
				boolean arrow = arrowMode();
				check(m, "Straight Line", lt == Toolbar.LINE && !arrow, "straightLineItem", -1);
				check(m, "Segmented Line", lt == Toolbar.POLYLINE, "polyLineItem", -1);
				check(m, "Freehand Line", lt == Toolbar.FREELINE, "freeLineItem", -1);
				check(m, "Arrow tool", lt == Toolbar.LINE && arrow, "arrowItem", -1);
			} else if (t.equals("zoom")) {
				String[][] z = {{"Reset Zoom", "orig"}, {"Zoom In", "in"}, {"Zoom Out", "out"}, {"View 100%", "100%"},
					{"Zoom To Selection", "to"}, {"Scale to Fit", "scale"}, {"Set...", "set"}, {"Maximize", "max"}};
				for (final String[] p : z)
					m.add(item(p[0], new Runnable() { public void run() { IJ.runPlugIn("ij.plugin.Zoom", p[1]); } }));
			} else if (t.equals("dropper")) {
				addPickerEntries(m);
			}
			if (hasOptions(it)) {
				if (m.getComponentCount() > before) m.addSeparator();
				final int id = builtinId(t);
				m.add(item(t.equals("zoom") ? "Reset zoom (double click)" : "Options... (double click)", new Runnable() {
					public void run() { options(id == Toolbar.LINE ? lineType() : id); }
				}));
			}
		} else if (it.kind == Item.Kind.SLOT) {
			final int slot = slotIndex(it.target);
			if (slot < 0) return false;
			if (isMenuTool(slot)) {
				addMirroredMenu(m, slot);
			} else {
				final PlugInTool tool = slot < tools().length ? tools()[slot] : null;
				if (isActionTool(slot)) m.add(item("Run", new Runnable() { public void run() { select(slot, e); } }));
				else m.add(item("Select tool", new Runnable() { public void run() { select(slot, e); } }));
				if (hasOptions(it))
					m.add(item("Options... (double click)", new Runnable() { public void run() { options(slot); } }));
				if (tool != null && !(tool instanceof MacroToolRunner) && overrides(tool, "showPopupMenu", MouseEvent.class, Toolbar.class))
					m.add(item("Tool menu...", new Runnable() {
						public void run() {
							try { tool.showPopupMenu(e, tb()); } catch (Throwable t) { IJ.showStatus("Side Toolbar: this tool's menu cannot open here"); }
						}
					}));
			}
		} else if (it.kind == Item.Kind.MORE) {
			addMoreEntries(m);
		}
		return m.getComponentCount() > before;
	}

	/** A sub-type entry that fires ImageJ's own menu item, so state, status text and events match ImageJ exactly. */
	private static void check(JPopupMenu m, String label, boolean state, final String field, final int selectFirst) {
		final JCheckBoxMenuItem ji = new JCheckBoxMenuItem(label, state);
		ji.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent ev) {
				if (selectFirst >= 0 && current() != selectFirst) call("setTool2", new Class<?>[] {int.class}, selectFirst);
				Object o = get(field);
				if (o instanceof CheckboxMenuItem) {
					CheckboxMenuItem ci = (CheckboxMenuItem) o;
					ci.setState(true);
					tb().itemStateChanged(new ItemEvent(ci, ItemEvent.ITEM_STATE_CHANGED, ci, ItemEvent.SELECTED));
				}
			}
		});
		m.add(ji);
	}

	static JMenuItem item(String label, final Runnable r) {
		JMenuItem mi = new JMenuItem(label);
		mi.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				try { r.run(); } catch (Throwable t) { IJ.handleException(t); }
			}
		});
		return mi;
	}

	private static void addPickerEntries(JPopupMenu m) {
		Object[][] colors = {{"White/Black", Color.white, Color.black}, {"Black/White", Color.black, Color.white},
			{"Red", Color.red, null}, {"Green", Color.green, null}, {"Blue", Color.blue, null},
			{"Yellow", Color.yellow, null}, {"Cyan", Color.cyan, null}, {"Magenta", Color.magenta, null}};
		for (final Object[] c : colors)
			m.add(item((String) c[0], new Runnable() {
				public void run() {
					foreground((Color) c[1]);
					if (c[2] != null) background((Color) c[2]);
					ColorPicker.update();
				}
			}));
		m.addSeparator();
		m.add(item("Foreground...", new Runnable() {
			public void run() { foreground(new ColorChooser("Select Foreground Color", Toolbar.getForegroundColor(), false).getColor()); }
		}));
		m.add(item("Background...", new Runnable() {
			public void run() { background(new ColorChooser("Select Background Color", Toolbar.getBackgroundColor(), false).getColor()); }
		}));
		m.add(item("Colors...", new Runnable() {
			public void run() {
				IJ.run("Colors...", "");
				Recorder.setForegroundColor(Toolbar.getForegroundColor());
				Recorder.setBackgroundColor(Toolbar.getBackgroundColor());
			}
		}));
		m.add(item("Color Picker...", new Runnable() { public void run() { IJ.run("Color Picker...", ""); } }));
	}

	private static void foreground(Color c) {
		if (c == null) return;
		Toolbar.setForegroundColor(c);
		Recorder.setForegroundColor(c);
	}

	private static void background(Color c) {
		if (c == null) return;
		Toolbar.setBackgroundColor(c);
		Recorder.setBackgroundColor(c);
	}

	/** Copies a menu tool's AWT menu; choosing an entry runs it through the tool, as ImageJ does. */
	static void addMirroredMenu(JPopupMenu m, final int slot) {
		PopupMenu pm = menus()[slot];
		final PlugInTool tool = tools()[slot];
		final String name = names()[slot];
		for (int i = 0; i < pm.getItemCount(); i++) {
			MenuItem mi = pm.getItem(i);
			final String label = mi.getLabel();
			if (label.equals("-")) { m.addSeparator(); continue; }
			final String cmd = mi.getActionCommand();
			JMenuItem ji = item(label, new Runnable() { public void run() { if (tool != null) tool.runMenuTool(name, cmd); } });
			ji.setEnabled(mi.isEnabled());
			m.add(ji);
		}
	}

	/** The ">>" menu of ImageJ's toolbar: toolsets, built-in extra tools, plugin tools. */
	static void addMoreEntries(JPopupMenu m) {
		String current = String.valueOf(get("currentSet"));
		String macros = IJ.getDir("macros");
		File startup = new File(macros, "StartupMacros.txt");
		if (!startup.exists()) startup = new File(macros, "StartupMacros.ijm");
		more(m, startup.exists() ? "Startup Macros" : "StartupMacros*", null, current);
		String[] list = new File(macros, "toolsets").list();
		if (list != null) {
			Arrays.sort(list);
			for (String n : list) {
				if (n.startsWith(".") || n.endsWith(" Tool")) continue;
				if (n.endsWith(".txt")) more(m, n.substring(0, n.length() - 4), null, current);
				else if (n.endsWith(".ijm")) more(m, n.substring(0, n.length() - 4) + " ", null, current);
			}
		}
		more(m, "Overlay Editing Tools*", null, current);
		m.addSeparator();
		for (String n : Toolbar.builtInTools) more(m, n, "Tool", current);
		boolean sep = false;
		for (String n : pluginTools()) {
			if (!sep) { m.addSeparator(); sep = true; }
			more(m, n, "Plugin Tool", current);
		}
		m.addSeparator();
		more(m, "Restore Startup Tools", null, current);
		more(m, "Remove Custom Tools", null, current);
		more(m, "Help...", null, current);
	}

	private static void more(JPopupMenu m, final String label, final String command, String current) {
		JCheckBoxMenuItem ji = new JCheckBoxMenuItem(label.trim(), label.equals(current));
		ji.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				CheckboxMenuItem ci = new CheckboxMenuItem(label, true);
				if (command != null) ci.setActionCommand(command);
				try {
					tb().itemStateChanged(new ItemEvent(ci, ItemEvent.ITEM_STATE_CHANGED, ci, ItemEvent.SELECTED));
				} catch (Throwable t) { IJ.handleException(t); }
			}
		});
		m.add(ji);
	}

	/** Entries of Plugins > Tools whose names end in " Tool" or " Menu", as ImageJ's ">>" menu lists them. */
	static List<String> pluginTools() {
		List<String> l = new ArrayList<String>();
		MenuBar mb = Menus.getMenuBar();
		if (mb == null) return l;
		for (int i = 0; i < mb.getMenuCount(); i++) {
			Menu menu = mb.getMenu(i);
			if (menu == null || !"Plugins".equals(menu.getLabel())) continue;
			for (int j = 0; j < menu.getItemCount(); j++) {
				MenuItem mi = menu.getItem(j);
				if (mi instanceof Menu && "Tools".equals(mi.getLabel())) {
					Menu tools = (Menu) mi;
					for (int k = 0; k < tools.getItemCount(); k++) {
						String n = tools.getItem(k).getLabel();
						if (n != null && (n.endsWith(" Tool") || n.endsWith(" Menu"))) l.add(n);
					}
				}
			}
		}
		return l;
	}
}
