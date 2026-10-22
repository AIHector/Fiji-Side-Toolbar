package sidetoolbar;

import ij.IJ;
import ij.ImagePlus;
import ij.Macro;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.io.DirectoryChooser;
import ij.io.OpenDialog;
import ij.macro.Interpreter;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Macro buttons of a shelf. Each button runs an .ijm file. With one image open it runs on that image; with
 * several, a dialog asks which ones (the active image is ticked). Running on every image of a folder is an option
 * (off unless switched on in the settings): one image at a time is opened, processed, saved and closed.
 */
final class MacroShelf {

	private MacroShelf() { }

	static Item itemFor(File f) {
		Item it = new Item(Item.Kind.SCRIPT, f.getAbsolutePath());
		String n = f.getName();
		int dot = n.lastIndexOf('.');
		it.label = (dot > 0 ? n.substring(0, dot) : n).replace('_', ' ');
		return it;
	}

	static void addFile(Shelf.Section c) {
		OpenDialog od = new OpenDialog("Add a macro (.ijm) to the shelf");
		if (od.getFileName() == null) return;
		Shelf.checkpoint();
		c.items.add(itemFor(new File(od.getDirectory(), od.getFileName())));
		Shelf.save();
		Shelf.showAll();
	}

	/** Creates a macro file in Fiji's macros/Side Toolbar folder, adds its button and opens it in the editor. */
	static void newMacro(Shelf.Section c) {
		GenericDialog gd = new GenericDialog("New Macro");
		gd.addStringField("Name:", "My macro", 24);
		gd.addMessage("The macro is saved in " + IJ.getDir("macros") + "Side Toolbar/ and opens in the editor.\n"
			+ "It runs on the current image: the Macro Recorder (Plugins > Macros > Record...) writes the code for you.");
		gd.showDialog();
		if (gd.wasCanceled()) return;
		String name = gd.getNextString().trim();
		if (name.isEmpty()) return;
		File dir = new File(IJ.getDir("macros"), "Side Toolbar");
		dir.mkdirs();
		File f = new File(dir, name.replaceAll("[^A-Za-z0-9 _-]", "_").replace(' ', '_') + ".ijm");
		try {
			if (!f.exists()) Store.write(f, "// " + name + "\n// Runs on the current image.\n\n");
		} catch (Exception e) {
			IJ.error("Side Toolbar", "Could not create " + f + ":\n" + e);
			return;
		}
		Shelf.checkpoint();
		Item it = itemFor(f);
		it.label = name;
		c.items.add(it);
		Shelf.save();
		Shelf.showAll();
		IJ.open(f.getPath());
	}

	/** A click on a macro button. */
	static void run(Item it) {
		if (!new File(it.target).exists()) {
			IJ.error("Side Toolbar", "Macro file not found:\n" + it.target);
			return;
		}
		List<ImagePlus> imgs = ShelfView.images();
		if (imgs.size() <= 1) runOn(it, imgs);
		else choose(it);
	}

	/** Asks which open images to run on (or, if allowed in the settings, every image in a folder). */
	static void choose(final Item it) {
		final List<ImagePlus> imgs = ShelfView.images();
		ImagePlus cur = WindowManager.getCurrentImage();
		GenericDialog gd = new GenericDialog("Run " + it.title());
		gd.addMessage("Run “" + it.title() + "” on these images, one after the other:");
		gd.addCheckbox("All open images", false);
		for (ImagePlus imp : imgs) gd.addCheckbox(imp.getTitle(), imp == cur);
		if (Shelf.folderMacros()) {
			gd.addMessage(" ");
			gd.addCheckbox("Instead, every image in a folder (opened, processed, saved and closed one at a time)", false);
		}
		gd.showDialog();
		if (gd.wasCanceled()) return;
		boolean all = gd.getNextBoolean();
		List<ImagePlus> chosen = new ArrayList<ImagePlus>();
		for (ImagePlus imp : imgs) if (gd.getNextBoolean() || all) chosen.add(imp);
		if (Shelf.folderMacros() && gd.getNextBoolean()) { runOnFolder(it); return; }
		if (chosen.isEmpty()) { IJ.showStatus("Side Toolbar: no image chosen"); return; }
		runOn(it, chosen);
	}

	static void runOn(final Item it, final List<ImagePlus> imgs) {
		new Thread(() -> {
			try {
				if (imgs.isEmpty()) { IJ.runMacroFile(it.target, it.args); return; }
				for (int i = 0; i < imgs.size(); i++) {
					ImagePlus imp = imgs.get(i);
					if (imp.getWindow() == null) continue; // closed meanwhile
					IJ.showStatus(it.title() + ": image " + (i + 1) + " of " + imgs.size() + " (" + imp.getTitle() + ")");
					ShelfView.bringToFront(imp);
					WindowManager.setTempCurrentImage(imp);
					IJ.runMacroFile(it.target, it.args);
					WindowManager.setTempCurrentImage(null);
				}
				IJ.showStatus(it.title() + ": done (" + imgs.size() + " image" + (imgs.size() == 1 ? "" : "s") + ")");
			} catch (Throwable t) {
				if (!Macro.MACRO_CANCELED.equals(t.getMessage())) IJ.handleException(t);
			} finally {
				WindowManager.setTempCurrentImage(null);
			}
		}, "Side Toolbar: " + it.title()).start();
	}

	/** Every image of a folder: one at a time, without showing them, saved as TIFF to an output folder. */
	static void runOnFolder(final Item it) {
		DirectoryChooser in = new DirectoryChooser("Folder of images to process");
		if (in.getDirectory() == null) return;
		DirectoryChooser out = new DirectoryChooser("Folder to save the results in");
		if (out.getDirectory() == null) return;
		final File inDir = new File(in.getDirectory()), outDir = new File(out.getDirectory());
		if (inDir.equals(outDir) && !IJ.showMessageWithCancel("Side Toolbar", "The results go into the same folder as the originals\n"
			+ "(saved as TIFF; originals with the same name and .tif would be replaced). Continue?")) return;
		new Thread(() -> {
			String[] names = inDir.list();
			if (names == null) return;
			java.util.Arrays.sort(names);
			int done = 0;
			boolean batch = Interpreter.batchMode;
			Interpreter.batchMode = true; // images are not shown: faster, and only one is in memory at a time
			try {
				for (int i = 0; i < names.length; i++) {
					File f = new File(inDir, names[i]);
					if (f.isDirectory() || names[i].startsWith(".")) continue;
					IJ.showStatus(it.title() + ": " + names[i] + " (" + (i + 1) + "/" + names.length + ")");
					IJ.showProgress(i, names.length);
					ImagePlus imp = IJ.openImage(f.getPath());
					if (imp == null) continue;
					WindowManager.setTempCurrentImage(imp);
					IJ.runMacroFile(it.target, it.args);
					ImagePlus result = WindowManager.getCurrentImage();
					if (result == null) result = imp;
					String base = names[i].replaceFirst("\\.[^.]+$", "");
					IJ.saveAs(result, "Tiff", new File(outDir, base + ".tif").getPath());
					if (result != imp) result.close();
					imp.close();
					WindowManager.setTempCurrentImage(null);
					done++;
					System.gc();
				}
			} catch (Throwable t) {
				if (!Macro.MACRO_CANCELED.equals(t.getMessage())) IJ.handleException(t);
			} finally {
				Interpreter.batchMode = batch;
				WindowManager.setTempCurrentImage(null);
				IJ.showProgress(1.0);
			}
			IJ.showStatus(it.title() + ": " + done + " images processed, saved in " + outDir);
		}, "Side Toolbar: folder").start();
	}
}
