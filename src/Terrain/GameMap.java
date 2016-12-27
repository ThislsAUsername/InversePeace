package Terrain;

import Engine.XYCoord;
import Terrain.Environment.Terrains;
import Units.Unit;

public class GameMap
{
  private Location[][] map;
  public final int mapWidth;
  public final int mapHeight;
  public CommandingOfficers.Commander[] commanders;

  public GameMap(CommandingOfficers.Commander[] COs, MapInfo mapInfo)
  {
    commanders = COs;
    mapWidth = mapInfo.getWidth();
    mapHeight = mapInfo.getHeight();
    map = new Location[mapWidth][mapHeight];

    // Build the map locations based on the MapInfo data.
    for(int y = 0; y < mapHeight; ++y)
    {
      for(int x = 0; x < mapWidth; ++x)
      {
        // Create this Location using the MapInfo terrain.
        map[x][y] = new Location(Environment.getTile(mapInfo.terrain[x][y], Environment.Weathers.CLEAR));
      }
    }

    // Print a warning if the number of Commanders we have does not match the number the map expects.
    if(COs.length != mapInfo.COProperties.length)
    {
      System.out.println("Warning! Wrong number of COs specified for map " + mapInfo.mapName);
    }
    // Assign properties according to MapInfo's direction.
    for(int co = 0; co < mapInfo.COProperties.length && co < COs.length; ++co)
    {
      // Loop through all locations assigned to this CO by mapInfo.
      for(int i = 0; i < mapInfo.COProperties[co].length; ++i)
      {
        // If the location can be owned, make the assignment.
        XYCoord coord = mapInfo.COProperties[co][i];
        int x = (int)coord.xCoord;
        int y = (int)coord.yCoord;
        Location location = map[x][y];
        if(location.isCaptureable())
        {
          // Check if this location holds an HQ.
          if( map[x][y].getEnvironment().terrainType == Terrains.HQ )
          {
            // If the CO has no HQ yet, assign this one.
            if( COs[co].HQLocation == null )
            {
              System.out.println("Assigning HQ at " + x + ", " + y + " to " + COs[co]);
              COs[co].HQLocation = location;
            }
            // If the CO does have an HQ, turn this location into a city.
            else
            {
              location.setEnvironment(Environment.getTile(Terrains.CITY, location.getEnvironment().weatherType));
            }
          }
          location.setOwner(COs[co]);
        }
        else
        {
          System.out.println("Warning! CO specified as owner of an uncapturable location in map " + mapInfo.mapName);
        }
      }
    }
  }

  /**
   * Returns true if (x,y) lies within the GameMap, false else.
   */
  public boolean isLocationValid(int x, int y)
  {
    return !(x < 0 || x >= mapWidth || y < 0 || y >= mapHeight);
  }

  /** Returns the Environment of the specified tile, or null if that location does not exist. */
  public Environment getEnvironment(int w, int h)
  {
    if( !isLocationValid(w, h) )
    {
      return null;
    }
    return map[w][h].getEnvironment();
  }

  /** Returns the Location at the specified location, or null if that Location does not exist. */
  public Location getLocation(int w, int h)
  {
    if( !isLocationValid(w, h) )
    {
      return null;
    }
    return map[w][h];
  }

  public void clearAllHighlights()
  {
    for( int w = 0; w < mapWidth; ++w )
    {
      for( int h = 0; h < mapHeight; ++h )
      {
        map[w][h].setHighlight(false);
      }
    }
  }

  /** Returns true if no unit is at the specified x and y coordinate, false else */
  public boolean isLocationEmpty(int x, int y)
  {
    return isLocationEmpty(null, x, y);
  }

  /** Returns true if no unit (excluding 'unit') is in the specified Location. */
  public boolean isLocationEmpty(Unit unit, int x, int y)
  {
    boolean empty = true;
    if( isLocationValid(x, y) )
    {
      if( getLocation(x, y).getResident() != null && getLocation(x, y).getResident() != unit )
      {
        empty = false;
      }
    }
    return empty;
  }

  public void addNewUnit(Unit unit, int x, int y)
  {
    if( getLocation(x, y).getResident() != null )
    {
      System.out.println("Error! Attempting to add a unit to an occupied Location!");
      return;
    }

    getLocation(x, y).setResident(unit);
    unit.x = x;
    unit.y = y;
  }

  public void moveUnit(Unit unit, int x, int y)
  {
    if( !isLocationEmpty(unit, x, y) )
    {
      System.out.println("ERROR! Attempting to move unit to an occupied Location!");
      return;
    }

    // Update the map
    Location priorLoc = getLocation(unit.x, unit.y);
    if( null != priorLoc )
    {
      priorLoc.setResident(null);
    }
    getLocation(x, y).setResident(unit);

    // Reset capture progress, since we moved.
    if( unit.getCaptureProgress() > 0 )
    {
      unit.stopCapturing();
    }

    // Update the Unit location.
    unit.x = x;
    unit.y = y;
  }

  /** Removes the Unit from the map, if the map agrees with the Unit on its location. */
  public void removeUnit(Unit u)
  {
    if( isLocationValid(u.x, u.y) )
    {
      if( getLocation(u.x, u.y).getResident() != u )
      {
        System.out.println("WARNING! Trying to remove a Unit that isn't where he claims to be.");
      }
      else
      {
        // Get the unit off the map.
        getLocation(u.x, u.y).setResident(null);

        // Tell the unit he's off the map.
        u.x = -1;
        u.y = -1;

        // Reset capture progress if needed.
        if( u.getCaptureProgress() > 0 )
        {
          u.stopCapturing();
        }
      }
    }
  }
}
