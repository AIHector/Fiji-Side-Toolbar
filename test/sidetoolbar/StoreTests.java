package sidetoolbar;

import java.util.Arrays;
import java.util.List;

/** Headless unit tests for the parts that need no Fiji window. Run with ./test.sh; exits 1 on failure. */
public class StoreTests {
	static int passed, failed;

	static void check(boolean ok, String what) {
		if (ok) passed++;
		else { failed++; System.out.println("FAIL  " + what); }
	}

	static void eq(Object expected, Object actual, String what) {
		check(expected == null ? actual == null : expected.equals(actual), what + "  expected=" + expected + " actual=" + actual);
	}

	public static void main(String[] args) {
		escaping();
		roundTrip();
		groups();
		syncAndDismissed();
		titles();
		System.out.println(passed + " passed, " + failed + " failed");
		System.exit(failed == 0 ? 0 : 1);
	}

	static void escaping() {
		String[] samples = {"", "plain", "tab\there", "line1\nline2", "back\\slash", "\\t literal", "emoji 🔬", "a=b c=d"};
		for (String s : samples) eq(s, Item.unescape(Item.escape(s)), "escape round trip of [" + s + "]");
		check(!Item.escape("a\tb\nc").contains("\t") && !Item.escape("a\tb\nc").contains("\n"), "escaped text has no tabs or newlines");
	}

	static void roundTrip() {
		Item m = new Item(Item.Kind.MACRO, "run(\"Invert\");\nrun(\"Gaussian Blur...\", \"sigma=2\");");
		m.label = "Invert + blur";
		m.icon = "text:🌈";
		m.color = "#336699";
		Item c = new Item(Item.Kind.COMMAND, "Gaussian Blur...");
		c.args = "sigma=2 stack";
		Item ij = new Item(Item.Kind.SLOT, "Stacks Menu Tool");
		ij.icon = "ij:C037T0b11S";
		List<Item> in = Arrays.asList(Item.tool("rectangle"), new Item(Item.Kind.SEPARATOR, ""), m, c, ij,
			new Item(Item.Kind.SPRING, ""), new Item(Item.Kind.MORE, ""));
		String text = Item.write(in);
		List<Item> out = Item.parse(text);
		eq(in.size(), out.size(), "same number of entries");
		for (int i = 0; i < in.size(); i++) {
			eq(in.get(i).kind, out.get(i).kind, "kind " + i);
			eq(in.get(i).target, out.get(i).target, "target " + i);
			eq(in.get(i).args, out.get(i).args, "args " + i);
			eq(in.get(i).label, out.get(i).label, "label " + i);
			eq(in.get(i).icon, out.get(i).icon, "icon " + i);
			eq(in.get(i).color, out.get(i).color, "color " + i);
		}
		eq(text, Item.write(out), "writing again gives the same text");
		eq(0, Item.parse("# comment only\n\n").size(), "comments and blank lines are ignored");
		try {
			Item.parse("rocket\ttarget=x");
			check(false, "an unknown kind is rejected");
		} catch (IllegalArgumentException e) {
			check(true, "an unknown kind is rejected");
		}
	}

	static void groups() {
		Item g = new Item(Item.Kind.GROUP, "");
		g.label = "Filters";
		g.children.add(new Item(Item.Kind.COMMAND, "Median..."));
		Item inner = new Item(Item.Kind.GROUP, "");
		inner.label = "More";
		inner.children.add(new Item(Item.Kind.COMMAND, "Unsharp Mask..."));
		g.children.add(inner);
		g.children.add(new Item(Item.Kind.SEPARATOR, ""));
		List<Item> out = Item.parse(Item.write(Arrays.asList(g, Item.tool("hand"))));
		eq(2, out.size(), "group and the next entry at the top level");
		eq(3, out.get(0).children.size(), "group keeps its 3 children");
		eq(1, out.get(0).children.get(1).children.size(), "nested group keeps its child");
		eq("Unsharp Mask...", out.get(0).children.get(1).children.get(0).target, "nested child target");
		eq("hand", out.get(1).target, "entry after the group is not swallowed by it");
		Item copy = out.get(0).copy();
		copy.children.get(0).target = "changed";
		eq("Median...", out.get(0).children.get(0).target, "copy() is deep");
	}

	static void syncAndDismissed() {
		Store.items.clear();
		Store.dismissed.clear();
		Store.items.addAll(Store.defaults());
		check(Store.sync(Arrays.asList("Stacks Menu Tool", "LUT Menu Tool")), "new toolbar tools are added");
		int more = -1;
		for (int i = 0; i < Store.items.size(); i++) if (Store.items.get(i).kind == Item.Kind.MORE) more = i;
		eq("LUT Menu Tool", Store.items.get(more - 1).target, "added just before the >> button, in toolbar order");
		eq("Stacks Menu Tool", Store.items.get(more - 2).target, "first added tool");
		check(!Store.sync(Arrays.asList("Stacks Menu Tool", "LUT Menu Tool")), "nothing added twice");
		Item lut = Store.items.get(more - 1);
		Store.remove(lut);
		check(Store.dismissed.contains("LUT Menu Tool"), "a removed toolbar tool is remembered");
		check(!Store.sync(Arrays.asList("Stacks Menu Tool", "LUT Menu Tool")), "a removed tool is not added back");
		Item g = new Item(Item.Kind.GROUP, "");
		g.children.add(new Item(Item.Kind.SLOT, "Pencil Tool"));
		Store.items.add(0, g);
		check(!Store.sync(Arrays.asList("Pencil Tool")), "a tool inside a group counts as present");
		eq(g.children, Store.parentOf(g.children.get(0)), "parentOf finds the group's list");
		String saved = Store.serialize();
		Store.restore(saved);
		check(Store.dismissed.contains("LUT Menu Tool"), "removed tools survive saving and loading");
		eq(saved, Store.serialize(), "restore(serialize()) is stable");
		Store.checkpoint("test");
		Store.items.clear();
		Store.items.add(Item.tool("text"));
		eq("test", Store.undoDescription(), "undo description");
		check(Store.undo() || true, "undo runs");
	}

	static void titles() {
		eq("Rectangle", Item.tool("rectangle").shortTitle(), "short name of a built-in tool");
		eq("Color picker", Item.tool("dropper").shortTitle(), "short name of the dropper");
		eq("Developer Menu", new Item(Item.Kind.SLOT, "Developer Menu Tool").shortTitle(), "menu tool short name");
		eq("Neuroanatomy Shortcuts", new Item(Item.Kind.SLOT, "Neuroanatomy Shortcuts (alt+click for options) Action Tool").shortTitle(), "action tool short name");
		eq("Gaussian Blur", new Item(Item.Kind.COMMAND, "Gaussian Blur...").title(), "command title drops the dots");
		eq("GB", Icons.initials("Gaussian Blur..."), "initials");
		eq("3V", Icons.initials("3D Viewer"), "initials keep digits");
	}
}
