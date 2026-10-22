package sidetoolbar;

import ij.IJ;
import ij.Macro;
import ij.Menus;

import java.io.File;

/** Runs command, macro and script buttons, each on its own thread so the toolbar never freezes. */
final class Runner {

	private Runner() { }

	static void run(final Item it) {
		switch (it.kind) {
			case COMMAND:
				if (Menus.getCommands() != null && Menus.getCommands().get(it.target) == null) {
					IJ.showStatus("Side Toolbar: no command \"" + it.target + "\" in this Fiji");
					IJ.beep();
					return;
				}
				if (it.args.trim().length() == 0) {
					IJ.doCommand(it.target); // like a menu click: own thread, recorded by the Macro Recorder
					return;
				}
				thread(it.title(), new Runnable() { public void run() { IJ.run(it.target, it.args); } });
				return;
			case MACRO:
				thread(it.title(), new Runnable() { public void run() { IJ.runMacro(it.target, ""); } });
				return;
			case SCRIPT:
				final File f = new File(it.target);
				if (!f.exists()) {
					IJ.error("Side Toolbar", "File not found:\n" + it.target);
					return;
				}
				thread(it.title(), new Runnable() {
					public void run() {
						String n = f.getName().toLowerCase();
						if (n.endsWith(".ijm") || n.endsWith(".txt")) IJ.runMacroFile(f.getPath(), it.args);
						else IJ.run("Run...", "path=[" + f.getPath() + "]"); // other script languages through Fiji
					}
				});
				return;
			default:
		}
	}

	private static void thread(String name, final Runnable r) {
		Thread t = new Thread(new Runnable() {
			public void run() {
				try {
					r.run();
				} catch (Throwable e) {
					if (!Macro.MACRO_CANCELED.equals(e.getMessage())) IJ.handleException(e);
				}
			}
		}, "Side Toolbar: " + name);
		t.start();
	}
}
