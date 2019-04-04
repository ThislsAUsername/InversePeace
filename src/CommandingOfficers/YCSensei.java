package CommandingOfficers;

import CommandingOfficers.Modifiers.COMovementModifier;
import CommandingOfficers.Modifiers.UnitTypeDamageModifier;
import Engine.XYCoord;
import Engine.GameEvents.CreateUnitEvent;
import Terrain.MapMaster;
import Terrain.Location;
import Terrain.TerrainType;
import Units.UnitModel;

public class YCSensei extends Commander
{
  private static final CommanderInfo coInfo = new CommanderInfo("Sensei", new instantiator());
  private static class instantiator extends COMaker
  {
    @Override
    public Commander create()
    {
      return new YCSensei();
    }
  }

  public YCSensei()
  {
    super(coInfo);

    for( UnitModel um : unitModels )
    {
      switch (um.chassis)
      {
        case AIR_LOW:
          um.modifyDamageRatio(50);
          break;
        case TROOP:
          um.modifyDamageRatio(40);
          break;
        case AIR_HIGH:
          break;
        default:
          um.modifyDamageRatio(-10);
          break;
      }
    }

    COMovementModifier moveMod = new COMovementModifier();
    moveMod.addApplicableUnitModel(getUnitModel(UnitModel.UnitEnum.APC));
    moveMod.addApplicableUnitModel(getUnitModel(UnitModel.UnitEnum.T_COPTER));
    moveMod.addApplicableUnitModel(getUnitModel(UnitModel.UnitEnum.LANDER));
    moveMod.apply(this);

    addCommanderAbility(new CopterCommand(this));
    addCommanderAbility(new AirborneAssault(this));
  }

  public static CommanderInfo getInfo()
  {
    return coInfo;
  }

  private static class CopterCommand extends CommanderAbility
  {
    private static final String NAME = "Copter Command";
    private static final int COST = 2;

    CopterCommand(Commander commander)
    {
      super(commander, NAME, COST);
    }

    @Override
    protected void perform(MapMaster gameMap)
    {
      UnitModel spawn = myCommander.getUnitModel(UnitModel.UnitEnum.INFANTRY);
      for( XYCoord xyc : myCommander.ownedProperties )
      {
        Location loc = gameMap.getLocation(xyc);
        if( loc.getEnvironment().terrainType == TerrainType.CITY && loc.getResident() == null)
        {
          CreateUnitEvent cue = new CreateUnitEvent(myCommander,spawn,loc.getCoordinates());
          myCommander.money += spawn.getCost();
          cue.performEvent(gameMap);
          loc.getResident().alterHP(-1);
          loc.getResident().isTurnOver = false;
        }
      }
      UnitTypeDamageModifier copterPowerMod = new UnitTypeDamageModifier(15);
      copterPowerMod.addApplicableUnitModel(myCommander.getUnitModel(UnitModel.UnitEnum.B_COPTER));
      myCommander.addCOModifier(copterPowerMod);
    }
  }

  private static class AirborneAssault extends CommanderAbility
  {
    private static final String NAME = "Lightning Strike";
    private static final int COST = 9;

    AirborneAssault(Commander commander)
    {
      // as we start in Bear form, UpTurn is the correct starting name
      super(commander, NAME, COST);
    }

    @Override
    protected void perform(MapMaster gameMap)
    {
      UnitModel spawn = myCommander.getUnitModel(UnitModel.UnitEnum.MECH);
      for( XYCoord xyc : myCommander.ownedProperties )
      {
        Location loc = gameMap.getLocation(xyc);
        if( loc.getEnvironment().terrainType == TerrainType.CITY && loc.getResident() == null)
        {
          CreateUnitEvent cue = new CreateUnitEvent(myCommander,spawn,loc.getCoordinates());
          myCommander.money += spawn.getCost();
          cue.performEvent(gameMap);
          loc.getResident().alterHP(-1);
          loc.getResident().isTurnOver = false;
        }
      }
      UnitTypeDamageModifier copterPowerMod = new UnitTypeDamageModifier(15);
      copterPowerMod.addApplicableUnitModel(myCommander.getUnitModel(UnitModel.UnitEnum.B_COPTER));
      myCommander.addCOModifier(copterPowerMod);
    }
  }
}
