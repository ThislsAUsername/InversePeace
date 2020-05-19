package Engine.UnitActionLifecycles;

import java.util.ArrayList;

import Engine.GameAction;
import Engine.GameActionSet;
import Engine.Path;
import Engine.UnitActionFactory;
import Engine.Utils;
import Engine.XYCoord;
import Engine.GameEvents.GameEventQueue;
import Engine.GameEvents.ResupplyEvent;
import Terrain.GameMap;
import Terrain.MapMaster;
import Units.Unit;

public abstract class ResupplyLifecycle
{
  public static class ResupplyFactory extends UnitActionFactory
  {
    private static final long serialVersionUID = 1L;

    @Override
    public GameActionSet getPossibleActions(GameMap map, Path movePath, Unit actor, boolean ignoreResident)
    {
      XYCoord moveLocation = movePath.getEndCoord();
      if( ignoreResident || map.isLocationEmpty(actor, moveLocation) )
      {
        // Search for a unit in resupply range.
        ArrayList<XYCoord> locations = Utils.findLocationsInRange(map, moveLocation, 1);

        // For each location, see if there is a friendly unit to re-supply.
        for( XYCoord loc : locations )
        {
          // If there's a friendly unit there who isn't us, we can resupply them.
          Unit other = map.getLocation(loc).getResident();
          if( other != null && other.CO == actor.CO && other != actor && !other.isFullySupplied() )
          {
            // We found at least one unit we can resupply. Since resupply actions aren't
            // targeted, we can just add our action and break here.
            return new GameActionSet(new ResupplyAction(actor, movePath), false);
          }
        }
      }
      return null;
    }

    @Override
    public String name()
    {
      return "RESUPPLY";
    }

    /**
     * From Serializable interface
     * @return The statically-defined object to use for this action type.
     */
    private Object readResolve()
    {
      return RESUPPLY;
    }
  }

  // A resupply action will refill fuel and ammunition for any adjacent friendly units.
  public static class ResupplyAction extends GameAction
  {
    private Unit unitActor = null;
    private Path movePath = null;

    /**
     * Creates a resupply action to be executed from the unit's location.
     * The location will update if the unit moves.
     */
    public ResupplyAction(Unit actor)
    {
      this(actor, null);
    }

    /**
     * Creates a resupply action to be executed from the end of path.
     * The location will not update if the unit moves.
     * @param actor
     * @param path
     */
    public ResupplyAction(Unit actor, Path path)
    {
      unitActor = actor;
      movePath = path;

      // Resupply action is a bit different from other actions. It can be used as
      // a unit's turn, but it can also be triggered by an APC during the turn-
      // initialization phase (and re-executed each turn).
    }

    private XYCoord myLocation()
    {
      XYCoord loc;
      if( movePath != null )
      {
        loc = movePath.getEndCoord();
      }
      else
      {
        loc = new XYCoord(unitActor.x, unitActor.y);
      }
      return loc;
    }

    @Override
    public GameEventQueue getEvents(MapMaster map)
    {
      // RESUPPLY actions consist of
      //   [MOVE]
      //   RESUPPLY
      GameEventQueue eventSequence = new GameEventQueue();
      XYCoord supplyLocation = null;

      // Validate action.
      boolean isValid = true;
      isValid &= unitActor != null && !unitActor.isTurnOver;
      // Unit can move between executions of this action, so verify it's still on the map.
      isValid &= (null != map) && map.isLocationValid(unitActor.x, unitActor.y);
      if( isValid )
      {
        // Figure out where we are acting.
        supplyLocation = myLocation();

        // Add a move event if we need to move.
        // Note that movePath being null is OK for ResupplyAction when it is being re-used.
        if( movePath != null )
        {
          // If we should be blocked, don't resupply anything.
          if( !Utils.enqueueMoveEvent(map, unitActor, movePath, eventSequence) )
            isValid = false; // isValid is used to signal pre-emption here rather than a malformed action.
                             // Strange control flow stems from ResupplyAction's dual purpose. 
        }
      }

      if( isValid )
      {
        // Get the adjacent map locations.
        ArrayList<XYCoord> locations = Utils.findLocationsInRange(map, supplyLocation, 1);

        // For each location, see if there is a friendly unit to re-supply.
        for( XYCoord loc : locations )
        {
          Unit other = map.getLocation(loc).getResident();
          if( other != null && other != unitActor && other.CO == unitActor.CO && !other.isFullySupplied() )
          {
            // Add a re-supply event for this unit.
            eventSequence.add(new ResupplyEvent(other));
          }
        }
      }
      else
      {
        // We can't create any events. Leave the event queue empty.
        System.out.println("WARNING! Attempting to get resupply events for invalid unit.");
      }

      return eventSequence;
    }

    @Override
    public XYCoord getMoveLocation()
    {
      return myLocation();
    }

    @Override
    public XYCoord getTargetLocation()
    {
      return myLocation();
    }

    @Override
    public String toString()
    {
      return String.format("[Resupply units adjacent to %s with %s]", myLocation(), unitActor.toStringWithLocation());
    }

    @Override
    public UnitActionFactory getType()
    {
      return UnitActionFactory.RESUPPLY;
    }
  } // ~ResupplyAction

  // No event, as ResupplyEvents are held in common with non-unit activities
}
