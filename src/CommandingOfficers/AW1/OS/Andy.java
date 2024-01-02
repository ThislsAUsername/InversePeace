package CommandingOfficers.AW1.OS;

import CommandingOfficers.*;
import CommandingOfficers.AW1.AW1Commander;
import Engine.GameScenario;
import UI.UIUtils;
import Terrain.MapMaster;
import Units.Unit;

public class Andy extends AW1Commander
{
  private static final long serialVersionUID = 1L;

  private static final CommanderInfo coInfo = new instantiator();
  public static CommanderInfo getInfo()
  {
    return coInfo;
  }
  private static class instantiator extends CommanderInfo
  {
    private static final long serialVersionUID = 1L;
    public instantiator()
    {
      super("Andy_1", UIUtils.SourceGames.AW1, UIUtils.OS);
      infoPages.add(new InfoPage(
            "Andy (AW1)\n"
          + "A brash and energetic boy wonder.\n"
          + "No real weakness. Ready to battle wherever and whenever.\n"));
      infoPages.add(new InfoPage(new HyperRepair(null),
            "Restores 2 HP to all units.\n"
          + "1.1x/0.9x damage dealt/taken.\n"));
      infoPages.add(new InfoPage(
            "Hit: Mechanics\n"
          + "Miss: Waking up early"));
      infoPages.add(AW1_MECHANICS_BLURB);
    }
    @Override
    public Commander create(GameScenario.GameRules rules)
    {
      return new Andy(rules);
    }
  }

  public Andy(GameScenario.GameRules rules)
  {
    super(coInfo, rules);

    addCommanderAbility(new HyperRepair(this));
  }

  private static class HyperRepair extends AW1BasicAbility
  {
    private static final long serialVersionUID = 1L;
    private static final String NAME = "Hyper Repair";
    private static final int COST = 6;
    private static final int HEAL = 2;

    HyperRepair(Andy commander)
    {
      super(commander, NAME, COST);
      AIFlags = PHASE_TURN_START | PHASE_TURN_END;
    }

    @Override
    protected void perform(MapMaster gameMap)
    {
      for( Unit u : myCommander.army.getUnits() )
      {
        u.alterHealth(HEAL*10);
      }
      super.perform(gameMap);
    }
  }

}
