package sidetoolbar;

import ij.IJ;
import ij.ImagePlus;
import ij.Menus;
import ij.Prefs;
import ij.WindowManager;
import ij.gui.ImageWindow;

import java.awt.Font;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.border.EmptyBorder;

/** The right-click menu of an image preview: place it on the screen, zoom, layouts, and the usual image commands. */
final class ImageMenus {

	private ImageMenus() { }

	static boolean has(String command) { return Menus.getCommands() != null && Menus.getCommands().get(command) != null; }

	static void fill(JPopupMenu m, final ImagePlus imp) {
		JLabel h = new JLabel(imp.getTitle());
		h.setFont(h.getFont().deriveFont(Font.BOLD, 11f));
		h.setBorder(new EmptyBorder(2, 10, 2, 10));
		m.add(h);
		m.add(Bridge.item("Bring to Front", () -> ShelfView.bringToFront(imp)));
		m.add(Bridge.item("Duplicate...", () -> onImage(imp, "Duplicate...")));
		m.add(Bridge.item("Copy to Clipboard (paste in other apps)", () -> onImage(imp, "Copy to System")));
		m.add(Bridge.item("Copy (ImageJ)", () -> onImage(imp, "Copy")));
		boolean layouts = has("Re-arrange now");

		JMenu place = new JMenu("Place on Screen");
		String[][] zones = {{"left-half", "Left Half"}, {"right-half", "Right Half"}, {"top-half", "Top Half"}, {"bottom-half", "Bottom Half"},
			{null, null}, {"top-left", "Top-Left Quarter"}, {"top-right", "Top-Right Quarter"}, {"bottom-left", "Bottom-Left Quarter"},
			{"bottom-right", "Bottom-Right Quarter"}, {null, null}, {"left-third", "Left Third"}, {"center-third", "Centre Third"},
			{"right-third", "Right Third"}, {"left-two-thirds", "Left Two Thirds"}, {"right-two-thirds", "Right Two Thirds"},
			{null, null}, {"center", "Centre"}, {"maximize", "Whole Screen"}, {"restore", "Back to Where It Was"}};
		for (final String[] z : zones) {
			if (z[0] == null) { place.addSeparator(); continue; }
			JMenuItem it = Bridge.item(z[1], () -> snap(imp, z[0]));
			it.setIcon(PlaceIcon.zone(z[0]));
			place.add(it);
		}
		place.setEnabled(layouts);
		m.add(place);
		JMenu display = new JMenu("Move to Display");
		JMenuItem nd = Bridge.item("Next Display", () -> snap(imp, "next-display"));
		nd.setIcon(PlaceIcon.zone("next-display"));
		display.add(nd);
		JMenuItem pd = Bridge.item("Previous Display", () -> snap(imp, "previous-display"));
		pd.setIcon(PlaceIcon.zone("previous-display"));
		display.add(pd);
		display.setEnabled(layouts && java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices().length > 1);
		m.add(display);
		JMenu lay = new JMenu("Apply Layout");
		for (int i = 0; i <= 9; i++) {
			final int n = i;
			String name = Prefs.get("fijilayouts.name." + i, "");
			lay.add(Bridge.item(n + (name.isEmpty() ? "" : ": " + name), () -> onImage(imp, "Layout " + n)));
		}
		lay.setEnabled(layouts);
		m.add(lay);

		JMenu zoom = new JMenu("Zoom");
		zoom.add(Bridge.item("In", () -> onImage(imp, "In [+]")));
		zoom.add(Bridge.item("Out", () -> onImage(imp, "Out [-]")));
		zoom.add(Bridge.item("100%", () -> onImage(imp, "View 100%")));
		zoom.add(Bridge.item("Original Scale", () -> onImage(imp, "Original Scale")));
		zoom.add(Bridge.item("To Selection", () -> onImage(imp, "To Selection")));
		m.add(zoom);

		JMenu image = new JMenu("Image");
		image.add(Bridge.item("Duplicate...", () -> onImage(imp, "Duplicate...")));
		image.add(Bridge.item("Rename...", () -> onImage(imp, "Rename...")));
		image.add(Bridge.item("Properties...", () -> onImage(imp, "Properties...")));
		image.add(Bridge.item("Show Info...", () -> onImage(imp, "Show Info...")));
		m.add(image);

		if (has("Undo History Window")) {
			JMenu u = new JMenu("Undo History");
			u.add(Bridge.item("Step Back", () -> onImage(imp, "Undo History Step Back")));
			u.add(Bridge.item("Step Forward", () -> onImage(imp, "Undo History Step Forward")));
			u.add(Bridge.item("Show History", () -> onImage(imp, "Undo History Window")));
			m.add(u);
		}
		m.addSeparator();
		m.add(Bridge.item("Save", () -> onImage(imp, "Save")));
		JMenu saveAs = new JMenu("Save As");
		String[][] formats = {{"TIFF...", "Tiff..."}, {"PNG...", "PNG..."}, {"JPEG...", "Jpeg..."}, {"GIF...", "Gif..."},
			{"BMP...", "BMP..."}, {"TIFF in ZIP...", "ZIP..."}, {"OME-TIFF (Bio-Formats)...", "Bio-Formats Exporter"},
			{"AVI (stacks)...", "AVI... "}};
		for (final String[] f : formats) {
			JMenuItem it = Bridge.item(f[0], () -> onImage(imp, f[1]));
			it.setEnabled(has(f[1]));
			saveAs.add(it);
		}
		saveAs.add(Bridge.item("PDF (one page per plane)...", () -> PdfExport.save(imp)));
		m.add(saveAs);
		m.add(Bridge.item("Close", () -> onImage(imp, "Close")));
	}

	/** Makes the image the active window, then runs a command on it (as if chosen from the menu with it in front). */
	static void onImage(final ImagePlus imp, final String command) {
		ShelfView.bringToFront(imp);
		new Thread(() -> {
			waitActive(imp);
			IJ.doCommand(command);
		}, "Side Toolbar: " + command).start();
	}

	/** Places the image with Window Layouts' snap zones (it acts on the active window). */
	static void snap(final ImagePlus imp, final String zone) {
		ShelfView.bringToFront(imp);
		new Thread(() -> {
			waitActive(imp);
			IJ.runPlugIn("fijilayouts.WindowLayouts_", "snap:" + zone);
		}, "Side Toolbar: snap").start();
	}

	private static void waitActive(ImagePlus imp) {
		ImageWindow w = imp.getWindow();
		for (int i = 0; i < 20; i++) {
			if (WindowManager.getCurrentImage() == imp && w != null && w.isActive()) break;
			IJ.wait(25);
		}
		WindowManager.setTempCurrentImage(null);
		IJ.wait(50);
	}
}
