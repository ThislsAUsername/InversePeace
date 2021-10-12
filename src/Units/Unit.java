package Units;

import java.util.ArrayList;
import java.util.List;

import CommandingOfficers.Commander;
import Engine.FloodFillFunctor;
import Engine.GameActionSet;
import Engine.GamePath;
import Engine.UnitActionFactory;
import Engine.XYCoord;
import Engine.GameEvents.GameEventQueue;
import Engine.UnitMods.UnitModList;
import Engine.UnitMods.UnitModifier;
import Terrain.GameMap;
import Terrain.MapLocation;
import Terrain.MapMaster;

public class Unit extends UnitState implements UnitModList
{
  private static final long serialVersionUID = 1L;
  public int x = -1;
  public int y = -1;

  public Unit(Commander co, UnitModel um)
  {
    super(co, um);
  }

  /**
   * Ready this unit for the next turn. Any actions it performs as part of
   * initialization will be returned in a GameEventQueue.
   * @param map
   * @param events
   */
  public GameEventQueue initTurn(MapMaster map)
  {
    // Make a queue to return any init events.
    GameEventQueue events = new GameEventQueue();

    MapLocation locus = map.getLocation(x, y);

    // Only perform turn initialization for the unit if it is on the map.
    //   Units that are e.g. in a transport don't burn fuel, etc.
    if( isStunned )
    {
      isTurnOver = true;
      isStunned = false;
    }
    else
      isTurnOver = false;
    if( captureTarget != null && captureTarget.getResident() != this )
    {
      captureTarget = null;
      captureProgress = 0;
    }

    if( null != heldUnits )
      for( Unit cargo : heldUnits )
        events.addAll(cargo.initTurn(map));

    if( null != locus )
    {
      fuel = Math.max(0, fuel - model.idleFuelBurn);

      // Collect any turn-initialization events for this unit.
      events.addAll(model.getTurnInitEvents(this, map));
    } // ~If location is valid.

    return events;
  }

  public FloodFillFunctor getMoveFunctor(boolean includeOccupied)
  {
    // Units cannot normally pass through enemies
    return getMoveFunctor(includeOccupied, false);
  }
  public FloodFillFunctor getMoveFunctor(boolean includeOccupied, boolean canTravelThroughEnemies)
  {
    return model.propulsion.getUnitMoveFunctor(this, includeOccupied, canTravelThroughEnemies);
  }

  /** Provides the authoritative/actual move power of the unit in question */
  public int getMovePower(GameMap map)
  {
    return getMovePower(new UnitContext(map, this, null, x, y));
  }
  public static int getMovePower(UnitContext uc)
  {
    for( UnitModifier mod : uc.mods )
      mod.modifyMovePower(uc);
    return uc.movePower;
  }

  public UnitContext getRangeContext(GameMap map, WeaponModel weapon)
  {
    return new UnitContext(map, this, weapon, x, y);
  }

  /**
   * @return whether or not this unit can attack the given unit type at the
   * specified range, accounting for the possibility of moving first.
   */
  public boolean canAttack(GameMap map, ITargetable targetType, int range, boolean afterMoving)
  {
    // if we have no weapons, we can't hurt things
    if( model.weapons == null )
      return false;

    boolean canHit = false;
    for( WeaponModel weapon : model.weapons )
    {
      if( !weapon.loaded(this) ) continue; // Can't shoot with no bullets.

      if( afterMoving && !weapon.canFireAfterMoving )
      {
        // If we are planning to move first, and the weapon
        // can't shoot after moving, then move along.
        continue;
      }
      UnitContext uc = getRangeContext(map, weapon);
      if( uc.rangeMax < range || uc.rangeMin > range )
      {
        // Can only hit things inside our range
        continue;
      }
      if( weapon.getDamage(targetType) > 0 )
      {
        canHit = true;
        break;
      }
    }
    return canHit;
  }

  /**
   * @return Whether this unit has a weapon with ammo that can hit `targetType`
   * under any combination of ranged/direct, move-first/static.
   */
  public boolean canTarget(UnitModel targetType)
  {
    // if we have no weapons, we can't hurt things
    if( model.weapons == null )
      return false;

    boolean canHit = false;
    for( WeaponModel weapon : model.weapons )
    {
      if( !weapon.loaded(this) ) continue; // Can't shoot with no bullets.

      if( weapon.getDamage(targetType) > 0 )
      {
        canHit = true;
        break;
      }
    }
    return canHit;
  }


  /** Compiles and returns a list of all actions this unit could perform on map after moving along movePath. */
  public ArrayList<GameActionSet> getPossibleActions(GameMap map, GamePath movePath)
  {
    return getPossibleActions(map, movePath, false);
  }
  public ArrayList<GameActionSet> getPossibleActions(GameMap map, GamePath movePath, boolean ignoreResident)
  {
    ArrayList<GameActionSet> actionSet = new ArrayList<GameActionSet>();
    for( UnitActionFactory at : model.possibleActions )
    {
      GameActionSet actions = at.getPossibleActions(map, movePath, this, ignoreResident);
      if( null != actions )
        actionSet.add(actions);
    }

    return actionSet;
  }

  public boolean hasCargoSpace(long type)
  {
    return (model.holdingCapacity > 0 && 
            heldUnits.size() < model.holdingCapacity &&
            ((model.carryableMask & type) > 0) &&
            ((model.carryableExclusionMask & type) == 0));
  }

  public static class CargoList extends ArrayList<Unit>
  {
    private static final long serialVersionUID = 1L;
    UnitModel model;
    public CargoList(UnitModel model)
    {
      super(model.holdingCapacity);
      this.model = model;
    }

    @Override
    public boolean add(Unit u)
    {
      if( size() >= model.holdingCapacity )
        throw new IllegalStateException("Cannot put a unit into a transport that is already full!");
      return super.add(u);
    }
  }

  /** Grant this unit full fuel and ammunition */
  public void resupply()
  {
    fuel = model.maxFuel;
    ammo = model.maxAmmo;
  }

  /** Returns true if resupply would have zero effect on this unit. */
  public boolean isFullySupplied()
  {
    boolean isFull = (model.maxFuel == fuel);
    isFull &= (model.maxAmmo == ammo);
    return isFull;
  }

  public int getRepairCost()
  {
    return model.getRepairCost(new UnitContext(this));
  }

  @Override
  public String toString()
  {
    return model.toString();
  }

  public String toStringWithLocation()
  {
    return String.format("%s at %s", model, new XYCoord(x, y));
  }


  private final ArrayList<UnitModifier> unitMods = new ArrayList<>();
  @Override
  public List<UnitModifier> getModifiers()
  {
    ArrayList<UnitModifier> output = new ArrayList<>();
    output.addAll(model.getModifiers());
    output.addAll(unitMods);
    return output;
  }

  @Override
  public void add(UnitModifier unitModifier)
  {
    unitMods.add(unitModifier);
  }
  @Override
  public void remove(UnitModifier unitModifier)
  {
    unitMods.remove(unitModifier);
  }
}
