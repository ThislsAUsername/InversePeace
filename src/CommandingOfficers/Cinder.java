package CommandingOfficers;

import java.util.ArrayList;

import CommandingOfficers.Modifiers.COModifier;
import Engine.GameInstance;
import Engine.GameScenario;
import Engine.Utils;
import Engine.XYCoord;
import Engine.Combat.BattleSummary;
import Engine.GameEvents.GameEventListener;
import Engine.GameEvents.GameEventQueue;
import Engine.UnitMods.BuildCountsTracker;
import Engine.UnitMods.StateTracker;
import Terrain.MapMaster;
import Units.Unit;
import Units.UnitModel;

/*
 * Cinder is based on getting an edge in the action economy, at the cost of unit health.
 */
public class Cinder extends Commander
{
  private static final long serialVersionUID = 1L;

  private static final CommanderInfo coInfo = new instantiator();
  private static class instantiator extends CommanderInfo
  {
    private static final long serialVersionUID = 1L;
    public instantiator()
    {
      super("Cinder");
      infoPages.add(new InfoPage(
          "'Cinders' are products of Grey Sky's super-soldier program who gain initiative in battle by warping time - they're named for the unpredictable thermal surges caused by their temporal meddling.\n" + 
          "Having taken this title as her name, Commander Cinder's blazing speed dominates the battlefield."));
      infoPages.add(new InfoPage(
          "Passive:\n" + 
          "- Units are built at 8 HP, but can act immediately.\n" +
          "- Building on a base that has produced this turn already incurs a fee of 1000 funds per build you have already done there this turn.\n"));
      infoPages.add(new InfoPage(
          SearAbility.SEAR_NAME+" ("+SearAbility.SEAR_COST+"):\n" +
          "Removes "+SearAbility.SEAR_WOUND+" HP from each of Cinder's units.\n" +
          "Units that had not yet acted have their supplies restored.\n" +
          "Units are refreshed and may act again.\n"));
      infoPages.add(new InfoPage(
          WitchFireAbility.WITCHFIRE_NAME+" ("+WitchFireAbility.WITCHFIRE_COST+"):\n" +
          "After any unit attacks, it will lose "+WitchFireAbility.WITCHFIRE_HP_COST+" HP and be refreshed.\n" +
          "This may be done repeatedly, but it can kill Cinder's own units.\n"));
    }
    @Override
    public Commander create(GameScenario.GameRules rules)
    {
      return new Cinder(rules);
    }
  }

  private static final int PREMIUM_PER_BUILD = 1000;

  private BuildCountsTracker buildCounts;

  public WitchFireListener witchFireListener;

  public Cinder(GameScenario.GameRules rules)
  {
    super(coInfo, rules);

    addCommanderAbility(new SearAbility());
    addCommanderAbility(new WitchFireAbility(this));
  }

  @Override
  public void registerForEvents(GameInstance game)
  {
    super.registerForEvents(game);
    buildCounts = StateTracker.initialize(game, BuildCountsTracker.class);

    witchFireListener = new WitchFireListener(this, WitchFireAbility.WITCHFIRE_HP_COST);
    witchFireListener.registerForEvents(game);
  }
  @Override
  public void unregister(GameInstance game)
  {
    super.unregister(game);
    witchFireListener.unregister(game);
  }

  public static CommanderInfo getInfo()
  {
    return coInfo;
  }

  /*
   * Cinder builds units at 8HP ready to act.
   */
  @Override
  public GameEventQueue receiveCreateUnitEvent(Unit unit)
  {
    XYCoord buildCoords = new XYCoord(unit.x, unit.y);
    if( this == unit.CO && myView.isLocationValid(buildCoords) )
    {
      unit.alterHP(-2);
      unit.isTurnOver = false;
    }
    return null;
  }

  /**
   * To compensate for the ability to continue producing units in a single turn,
   * the cost of units increases for repeated purchases from a single property.
   */
  @Override
  public int getPriceOffset(XYCoord coord, UnitModel um, int currentPrice)
  {
    return buildCounts.getCountFor(this, coord)*PREMIUM_PER_BUILD;
  }

  /*
   * Sear causes 1 mass damage to Cinder's own troops, in exchange for refreshing them.
   */
  private static class SearAbility extends CommanderAbility
  {
    private static final long serialVersionUID = 1L;
    private static final String SEAR_NAME = "Sear";
    private static final int SEAR_COST = 5;
    private static final int SEAR_WOUND = -1;

    SearAbility()
    {
      super(SEAR_NAME, SEAR_COST);
      AIFlags = PHASE_TURN_END;
    }

    @Override
    protected void perform(Commander co, MapMaster gameMap)
    {
      for( Unit unit : co.units )
      {
        if( unit.isTurnOver )
        {
          unit.resupply(); // the missing HP has to go somewhere...
        }
        unit.alterHP(SEAR_WOUND);
        unit.isTurnOver = false;
      }
    }
  }

  /*
   * Witchfire causes Cinder's troops to automatically refresh after attacking, at the cost of 1 HP
   */
  private static class WitchFireAbility extends CommanderAbility implements COModifier
  {
    private static final long serialVersionUID = 1L;
    private static final String WITCHFIRE_NAME = "Witchfire";
    private static final int WITCHFIRE_COST = 9;
    private static final int WITCHFIRE_HP_COST = 1;
    private Cinder coCast;

    WitchFireAbility(Cinder commander)
    {
      super(WITCHFIRE_NAME, WITCHFIRE_COST);
      coCast = commander;
    }

    @Override
    protected void enqueueCOMods(Commander co, MapMaster gameMap, ArrayList<COModifier> modList)
    {
      modList.add(this); // TODO
    }

    @Override
    protected void perform(Commander co, MapMaster gameMap)
    {}

    @Override // COModifier interface.
    public void applyChanges(Commander commander)
    {
      // TODO
      coCast.witchFireListener.listen = true;
    }

    @Override
    public void revertChanges(Commander commander)
    {
      coCast.witchFireListener.listen = false;
    }
  }

  private static class WitchFireListener implements GameEventListener
  {
    private static final long serialVersionUID = 1L;
    private Cinder myCommander = null;
    private final int refreshCost;
    public boolean listen = false;

    public WitchFireListener(Cinder myCo, int HPCost)
    {
      myCommander = myCo;
      refreshCost = HPCost;
    }

    @Override
    public GameEventQueue receiveBattleEvent(BattleSummary battleInfo)
    {
      if( !listen )
        return null;
      GameEventQueue results = new GameEventQueue();
      // Determine if we were part of this fight. If so, refresh at our own expense.
      Unit minion = battleInfo.attacker;
      if( minion.CO == myCommander )
      {
        int hp = minion.getHP();
        if( hp > refreshCost )
        {
          minion.alterHP(-refreshCost);
          minion.isTurnOver = false;
        }
        else
        {
          // Guess he's not gonna make it.
          // TODO: Maybe add a debuff event/animation here as well.
          Utils.enqueueDeathEvent(minion, results);
        }
      }
      return results;
    }
  }
}
