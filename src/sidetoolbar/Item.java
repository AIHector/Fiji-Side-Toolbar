package sidetoolbar;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** One entry of the side toolbar: a tool, a command, a macro, a script, a group of entries, or a divider. */
final class Item {

	enum Kind {
		TOOL,      // one of ImageJ's built-in tools; target = rectangle, oval, polygon, freehand, line, angle, point, wand, text, zoom, hand, dropper
		SLOT,      // a macro or plugin tool installed in ImageJ's toolbar; target = its name ("Stacks Menu Tool", ...)
		MORE,      // the ">>" menu: switch toolsets, add tools
		COMMAND,   // a Fiji menu command; target = command name, args = its options string
		MACRO,     // target = macro code
		SCRIPT,    // target = path of a macro or script file
		GROUP,     // a button that opens its children as a menu
		SEPARATOR, // a thin line
		SPRING     // empty space that pushes the following entries to the bottom
	}

	Kind kind;
	String target = "";
	String args = "";
	String label = "";
	String icon = "";   // "" = automatic; "text:Ab", "ij:<ImageJ icon code>", "file:/path.png", "tool:zoom"
	String color = "";  // "#rrggbb" for text and automatic icons; "" = automatic
	final List<Item> children = new ArrayList<Item>();

	Item(Kind kind, String target) {
		this.kind = kind;
		this.target = target == null ? "" : target;
	}

	static Item tool(String name) { return new Item(Kind.TOOL, name); }

	boolean isButton() { return kind != Kind.SEPARATOR && kind != Kind.SPRING; }

	boolean isTool() { return kind == Kind.TOOL || kind == Kind.SLOT; }

	/** The name shown in tooltips, menus and label mode. */
	String title() {
		if (label.length() > 0) return label;
		switch (kind) {
			case TOOL: return Bridge.builtinTitle(target);
			case SLOT: return target;
			case MORE: return "More tools (switch toolsets, add tools)";
			case COMMAND: return target.endsWith("...") ? target.substring(0, target.length() - 3) : target;
			case MACRO: return "Macro";
			case SCRIPT: return new File(target).getName();
			case GROUP: return "Group";
			case SEPARATOR: return "Separator";
			case SPRING: return "Spring";
		}
		return target;
	}

	/** A short name for label mode ("Rectangle", "Developer Menu"); the full title stays in the tooltip. */
	String shortTitle() {
		if (label.length() > 0) return label;
		switch (kind) {
			case TOOL: return Bridge.builtinShort(target);
			case SLOT: {
				String t = target.replaceAll("\\s*\\(.*\\)", "");
				for (String end : new String[] {" Action Tool", " Menu Tool", " Tool"})
					if (t.endsWith(end)) return t.substring(0, t.length() - end.length()) + (end.equals(" Menu Tool") ? " Menu" : "");
				return t;
			}
			case MORE: return "More tools";
			default: return title();
		}
	}

	Item copy() {
		Item c = new Item(kind, target);
		c.args = args;
		c.label = label;
		c.icon = icon;
		c.color = color;
		for (Item ch : children) c.children.add(ch.copy());
		return c;
	}

	// ---------------------------------------------------------------- text format
	// One entry per line: the kind, then tab-separated key=value fields. A group line is followed by its
	// children and closed by a line "end". Tabs, newlines and backslashes in values are escaped.

	static String escape(String s) {
		StringBuilder b = new StringBuilder();
		for (char c : s.toCharArray()) {
			if (c == '\\') b.append("\\\\");
			else if (c == '\t') b.append("\\t");
			else if (c == '\n') b.append("\\n");
			else if (c == '\r') continue;
			else b.append(c);
		}
		return b.toString();
	}

	static String unescape(String s) {
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '\\' && i + 1 < s.length()) {
				char n = s.charAt(++i);
				b.append(n == 't' ? '\t' : n == 'n' ? '\n' : n);
			} else b.append(c);
		}
		return b.toString();
	}

	private void field(StringBuilder b, String key, String value) {
		if (value.length() > 0) b.append('\t').append(key).append('=').append(escape(value));
	}

	void write(StringBuilder b, String indent) {
		b.append(indent).append(kind.name().toLowerCase());
		field(b, "target", target);
		field(b, "args", args);
		field(b, "label", label);
		field(b, "icon", icon);
		field(b, "color", color);
		b.append('\n');
		if (kind == Kind.GROUP) {
			for (Item c : children) c.write(b, indent + "  ");
			b.append(indent).append("end\n");
		}
	}

	static String write(List<Item> items) {
		StringBuilder b = new StringBuilder();
		for (Item i : items) i.write(b, "");
		return b.toString();
	}

	/** Parses one entry line; returns null for "end", blank lines and comments. Throws on an unknown kind. */
	static Item parseLine(String line) {
		String t = line.trim();
		if (t.length() == 0 || t.startsWith("#") || t.equals("end")) return null;
		String[] parts = line.trim().split("\t");
		Item it = new Item(Kind.valueOf(parts[0].trim().toUpperCase()), "");
		for (int i = 1; i < parts.length; i++) {
			int eq = parts[i].indexOf('=');
			if (eq < 0) continue;
			String k = parts[i].substring(0, eq), v = unescape(parts[i].substring(eq + 1));
			if (k.equals("target")) it.target = v;
			else if (k.equals("args")) it.args = v;
			else if (k.equals("label")) it.label = v;
			else if (k.equals("icon")) it.icon = v;
			else if (k.equals("color")) it.color = v;
		}
		return it;
	}

	/** Parses a whole configuration; groups nest until their "end" line. */
	static List<Item> parse(String text) {
		List<Item> top = new ArrayList<Item>();
		List<List<Item>> stack = new ArrayList<List<Item>>();
		stack.add(top);
		for (String line : text.split("\n")) {
			String t = line.trim();
			if (t.equals("end")) {
				if (stack.size() > 1) stack.remove(stack.size() - 1);
				continue;
			}
			Item it = parseLine(line);
			if (it == null) continue;
			stack.get(stack.size() - 1).add(it);
			if (it.kind == Kind.GROUP) stack.add(it.children);
		}
		return top;
	}
}
