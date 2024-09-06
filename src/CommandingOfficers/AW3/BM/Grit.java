package CommandingOfficers.AW3.BM;

import java.util.ArrayList;

import CommandingOfficers.*;
import CommandingOfficers.AW3.AW3Commander;
import Engine.GameScenario;
import Engine.Combat.StrikeParams;
import Engine.UnitMods.IndirectDamageModifier;
import Engine.UnitMods.UnitIndirectRangeModifier;
import Engine.UnitMods.UnitModifier;
import UI.UIUtils;
import Terrain.MapMaster;
import Units.UnitContext;
import Units.UnitModel;

public class Grit extends AW3Commander
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
      super("Grit", UIUtils.SourceGames.AW3, UIUtils.BM);
      infoPages.add(new InfoPage(
            "Grit (AW3)\n"
          + "A laid-back style masks his dependability. A peerless marksman. Works well with Olaf.\n"
          + "Range for ranged units is one space more than other COs. They cause more damage too. Weak in combat with non-infantry direct combat units.\n"
          + "(+1 indirect range, +20 indirect attack, -20 non-footsoldier direct attack)\n"));
      infoPages.add(new InfoPage(new SnipeAttack(null, null),
            "Increases the range of distance weapons by one space. Attack strength of these units also increases (+30, +60 total).\n"
          + "+10 attack and defense.\n"));
      infoPages.add(new InfoPage(new SuperSnipe(null, null),
            "Distance weapons can shoot two spaces farther than normal. They also receive a firepower bonus (+30, +60 total).\n"
          + "+10 attack and defense.\n"));
      infoPages.add(new InfoPage(
            "Hit: Cats\n"
          + "Miss: Rats"));
      infoPages.add(AW3_MECHANICS_BLURB);
    }
    @Override
    public Commander create(GameScenario.GameRules rules)
    {
      return new Grit(rules);
    }
  }

  public Grit(GameScenario.GameRules rules)
  {
    super(coInfo, rules);

    CommanderAbility.CostBasis cb = getGameBasis();
    addCommanderAbility(new SnipeAttack(this, cb));
    addCommanderAbility(new SuperSnipe(this, cb));
  }

  @Override
  public void modifyAttackRange(UnitContext uc)
  {
    if( uc.weapon != null && uc.weapon.rangeMax() > 1 )
      uc.rangeMax += 1;
  }
  @Override
  public void modifyUnitAttack(StrikeParams params)
  {
    if( params.battleRange > 1 )
      params.attackPower += 20;
    if( params.attacker.model.isAny(UnitModel.TROOP) )
      return;
    if( params.battleRange < 2 )
      params.attackPower -= 20;
  }

  private static class SnipeAttack extends AW3Ability
  {
    private static final long serialVersionUID = 1L;
    private static final String NAME = "Snipe Attack";
    private static final int COST = 3;
    private static final int BUFF = 30;
    UnitModifier statMod, rangeMod;

    SnipeAttack(Grit commander, CostBasis basis)
    {
      super(commander, NAME, COST, basis);
      statMod  = new IndirectDamageModifier(BUFF);
      rangeMod = new UnitIndirectRangeModifier(1);
    }

    @Override
    protected void enqueueMods(MapMaster gameMap, ArrayList<UnitModifier> modList)
    {
      modList.add(statMod);
      modList.add(rangeMod);
    }
  }

  private static class SuperSnipe extends AW3Ability
  {
    private static final long serialVersionUID = 1L;
    private static final String NAME = "Super Snipe";
    private static final int COST = 6;
    private static final int BUFF = 30;
    UnitModifier statMod, rangeMod;

    SuperSnipe(Commander commander, CostBasis basis)
    {
      super(commander, NAME, COST, basis);
      statMod  = new IndirectDamageModifier(BUFF);
      rangeMod = new UnitIndirectRangeModifier(2);
    }

    @Override
    protected void enqueueMods(MapMaster gameMap, ArrayList<UnitModifier> modList)
    {
      modList.add(statMod);
      modList.add(rangeMod);
    }
  }

}