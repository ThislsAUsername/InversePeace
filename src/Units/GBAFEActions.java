package Units;

import java.util.ArrayList;

import Engine.GameAction;
import Engine.GameActionSet;
import Engine.GamePath;
import Engine.UnitActionFactory;
import Engine.Utils;
import Engine.XYCoord;
import Engine.GameEvents.GameEvent;
import Engine.GameEvents.GameEventQueue;
import Engine.GameEvents.HealUnitEvent;
import Engine.GameEvents.ModifyFundsEvent;
import Engine.GameEvents.ResupplyEvent;
import Engine.UnitActionLifecycles.TransformLifecycle.TransformEvent;
import Engine.UnitActionLifecycles.WaitLifecycle;
import Terrain.GameMap;
import Terrain.MapLocation;
import Terrain.MapMaster;

public class GBAFEActions
{
  public static final int PROMOTION_COST = 5000;
  public static class PromotionFactory extends UnitActionFactory
  {
    private static final long serialVersionUID = 1L;
    public final UnitModel destinationType;
    public final String name;

    public PromotionFactory(UnitModel type)
    {
      destinationType = type;
      name = "~" + type.name + " ("+PROMOTION_COST+")";
    }

    @Override
    public GameActionSet getPossibleActions(GameMap map, GamePath movePath, Unit actor, boolean ignoreResident)
    {
      XYCoord moveLocation = movePath.getEndCoord();
      if( ignoreResident || map.isLocationEmpty(actor, moveLocation) )
      {
        boolean validPromo = true;
        validPromo &= actor.CO.army.money >= PROMOTION_COST;
        MapLocation destInfo = map.getLocation(moveLocation);
        validPromo &= !actor.CO.isEnemy(destInfo.getOwner());
        if( validPromo )
          return new GameActionSet(new PromotionAction(actor, movePath, this), false);
      }
      return null;
    }

    @Override
    public String name(Unit actor)
    {
      return name;
    }
  }

  /** Effectively a WAIT that costs money, and the unit ends up as a different unit at the end of it. */
  public static class PromotionAction extends WaitLifecycle.WaitAction
  {
    private PromotionFactory type;
    Unit actor;

    public PromotionAction(Unit unit, GamePath path, PromotionFactory pType)
    {
      super(unit, path);
      type = pType;
      actor = unit;
    }

    @Override
    public GameEventQueue getEvents(MapMaster gameMap)
    {
      GameEventQueue transformEvents = super.getEvents(gameMap);

      if( transformEvents.size() > 0 ) // if we successfully made a move action
      {
        GameEvent moveEvent = transformEvents.peek();
        if( moveEvent.getEndPoint().equals(getMoveLocation()) ) // make sure we shouldn't be pre-empted
        {
          transformEvents.add(new ModifyFundsEvent(actor.CO.army, -1 * PROMOTION_COST));
          transformEvents.add(new TransformEvent(actor, type.destinationType));
          transformEvents.add(new HealUnitEvent(actor, 10, null)); // "Free" fullheal included, for tactical spice
          transformEvents.add(new ResupplyEvent(null, actor));     //   and also resupply, since we use this for ballistae
        }
      }
      return transformEvents;
    }

    @Override
    public String toString()
    {
      return String.format("[Move %s to %s and promote to %s]", actor.toStringWithLocation(), getMoveLocation(),
          type.destinationType);
    }

    @Override
    public UnitActionFactory getType()
    {
      return type;
    }
  } // ~PromotionAction

  /**
   * Repair, but:<p>
   * It's free<p>
   * It has variable heal quantity<p>
   * It has variable range<p>
   * It doesn't take target HP into account because it'd be very annoying to play with<p>
   * It doesn't work on boats (I have stupid plans for boats)<p>
   */
  public static class HealStaffFactory extends UnitActionFactory
  {
    private static final long serialVersionUID = 1L;
    public final String name;
    public final int quantity, range;

    public HealStaffFactory(String name, int healHP, int range)
    {
      this.name = name;
      quantity = healHP;
      this.range = range;
    }

    @Override
    public GameActionSet getPossibleActions(GameMap map, GamePath movePath, Unit actor, boolean ignoreResident)
    {
      XYCoord moveLocation = movePath.getEndCoord();
      if( ignoreResident || map.isLocationEmpty(actor, moveLocation) )
      {
        ArrayList<GameAction> repairOptions = new ArrayList<GameAction>();
        ArrayList<XYCoord> locations = Utils.findLocationsInRange(map, moveLocation, 1, range);

        // For each location, see if there is a friendly unit to repair.
        for( XYCoord loc : locations )
        {
          // If there's a friendly unit there who isn't us, we can repair them.
          Unit other = map.getLocation(loc).getResident();
          if( other != null && !other.model.isAny(UnitModel.SHIP) && !actor.CO.isEnemy(other.CO) && other != actor
              && (!other.isFullySupplied() || other.isHurt()) )
          {
            repairOptions.add(new HealStaffAction(this, actor, movePath, other));
          }
        }

        // Only add this action set if we actually have a target
        if( !repairOptions.isEmpty() )
        {
          // Bundle our attack options into an action set
          return new GameActionSet(repairOptions);
        }
      }
      return null;
    }

    @Override
    public String name(Unit actor)
    {
      return name;
    }
  }

  public static class HealStaffAction extends GameAction
  {
    private GamePath movePath;
    private XYCoord startCoord;
    private XYCoord moveCoord;
    private XYCoord repairCoord;
    Unit benefactor;
    Unit beneficiary;
    HealStaffFactory type;

    public HealStaffAction(HealStaffFactory type, Unit actor, GamePath path, Unit target)
    {
      this.type = type;
      benefactor = actor;
      beneficiary = target;
      movePath = path;
      if( benefactor != null && null != beneficiary )
      {
        startCoord = new XYCoord(actor.x, actor.y);
        repairCoord = new XYCoord(target.x, target.y);
      }
      if( null != path && (path.getEnd() != null) )
      {
        moveCoord = movePath.getEndCoord();
      }
    }

    @Override
    public GameEventQueue getEvents(MapMaster gameMap)
    {
      // Repair actions consist of
      //   MOVE
      //   HEAL
      GameEventQueue healEvents = new GameEventQueue();

      boolean isValid = true;

      if( (null != gameMap) && (null != startCoord) && (null != repairCoord) && gameMap.isLocationValid(startCoord)
          && gameMap.isLocationValid(repairCoord) )
      {
        isValid &= benefactor != null && !benefactor.isTurnOver;
        isValid &= isValid && null != beneficiary && !benefactor.CO.isEnemy(beneficiary.CO);
        isValid &= (movePath != null) && (movePath.getPathLength() > 0);
      }
      else
        isValid = false;

      if( isValid )
      {
        if( Utils.enqueueMoveEvent(gameMap, benefactor, movePath, healEvents) )
        {
          // No surprises in the fog.
          healEvents.add(new HealUnitEvent(beneficiary, type.quantity, null));
        }
      }
      return healEvents;
    }

    @Override
    public Unit getActor()
    {
      return benefactor;
    }

    @Override
    public XYCoord getMoveLocation()
    {
      return moveCoord;
    }

    @Override
    public XYCoord getTargetLocation()
    {
      return repairCoord;
    }

    @Override
    public String toString()
    {
      return String.format("[Move %s to %s and use %s to heal %s]", benefactor.toStringWithLocation(), moveCoord,
          type.name, beneficiary.toStringWithLocation());
    }

    @Override
    public UnitActionFactory getType()
    {
      return type;
    }
  } // ~RepairUnitAction
}
