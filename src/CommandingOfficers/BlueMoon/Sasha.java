package CommandingOfficers.BlueMoon;

import Engine.GameScenario;
import CommandingOfficers.Commander;
import CommandingOfficers.CommanderAbility;
import CommandingOfficers.CommanderInfo;
import CommandingOfficers.Modifiers.COModifier;
import Engine.Combat.BattleSummary;
import Engine.GameEvents.GameEventListener;
import Terrain.MapMaster;

public class Sasha extends Commander
{
  private static final long serialVersionUID = 1L;
  private static final CommanderInfo coInfo = new instantiator();
  private static class instantiator extends CommanderInfo
  {
    private static final long serialVersionUID = 1L;
    public instantiator()
    {
      super("Sasha");
      infoPages.add(new InfoPage(
          "Sasha\r\n" + 
          "  Receives +100 income per property (note: labs and comtowers do not count towards this)\r\n" + 
          "Market Crash -- Reduce enemy power bar(s) by 10% per 5000 funds\r\n" + 
          "War Bonds -- Receive funds for damage dealt to enemy units (50% of the respective funds damage)"));
    }
    @Override
    public Commander create(GameScenario.GameRules rules)
    {
      return new Sasha(rules);
    }
  }

  // Variables to characterize Patch's abilities.

  private static final String PILLAGE_NAME = "War Bonds";
  private static final int PILLAGE_COST = 6;
  private static final double PILLAGE_INCOME = 0.5;

  public Sasha(GameScenario.GameRules rules)
  {
    super(coInfo, rules);
    
    incomeAdjustment = 100;

    addCommanderAbility(new MarketCrash(this));
    addCommanderAbility(new WarBonds(this, PILLAGE_NAME, PILLAGE_COST, PILLAGE_INCOME));

  }

  public static CommanderInfo getInfo()
  {
    return coInfo;
  }

  private static class MarketCrash extends CommanderAbility
  {
    private static final long serialVersionUID = 1L;
    private static final String NAME = "Market Crash";
    private static final int COST = 2;
    private static final double FUNDS_TO_DRAIN_ALL = 50000;

    MarketCrash(Commander commander)
    {
      super(commander, NAME, COST);
    }

    @Override
    protected void perform(MapMaster gameMap)
    {
      for( Commander co : gameMap.commanders )
      {
        if (myCommander.isEnemy(co))
        {
          double max = 0;
          for (double val : co.getAbilityCosts())
          {
            max = Math.max(max, val);
          }
          co.modifyAbilityPower(-max*(myCommander.money/FUNDS_TO_DRAIN_ALL));
        }
      }
    }
  }

  private static class WarBonds extends CommanderAbility implements COModifier
  {
    private static final long serialVersionUID = 1L;
    private DamageDealtToIncomeConverter listener = null;

    WarBonds(Commander myCO, String abilityName, int abilityCost, double incomeRatio)
    {
      super(myCO, abilityName, abilityCost);

      // Create an object to handle receiving battle outcomes and generating income.
      listener = new DamageDealtToIncomeConverter(myCO, incomeRatio);
    }

    @Override
    protected void perform(MapMaster gameMap)
    {
      // Register this class as a COModifier, so we can deactivate one turn from now.
      myCommander.addCOModifier(this);

      // Register the damage-to-income listener.
      GameEventListener.registerEventListener(listener);
    }

    @Override // COModifier interface.
    public void applyChanges(Commander commander)
    {
      // No special action required.
    }

    @Override
    public void revertChanges(Commander commander)
    {
      // This will be called when the Commander removes this COModifier. It will remove the damage
      // modifier we added as well, so we just need to turn off the the damage-to-income listener.
      GameEventListener.unregisterEventListener(listener);
    }
  }

  /**
   * This GameEventListener will inspect published BattleEvents. If the owning Commander took
   * part in the battle, then any damage done to the opponent will give income to the owning
   * Commander. The amount of income is a fraction of value of the damage that was done, thus
   * damaging more expensive units will grant more income.
   */
  private static class DamageDealtToIncomeConverter extends GameEventListener
  {
    private static final long serialVersionUID = 1L;
    private Commander myCommander = null;
    private double myIncomeRatio = 0.0;

    public DamageDealtToIncomeConverter(Commander myCo, double incomeRatio)
    {
      myCommander = myCo;
      myIncomeRatio = incomeRatio;
    }

    @Override
    public void receiveBattleEvent(BattleSummary battleInfo)
    {
      // Determine if we were part of this fight. If so, cash in on any damage done to the other guy.
      double hpLoss = 0;
      double unitCost = 0;
      if( battleInfo.attacker.CO == myCommander )
      {
        hpLoss = battleInfo.defenderHPLoss;
        unitCost = battleInfo.defender.model.getCost();
      }
      else if( battleInfo.defender.CO == myCommander )
      {
        hpLoss = battleInfo.attackerHPLoss;
        unitCost = battleInfo.attacker.model.getCost();
      }

      // Do the necessary math, then round to the nearest int.
      int income = (int)(hpLoss * (unitCost/10.0) * myIncomeRatio + 0.5);
      myCommander.money += income;
    }
  }
}
