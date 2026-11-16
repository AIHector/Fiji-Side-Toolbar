package sidetoolbar;
import ij.ImagePlus;import ij.process.ByteProcessor;import java.util.*;
public final class ShelfOrderTests {
 static int passed;
 static void check(boolean value,String why){if(!value)throw new AssertionError(why);passed++;}
 static ShelfView.Tile tile(Shelf.Section section,String name){ShelfView.Tile t=new ShelfView.Tile();t.section=section;t.kind=section.kind.equals(Shelf.MACROS)?"macro":"window";t.item=Shelf.launcher(name,name,name,"");return t;}
 public static void main(String[]args){try{
  Shelf s=new Shelf();Shelf.Section windows=new Shelf.Section(Shelf.WINDOWS),macros=new Shelf.Section(Shelf.MACROS),images=new Shelf.Section(Shelf.IMAGES);
  s.sections.addAll(Arrays.asList(windows,images,macros));Shelf.all.clear();Shelf.all.add(s);
  ShelfView.Tile a=tile(windows,"A"),b=tile(windows,"B"),c=tile(windows,"C");windows.items.addAll(Arrays.asList(a.item,b.item,c.item));
  List<ShelfView.Tile> original=Arrays.asList(a,b,c),moved=ShelfOrder.moved(original,a,2);
  check(moved.get(2)==a&&original.get(0)==a,"preview move does not mutate original order");
  ShelfOrder.apply(windows,moved);List<ShelfView.Tile> regenerated=new ArrayList<>(original);ShelfOrder.sort(windows,regenerated);
  check(regenerated.get(0)==b&&regenerated.get(2)==a,"regenerated windows use saved order");
  ShelfView.Tile d=tile(windows,"D");regenerated.add(d);ShelfOrder.sort(windows,regenerated);check(regenerated.get(3)==d,"new tools append without displacing saved order");
  check(ShelfOrder.equal(original,ShelfOrder.moved(original,a,0)),"unchanged drop is a no-op");
  windows.windowOrder.add("unavailable\twindow\nname");windows.hiddenWindows.add("hidden\tname");
  Shelf parsed=Shelf.parse(Shelf.serialize()).get(0);
  check(parsed.sections.get(0).windowOrder.equals(windows.windowOrder),"window order escapes and round-trips");
  check(parsed.sections.get(0).hiddenWindows.equals(windows.hiddenWindows),"hidden tool choices round-trip");
  ShelfView.Tile im1=new ShelfView.Tile(),im2=new ShelfView.Tile();im1.section=im2.section=images;im1.kind=im2.kind="image";
  im1.imp=new ImagePlus("session-image-one",new ByteProcessor(2,2));im2.imp=new ImagePlus("session-image-two",new ByteProcessor(2,2));
  ShelfOrder.apply(images,Arrays.asList(im2,im1));List<ShelfView.Tile> imageTiles=new ArrayList<>(Arrays.asList(im1,im2));ShelfOrder.sort(images,imageTiles);
  check(imageTiles.get(0).imp==im2.imp,"images use live object identities");
  check(!Shelf.serialize().contains("session-image")&&!Shelf.serialize().contains("imageOrder"),"image IDs and names are not persisted as reusable identities");
  ShelfView.Tile ma=tile(macros,"Macro A"),mb=tile(macros,"Macro B");macros.items.addAll(Arrays.asList(ma.item,mb.item));
  ShelfOrder.apply(macros,Arrays.asList(mb,ma));check(macros.items.get(0)==mb.item,"macro reordering retains button objects and settings");
  parsed=Shelf.parse(Shelf.serialize()).get(0);check(parsed.sections.get(2).items.get(0).target.equals("Macro B"),"macro order survives saved configuration");
  ShelfView.Tile copy=tile(windows,"A");copy.item=a.item;check(ShelfOrder.same(copy,a),"a refreshed tile has stable button identity");
  copy.section=macros;check(!ShelfOrder.same(copy,a),"categories cannot impersonate each other's tiles");
  ShelfView.Tile duplicate=tile(windows,"A");windows.items.add(duplicate.item);
  check(!ShelfOrder.windowKey(a).equals(ShelfOrder.windowKey(duplicate)),"duplicate launchers have independent persistent identities");
  ShelfOrder.apply(windows,Arrays.asList(duplicate,b,a,c));a.item.label="Renamed A";List<ShelfView.Tile> repeated=new ArrayList<>(Arrays.asList(a,b,c,duplicate));ShelfOrder.sort(windows,repeated);
  check(repeated.get(0)==duplicate&&repeated.get(2)==a,"duplicate buttons reorder independently and renaming preserves position");
  String key=ShelfOrder.windowKey(a);parsed=Shelf.parse(Shelf.serialize()).get(0);ShelfView.Tile reloaded=new ShelfView.Tile();reloaded.section=parsed.sections.get(0);reloaded.item=reloaded.section.items.get(0);
  check(ShelfOrder.windowKey(reloaded).equals(key),"launcher identity survives configuration reload");
  System.out.println(passed+" shelf ordering checks passed");System.exit(0);
 }catch(Throwable e){e.printStackTrace();System.exit(1);}}
}
