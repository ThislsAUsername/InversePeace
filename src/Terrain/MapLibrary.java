package Terrain;

import java.util.ArrayDeque;
import java.util.ArrayList;

import Terrain.MapInfo.MapNode;
import Terrain.Maps.MapReader;
import Terrain.Maps.TestRange;
import lombok.var;

public class MapLibrary
{
  private static ArrayList<MapInfo> availableMaps;
  private static MapNode rootMap;
  
  public static ArrayList<MapInfo> getMapList()
  {
    if( null == availableMaps )
    {
      loadMapInfos();
    }
    return availableMaps;
  }
  public static MapNode getMapGraph()
  {
    if( null == rootMap )
    {
      loadMapInfos();
    }
    return rootMap;
  }
  
  private static void loadMapInfos()
  {
    availableMaps = new ArrayList<MapInfo>();
    availableMaps.add(TestRange.getMapInfo());

    rootMap = MapReader.readMapData();

    for( var map : availableMaps )
      rootMap.children.add(0, new MapNode(rootMap, map.mapName, map));

    // Iterate over the nodes to get a master list of maps, as well.
    var nodes = new ArrayDeque<MapNode>();
    nodes.add(rootMap);
    while (!nodes.isEmpty())
    {
      MapNode n = nodes.poll();
      if( null == n.result )
        nodes.addAll(n.children);
      else
        availableMaps.add(n.result);
    }
  }

  public static MapInfo getByName(String mapName)
  {
    ArrayList<MapInfo> maps = getMapList();
    MapInfo requested = null;
    for(MapInfo mi : maps)
    {
      if( mi.mapName.equalsIgnoreCase(mapName) )
      {
        requested = mi;
        break;
      }
    }
    return requested;
  }
}
