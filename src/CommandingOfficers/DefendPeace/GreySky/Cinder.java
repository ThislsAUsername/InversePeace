package CommandingOfficers.DefendPeace.GreySky;

import java.awt.Color;
import java.util.ArrayList;

import CommandingOfficers.Commander;
import CommandingOfficers.CommanderAbility;
import CommandingOfficers.CommanderInfo;
import Engine.Army;
import Engine.GameInstance;
import Engine.GameScenario;
import Engine.Utils;
import Engine.XYCoord;
import Engine.Combat.BattleSummary;
import Engine.GameEvents.GameEventQueue;
import Engine.StateTrackers.BuildCountsTracker;
import Engine.StateTrackers.CountManager;
import Engine.StateTrackers.StateTracker;
import Engine.UnitMods.UnitFightStatModifier;
import Engine.UnitMods.UnitModifier;
import Terrain.MapMaster;
import UI.UIUtils;
import Units.Unit;
import Units.UnitContext;
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
      super("Cinder", UIUtils.SourceGames.DEFEND_PEACE, UIUtils.GS);
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
          "Resupply units that had not yet acted.\n" +
          "Does not increase in activation cost." +
          "+10 attack and defense.\n"));
      infoPages.add(new InfoPage(
          WitchFireAbility.WITCHFIRE_NAME+" ("+WitchFireAbility.WITCHFIRE_COST+"):\n" +
          "After any unit attacks, it will be reactivated; this costs 1 HP per attack made by that unit so far.\n" +
          "This may be done repeatedly, but it can kill Cinder's own units.\n" +
          "+10 attack and defense.\n"));
    }
    @Override
    public Commander create(GameScenario.GameRules rules)
    {
      return new Cinder(rules);
    }
  }

  private static final int PREMIUM_PER_BUILD = 1000;

  private BuildCountsTracker buildCounts;

  public Cinder(GameScenario.GameRules rules)
  {
    super(coInfo, rules);

    addCommanderAbility(new SearAbility(this));
    addCommanderAbility(new WitchFireAbility(this));
  }

  @Override
  public void initForGame(GameInstance game)
  {
    super.initForGame(game);
    buildCounts = StateTracker.instance(game, BuildCountsTracker.class);
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
    if( this == unit.CO && army.myView.isLocationValid(buildCoords) )
    {
      unit.alterHealth(-20);
      unit.isTurnOver = false;
    }
    return null;
  }

  /**
   * To compensate for the ability to continue producing units in a single turn,
   * the cost of units increases for repeated purchases from a single property.
   */
  @Override
  public int getBuyCost(UnitModel um, XYCoord coord)
  {
    UnitContext uc = getCostContext(um, coord);
    uc.costShift += buildCounts.getCountFor(this, uc.coord) * PREMIUM_PER_BUILD;
    return uc.getCostTotal();
  }

  @Override
  public char getPlaceMarking(XYCoord xyc, Army activeArmy)
  {
    if( activeArmy != this.army )
      return super.getPlaceMarking(xyc, activeArmy);
    int count = buildCounts.getCountFor(this, xyc);
    if( !army.myView.isLocationValid(xyc) || count < 1 )
      return super.getPlaceMarking(xyc, activeArmy);

    return ("" + count).charAt(0);
  }
  @Override
  public Color getMarkingColor(XYCoord xyc)
  {
    // Invert my color so the number is easily visible
    int r = 255 - myColor.getRed();
    int g = 255 - myColor.getGreen();
    int b = 255 - myColor.getBlue();
    Color buildCountColor = new Color(r, g, b);
    return buildCountColor;
  }

  /*
   * Sear causes 1 mass damage to Cinder's own troops, in exchange for refreshing them.
   */
  private static class SearAbility extends CommanderAbility
  {
    private static final long serialVersionUID = 1L;
    private static final String SEAR_NAME = "Sear";
    private static final int SEAR_COST = 6;
    private static final int SEAR_WOUND = -10;
    UnitModifier statMod = new UnitFightStatModifier(10);

    SearAbility(Cinder cinder)
    {
      super(cinder, SEAR_NAME, SEAR_COST);
      AIFlags = PHASE_TURN_END;
    }

    @Override
    public int getCost()
    {
      // Override default cost-increase behavior
      return SEAR_COST * CHARGERATIO_FUNDS;
    }

    @Override
    protected void perform(MapMaster gameMap)
    {
      for( Unit unit : myCommander.army.getUnits() )
      {
        if( !unit.isTurnOver )
        {
          unit.resupply(); // the missing HP has to go somewhere...
        }
        unit.alterHealth(SEAR_WOUND);
        unit.isTurnOver = false;
      }
    }
    @Override
    public void enqueueUnitMods(MapMaster gameMap, ArrayList<UnitModifier> modList)
    {
      modList.add(statMod);
    }
  }

  /*
   * Witchfire causes Cinder's troops to automatically refresh after attacking, at the cost of scaling HP
   */
  private static class WitchFireAbility extends CommanderAbility
  {
    private static final long serialVersionUID = 1L;
    private static final String WITCHFIRE_NAME = "Witchfire";
    private static final int WITCHFIRE_COST = 7;
    private WitchFireTracker tracker;
    UnitModifier statMod = new UnitFightStatModifier(10);

    WitchFireAbility(Cinder cinder)
    {
      super(cinder, WITCHFIRE_NAME, WITCHFIRE_COST);
    }
    @Override
    public void initForGame(GameInstance game)
    {
      tracker = WitchFireTracker.instance(game, WitchFireTracker.class);
    }

    @Override
    protected void perform(MapMaster gameMap)
    {
      tracker.startTracking(myCommander.army);
    }
    @Override
    public void enqueueUnitMods(MapMaster gameMap, ArrayList<UnitModifier> modList)
    {
      modList.add(statMod);
    }

    @Override
    protected void revert(MapMaster gameMap)
    {
      tracker.stopTracking(myCommander.army);
    }
  }

  private static class WitchFireTracker extends StateTracker
  {
    private static final long serialVersionUID = 1L;

    private CountManager<Army, Unit> attackCounts = new CountManager<>();

    public void startTracking(Army army)
    {
      attackCounts.getCountFor(army);
    }
    public void stopTracking(Army army)
    {
      attackCounts.resetCountFor(army);
    }

    @Override
    public GameEventQueue receiveBattleEvent(BattleSummary battleInfo)
    {
      Army army = battleInfo.attacker.CO.army;
      if( !attackCounts.hasCountFor(army) )
        return null;
      // Since an active CO was part of the fight, reactivate the attacker at the cost of HP.
      GameEventQueue results = new GameEventQueue();
      Unit minion = battleInfo.attacker.unit;
      // Cost starts at 10, then adds 10 for each subsequent attack
      int refreshCost = 10 * (1+attackCounts.getCountFor(army, minion));
      int health = minion.getHealth();
      if( health > refreshCost )
      {
        minion.alterHealth(-refreshCost);
        minion.isTurnOver = false;
        attackCounts.incrementCount(army, minion);
      }
      else
      {
        // Guess he's not gonna make it.
        // TODO: Maybe add a debuff event/animation here as well.
        Utils.enqueueDeathEvent(minion, results);
      }
      return results;
    }

    @Override
    public char getUnitMarking(Unit unit, Army activeArmy)
    {
      Army army = unit.CO.army;
      char defaultVal = super.getUnitMarking(unit, activeArmy);
      // Don't pollute the pool for the early out from earlier
      if( !attackCounts.hasCountFor(army) )
        return defaultVal;
      int count = attackCounts.getCountFor(army, unit);
      if( 0 >= count )
        return defaultVal;
      // Units can't survive attacking 10 times, so don't worry about that
      return ("" + count).charAt(0);
    }
    @Override
    public Color getMarkingColor(Unit unit)
    {
      return unit.CO.myColor;
    }
  }
}
