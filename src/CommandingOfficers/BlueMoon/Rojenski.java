package CommandingOfficers.BlueMoon;

import CommandingOfficers.Commander;
import CommandingOfficers.CommanderAbility;
import CommandingOfficers.CommanderInfo;
import CommandingOfficers.CommanderInfo.InfoPage;
import CommandingOfficers.Modifiers.IndirectRangeBoostModifier;
import Terrain.MapMaster;
import Units.UnitModel;
import Units.UnitModel.ChassisEnum;
import Units.Weapons.WeaponModel;

public class Rojenski extends Commander
{
  private static final CommanderInfo coInfo = new instantiator();
  private static class instantiator extends CommanderInfo
  {
    public instantiator()
    {
      super("Rojenski");
      infoPages.add(new InfoPage(
          "--Rojenski (rebalanced Grit)--\r\n" + 
          "Indirects gain +20% firepower and +1 range, everything else loses -20% firepower.\r\n" + 
          "xxxXXX\r\n" + 
          "LONG SHOT: All indirects gain +1 range.\r\n" + 
          "LONG BARREL: All indirects gain +2 range."));
    }
    @Override
    public Commander create()
    {
      return new Rojenski();
    }
  }

  public Rojenski()
  {
    super(coInfo);

    for( UnitModel um : unitModels.values() )
    {
      boolean buff = false;
      if( !buff && um.weaponModels != null )
      {
        for( WeaponModel pewpew : um.weaponModels )
        {
          if( pewpew.maxRange > 1 )
          {
            pewpew.maxRange += 1;
            buff = true;
          }
        }
      }
      if( buff )
      {
        um.modifyDamageRatio(20);
      }
      else
      {
        um.modifyDamageRatio(-20);
      }
    }

    addCommanderAbility(new RangeBonus(this, "Long Shot", 3, 1));
    addCommanderAbility(new RangeBonus(this, "Long Barrel", 6, 2));
  }

  public static CommanderInfo getInfo()
  {
    return coInfo;
  }

  private static class RangeBonus extends CommanderAbility
  {
    private int power = 1;

    RangeBonus(Commander commander, String name, int cost, int buff)
    {
      super(commander, name, cost);
      power = buff;
    }

    @Override
    protected void perform(MapMaster gameMap)
    {
      myCommander.addCOModifier(new IndirectRangeBoostModifier(power));
    }
  }
}

