package CommandingOfficers.Lazuria;

import Engine.GameScenario;
import CommandingOfficers.Commander;
import CommandingOfficers.CommanderAbility;
import CommandingOfficers.CommanderInfo;
import CommandingOfficers.Modifiers.COMovementModifier;
import Terrain.MapMaster;
import Units.UnitModel;

public class Tasha extends Commander
{
  private static final long serialVersionUID = 1L;
  private static final CommanderInfo coInfo = new instantiator();
  private static class instantiator extends CommanderInfo
  {
    private static final long serialVersionUID = 1L;
    public instantiator()
    {
      super("Tasha");
      infoPages.add(new InfoPage(
          "--TASHA--\r\n" + 
          "Air units gain +40% firepower and +20% defense.\r\n" + 
          "xxXXX\r\n" + 
          "SONIC BOOM: All air units gain +1 movement.\r\n" + 
          "FOX ONE: All air units gain +2 movement."));
    }
    @Override
    public Commander create(GameScenario.GameRules rules)
    {
      return new Tasha(rules);
    }
  }

  public Tasha(GameScenario.GameRules rules)
  {
    super(coInfo, rules);

    for( UnitModel um : unitModels )
    {
      if( um.isAirUnit() )
      {
        um.modifyDamageRatio(40);
        um.modifyDefenseRatio(20);
      }
    }

    addCommanderAbility(new AirMoveBonus(this, "Sonic Boom", 2, 2));
    addCommanderAbility(new AirMoveBonus(this, "Fox One", 5, 4));
  }

  public static CommanderInfo getInfo()
  {
    return coInfo;
  }

  private static class AirMoveBonus extends CommanderAbility
  {
    private static final long serialVersionUID = 1L;
    private int power = 1;

    AirMoveBonus(Commander commander, String name, int cost, int buff)
    {
      super(commander, name, cost);
      power = buff;
    }

    @Override
    protected void perform(MapMaster gameMap)
    {
      COMovementModifier airMoveMod = new COMovementModifier(power);
      for( UnitModel um : myCommander.unitModels )
      {
        if( um.isAirUnit() )
        {
          airMoveMod.addApplicableUnitModel(um);
        }
      }
      myCommander.addCOModifier(airMoveMod);
    }
  }
}
