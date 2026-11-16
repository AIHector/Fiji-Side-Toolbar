package sidetoolbar;
import ij.*;import ij.gui.Toolbar;import ij.process.ByteProcessor;
import java.awt.*;import java.awt.event.*;import java.awt.image.BufferedImage;import java.io.*;import java.lang.reflect.*;import java.nio.file.*;import java.util.*;import java.util.List;
import javax.swing.*;import javax.imageio.ImageIO;

/** Real components in an isolated ImageJ; events never go to the physical pointer or another process. */
public final class ShelfDragTests {
 static int passed;static File output;
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);passed++;System.out.println("PASS "+why);}
 static Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
 static void pause()throws Exception{Thread.sleep(300);EventQueue.invokeAndWait(()->{});}
 @SuppressWarnings("unchecked") static List<ShelfView.Tile> tiles(ShelfView view)throws Exception{return new ArrayList<>((List<ShelfView.Tile>)field(view,"tiles"));}
 static List<ShelfView.Tile> category(ShelfView view,String kind)throws Exception{List<ShelfView.Tile> result=new ArrayList<>();for(ShelfView.Tile tile:tiles(view))if(tile.section.kind.equals(kind)&&!tile.kind.equals("add"))result.add(tile);return result;}
 static Point center(ShelfView.Tile tile){return new Point(tile.r.x+tile.r.width/2,tile.r.y+tile.r.height/2);}
 static void event(Component component,int kind,Point point)throws Exception{
  EventQueue.invokeAndWait(()->{Point screen=component.getLocationOnScreen();component.dispatchEvent(new MouseEvent(component,kind,System.currentTimeMillis(),kind==MouseEvent.MOUSE_RELEASED?0:InputEvent.BUTTON1_DOWN_MASK,point.x,point.y,screen.x+point.x,screen.y+point.y,1,false,kind==MouseEvent.MOUSE_DRAGGED?MouseEvent.NOBUTTON:MouseEvent.BUTTON1));});
 }
 static void drag(ShelfView view,ShelfView.Tile from,Point to,boolean release)throws Exception{event(view,MouseEvent.MOUSE_PRESSED,center(from));event(view,MouseEvent.MOUSE_DRAGGED,to);if(release){event(view,MouseEvent.MOUSE_RELEASED,to);pause();}}
 static Point after(ShelfView.Tile tile,boolean horizontal){Point p=center(tile);if(horizontal)p.x+=tile.r.width/2-2;else p.y+=tile.r.height/2-2;return p;}
 static void size(ShelfView view,int width,int height)throws Exception{EventQueue.invokeAndWait(()->{view.window.setBounds(100,140,width,height);view.window.setVisible(true);view.window.validate();view.rebuild();});pause();}
 static void screenshot(ShelfView view,String name)throws Exception{EventQueue.invokeAndWait(()->{BufferedImage image=new BufferedImage(view.getWidth(),view.getHeight(),BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();view.paint(g);g.dispose();try{ImageIO.write(image,"png",new File(output,name));}catch(IOException e){throw new RuntimeException(e);}});}
 static ShelfView view(){return Shelf.all.get(0).view;}
 static Shelf.Section section(String kind){for(Shelf.Section c:Shelf.all.get(0).sections)if(c.kind.equals(kind))return c;throw new AssertionError(kind);}
 static ImagePlus image(String name,int value){ByteProcessor bp=new ByteProcessor(24,24);bp.setValue(value);bp.fill();ImagePlus image=new ImagePlus(name,bp);image.show();return image;}
 static void popup(ShelfView view,ShelfView.Tile tile)throws Exception{
  Point p=center(tile);EventQueue.invokeAndWait(()->{try{Method m=ShelfView.class.getDeclaredMethod("menu",MouseEvent.class);m.setAccessible(true);m.invoke(view,new MouseEvent(view,MouseEvent.MOUSE_RELEASED,System.currentTimeMillis(),0,p.x,p.y,1,true,MouseEvent.BUTTON3));}catch(Exception e){throw new RuntimeException(e);}});
 }
 static boolean contains(JComponent menu,String label){for(Component c:menu.getComponents()){if(c instanceof JMenuItem&&((JMenuItem)c).getText().equals(label))return true;if(c instanceof JMenu&&contains(((JMenu)c).getPopupMenu(),label))return true;}return false;}
 public static void main(String[]args)throws Exception{try{
  Thread watchdog=new Thread(()->{try{Thread.sleep(45000);}catch(InterruptedException ignored){}System.err.println("GUI test exceeded 45 seconds");System.exit(2);});watchdog.setDaemon(true);watchdog.start();
  output=new File(args[0]);new ImageJ(ImageJ.NO_SHOW);Prefs.set("sidetoolbar.shelfsize",56);Prefs.set("fijilayouts.auto",false);
  boolean combined=false;try{Prefs.set("undohistory.testMemoryMB",64);Prefs.set("undohistory.useDisk",false);Prefs.set("undohistory.recordMacro",false);
   ((ij.plugin.PlugIn)Class.forName("fijilayouts.WindowLayouts_").newInstance()).run("install");((ij.plugin.PlugIn)Class.forName("undohistory.Undo_History").newInstance()).run("start");combined=true;
  }catch(ClassNotFoundException optional){}
  Shelf.load();Shelf.all.clear();Side_Toolbar.installEscape();
  File marker=new File(output,"drag-must-not-run.txt");Files.deleteIfExists(marker.toPath());File macroFile=new File(output,"drag-test.ijm");Store.write(macroFile,"File.saveString(\"executed\", \""+marker.getAbsolutePath().replace("\\","\\\\").replace("\"","\\\"")+"\");");
  ImagePlus first=image("First",10);pause();byte[] pixels=((byte[])first.getProcessor().getPixels()).clone();
  if(combined)EventQueue.invokeAndWait(()->{ij.gui.ImageCanvas canvas=first.getCanvas();canvas.dispatchEvent(new MouseWheelEvent(canvas,MouseEvent.MOUSE_WHEEL,System.currentTimeMillis(),InputEvent.CTRL_DOWN_MASK,6,6,6,6,0,false,MouseWheelEvent.WHEEL_UNIT_SCROLL,1,-2,-2.3));});
  Object originalCanvas=first.getCanvas();double originalZoom=first.getCanvas().getMagnification();
  Shelf shelf=new Shelf();shelf.place="bottom";Shelf.Section tools=new Shelf.Section(Shelf.WINDOWS),images=new Shelf.Section(Shelf.IMAGES),macros=new Shelf.Section(Shelf.MACROS);
  tools.items.add(Shelf.launcher("ROI Manager...","ROI Manager","ROI",""));tools.items.add(Shelf.launcher("log","Log","Log",""));tools.items.add(Shelf.launcher("missing command","Unavailable","Unavailable",""));
  for(String label:new String[]{"Macro A","Macro B","Macro C"}){Item item=MacroShelf.itemFor(macroFile);item.label=label;macros.items.add(item);}
  shelf.sections.addAll(Arrays.asList(tools,images,macros));Shelf.all.add(shelf);
  final ShelfView[] created={null};EventQueue.invokeAndWait(()->{created[0]=new ShelfView(shelf);shelf.view=created[0];});ShelfView view=created[0];size(view,1000,56);
  List<ShelfView.Tile> macroTiles=category(view,Shelf.MACROS);Item firstMacro=macroTiles.get(0).item;
  Point macroDrop=after(macroTiles.get(2),true);drag(view,macroTiles.get(0),macroDrop,false);
  check(macros.items.get(0)==firstMacro,"drag preview does not save order early");screenshot(view,"shelf-drag-preview.png");
  event(view,MouseEvent.MOUSE_RELEASED,macroDrop);pause();
  check(macros.items.get(2)==firstMacro,"bottom macro button drops at requested position");
  check(!marker.exists(),"dragging a macro never executes it");
  List<Shelf> saved=Shelf.parse(Store.read(Shelf.file()));check(saved.get(0).sections.get(2).items.get(2).label.equals("Macro A"),"macro order is saved immediately");
  String before=Shelf.serialize();macroTiles=category(view,Shelf.MACROS);drag(view,macroTiles.get(0),center(category(view,Shelf.WINDOWS).get(0)),true);
  check(Shelf.serialize().equals(before),"drop into another category cancels without changing configuration");
  macroTiles=category(view,Shelf.MACROS);drag(view,macroTiles.get(0),new Point(-30,28),true);check(Shelf.serialize().equals(before),"drop outside shelf cancels without deleting button");
  macroTiles=category(view,Shelf.MACROS);drag(view,macroTiles.get(0),after(macroTiles.get(2),true),false);
  EventQueue.invokeAndWait(()->view().cancelItemDrag());event(view,MouseEvent.MOUSE_RELEASED,after(macroTiles.get(2),true));pause();
  check(Shelf.serialize().equals(before)&&!marker.exists(),"cancelling drag preserves order and suppresses release action");
  macroTiles=category(view,Shelf.MACROS);Point escapeDrop=after(macroTiles.get(2),true);drag(view,macroTiles.get(0),escapeDrop,false);
  EventQueue.invokeAndWait(()->KeyboardFocusManager.getCurrentKeyboardFocusManager().dispatchEvent(new KeyEvent(view(),KeyEvent.KEY_PRESSED,System.currentTimeMillis(),0,KeyEvent.VK_ESCAPE,(char)27)));
  check(field(view,"dragging")==null,"Escape cancels a shelf drag through the shared key dispatcher");event(view,MouseEvent.MOUSE_RELEASED,escapeDrop);pause();
  check(Shelf.serialize().equals(before),"Escape leaves saved category order untouched");
  Frame transientTool=new Frame("Transient tool");EventQueue.invokeAndWait(()->{transientTool.setSize(150,80);transientTool.setVisible(true);view().rebuild();});pause();
  List<ShelfView.Tile> toolTiles=category(view,Shelf.WINDOWS);check(toolTiles.stream().anyMatch(t->t.item!=null&&t.item.target.equals("missing command")),"unavailable launchers remain visible and editable");
  ShelfView.Tile transientTile=toolTiles.stream().filter(t->t.window==transientTool).findFirst().get();
  drag(view,transientTile,center(toolTiles.get(0)),true);check(category(view,Shelf.WINDOWS).get(0).window==transientTool,"unpinned live tool windows can move before pinned tools");
  EventQueue.invokeAndWait(()->view().rebuild());pause();check(category(view,Shelf.WINDOWS).get(0).window==transientTool,"tool order survives automatic rebuild");
  check(Shelf.parse(Store.read(Shelf.file())).get(0).sections.get(0).windowOrder.get(0).equals("window:Transient tool"),"live tool title order is persisted");
  ImagePlus second=image("Second",20),third=image("Third",30);EventQueue.invokeAndWait(()->view().rebuild());pause();
  List<ShelfView.Tile> imageTiles=category(view,Shelf.IMAGES);ImagePlus active=WindowManager.getCurrentImage();
  drag(view,imageTiles.get(2),center(imageTiles.get(0)),true);check(category(view,Shelf.IMAGES).get(0).imp==third,"image thumbnails reorder by dragging");
  check(WindowManager.getCurrentImage()==active,"image drag does not activate another image");
  check(Arrays.equals(pixels,(byte[])first.getProcessor().getPixels()),"reordering never modifies source pixels");
  if(combined){
   check(originalCanvas.getClass().getName().equals("fijilayouts.SmoothCanvas")&&first.getCanvas()==originalCanvas&&first.getCanvas().getMagnification()==originalZoom,"toolbar reordering preserves the installed fractional zoom canvas and magnification");
   Class<?> type=Class.forName("undohistory.Engine");Method get=type.getDeclaredMethod("get");get.setAccessible(true);Object engine=get.invoke(null);Method getView=type.getDeclaredMethod("view",int.class);getView.setAccessible(true);
   check(((List<?>)field(getView.invoke(engine,first.getID()),"rows")).size()==1,"toolbar customization adds no processing steps to image Undo History");
  }
  ShelfView.Tile hidden=category(view,Shelf.IMAGES).get(0);Method hide=ShelfView.class.getDeclaredMethod("hideTile",ShelfView.Tile.class);hide.setAccessible(true);final ShelfView current=view;
  EventQueue.invokeAndWait(()->{try{hide.invoke(current,hidden);}catch(Exception e){throw new RuntimeException(e);}});pause();
  check(category(view,Shelf.IMAGES).size()==2&&third.isVisible(),"hiding thumbnail leaves image open");
  EventQueue.invokeAndWait(Shelf::undo);view=view();size(view,1000,56);
  check(category(view,Shelf.IMAGES).size()==3&&category(view,Shelf.IMAGES).get(0).imp==third,"Undo restores hidden image and its preceding manual order");
  EventQueue.invokeAndWait(Shelf::undo);view=view();size(view,1000,56);
  check(category(view,Shelf.IMAGES).get(0).imp==first,"Undo image reordering restores session identity order");
  final ShelfView imageEditor=view;final ShelfView.Tile imageTile=category(view,Shelf.IMAGES).get(0);
  Method editImage=ShelfView.class.getDeclaredMethod("editImageLook",ShelfView.Tile.class);editImage.setAccessible(true);
  javax.swing.Timer answer=new javax.swing.Timer(100,event->{for(Window w:Window.getWindows())if(w.isVisible()&&w instanceof ij.gui.GenericDialog&&((Dialog)w).getTitle().equals("Shelf Button")){
   ij.gui.GenericDialog dialog=(ij.gui.GenericDialog)w;((java.awt.TextField)dialog.getStringFields().get(0)).setText("Custom image alias");
   ((java.awt.TextField)dialog.getStringFields().get(1)).setText("F");((java.awt.TextField)dialog.getStringFields().get(2)).setText("#2255aa");
   try{Field okay=ij.gui.GenericDialog.class.getDeclaredField("okay");okay.setAccessible(true);Button ok=(Button)okay.get(dialog);
    if(ok!=null){((javax.swing.Timer)event.getSource()).stop();dialog.actionPerformed(new ActionEvent(ok,ActionEvent.ACTION_PERFORMED,"OK"));}
   }catch(Exception ex){throw new RuntimeException(ex);}
  }});
  EventQueue.invokeAndWait(()->{answer.start();try{editImage.invoke(imageEditor,imageTile);}catch(Exception e){throw new RuntimeException(e);}});pause();
  check(section(Shelf.IMAGES).imageLooks.get(first).icon.equals("text:F")&&ShelfView.nameOf(first).equals("Custom image alias"),"image tile name and custom icon are editable through its dialog");
  check(first.getTitle().equals("First")&&Arrays.equals(pixels,(byte[])first.getProcessor().getPixels()),"image tile appearance does not rename or modify actual image");
  check(!Shelf.serialize().contains("Custom image alias"),"image appearances remain session data instead of reusable image IDs");
  screenshot(view,"shelf-custom-image.png");EventQueue.invokeAndWait(Shelf::undo);view=view();size(view,1000,56);
  check(section(Shelf.IMAGES).imageLooks.isEmpty()&&ShelfView.nameOf(first).equals("First"),"Undo restores image alias and preview appearance together");
  final Shelf vertical=Shelf.all.get(0);vertical.place="left";size(view,3*56+2*9,420);
  macroTiles=category(view,Shelf.MACROS);Item verticalFirst=macroTiles.get(0).item;drag(view,macroTiles.get(0),after(macroTiles.get(2),false),true);
  check(section(Shelf.MACROS).items.get(2)==verticalFirst,"vertical category reorders along its column");
  imageTiles=category(view,Shelf.IMAGES);drag(view,imageTiles.get(2),center(imageTiles.get(0)),true);
  check(category(view,Shelf.IMAGES).get(0).imp==third,"vertical image column supports the same drag interaction");
  vertical.place="bottom";size(view,440,56);screenshot(view,"shelf-narrow.png");
  @SuppressWarnings("unchecked") List<Rectangle> areas=(List<Rectangle>)field(view,"sectionRects");
  check(areas.get(0).x+areas.get(0).width<areas.get(1).x&&areas.get(1).x+areas.get(1).width<areas.get(2).x,"narrow shelves keep category areas separate");
  Point wheelPoint=new Point(areas.get(0).x+areas.get(0).width/2,28);final ShelfView narrow=view;
  EventQueue.invokeAndWait(()->narrow.dispatchEvent(new MouseWheelEvent(narrow,MouseEvent.MOUSE_WHEEL,System.currentTimeMillis(),0,wheelPoint.x,wheelPoint.y,0,false,MouseWheelEvent.WHEEL_UNIT_SCROLL,1,2)));pause();
  @SuppressWarnings("unchecked") Map<Shelf.Section,Integer> offsets=(Map<Shelf.Section,Integer>)field(view,"scrollOffsets");
  check(offsets.get(section(Shelf.WINDOWS))>0,"wheel scrolling reaches overflowed tools as well as images");
  Shelf.Section hiddenSection=section(Shelf.IMAGES);Shelf.checkpoint();for(ImagePlus image:ShelfView.images())hiddenSection.hiddenImages.put(image,true);EventQueue.invokeAndWait(()->view().rebuild());pause();
  ShelfView.Tile empty=tiles(view).stream().filter(t->t.section==hiddenSection&&t.kind.equals("add")).findFirst().get();popup(view,empty);
  JPopupMenu recovery=(JPopupMenu)MenuSelectionManager.defaultManager().getSelectedPath()[0];check(contains(recovery,"Show Hidden Images"),"fully hidden image category keeps an accessible recovery menu");
  for(Component item:recovery.getComponents())if(item instanceof JMenuItem&&((JMenuItem)item).getText().equals("Show Hidden Images")){final JMenuItem show=(JMenuItem)item;EventQueue.invokeAndWait(show::doClick);}
  EventQueue.invokeAndWait(()->MenuSelectionManager.defaultManager().clearSelectedPath());pause();check(category(view,Shelf.IMAGES).size()==3,"Show Hidden Images restores tiles without reopening files");
  List<Item> macroBefore=new ArrayList<>(section(Shelf.MACROS).items);
  for(int n=0;n<12;n++){Item extra=MacroShelf.itemFor(macroFile);extra.label="Extra "+n;section(Shelf.MACROS).items.add(extra);}
  EventQueue.invokeAndWait(()->view().rebuild());pause();
  areas=(List<Rectangle>)field(view,"sectionRects");Rectangle macroArea=areas.get(2);Point nearEdge=new Point(macroArea.x+macroArea.width-3,28);
  macroTiles=category(view,Shelf.MACROS);drag(view,macroTiles.get(0),nearEdge,false);Thread.sleep(400);EventQueue.invokeAndWait(()->{});
  check(offsets.get(section(Shelf.MACROS))>0,"holding a dragged item at category edge automatically scrolls");
  check(section(Shelf.MACROS).items.get(0)==macroBefore.get(0),"edge-scroll preview does not commit a partial move");
  EventQueue.invokeAndWait(()->view().cancelItemDrag());section(Shelf.MACROS).items.clear();section(Shelf.MACROS).items.addAll(macroBefore);EventQueue.invokeAndWait(()->view().rebuild());pause();
  popup(view,category(view,Shelf.WINDOWS).get(category(view,Shelf.WINDOWS).size()-1));
  JPopupMenu menu=(JPopupMenu)MenuSelectionManager.defaultManager().getSelectedPath()[0];check(contains(menu,"Add Tool Launcher...")&&contains(menu,"Move Earlier"),"tool context menu exposes category editing and ordering");
  EventQueue.invokeAndWait(()->MenuSelectionManager.defaultManager().clearSelectedPath());
  size(view,1000,56);screenshot(view,"shelf-arranged.png");
  Store.load();Store.items.clear();Item oval=Item.tool("oval"),hand=Item.tool("hand"),rectangle=Item.tool("rectangle");Store.items.addAll(Arrays.asList(oval,hand,rectangle));Toolbar.getInstance().setTool(Toolbar.RECTANGLE);
  final Dock[] dock={null};EventQueue.invokeAndWait(()->{dock[0]=new Dock(IJ.getInstance());dock[0].window.setBounds(150,250,120,400);dock[0].window.setVisible(true);dock[0].setSize(120,400);});pause();
  Rectangle from=dock[0].shownRect(oval),to=dock[0].shownRect(rectangle);Point press=new Point(from.x+from.width/2,from.y+from.height/2),drop=new Point(to.x+to.width/2,to.y+to.height-1);
  event(dock[0],MouseEvent.MOUSE_PRESSED,press);check(Toolbar.getToolId()==Toolbar.RECTANGLE,"main toolbar press does not select tool before drag decision");
  event(dock[0],MouseEvent.MOUSE_DRAGGED,drop);
  EventQueue.invokeAndWait(()->KeyboardFocusManager.getCurrentKeyboardFocusManager().dispatchEvent(new KeyEvent(dock[0],KeyEvent.KEY_PRESSED,System.currentTimeMillis(),0,KeyEvent.VK_ESCAPE,(char)27)));
  check(field(dock[0],"dragItem")==null&&Store.items.get(0)==oval,"Escape cancels main toolbar drag without changing model");
  event(dock[0],MouseEvent.MOUSE_RELEASED,drop);pause();
  from=dock[0].shownRect(oval);to=dock[0].shownRect(rectangle);press=new Point(from.x+from.width/2,from.y+from.height/2);drop=new Point(to.x+to.width/2,to.y+to.height-1);
  event(dock[0],MouseEvent.MOUSE_PRESSED,press);
  event(dock[0],MouseEvent.MOUSE_DRAGGED,drop);event(dock[0],MouseEvent.MOUSE_RELEASED,drop);pause();
  check(Toolbar.getToolId()==Toolbar.RECTANGLE,"rearranging main drawing tools does not activate dragged tool");
  check(Store.items.indexOf(oval)>Store.items.indexOf(rectangle),"main toolbar still persists reordered drawing tools");
  Rectangle click=dock[0].shownRect(oval);Point point=new Point(click.x+click.width/2,click.y+click.height/2);event(dock[0],MouseEvent.MOUSE_PRESSED,point);event(dock[0],MouseEvent.MOUSE_RELEASED,point);pause();
  check(Toolbar.getToolId()==Toolbar.OVAL,"ordinary main-toolbar click still selects tool on release");
  check(!marker.exists(),"no test rearrangement launched macro");
  System.out.println(passed+" toolbar drag and editing checks passed");System.exit(0);
 }catch(Throwable failure){failure.printStackTrace();System.exit(1);}}
 static Button findButton(Container parent,String name){for(Component c:parent.getComponents()){if(c instanceof Button&&((Button)c).getLabel().equals(name))return (Button)c;if(c instanceof Container){Button found=findButton((Container)c,name);if(found!=null)return found;}}return null;}
}
