package Units;

import java.io.Serializable;
import java.util.ArrayList;

import CommandingOfficers.Commander;
import Engine.XYCoord;
import Terrain.MapLocation;

public abstract class UnitState implements Serializable
{
  private static final long serialVersionUID = 1L;

  public final ArrayList<Unit> heldUnits;
  public int ammo;
  public int fuel;
  public int materials;
  public boolean isTurnOver;
  public boolean isStunned;

  public UnitModel model;
  public Commander CO;

  /**
   * HP determines the current actual durability of a unit.
   * It's typically in range [1-10]. A unit at 0 HP is dead.
   * Health is HP value as a percentage, thus ~10x the HP value.
   * When determining HP, health must always be rounded up.
   */
  public int health;

  protected int captureProgress;
  protected MapLocation captureTarget;


  public UnitState(Commander co, UnitModel um)
  {
    CO = co;
    model = um;
    ammo = model.maxAmmo;
    fuel = model.maxFuel;
    materials = model.maxMaterials;
    isTurnOver = true;
    health = UnitModel.MAXIMUM_HP;
    captureProgress = 0;
    captureTarget = null;

    heldUnits = new ArrayList<>(model.baseCargoCapacity);
  }
  public UnitState(UnitState other)
  {
    CO = other.CO;
    model = other.model;
    isTurnOver = other.isTurnOver;
    copyUnitState(other);
    captureProgress = other.captureProgress;
    captureTarget = other.captureTarget;

    heldUnits = new ArrayList<>(model.baseCargoCapacity);
    heldUnits.addAll(other.heldUnits);
  }
  public void copyUnitState(UnitState other)
  {
    ammo = other.ammo;
    fuel = other.fuel;
    materials = other.materials;
    health = other.health;
  }


  /** Expend ammo, if the weapon uses ammo */
  public void fire(WeaponModel weapon)
  {
    if( !weapon.hasInfiniteAmmo )
    {
      if( ammo > 0 )
        ammo--;
      else
        System.out.println("WARNING: " + toString() + " fired with no available ammo!");
    }
  }

  public boolean hasMaterials()
  {
    return materials > 0 || !model.needsMaterials;
  }

  public boolean isHurt()
  {
    return getHP() < UnitModel.MAXIMUM_HP;
  }
  public int getHP()
  {
    return roundHP(health);
  }
  public int getHPFactor()
  {
    return roundHP(health) / 10;
  }
  public static int roundHP(int health)
  {
    if( health >= 0 )
      //     "round up", then kill the last digit
      return (health + 9) / 10 * 10;
    // Truncation rounds toward zero, so we need to round down for negative values
    return (health - 9) / 10 * 10;
  }

  /**
   * Reduces HP by the specified amount.
   * <p>Enforces a minimum (optional) of 0.
   * <p>Use this for lethal damage, especially unit-on-unit violence. Do not use for healing.
   * @return the change in *rounded* HP
   */
  public int damageHP(int damage)
  {
    return damageHP(damage, false);
  }
  public int damageHP(int damage, boolean allowOverkill)
  {
    if( damage < 0 )
      throw new ArithmeticException("Cannot inflict negative damage!");
    int before = getHP();
    health = health - damage;
    if( !allowOverkill )
      health = Math.max(0, health);
    return getHP() - before;
  }

  /**
   * Increases HP by the specified amount.
   * <p>Enforces a minimum of 1, and a maximum (optional) of MAXIMUM_HP.
   * <p>When healing, rounds health up to a whole HP (e.g. 25 + 20 = 45 -> 50)
   * <p>Use this for most non-combat HP changes (mass damage/silos/healing).
   * @return the change in HP
   */
  public int alterHP(int change)
  {
    return alterHP(change, true, false);
  }
  public int alterHP(int change, boolean allowOver)
  {
    return alterHP(change, true, allowOver);
  }
  public int alterHP(int change, boolean roundUp, boolean allowOver)
  {
    final int oldHP = getHP();
    int realChange = change;

    // Only enforce the maximum HP if we're healing
    if( !allowOver && change > 0 )
    {
      // If we already have overhealing, treat current HP as the max to avoid e.g. heals reducing HP
      final int capHP = Math.max(oldHP, UnitModel.MAXIMUM_HP);
      // Apply the cap as needed
      final int newHP = Math.min(capHP, oldHP + change);
      // Figure out whether that reduces our healing
      realChange = Math.min(change, newHP - health);
    }

    health = Math.max(1, health + realChange);
    // Round HP up, if healing
    if( roundUp && change >= 0 )
      health = getHP();

    return getHP() - oldHP;
  }

  /**
   * Increases health by the specified amount.
   * <p>Enforces a minimum of 1, and a maximum (optional) of MAXIMUM_HP.
   * <p>Does not round.
   * <p>Use this when you want precise non-combat health changes, or want to heal without rounding up.
   * @return the change in HP
   */
  public int alterHealthPercent(int percentChange)
  {
    return alterHP(percentChange, false, false);
  }


  public boolean capture(MapLocation target)
  {
    boolean success = false;

    if( target != captureTarget )
    {
      captureTarget = target;
      captureProgress = 0;
    }
    captureProgress += getHPFactor();
    if( captureProgress >= target.getEnvironment().terrainType.getCaptureThreshold() )
    {
      target.setOwner(CO);
      captureProgress = 0;
      target = null;
      success = true;
    }

    return success;
  }

  public void stopCapturing()
  {
    captureTarget = null;
    captureProgress = 0;
  }

  public int getCaptureProgress()
  {
    return captureProgress;
  }
  public XYCoord getCaptureTargetCoords()
  {
    XYCoord target = null;
    if( null != captureTarget )
    {
      target = captureTarget.getCoordinates();
    }
    return target;
  }
}
