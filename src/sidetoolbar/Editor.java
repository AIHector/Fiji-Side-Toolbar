package sidetoolbar;

import ij.IJ;
import ij.Menus;
import ij.io.OpenDialog;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Menu;
import java.awt.MenuBar;
import java.awt.MenuItem;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.TransferHandler;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** The dialogs: "Add Buttons" (search everything, double-click or drag onto the bar) and "Edit Button". */
final class Editor {

	private Editor() { }

	static Frame owner() { return IJ.getInstance(); }

	// ---------------------------------------------------------------- catalogue

	/** One line of the Add list: an item to add plus where it comes from (menu path, "Tool", ...). */
	static final class Entry {
		final Item item;
		final String where;
		final String special; // null, or "macro", "script", "group": opens a dialog instead of adding directly
		final String search;
		private ImageIcon icon;

		ImageIcon icon() {
			if (icon == null) icon = new ImageIcon(Icons.render(item, 22).getScaledInstance(22, 22, java.awt.Image.SCALE_SMOOTH));
			return icon;
		}

		Entry(Item item, String where, String special) {
			this.item = item;
			this.where = where;
			this.special = special;
			this.search = (item.title() + " " + item.target + " " + where).toLowerCase(Locale.US);
		}
	}

	/** Menu path of every command ("Process > Filters"), read from Fiji's menu bar. */
	static Map<String, String> menuPaths() {
		Map<String, String> m = new HashMap<String, String>();
		MenuBar mb = Menus.getMenuBar();
		if (mb != null) for (int i = 0; i < mb.getMenuCount(); i++) walk(mb.getMenu(i), mb.getMenu(i).getLabel(), m);
		return m;
	}

	private static void walk(Menu menu, String path, Map<String, String> m) {
		for (int i = 0; i < menu.getItemCount(); i++) {
			MenuItem mi = menu.getItem(i);
			if (mi instanceof Menu) walk((Menu) mi, path + " › " + mi.getLabel(), m);
			else if (mi.getLabel() != null && !m.containsKey(mi.getLabel())) m.put(mi.getLabel(), path);
		}
	}

	static List<Entry> catalogue() {
		List<Entry> l = new ArrayList<Entry>();
		l.add(new Entry(special(Item.Kind.SEPARATOR, "text:\u2014"), "Layout: a thin line between buttons", null));
		l.add(new Entry(special(Item.Kind.SPRING, "text:\u2195"), "Layout: pushes the next buttons to the bottom", null));
		l.add(new Entry(special(Item.Kind.GROUP, "text:\u25a4"), "Layout: a button that opens a menu of buttons", "group"));
		l.add(new Entry(special(Item.Kind.MACRO, "text:{ }"), "Write macro code for this button", "macro"));
		l.add(new Entry(special(Item.Kind.SCRIPT, "text:\u25b6"), "Run a macro or script file (.ijm, .py, .groovy, ...)", "script"));
		for (String t : Store.BUILTIN) l.add(new Entry(Item.tool(t), "ImageJ tool", null));
		for (String n : Bridge.slotNames()) l.add(new Entry(new Item(Item.Kind.SLOT, n), "Toolbar tool (loaded now)", null));
		l.add(new Entry(new Item(Item.Kind.MORE, ""), "ImageJ tool: the » menu", null));
		List<String> rec;
		synchronized (Dock.recent) { rec = new ArrayList<String>(Dock.recent); }
		Map<String, String> paths = menuPaths();
		for (String c : rec) l.add(new Entry(new Item(Item.Kind.COMMAND, c), "Recent · " + pathOf(paths, c), null));
		if (Menus.getCommands() != null) {
			List<String> cmds = new ArrayList<String>();
			for (Object k : Menus.getCommands().keySet()) cmds.add((String) k);
			Collections.sort(cmds, String.CASE_INSENSITIVE_ORDER);
			for (String c : cmds) l.add(new Entry(new Item(Item.Kind.COMMAND, c), pathOf(paths, c), null));
		}
		return l;
	}

	/** A catalogue entry with a fixed icon (the icon is not copied to the toolbar). */
	private static Item special(Item.Kind kind, String icon) {
		Item it = new Item(kind, "");
		it.icon = icon;
		return it;
	}

	static String pathOf(Map<String, String> paths, String c) {
		String p = paths.get(c);
		return p != null ? p : "Command";
	}

	static final class Renderer extends DefaultListCellRenderer {
		public Component getListCellRendererComponent(JList<?> list, Object v, int i, boolean sel, boolean focus) {
			Entry e = (Entry) v;
			String title = e.item.kind == Item.Kind.MACRO ? "Macro..." : e.item.kind == Item.Kind.SCRIPT ? "Script or macro file..."
				: e.item.kind == Item.Kind.GROUP ? "Group..." : e.item.title();
			String html = "<html>" + Dock.Tip.esc(title) + " <font color='#8a8a8a'>&nbsp;"
				+ Dock.Tip.esc(e.where) + "</font></html>";
			JLabel l = (JLabel) super.getListCellRendererComponent(list, html, i, sel, focus);
			l.setIcon(e.icon());
			l.setIconTextGap(8);
			l.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
			return l;
		}
	}

	/** A search field over a filtered list. */
	static final class Finder extends JPanel {
		final JTextField field = new JTextField();
		final Model model = new Model();
		final JList<Entry> list = new JList<Entry>(model);
		private final List<Entry> all;

		/** The filtered entries; replaced in one step per keystroke (thousands of commands). */
		static final class Model extends javax.swing.AbstractListModel<Entry> {
			private List<Entry> shown = new ArrayList<Entry>();
			public int getSize() { return shown.size(); }
			public Entry getElementAt(int i) { return shown.get(i); }
			boolean isEmpty() { return shown.isEmpty(); }
			int size() { return shown.size(); }

			void set(List<Entry> l) {
				int before = shown.size();
				shown = l;
				if (before > 0) fireIntervalRemoved(this, 0, before - 1);
				if (!l.isEmpty()) fireIntervalAdded(this, 0, l.size() - 1);
			}
		}

		Finder(List<Entry> all) {
			super(new BorderLayout(0, 6));
			this.all = all;
			list.setCellRenderer(new Renderer());
			list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
			list.setVisibleRowCount(16);
			// fixed row size: the list does not measure (and draw) thousands of rows to lay itself out
			list.setFixedCellHeight(28);
			list.setFixedCellWidth(560);
			field.putClientProperty("JTextField.placeholderText", "Search commands and tools (e.g. gauss, threshold, lut)");
			add(field, BorderLayout.NORTH);
			JScrollPane sp = new JScrollPane(list);
			sp.setPreferredSize(new Dimension(520, 420));
			add(sp, BorderLayout.CENTER);
			field.getDocument().addDocumentListener(new DocumentListener() {
				public void insertUpdate(DocumentEvent e) { filter(); }
				public void removeUpdate(DocumentEvent e) { filter(); }
				public void changedUpdate(DocumentEvent e) { filter(); }
			});
			field.addKeyListener(new KeyAdapter() {
				public void keyPressed(KeyEvent e) {
					int i = list.getSelectedIndex();
					if (e.getKeyCode() == KeyEvent.VK_DOWN && i < model.size() - 1) select(i + 1);
					else if (e.getKeyCode() == KeyEvent.VK_UP && i > 0) select(i - 1);
				}
			});
			filter();
		}

		static int rank(Entry e, String w) {
			String t = e.item.title().toLowerCase(Locale.US);
			if (t.startsWith(w)) return 0;
			if (t.contains(" " + w) || t.contains("(" + w)) return 1;
			return 2;
		}

		private void select(int i) {
			list.setSelectedIndex(i);
			list.ensureIndexIsVisible(i);
		}

		void filter() {
			String[] words = field.getText().toLowerCase(Locale.US).trim().split("\\s+");
			List<Entry> l = new ArrayList<Entry>();
			for (Entry e : all) {
				boolean ok = true;
				for (String w : words) if (w.length() > 0 && !e.search.contains(w)) { ok = false; break; }
				if (ok) l.add(e);
			}
			final String first = words.length > 0 ? words[0] : "";
			if (first.length() > 0) {
				// names starting with the first word, then names with a word starting with it, then the rest
				java.util.Collections.sort(l, (a, b) -> rank(a, first) - rank(b, first));
			}
			model.set(l);
			if (!model.isEmpty()) {
				list.setSelectedIndex(0);
				list.ensureIndexIsVisible(0);
			}
		}
	}

	// ---------------------------------------------------------------- Add dialog

	private static JDialog addDialog;
	private static int addAt = -1;

	/** Shows the Add dialog; new buttons go to visible index "at" (-1: before the ">>" button, or at the end). */
	static void showAdd(int at) {
		addAt = at;
		if (addDialog != null && addDialog.isDisplayable()) {
			addDialog.toFront();
			return;
		}
		final JDialog d = new JDialog(owner(), "Add Buttons to the Side Toolbar", false);
		final Finder f = new Finder(catalogue());
		f.list.setDragEnabled(true);
		f.list.setTransferHandler(new TransferHandler() {
			public int getSourceActions(JComponent c) { return COPY; }

			protected Transferable createTransferable(JComponent c) {
				List<Item> l = new ArrayList<Item>();
				for (Entry e : f.list.getSelectedValuesList()) {
					if (e.special != null && !e.special.equals("group")) continue;
					Item it = e.item.copy();
					if (!it.isButton() || it.kind == Item.Kind.GROUP) it.icon = "";
					if (it.kind == Item.Kind.GROUP) it.label = "New group";
					l.add(it);
				}
				return l.isEmpty() ? null : new StringSelection(Dock.FLAVOR_PREFIX + Item.write(l));
			}
		});
		Runnable add = () -> {
			List<Entry> sel = f.list.getSelectedValuesList();
			if (sel.isEmpty()) return;
			List<Item> l = new ArrayList<Item>();
			for (Entry e : sel) {
				if ("macro".equals(e.special)) { newOfKind(Item.Kind.MACRO, addAt); return; }
				if ("script".equals(e.special)) { newScript(addAt); return; }
				if ("group".equals(e.special)) { newGroup(addAt); return; }
				Item it = e.item.copy();
				if (it.kind == Item.Kind.SEPARATOR || it.kind == Item.Kind.SPRING) it.icon = "";
				l.add(it);
			}
			if (Dock.instance != null) Dock.instance.insert(l, null, addAt);
			if (addAt >= 0) addAt += l.size();
		};
		f.list.addMouseListener(new MouseAdapter() {
			public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) add.run(); }
		});
		f.field.addActionListener(e -> add.run());
		JPanel south = new JPanel(new BorderLayout());
		JLabel hint = new JLabel("<html><div style='width:330px'><font color='#666666'>Type to search all Fiji commands and tools. "
			+ "Double-click or press Return to add, or drag entries onto the toolbar where you want them.</font></div></html>");
		hint.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 10));
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		JButton addB = new JButton("Add"), close = new JButton("Close");
		addB.addActionListener(e -> add.run());
		close.addActionListener(e -> d.dispose());
		buttons.add(addB);
		buttons.add(close);
		south.add(hint, BorderLayout.CENTER);
		south.add(buttons, BorderLayout.EAST);
		JPanel p = new JPanel(new BorderLayout(0, 8));
		p.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		p.add(f, BorderLayout.CENTER);
		p.add(south, BorderLayout.SOUTH);
		d.setContentPane(p);
		d.pack();
		place(d);
		d.setVisible(true);
		d.toFront();
		javax.swing.SwingUtilities.invokeLater(() -> f.field.requestFocus());
		addDialog = d;
	}

	/** Puts a dialog beside the side toolbar. */
	static void place(java.awt.Window d) {
		Dock dock = Dock.instance;
		if (dock == null) { d.setLocationRelativeTo(null); return; }
		java.awt.Rectangle b = dock.window.getBounds();
		int x = Store.side().equals("right") ? b.x - d.getWidth() - 8 : b.x + b.width + 8;
		d.setLocation(x, b.y + 60);
	}

	/** A modal command chooser (for the Edit dialog); returns null if cancelled. */
	static String chooseCommand(java.awt.Window parent) {
		List<Entry> cmds = new ArrayList<Entry>();
		for (Entry e : catalogue()) if (e.item.kind == Item.Kind.COMMAND && !e.where.startsWith("Recent")) cmds.add(e);
		final Finder f = new Finder(cmds);
		final JDialog d = new JDialog(parent, "Choose a Command", java.awt.Dialog.ModalityType.APPLICATION_MODAL);
		final String[] result = {null};
		Runnable ok = () -> {
			Entry e = f.list.getSelectedValue();
			if (e != null) result[0] = e.item.target;
			d.dispose();
		};
		f.field.addActionListener(e -> ok.run());
		f.list.addMouseListener(new MouseAdapter() {
			public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) ok.run(); }
		});
		JPanel p = new JPanel(new BorderLayout(0, 8));
		p.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		p.add(f, BorderLayout.CENTER);
		JPanel b = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		JButton okB = new JButton("OK"), cancel = new JButton("Cancel");
		okB.addActionListener(e -> ok.run());
		cancel.addActionListener(e -> d.dispose());
		b.add(cancel);
		b.add(okB);
		p.add(b, BorderLayout.SOUTH);
		d.setContentPane(p);
		d.pack();
		d.setLocationRelativeTo(parent);
		d.setVisible(true);
		return result[0];
	}

	// ---------------------------------------------------------------- new entries

	static Item itemForFile(File f) {
		Item it = new Item(Item.Kind.SCRIPT, f.getAbsolutePath());
		String n = f.getName();
		int dot = n.lastIndexOf('.');
		it.label = (dot > 0 ? n.substring(0, dot) : n).replace('_', ' ');
		return it;
	}

	static void newOfKind(Item.Kind kind, int at) {
		Item it = new Item(kind, "");
		if (edit(it, true) && Dock.instance != null) Dock.instance.insert(Dock.single(it), null, at);
	}

	static void newScript(int at) {
		OpenDialog od = new OpenDialog("Choose a macro or script file");
		if (od.getFileName() == null) return;
		Item it = itemForFile(new File(od.getDirectory(), od.getFileName()));
		if (edit(it, true) && Dock.instance != null) Dock.instance.insert(Dock.single(it), null, at);
	}

	static void newGroup(int at) {
		Item g = new Item(Item.Kind.GROUP, "");
		g.label = "New group";
		if (edit(g, true) && Dock.instance != null) Dock.instance.insert(Dock.single(g), null, at);
	}

	// ---------------------------------------------------------------- Edit dialog

	static void edit(Item it) {
		edit(it, false);
	}

	/** Edits an entry in place (or a new one); returns true if confirmed. */
	static boolean edit(final Item it, boolean isNew) {
		final Item work = it.copy();
		final JDialog d = new JDialog(owner(), isNew ? "New Button" : "Edit Button", true);
		JPanel p = new JPanel(new GridBagLayout());
		p.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(4, 4, 4, 4);
		c.anchor = GridBagConstraints.WEST;
		c.fill = GridBagConstraints.HORIZONTAL;
		int row = 0;

		final Preview preview = new Preview(work);
		final JTextField label = new JTextField(work.label, 28);
		addRow(p, c, row++, "Name:", label, null);
		label.getDocument().addDocumentListener(onChange(() -> { work.label = label.getText(); preview.repaint(); }));

		final JTextField target = new JTextField(work.target, 28);
		final JTextField args = new JTextField(work.args, 28);
		final JTextArea code = new JTextArea(work.target, 10, 46);
		final GroupEditor children = work.kind == Item.Kind.GROUP ? new GroupEditor(work) : null;
		switch (work.kind) {
			case COMMAND: {
				JButton choose = new JButton("Choose...");
				choose.addActionListener(e -> {
					String s = chooseCommand(d);
					if (s != null) target.setText(s);
				});
				addRow(p, c, row++, "Command:", target, choose);
				addRow(p, c, row++, "Options:", args, null);
				addNote(p, c, row++, "Options as the Macro Recorder writes them, e.g. \"sigma=2 stack\". Empty: the command asks, as from its menu.");
				break;
			}
			case MACRO: {
				code.setFont(new Font("Monospaced", Font.PLAIN, 12));
				addRow(p, c, row++, "Macro code:", new JScrollPane(code), null);
				addNote(p, c, row++, "ImageJ macro language, e.g. run(\"Gaussian Blur...\", \"sigma=2\"); The Macro Recorder writes it for you.");
				break;
			}
			case SCRIPT: {
				JButton browse = new JButton("Browse...");
				browse.addActionListener(e -> {
					OpenDialog od = new OpenDialog("Choose a macro or script file");
					if (od.getFileName() != null) target.setText(new File(od.getDirectory(), od.getFileName()).getPath());
				});
				addRow(p, c, row++, "File:", target, browse);
				addRow(p, c, row++, "Argument:", args, null);
				addNote(p, c, row++, ".ijm macros get the argument through getArgument(); other languages run through Fiji.");
				break;
			}
			case GROUP:
				addRow(p, c, row++, "Buttons:", children, null);
				addNote(p, c, row++, "Drag buttons from the toolbar onto the middle of the group to put them in it.");
				break;
			case TOOL: case SLOT: case MORE:
				addRow(p, c, row++, "Tool:", new JLabel(work.kind == Item.Kind.MORE ? "ImageJ's » menu"
					: work.kind == Item.Kind.TOOL ? Bridge.builtinTitle(work.target) : work.target), null);
				break;
			default:
		}
		target.getDocument().addDocumentListener(onChange(() -> { work.target = target.getText(); preview.repaint(); }));

		// icon
		final String[] kinds = {"Automatic", "Letters or emoji", "ImageJ icon code", "Image file", "Picture of an ImageJ tool"};
		final String[] prefixes = {"", "text:", "ij:", "file:", "tool:"};
		final JComboBox<String> iconKind = new JComboBox<String>(kinds);
		final JTextField iconValue = new JTextField(20);
		final JComboBox<String> toolPic = new JComboBox<String>(Store.BUILTIN);
		int k = 0;
		for (int i = 1; i < prefixes.length; i++) if (work.icon.startsWith(prefixes[i])) k = i;
		iconKind.setSelectedIndex(k);
		iconValue.setText(k == 0 ? "" : work.icon.substring(prefixes[k].length()));
		if (k == 4) toolPic.setSelectedItem(iconValue.getText());
		final JButton iconBrowse = new JButton("Browse...");
		final JButton colorB = new JButton("Colour...");
		final JPanel valuePanel = new JPanel(new BorderLayout(4, 0));
		final Runnable syncIcon = () -> {
			int i = iconKind.getSelectedIndex();
			String v = i == 4 ? String.valueOf(toolPic.getSelectedItem()) : iconValue.getText();
			work.icon = i == 0 ? "" : prefixes[i] + v;
			valuePanel.removeAll();
			if (i == 4) valuePanel.add(toolPic, BorderLayout.CENTER);
			else if (i != 0) valuePanel.add(iconValue, BorderLayout.CENTER);
			if (i == 3) valuePanel.add(iconBrowse, BorderLayout.EAST);
			valuePanel.revalidate();
			valuePanel.repaint();
			colorB.setEnabled(i == 0 || i == 1);
			preview.repaint();
		};
		iconKind.addActionListener(e -> syncIcon.run());
		toolPic.addActionListener(e -> syncIcon.run());
		iconValue.getDocument().addDocumentListener(onChange(() -> {
			int i = iconKind.getSelectedIndex();
			if (i != 0 && i != 4) { work.icon = prefixes[i] + iconValue.getText(); preview.repaint(); }
		}));
		iconBrowse.addActionListener(e -> {
			OpenDialog.setDefaultDirectory(IJ.getDir("macros") + "toolsets" + File.separator + "icons");
			OpenDialog od = new OpenDialog("Choose an icon image (PNG, GIF, JPEG)");
			if (od.getFileName() != null) iconValue.setText(new File(od.getDirectory(), od.getFileName()).getPath());
		});
		colorB.addActionListener(e -> {
			Color col = JColorChooser.showDialog(d, "Icon Colour", Icons.colorOf(work));
			if (col != null) { work.color = Icons.hex(col); preview.repaint(); }
		});
		JPanel iconRow = new JPanel(new BorderLayout(6, 0));
		JPanel left = new JPanel(new BorderLayout(4, 4));
		left.add(iconKind, BorderLayout.NORTH);
		left.add(valuePanel, BorderLayout.CENTER);
		JPanel colorRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		JButton gallery = new JButton("Gallery...");
		gallery.addActionListener(e -> {
			String spec = Gallery.choose(d, work);
			if (spec == null) return;
			int i = 0;
			for (int j = 1; j < prefixes.length; j++) if (spec.startsWith(prefixes[j])) i = j;
			String v = spec.substring(prefixes[i].length());
			iconKind.setSelectedIndex(i);
			if (i == 4) toolPic.setSelectedItem(v); else iconValue.setText(v);
			syncIcon.run();
		});
		JButton autoColor = new JButton("Automatic colour");
		autoColor.addActionListener(e -> { work.color = ""; preview.repaint(); });
		colorRow.add(gallery);
		colorRow.add(colorB);
		colorRow.add(autoColor);
		left.add(colorRow, BorderLayout.SOUTH);
		iconRow.add(left, BorderLayout.CENTER);
		iconRow.add(preview, BorderLayout.EAST);
		addRow(p, c, row++, "Icon:", iconRow, null);
		addNote(p, c, row++, "Letters: up to 3 characters or an emoji. ImageJ icon code: the language of macro tool icons, e.g. "
			+ "\"C037T0b11BT8b11I\" (see imagej.net/ij/developer/macro/macros.html#icons).");
		syncIcon.run();

		final boolean[] ok = {false};
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		JButton okB = new JButton("OK"), cancel = new JButton("Cancel");
		okB.addActionListener(e -> {
			if (work.kind == Item.Kind.MACRO) work.target = code.getText();
			else if (work.kind == Item.Kind.COMMAND || work.kind == Item.Kind.SCRIPT) work.target = target.getText().trim();
			work.args = args.getText().trim();
			if (work.kind == Item.Kind.COMMAND && Menus.getCommands() != null && Menus.getCommands().get(work.target) == null) {
				IJ.error("Side Toolbar", "There is no command \"" + work.target + "\" in Fiji's menus.\nUse Choose... to pick one.");
				return;
			}
			if (children != null) {
				work.children.clear();
				work.children.addAll(children.items());
			}
			ok[0] = true;
			d.dispose();
		});
		cancel.addActionListener(e -> d.dispose());
		buttons.add(cancel);
		buttons.add(okB);
		c.gridx = 0; c.gridy = row; c.gridwidth = 3;
		p.add(buttons, c);
		d.getRootPane().setDefaultButton(okB);
		d.setContentPane(p);
		d.pack();
		place(d);
		d.setVisible(true);
		if (!ok[0]) return false;
		if (!isNew) Store.checkpoint("Edit " + it.title());
		it.target = work.target;
		it.args = work.args;
		it.label = work.label;
		it.icon = work.icon;
		it.color = work.color;
		if (it.kind == Item.Kind.GROUP) {
			it.children.clear();
			it.children.addAll(work.children);
		}
		if (!isNew) {
			Store.save();
			if (Dock.instance != null) Dock.instance.changed();
		}
		return true;
	}

	private static void addRow(JPanel p, GridBagConstraints c, int row, String name, Component field, Component extra) {
		c.gridy = row;
		c.gridx = 0; c.gridwidth = 1; c.weightx = 0;
		c.anchor = GridBagConstraints.NORTHWEST;
		JLabel l = new JLabel(name);
		l.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
		p.add(l, c);
		c.gridx = 1; c.weightx = 1; c.gridwidth = extra == null ? 2 : 1;
		p.add(field, c);
		if (extra != null) { c.gridx = 2; c.weightx = 0; c.gridwidth = 1; p.add(extra, c); }
	}

	private static void addNote(JPanel p, GridBagConstraints c, int row, String text) {
		JLabel l = new JLabel("<html><div style='width:360px'><font color='#777777'>" + Dock.Tip.esc(text) + "</font></div></html>");
		l.setFont(l.getFont().deriveFont(11f));
		c.gridy = row; c.gridx = 1; c.gridwidth = 2; c.weightx = 1;
		p.add(l, c);
	}

	static DocumentListener onChange(final Runnable r) {
		return new DocumentListener() {
			public void insertUpdate(DocumentEvent e) { r.run(); }
			public void removeUpdate(DocumentEvent e) { r.run(); }
			public void changedUpdate(DocumentEvent e) { r.run(); }
		};
	}

	/** Large icon preview, at the toolbar's own look. */
	static final class Preview extends JComponent {
		private final Item item;

		Preview(Item item) {
			this.item = item;
			setPreferredSize(new Dimension(64, 64));
		}

		protected void paintComponent(Graphics g0) {
			Graphics2D g = (Graphics2D) g0;
			g.setColor(Dock.BG);
			g.fillRoundRect(0, 0, 63, 63, 10, 10);
			g.setColor(Dock.EDGE);
			g.drawRoundRect(0, 0, 63, 63, 10, 10);
			Icons.paint(g, item, 4, 4, 56);
		}
	}

	/** The list of a group's buttons, with reordering. */
	static final class GroupEditor extends JPanel {
		private final DefaultListModel<Entry> model = new DefaultListModel<Entry>();
		private final JList<Entry> list = new JList<Entry>(model);

		GroupEditor(Item group) {
			super(new BorderLayout(6, 0));
			for (Item i : group.children) model.addElement(new Entry(i, kindName(i), null));
			list.setCellRenderer(new Renderer());
			list.setVisibleRowCount(7);
			add(new JScrollPane(list), BorderLayout.CENTER);
			JPanel b = new JPanel(new GridBagLayout());
			GridBagConstraints c = new GridBagConstraints();
			c.gridx = 0; c.fill = GridBagConstraints.HORIZONTAL; c.insets = new Insets(0, 0, 3, 0);
			b.add(button("Up", () -> move(-1)), c);
			b.add(button("Down", () -> move(1)), c);
			b.add(button("Edit...", () -> {
				Entry e = list.getSelectedValue();
				if (e != null && edit(e.item, true)) list.repaint();
			}), c);
			b.add(button("Add...", () -> {
				String s = chooseCommand(javax.swing.SwingUtilities.getWindowAncestor(this));
				if (s != null) model.addElement(new Entry(new Item(Item.Kind.COMMAND, s), "Command", null));
			}), c);
			b.add(button("Separator", () -> model.addElement(new Entry(new Item(Item.Kind.SEPARATOR, ""), "Separator", null))), c);
			b.add(button("Remove", () -> {
				int i = list.getSelectedIndex();
				if (i >= 0) model.remove(i);
			}), c);
			add(b, BorderLayout.EAST);
		}

		private static String kindName(Item i) {
			switch (i.kind) {
				case COMMAND: return i.target;
				case TOOL: case SLOT: return "Tool";
				default: return i.kind.name().toLowerCase(Locale.US);
			}
		}

		private JButton button(String t, Runnable r) {
			JButton b = new JButton(t);
			b.addActionListener(e -> r.run());
			return b;
		}

		private void move(int d) {
			int i = list.getSelectedIndex(), j = i + d;
			if (i < 0 || j < 0 || j >= model.size()) return;
			Entry e = model.remove(i);
			model.add(j, e);
			list.setSelectedIndex(j);
		}

		List<Item> items() {
			List<Item> l = new ArrayList<Item>();
			for (int i = 0; i < model.size(); i++) l.add(model.get(i).item);
			return l;
		}
	}
}
