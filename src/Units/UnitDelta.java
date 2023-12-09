package Units;

import java.io.Serializable;

import CommandingOfficers.Commander;

public class UnitDelta implements Serializable
{
  private static final long serialVersionUID = 1L;

  public final UnitContext before, after;
  public final int deltaHP, deltaAmmo, deltaFuel, deltaMaterials;
  public final int deltaPreciseHP;

  public final UnitModel model;
  public final Unit unit;
  public final Commander CO;

  public UnitDelta(UnitContext start, UnitContext end)
  {
    super();
    before = start;
    after = end;
    deltaHP = after.getHP() - before.getHP();
    deltaPreciseHP = after.health - before.health;
    deltaAmmo = after.ammo - before.ammo;
    deltaFuel = after.fuel - before.fuel;
    deltaMaterials = after.materials - before.materials;

    model = after.model;
    unit = after.unit;
    CO = after.CO;
  }

  public int getHPDamage()
  {
    return deltaHP * -1;
  }
  public int getPreciseHPDamage()
  {
    return deltaPreciseHP * -1;
  }
}
