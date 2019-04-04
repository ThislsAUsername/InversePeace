package CommandingOfficers;

import CommandingOfficers.Modifiers.CODamageModifier;
import Terrain.MapMaster;
import Terrain.Location;
import Units.Unit;

public class BHHawke extends Commander
{
  private static final CommanderInfo coInfo = new CommanderInfo("Hawke", new instantiator());

  private static class instantiator extends COMaker
  {
    @Override
    public Commander create()
    {
      return new BHHawke();
    }
  }

  public BHHawke()
  {
    super(coInfo);
    new CODamageModifier(10).apply(this);

    addCommanderAbility(new BlackWave(this));
    addCommanderAbility(new BlackStorm(this));
  }

  public static CommanderInfo getInfo()
  {
    return coInfo;
  }

  private static class BlackWave extends CommanderAbility
  {
    private static final String NAME = "Black Wave";
    private static final int COST = 5;
    private static final int POWER = 1;

    BlackWave(Commander commander)
    {
      super(commander, NAME, COST);
    }

    @Override
    protected void perform(MapMaster gameMap)
    {
      for( int i = 0; i < gameMap.mapWidth; i++ )
      {
        for( int j = 0; j < gameMap.mapHeight; j++ )
        {
          Location loc = gameMap.getLocation(i, j);
          Unit victim = loc.getResident();
          if( victim != null )
          {
            if( myCommander.isEnemy(victim.CO) )
              victim.alterHP(-POWER);
            if( myCommander == victim.CO )
              victim.alterHP(POWER);
          }
        }
      }
    }
  }

  private static class BlackStorm extends CommanderAbility
  {
    private static final String NAME = "Black Storm";
    private static final int COST = 9;
    private static final int POWER = 2;

    BlackStorm(Commander commander)
    {
      super(commander, NAME, COST);
    }

    @Override
    protected void perform(MapMaster gameMap)
    {
      for( int i = 0; i < gameMap.mapWidth; i++ )
      {
        for( int j = 0; j < gameMap.mapHeight; j++ )
        {
          Location loc = gameMap.getLocation(i, j);
          Unit victim = loc.getResident();
          if( victim != null )
          {
            if( myCommander.isEnemy(victim.CO) )
              victim.alterHP(-POWER);
            if( myCommander == victim.CO )
              victim.alterHP(POWER);
          }
        }
      }
    }
  }
}
