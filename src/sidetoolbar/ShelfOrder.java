package sidetoolbar;

import java.util.*;

/** Ordering belongs to each category; live images use weak session identities, never reusable image IDs on disk. */
final class ShelfOrder {
    static Object identity(ShelfView.Tile tile){return tile.imp!=null?tile.imp:tile.item!=null?tile.item:tile.window;}
    static boolean same(ShelfView.Tile a,ShelfView.Tile b){return a!=null&&b!=null&&a.section==b.section&&identity(a)==identity(b)&&a.kind.equals(b.kind);}
    static String windowKey(ShelfView.Tile tile){return tile.item!=null?"pin:"+tile.section.itemIDs.computeIfAbsent(tile.item,item->UUID.randomUUID().toString()):"window:"+ShelfView.titleOf(tile.window);}
    static void sort(Shelf.Section section,List<ShelfView.Tile> tiles){
        if(section.kind.equals(Shelf.IMAGES))tiles.sort(Comparator.comparingInt(t->section.imageOrder.getOrDefault(t.imp,Integer.MAX_VALUE)));
        else if(section.kind.equals(Shelf.WINDOWS))tiles.sort(Comparator.comparingInt(t->{int i=section.windowOrder.indexOf(windowKey(t));return i<0?Integer.MAX_VALUE:i;}));
    }
    static boolean equal(List<ShelfView.Tile> a,List<ShelfView.Tile> b){
        if(a.size()!=b.size())return false;for(int i=0;i<a.size();i++)if(!same(a.get(i),b.get(i)))return false;return true;
    }
    static List<ShelfView.Tile> moved(List<ShelfView.Tile> original,ShelfView.Tile dragged,int index){
        List<ShelfView.Tile> result=new ArrayList<>(original);result.removeIf(t->same(t,dragged));
        result.add(Math.max(0,Math.min(index,result.size())),dragged);return result;
    }
    static void apply(Shelf.Section section,List<ShelfView.Tile> tiles){
        if(section.kind.equals(Shelf.IMAGES)){
            section.imageOrder.clear();for(int i=0;i<tiles.size();i++)section.imageOrder.put(tiles.get(i).imp,i);
        }else if(section.kind.equals(Shelf.WINDOWS)){
            List<String> order=new ArrayList<>();for(ShelfView.Tile tile:tiles)if(!order.contains(windowKey(tile)))order.add(windowKey(tile));
            for(String key:section.windowOrder)if(!order.contains(key))order.add(key);
            section.windowOrder.clear();section.windowOrder.addAll(order);
        }else{section.items.clear();for(ShelfView.Tile tile:tiles)section.items.add(tile.item);}
    }
}
