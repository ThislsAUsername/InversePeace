package CommandingOfficers;

import Engine.GameInstance;
import Engine.GameScenario;
import Engine.Utils;
import Engine.XYCoord;
import Engine.Combat.BattleSummary;
import Engine.GameEvents.GameEventQueue;
import Engine.UnitMods.BuildCountsTracker;
import Engine.UnitMods.CountTracker;
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
          "Remove "+SearAbility.SEAR_WOUND+" HP from each of Cinder's units.\n" +
          "Reactivate all units.\n" +
          "Resupply units that had not yet acted.\n"));
      infoPages.add(new InfoPage(
          WitchFireAbility.WITCHFIRE_NAME+" ("+WitchFireAbility.WITCHFIRE_COST+"):\n" +
          "After any unit attacks, it will be reactivated; this costs 1 HP per attack made by that unit so far.\n" +
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

  public WitchFireAbility witchFire = new WitchFireAbility();

  public Cinder(GameScenario.GameRules rules)
  {
    super(coInfo, rules);

    addCommanderAbility(new SearAbility());
    addCommanderAbility(witchFire);
  }

  @Override
  public void registerForEvents(GameInstance game)
  {
    super.registerForEvents(game);
    buildCounts = StateTracker.initialize(game, BuildCountsTracker.class);

    WitchFireTracker wfTracker = WitchFireTracker.initialize(game, WitchFireTracker.class);
    witchFire.init(wfTracker);
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
  private static class WitchFireAbility extends CommanderAbility
  {
    private static final long serialVersionUID = 1L;
    private static final String WITCHFIRE_NAME = "Witchfire";
    private static final int WITCHFIRE_COST = 9;
    private WitchFireTracker tracker;

    WitchFireAbility()
    {
      super(WITCHFIRE_NAME, WITCHFIRE_COST);
    }
    public void init(WitchFireTracker tracker)
    {
      this.tracker = tracker;
    }

    @Override
    protected void perform(Commander co, MapMaster gameMap)
    {
      tracker.startTracking(co);
    }

    @Override
    protected void revert(Commander co, MapMaster gameMap)
    {
      tracker.stopTracking(co);
    }
  }

  private static class WitchFireTracker extends StateTracker<WitchFireTracker>
  {
    private static final long serialVersionUID = 1L;

    protected WitchFireTracker(Class<WitchFireTracker> key, GameInstance gi)
    {
      super(key, gi);
    }
    @Override
    protected WitchFireTracker item()
    {
      return this;
    }

    private CountTracker<Commander, Unit> attackCounts = new CountTracker<>();

    public void startTracking(Commander co)
    {
      attackCounts.getCountFor(co);
    }
    public void stopTracking(Commander co)
    {
      attackCounts.resetCountFor(co);
    }

    @Override
    public GameEventQueue receiveBattleEvent(BattleSummary battleInfo)
    {
      Commander co = battleInfo.attacker.CO;
      if( !attackCounts.hasCountFor(co) )
        return null;
      // Since an active CO was part of the fight, reactivate the attacker at the cost of HP.
      GameEventQueue results = new GameEventQueue();
      Unit minion = battleInfo.attacker.unit;
      // Cost starts at 1, then adds one for each subsequent attack
      int refreshCost = 1+attackCounts.getCountFor(co, minion);
      int hp = minion.getHP();
      if( hp > refreshCost )
      {
        minion.alterHP(-refreshCost);
        minion.isTurnOver = false;
        attackCounts.incrementCount(co, minion);
      }
      else
      {
        // Guess he's not gonna make it.
        // TODO: Maybe add a debuff event/animation here as well.
        Utils.enqueueDeathEvent(minion, results);
      }
      return results;
    }
  }
}
