package sidetoolbar;

import ij.IJ;
import ij.Menus;
import ij.Prefs;
import ij.gui.GenericDialog;
import ij.io.OpenDialog;
import ij.io.SaveDialog;
import ij.plugin.PlugIn;

import java.io.File;
import javax.swing.SwingUtilities;

/**
 * Side Toolbar: Fiji's toolbar as a vertical bar along the left (or right) edge of the screen, whose buttons can be
 * added, edited, moved and removed freely. Commands are under Plugins > Side Toolbar.
 */
public class Side_Toolbar implements PlugIn {

	public void run(final String arg) {
		if (IJ.getInstance() == null) {
			IJ.log("Side Toolbar needs the Fiji window (it does not run headless or in batch mode).");
			return;
		}
		SwingUtilities.invokeLater(() -> {
			try {
				dispatch(arg);
			} catch (Throwable t) {
				IJ.handleException(t);
			}
		});
	}

	static void dispatch(String arg) {
		if (arg.equals("start")) start();
		else if (arg.equals("stop")) stop();
		else if (arg.equals("add")) { start(); Editor.showAdd(-1); }
		else if (arg.equals("settings")) settings();
		else if (arg.equals("undo")) { if (Dock.instance != null) Dock.instance.undo(); }
		else if (arg.equals("export")) exportConfig();
		else if (arg.equals("import")) importConfig();
		else if (arg.equals("reset")) reset();
		else if (arg.equals("help")) help();
	}

	/** Shows the side toolbar (also called at startup by the AutoRun macro). */
	static void start() {
		if (Dock.instance != null) {
			Chrome.apply();
			return;
		}
		Store.load();
		Store.sync(Bridge.slotNames());
		addUndoHistoryButtonOnce();
		Store.save();
		if (!Prefs.get("sidetoolbar.enabled", true)) {
			Prefs.set("sidetoolbar.enabled", true);
			Store.persistPrefs();
		}
		new Dock(IJ.getInstance());
		Shelf.load();
		if (Shelf.enabled()) for (Shelf s : Shelf.all) if (s.view == null) s.view = new ShelfView(s);
		Chrome.apply();
		for (Shelf s : Shelf.all) if (s.view != null) s.view.rebuild();
		OnTop.install();
		OnTop.update();
	}

	/** Adds one "Undo History" button just above ">>", once, when the Undo History plugin is installed. */
	static void addUndoHistoryButtonOnce() {
		if (Prefs.get("sidetoolbar.undobutton", false)) return;
		if (Menus.getCommands() == null || Menus.getCommands().get("Undo History Window") == null) return;
		Item it = new Item(Item.Kind.COMMAND, "Undo History Window");
		it.label = "Undo History";
		it.icon = "text:\u21b6";
		it.color = "#2d6ec8";
		int at = Store.items.size();
		for (int i = 0; i < Store.items.size(); i++) if (Store.items.get(i).kind == Item.Kind.MORE) { at = i; break; }
		Store.items.add(at, it);
		Prefs.set("sidetoolbar.undobutton", true);
		Store.persistPrefs();
	}

	/** Back to ImageJ's own toolbar; the side toolbar's contents are kept for next time. */
	static void stop() {
		if (Dock.instance != null) Dock.instance.dispose();
		Shelf.hideAll();
		Chrome.restoreClassic();
		Prefs.set("sidetoolbar.enabled", false);
		Store.persistPrefs();
		IJ.showStatus("Side Toolbar off. Plugins > Side Toolbar > Start Side Toolbar shows it again.");
		rearrangeWindows();
	}

	static void settings() {
		String[] sides = {"Left", "Right"};
		String[] sizes = {"Small (26 px)", "Medium (32 px)", "Large (40 px)", "Extra large (50 px)"};
		int[] px = {26, 32, 40, 50};
		String[] columns = {"Automatic (more only when needed)", "1", "2", "3"};
		String[] status = {"Top of the screen", "Bottom of the screen", "Classic ImageJ window (with its own toolbar too)"};
		String[] statusKeys = {"top", "bottom", "classic"};
		int size = 1;
		for (int i = 0; i < px.length; i++) if (Math.abs(px[i] - Store.buttonSize()) < Math.abs(px[size] - Store.buttonSize())) size = i;
		int st = 0;
		for (int i = 0; i < statusKeys.length; i++) if (statusKeys[i].equals(Store.status())) st = i;

		GenericDialog gd = new GenericDialog("Side Toolbar Settings");
		gd.addChoice("Side of the screen:", sides, Store.side().equals("right") ? sides[1] : sides[0]);
		gd.addChoice("Button size:", sizes, sizes[size]);
		gd.addChoice("Columns:", columns, columns[Math.min(Store.columns(), 3)]);
		gd.addCheckbox("Show button names beside the icons", Store.labels());
		gd.addChoice("ImageJ status bar:", status, status[st]);
		gd.addCheckbox("Keep the toolbar above Fiji's windows", Store.onTop());
		gd.addMessage("Shelf (open tools, image previews, macro buttons):");
		gd.addCheckbox("Show the shelf", Shelf.enabled());
		gd.addNumericField("Shelf size:", Shelf.size(), 0, 4, "px (height of a bar, width of a column; 36 to 120)");
		gd.addCheckbox("Macro buttons may also run on every image in a folder (one at a time)", Shelf.folderMacros());
		gd.addMessage("Toolbar contents: " + Store.file().getPath(), null, java.awt.Color.gray);
		gd.addHelp(helpHtml());
		gd.showDialog();
		if (gd.wasCanceled()) return;
		Prefs.set("sidetoolbar.side", gd.getNextChoiceIndex() == 1 ? "right" : "left");
		Prefs.set("sidetoolbar.size", px[gd.getNextChoiceIndex()]);
		Prefs.set("sidetoolbar.columns", gd.getNextChoiceIndex());
		Prefs.set("sidetoolbar.labels", gd.getNextBoolean());
		Prefs.set("sidetoolbar.status", statusKeys[gd.getNextChoiceIndex()]);
		Prefs.set("sidetoolbar.ontop", gd.getNextBoolean());
		Prefs.set("sidetoolbar.shelves", gd.getNextBoolean());
		Prefs.set("sidetoolbar.shelfsize", Math.max(36, Math.min(120, (int) gd.getNextNumber())));
		Prefs.set("sidetoolbar.foldermacros", gd.getNextBoolean());
		Store.persistPrefs();
		SwingUtilities.invokeLater(() -> {
			start();
			Shelf.showAll(); // also hides the shelves when switched off, and re-arranges the windows
			OnTop.update();
		});
	}

	/** Lets Window Layouts (if installed and automatic) re-tile the windows around the new toolbar size. */
	static void rearrangeWindows() {
		if (Menus.getCommands() != null && Menus.getCommands().get("Re-arrange now") != null && Prefs.get("fijilayouts.auto", true))
			IJ.doCommand("Re-arrange now");
	}

	static void exportConfig() {
		Store.load();
		SaveDialog sd = new SaveDialog("Export Side Toolbar", "Side_Toolbar", ".txt");
		if (sd.getFileName() == null) return;
		File f = new File(sd.getDirectory(), sd.getFileName());
		try {
			Store.write(f, Store.serialize());
			IJ.showStatus("Side Toolbar exported to " + f);
		} catch (Exception e) {
			IJ.error("Side Toolbar", "Could not export:\n" + e);
		}
	}

	static void importConfig() {
		OpenDialog od = new OpenDialog("Import Side Toolbar");
		if (od.getFileName() == null) return;
		File f = new File(od.getDirectory(), od.getFileName());
		try {
			String text = Store.read(f);
			if (Item.parse(text).isEmpty()) throw new IllegalArgumentException("no buttons in this file");
			Store.load();
			Store.checkpoint("Import " + f.getName());
			Store.restore(text);
			Store.save();
			start();
			if (Dock.instance != null) Dock.instance.changed();
		} catch (Exception e) {
			IJ.error("Side Toolbar", "Could not import " + f + ":\n" + e.getMessage());
		}
	}

	static void reset() {
		if (!IJ.showMessageWithCancel("Side Toolbar", "Replace the toolbar's buttons with the default set\n"
			+ "(ImageJ's tools in their usual order)?\n \nYou can undo this from the toolbar's \u2022\u2022\u2022 menu.")) return;
		Store.load();
		Store.checkpoint("Reset");
		Store.items.clear();
		Store.dismissed.clear();
		Store.items.addAll(Store.defaults());
		Store.sync(Bridge.slotNames());
		Store.save();
		start();
		if (Dock.instance != null) Dock.instance.changed();
	}

	static void help() {
		new ij.gui.HTMLDialog("Side Toolbar", helpHtml(), false);
	}

	static String helpHtml() {
		return "<html><body style='width:460px;font-family:sans-serif'>"
			+ "<h3>Side Toolbar</h3>"
			+ "<p>Fiji's toolbar, vertical, along the side of the screen. Every tool works as in ImageJ's toolbar: "
			+ "click to select, double-click for options, right-click (or hold) for its menu.</p>"
			+ "<ul>"
			+ "<li><b>Move</b> a button: drag it. The others make room.</li>"
			+ "<li><b>Remove</b> a button: drag it off the bar (a red cross appears), or right-click &gt; Remove. "
			+ "Undo it from the notice that appears, or right-click &gt; Undo.</li>"
			+ "<li><b>Add</b> buttons: click <b>+</b> at the bottom. Search every Fiji command and tool, then double-click "
			+ "or drag entries onto the bar. Script and macro files can be dropped on the bar from the Finder.</li>"
			+ "<li><b>Edit</b> a button (name, icon, command options, macro code): right-click &gt; Edit Button.</li>"
			+ "<li><b>Group</b> buttons: + &gt; Group, then drag buttons onto the middle of the group.</li>"
			+ "<li><b>Spring</b>: empty space that pushes the next buttons to the bottom of the bar.</li>"
			+ "<li><b>\u2022\u2022\u2022</b> (beside +): settings, undo, export, import, reset, and the classic toolbar.</li>"
			+ "</ul>"
			+ "<p>Every change is saved at once to " + Store.file().getPath() + ".</p>"
			+ "</body></html>";
	}
}
