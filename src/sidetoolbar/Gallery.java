package sidetoolbar;

import ij.IJ;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

/** A grid of ready-made icons: ImageJ's tool pictures, the toolbar's tool icons, toolset images, symbols. */
final class Gallery {

	private Gallery() { }

	/** Symbols that read well at toolbar size (letters and emoji can also be typed in the Edit dialog). */
	static final String[] SYMBOLS = {
		"★", "●", "■", "▲", "◆", "✕", "✓", "↺", "↻", "↔", "↕",
		"✂", "⚙", "✎", "⎘", "⌖", "Σ", "μ", "Δ", "σ", "√", "±",
		"∅", "∞", "+", "−", "×", "÷", "#", "%", "3D", "2D", "Z", "T", "C", "8b", "16", "32", "RGB",
		"🔬", "🧪", "🧬", "🧠", "📏", "📐", "🎨",
		"🔍", "📊", "📈", "💾", "📂", "🖼", "✨", "🌈",
		"🔄", "📌", "🎯", "⚡", "🔥", "💧", "🌀", "❄"
	};

	static List<String> specs() {
		List<String> l = new ArrayList<String>();
		for (String t : Store.BUILTIN) l.add("tool:" + t);
		for (String n : Bridge.slotNames()) {
			int slot = Bridge.slotIndex(n);
			String[] icons = Bridge.icons();
			if (slot >= 0 && slot < icons.length && icons[slot] != null) l.add("ij:" + icons[slot]);
		}
		String[] files = new File(IJ.getDir("macros") + "toolsets" + File.separator + "icons").list();
		if (files != null) {
			Arrays.sort(files);
			for (String f : files) {
				String n = f.toLowerCase();
				if (n.endsWith(".png") || n.endsWith(".gif") || n.endsWith(".jpg")) l.add("file:" + f);
			}
		}
		for (String s : SYMBOLS) l.add("text:" + s);
		return l;
	}

	/** Shows the gallery; returns the chosen icon specification, or null. */
	static String choose(Window parent, Item like) {
		final JDialog d = new JDialog(parent, "Icon Gallery", java.awt.Dialog.ModalityType.APPLICATION_MODAL);
		final String[] result = {null};
		List<String> specs = specs();
		JPanel grid = new JPanel(new GridLayout(0, 10, 4, 4));
		grid.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		for (final String spec : specs) {
			final Item sample = like.copy();
			sample.icon = spec;
			JComponent cell = new JComponent() {
				boolean over;
				{
					setPreferredSize(new Dimension(44, 44));
					setToolTipText(spec.length() > 40 ? spec.substring(0, 40) + "…" : spec);
					addMouseListener(new MouseAdapter() {
						public void mouseEntered(MouseEvent e) { over = true; repaint(); }
						public void mouseExited(MouseEvent e) { over = false; repaint(); }
						public void mouseClicked(MouseEvent e) { result[0] = spec; d.dispose(); }
					});
				}

				protected void paintComponent(Graphics g0) {
					Graphics2D g = (Graphics2D) g0;
					g.setColor(over ? Dock.HOVER : Dock.BG);
					g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
					Icons.paint(g, sample, (getWidth() - 36) / 2, (getHeight() - 36) / 2, 36);
				}
			};
			grid.add(cell);
		}
		JPanel p = new JPanel(new BorderLayout(0, 6));
		p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		JLabel hint = new JLabel("<html><font color='#666666'>Click an icon. ImageJ tool pictures, the icons of the tools in "
			+ "the toolbar, Fiji's toolset images, and symbols. Letters and symbols use the button's colour.</font></html>");
		p.add(hint, BorderLayout.NORTH);
		JScrollPane sp = new JScrollPane(grid);
		sp.setPreferredSize(new Dimension(500, 360));
		sp.getVerticalScrollBar().setUnitIncrement(16);
		p.add(sp, BorderLayout.CENTER);
		JPanel b = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		JButton cancel = new JButton("Cancel");
		cancel.addActionListener(e -> d.dispose());
		b.add(cancel);
		p.add(b, BorderLayout.SOUTH);
		d.setContentPane(p);
		d.getContentPane().setBackground(Color.white);
		d.pack();
		d.setLocationRelativeTo(parent);
		d.setVisible(true);
		return result[0];
	}
}
