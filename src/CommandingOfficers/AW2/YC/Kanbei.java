package CommandingOfficers.AW2.YC;

import java.util.ArrayList;

import CommandingOfficers.*;
import CommandingOfficers.AW2.AW2Commander;
import Engine.GameScenario;
import Engine.Combat.StrikeParams;
import Engine.Combat.StrikeParams.BattleParams;
import Engine.UnitMods.CounterMultiplierModifier;
import Engine.UnitMods.UnitDamageModifier;
import Engine.UnitMods.UnitFightStatModifier;
import Engine.UnitMods.UnitModifier;
import UI.UIUtils;
import Terrain.MapMaster;
import Units.UnitContext;

public class Kanbei extends AW2Commander
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
      super("Kanbei", UIUtils.SourceGames.AW2, UIUtils.YC);
      infoPages.add(new InfoPage(
            "Kanbei (AW2)\n"
          + "The emperor of Yellow Comet. A skilled CO who has a soft spot for his daughter.\n"
          + "All units have high offensive and defensive abilities, but are expensive to deploy.\n"
          + "(+30/30 stats for 1.2x prices)\n"));
      infoPages.add(new InfoPage(new MoraleBoost(null, null),
            "Increases attack strength of all units.\n"
          + "(+20/10 stats, total 150/140)\n"));
      infoPages.add(new InfoPage(new SamuraiSpirit(null, null),
            "Strengthens offensive and defensive abilities of all units. Damage inflicted when counter attacking is multiplied by 1.5.\n"
          + "(+20/30 stats, total 150/160; 1.5x damage on counterattack)\n"));
      infoPages.add(new InfoPage(
            "Hit: Sonja\n"
          + "Miss: Computers"));
      infoPages.add(AW2_MECHANICS_BLURB);
    }
    @Override
    public Commander create(GameScenario.GameRules rules)
    {
      return new Kanbei(rules);
    }
  }

  public Kanbei(GameScenario.GameRules rules)
  {
    super(coInfo, rules);

    CommanderAbility.CostBasis cb = getGameBasis();
    addCommanderAbility(new MoraleBoost(this, cb));
    addCommanderAbility(new SamuraiSpirit(this, cb));
  }
  @Override
  public void modifyUnitAttack(StrikeParams params)
  {
    params.attackPower += 30;
  }
  @Override
  public void modifyUnitDefenseAgainstUnit(BattleParams params)
  {
    params.defenseSubtraction += 30;
  }
  @Override
  public void modifyCost(UnitContext uc)
  {
    uc.costRatio += 20;
  }

  private static class MoraleBoost extends AW2Ability
  {
    private static final long serialVersionUID = 1L;
    private static final String NAME = "Morale Boost";
    private static final int COST = 4;
    UnitModifier statMod;

    MoraleBoost(Kanbei commander, CostBasis basis)
    {
      super(commander, NAME, COST, basis);
      statMod = new UnitDamageModifier(20);
    }
    @Override
    protected void enqueueMods(MapMaster gameMap, ArrayList<UnitModifier> modList)
    {
      modList.add(statMod);
    }
  }

  private static class SamuraiSpirit extends AW2Ability
  {
    private static final long serialVersionUID = 1L;
    private static final String NAME = "Samurai Spirit";
    private static final int COST = 7;
    UnitModifier statMod, counterMod;

    SamuraiSpirit(Kanbei commander, CostBasis basis)
    {
      super(commander, NAME, COST, basis);
      statMod = new UnitFightStatModifier(20);
      counterMod = new CounterMultiplierModifier(150);
      AIFlags = PHASE_TURN_START | PHASE_TURN_END;
    }
    @Override
    protected void enqueueMods(MapMaster gameMap, ArrayList<UnitModifier> modList)
    {
      modList.add(statMod);
      modList.add(counterMod);
    }
  }

}
