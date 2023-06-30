package AI;

import java.util.*;
import java.util.Map.Entry;

import CommandingOfficers.Commander;
import CommandingOfficers.CommanderAbility;
import Engine.*;
import Engine.GamePath.PathNode;
import Engine.Combat.BattleSummary;
import Engine.Combat.CombatEngine;
import Engine.Combat.CombatContext.CalcType;
import Engine.UnitActionLifecycles.BattleLifecycle.BattleAction;
import Engine.UnitActionLifecycles.CaptureLifecycle.CaptureAction;
import Engine.UnitActionLifecycles.WaitLifecycle;
import Terrain.*;
import Units.*;
import Units.MoveTypes.MoveType;

public class WallyAI extends ModularAI
{
  public WallyAI(Army army)
  {
    super(army);
    aiPhases = new ArrayList<AIModule>(
        Arrays.asList(
            new PowerActivator(army, CommanderAbility.PHASE_TURN_START),
            new DrainActionQueue(army, this),
            new GenerateThreatMap(army, this), // FreeRealEstate and Travel need this, and NHitKO/building do too because of eviction

            new WallyCapper(army, this),
            new NHitKO(army, this),
            new SiegeAttacks(army, this),
            new PowerActivator(army, CommanderAbility.PHASE_BUY),
            new FreeRealEstate(army, this, false, false), // prioritize non-eviction
            new FreeRealEstate(army, this, true,  false), // evict if necessary
            new BuildStuff(army, this),
            new Travel(army, this),
            new FreeRealEstate(army, this, true,  true), // step on industries we're not using
            new Eviction(army, this), // Getting dudes out of the way

            new FillActionQueue(army, this),
            new PowerActivator(army, CommanderAbility.PHASE_TURN_END)
            ));
  }

  private static class instantiator implements AIMaker
  {
    @Override
    public AIController create(Army army)
    {
      return new WallyAI(army);
    }

    @Override
    public String getName()
    {
      return "Wally";
    }

    @Override
    public String getDescription()
    {
      return
          "Wally values units based on firepower and the area they can threaten.\n" +
          "He tries to keep units out of harm's way, and to protect expensive units with cheaper ones.\n" +
          "He can be overly timid, and thus is a fan of artillery.";
    }
  }
  public static final AIMaker info = new instantiator();

  @Override
  public AIMaker getAIInfo()
  {
    return info;
  }

  // What % damage I'll ignore when checking safety
  private static final int INDIRECT_THREAT_THRESHOLD = 7;
  private static final int DIRECT_THREAT_THRESHOLD = 13;
  private static final int    UNIT_HEAL_THRESHOLD = 6; // HP at which units heal
  private static final double UNIT_REFUEL_THRESHOLD = 1.3; // Factor of cost to get to fuel to start worrying about fuel
  private static final double UNIT_REARM_THRESHOLD = 0.25; // Fraction of ammo in any weapon below which to consider resupply
  private static final double AGGRO_EFFECT_THRESHOLD = 0.42; // How effective do I need to be against a unit to target it?
  private static final double AGGRO_FUNDS_WEIGHT = 0.9; // Multiplier on damage I need to get before a sacrifice is worth it
  private static final double AGGRO_CHEAPER_WEIGHT = 0.01; // Multiplier on the score penalty for using expensive units to blow up stragglers
  private static final double RANGE_WEIGHT = 1; // Exponent for how powerful range is considered to be
  private static final double TERRAIN_PENALTY_WEIGHT = 3; // Exponent for how crippling we think high move costs are
  private static final double MIN_SIEGE_RANGE_WEIGHT = 0.8; // Exponent for how much to penalize siege weapon ranges for their min ranges

  private static final double TERRAIN_FUNDS_WEIGHT = 2.5; // Multiplier for per-city income for adding value to units threatening to cap
  private static final double TERRAIN_INDUSTRY_WEIGHT = 20000; // Funds amount added to units threatening to cap an industry
  private static final double TERRAIN_HQ_WEIGHT = 42000; //                  "                                      HQ

  private static final CalcType CALC = CalcType.PESSIMISTIC;

  private static enum TravelPurpose
  {
    // BUSINESS, PLEASURE,
    WANDER(0), SUPPLIES(7), KILL(13), CONQUER(42), NA(99);
    public final int priority;
    TravelPurpose(int p)
    {
      priority = p;
    }
  }
  private static class ActionPlan
  {
    final GameAction action;
    final XYCoord startPos;
    final Object whodunit;
    GamePath path = null;
    XYCoord clearTile; // The tile we expect to have emptied with our attack, if any.
    TravelPurpose purpose = TravelPurpose.NA;
    ActionPlan(Object whodunit, GameAction action)
    {
      this.whodunit = whodunit;
      this.action = action;
      if( null != action.getActor() )
        startPos = new XYCoord(action.getActor());
      else
        startPos = action.getTargetLocation();
    }
    @Override
    public String toString()
    {
      if( null == clearTile )
        return whodunit.toString() + "\n\t" + action.toString() + "\n";
      return whodunit.toString() + "\n\t" + String.format("%s\n\tclearing %s", action, clearTile) + "\n";
    }
  }
  public ActionPlan lastAction;
  private Queue<ActionPlan> queuedActions = new ArrayDeque<>();
  private static class UnitPrediction
  {
    UnitContext identity; // Must be non-null if toAchieve is; UC's unit may be null.
    HashMap<ActionPlan, Integer> damageInstances = new HashMap<>();
    ActionPlan toAchieve;
  }
  private UnitPrediction[][] mapPlan; // TODO: Invalidate if any unit gets trapped/ends up not where we expect - cache unit+destination on offer action
  public PredictionMap predMap; // Owns a reference to the above, to expose its contents to Utils
  private HashSet<Unit> plannedUnits;
  private static class TileThreat
  {
    UnitContext identity;
    ArrayList<WeaponModel> relevantWeapons = new ArrayList<>();
  }
  /**
   * For each X/Y coordinate, stores the enemies that can threaten this tile and what weapon(s) they can do it with
   * <p>Doesn't consider current allied unit positions/blocking
   * <p>Each unit should only have one UnitContext (matching mapPlan) in this map, so predicted damage can propagate to all tiles automagically
   */
  public ArrayList<TileThreat>[][] threatMap;
  private ArrayList<Unit> allThreats;
  private HashMap<UnitModel, Double> unitEffectiveMove = null; // How well the unit can move, on average, on this map

  @SuppressWarnings("unchecked") // Java whines if I use generics and lists at the same time
  private void init(GameMap map)
  {
    lastAction = null;
    allThreats = new ArrayList<Unit>();
    capPhase = new CapPhaseAnalyzer(map, myArmy);
    mapPlan = new UnitPrediction[map.mapWidth][map.mapHeight];
    predMap = new PredictionMap(myArmy, mapPlan);
    plannedUnits = new HashSet<>();
    threatMap = new ArrayList[map.mapWidth][map.mapHeight];
    for( int x = 0; x < map.mapWidth; ++x )
      for( int y = 0; y < map.mapHeight; ++y )
      {
        mapPlan[x][y] = new UnitPrediction();
        threatMap[x][y] = new ArrayList<>();
      }

    unitEffectiveMove = new HashMap<>();
    // init all move multipliers before powers come into play
    for( Army army : map.game.armies )
    {
      for( Commander co : army.cos )
      {
        for( UnitModel model : co.unitModels )
        {
          getEffectiveMove(model);
        }
      }
    }
  }

  @Override
  public void initTurn(GameMap gameMap)
  {
    if( null == unitEffectiveMove )
      init(gameMap);
    queuedActions.clear();
    lastAction = null;
    super.initTurn(gameMap);
    log(String.format("[======== Wally initializing turn %s for %s =========]", turnNum, myArmy));
  }

  @Override
  public void endTurn()
  {
    super.endTurn();
    log(String.format("[======== Wally ending turn %s for %s =========]", turnNum, myArmy));
  }

  private void updatePlan(Object whodunit, Unit unit, GamePath path, GameAction action)
  {
    updatePlan(whodunit, unit, path, action, false, 42);
  }
  private void updatePlan(Object whodunit, UnitContext uc, GamePath path, GameAction action)
  {
    updatePlan(whodunit, uc, path, action, false, 42);
  }
  private void updatePlan(Object whodunit, Unit unit, GamePath path, GameAction action, boolean isAttack, int percentDamage)
  {
    updatePlan(whodunit, new UnitContext(unit), path, action, isAttack, percentDamage);
  }
  private void updatePlan(Object whodunit, UnitContext uc, GamePath path, GameAction action, boolean isAttack, int percentDamage)
  {
    if( null == action )
      return;
    XYCoord dest = action.getMoveLocation();
    final UnitPrediction destPredictTile = mapPlan[dest.xCoord][dest.yCoord];
    ActionPlan canceled = destPredictTile.toAchieve;
    if( null != canceled )
    {
      if( null != canceled.action.getActor() )
        plannedUnits.remove(canceled.action.getActor());
      // Assume only one attack vs any given target - this is not always true, but I don't care
      if( canceled.action.getType() == UnitActionFactory.ATTACK )
      {
        final XYCoord ctt = canceled.action.getTargetLocation();
        mapPlan[ctt.xCoord][ctt.yCoord].damageInstances.remove(canceled);
      }
      // Don't mess with canceling attacks that clear tiles, at least for now
//      final XYCoord cct = canceled.clearTile;
//      if( null != cct )
//        mapPlan[cct.xCoord][cct.yCoord].identity =
      // Don't chain cancellations - if Wally is dumb enough to cause that, he can recalculate :P
    }

    // Assuming our path has been planned well re:terrain, running into enemies is the only obstacle
    if( null != path )
    {
      if( path.getPathLength() < path.getEndCoord().getDistance(uc.unit) ) // Assume unit is real if it's moving
      {
        log(String.format("      %s generated bad path: %s", whodunit, path));
        return;
      }
    }

    final ActionPlan plan = new ActionPlan(whodunit, action);
    plan.path = path;
    destPredictTile.toAchieve = plan;
    destPredictTile.identity = uc;
    uc.coord = dest;
    if( null != uc.unit )
      plannedUnits.add(uc.unit);

    if( isAttack )
    {
      final XYCoord target = action.getTargetLocation();
      mapPlan[target.xCoord][target.yCoord].damageInstances.put(plan, percentDamage);
    }
  }

  /**
   * @return Whether things are going as planned
   */
  private boolean checkIfUnexpected(GameMap gameMap)
  {
    boolean theUnexpected = false;
    if( null != lastAction )
    {
      final GameAction action = lastAction.action;
      final Unit actor = action.getActor();
      if( null != actor ) // Make sure only our unit is in the start and end position
      {
        if( !gameMap.isLocationEmpty(actor, lastAction.startPos) )
          theUnexpected = true;
        if( actor != gameMap.getResident(action.getMoveLocation()) )
          theUnexpected = true;
      }
      final XYCoord clearTile = lastAction.clearTile;
      if( null != clearTile && null != gameMap.getResident(clearTile) )
        theUnexpected = true;
    }
    return theUnexpected;
  }

  public static class DrainActionQueue implements AIModule
  {
    private static final long serialVersionUID = 1L;
    public Army myArmy;
    public final WallyAI ai;

    public DrainActionQueue(Army co, WallyAI ai)
    {
      myArmy = co;
      this.ai = ai;
    }

    @Override
    public GameAction getNextAction(PriorityQueue<Unit> unitQueue, GameMap map)
    {
      boolean theUnexpected = ai.checkIfUnexpected(map);
      // If anything we didn't expect happened, scrap and re-plan everything
      if( theUnexpected )
        ai.queuedActions.clear();

      ActionPlan ae = ai.queuedActions.poll();
      // Check if it's an attack and has been invalidated by RNG shenanigans
      if (null != ae && ae.action.getType() == UnitActionFactory.ATTACK
          && null == map.getResident(ae.action.getTargetLocation()))
      {
        ai.log(String.format("  Discarding invalid attack: %s", ae.action));
        ae = null;
        ai.queuedActions.clear(); // Too bad, gotta re-plan
      }
      if( null == ae )
      {
        ai.lastAction = null; // Don't be surprised when our dudes we moved last turn die
        return null;
      }

      ai.lastAction = ae;
      return ae.action;
    }
  }
  public static class FillActionQueue implements AIModule
  {
    private static final long serialVersionUID = 1L;
    public Army myArmy;
    public final WallyAI ai;

    public FillActionQueue(Army co, WallyAI ai)
    {
      myArmy = co;
      this.ai = ai;
    }

    @Override
    public GameAction getNextAction(PriorityQueue<Unit> unitQueue, GameMap map)
    {
      ai.log(String.format("  Filling action queue from map plan"));
      HashSet<XYCoord> vacatedTiles = new HashSet<>();
      HashSet<XYCoord> revisitTiles = new HashSet<>();
      HashMap<Unit, ActionPlan> actionsBooked = new HashMap<>();

      for( int x = 0; x < map.mapWidth; ++x )
        for( int y = 0; y < map.mapHeight; ++y )
        {
          ActionPlan readyPlan = fetchPlanAndVacateTiles(vacatedTiles, ai, map, x, y);
          if( null != readyPlan )
          {
            final UnitContext actor = ai.mapPlan[x][y].identity;
            if( null != actor.unit )
            {
              if( actionsBooked.containsKey(actor.unit) )
                ai.log(String.format("Warning: Unit is double-booked %s:\n\t%s\n\t%s", actor, readyPlan, actionsBooked.get(actor.unit)));
              actionsBooked.put(actor.unit, readyPlan);
            }
            ai.queuedActions.add(readyPlan);
            ai.mapPlan[x][y].toAchieve = null;
          }
          else if( null != ai.mapPlan[x][y].toAchieve )
            revisitTiles.add(new XYCoord(x, y));
        }

      XYCoord actionAtCoord = new XYCoord(-1, -1);
      while (null != actionAtCoord)
      {
        actionAtCoord = null;
        for( XYCoord movexyc : revisitTiles )
        {
          int x = movexyc.xCoord, y = movexyc.yCoord;
          ActionPlan readyPlan = fetchPlanAndVacateTiles(vacatedTiles, ai, map, x, y);
          if( null != readyPlan )
          {
            final UnitContext actor = ai.mapPlan[x][y].identity;
            if( null != actor.unit )
            {
              if( actionsBooked.containsKey(actor.unit) )
                ai.log(String.format("Warning: Unit is double-booked %s:\n\t%s\n\t%s", actor, readyPlan, actionsBooked.get(actor.unit)));
              actionsBooked.put(actor.unit, readyPlan);
            }
            ai.queuedActions.add(readyPlan);
            ai.mapPlan[x][y].toAchieve = null;
            actionAtCoord = movexyc;
            break;
          }
        }
        if( null != actionAtCoord )
          revisitTiles.remove(actionAtCoord);
      }

      // Clear out any stragglers to maybe re-plan
      for( XYCoord movexyc : revisitTiles )
      {
        int x = movexyc.xCoord, y = movexyc.yCoord;
        ai.mapPlan[x][y].identity = null;
        ai.mapPlan[x][y].toAchieve = null;
      }
      ai.plannedUnits.clear();

      if( ai.queuedActions.isEmpty() )
        return null;

      ActionPlan action = ai.queuedActions.poll();
      ai.log(String.format("  First queued action: %s", action.action));
      ai.lastAction = action;
      return action.action;
    }

    private static ActionPlan fetchPlanAndVacateTiles(HashSet<XYCoord> vacatedTiles,
                                       WallyAI ai, GameMap map,
                                       int x, int y)
    {
      UnitContext actor = ai.mapPlan[x][y].identity;
      ActionPlan  plan  = ai.mapPlan[x][y].toAchieve;
      if( null == plan )
        return null; // Nothing to do here

      final Unit unit = actor.unit;
      if( null != unit && unit.isTurnOver )
      {
        ai.log(String.format("Warning: Action planned for tired unit %s:\n\t%s", unit, plan));
        return null; // Nothing to do here
      }

      final XYCoord movexyc = new XYCoord(x, y);
      if( !vacatedTiles.contains(movexyc)
          && !map.isLocationEmpty(unit, movexyc)
          )
        return null; // Location is full and won't be emptied by our current confirmed plans

      // Assuming our path has been planned well re:terrain, running into enemies is the only obstacle
      GamePath movePath = plan.path;
      if( null != movePath )
      {
        MoveType fff = actor.calculateMoveType();
        ArrayList<PathNode> waypoints = movePath.getWaypoints();
        // We iterate from 1 because the first waypoint is the unit's initial position.
        for( int i = 1; i < waypoints.size(); i++)
        {
          XYCoord from = waypoints.get(i-1).GetCoordinates();
          XYCoord to   = waypoints.get( i ).GetCoordinates();
          int cost = fff.getTransitionCost(map, from, to, actor.unit, false); // Assume unit is real if it's moving
          if( cost > actor.movePower )
            if( !vacatedTiles.contains(to) )
              return null;
        }
      }

      if( actor.unit != plan.action.getActor())
        ai.log(String.format("Warning: plan/action mismatch with %s:\n\t%s\n\t!=\n\t%s", plan, actor.unit, plan.action.getActor()));
      if( null != plan.startPos )
        vacatedTiles.add(plan.startPos);

      // If we're an attack whose damage sums up to clear the target out, populate that state now.
      if( plan.action.getType() == UnitActionFactory.ATTACK )
      {
        XYCoord tt = plan.action.getTargetLocation();
        final UnitPrediction targetPredictTile = ai.mapPlan[tt.xCoord][tt.yCoord];
        final UnitContext target = targetPredictTile.identity;

        int percentDamage = targetPredictTile.damageInstances.get(plan);
        target.damageHP(percentDamage / 10.0, true); // Destructively modifying the planning state is fine and convenient at this stage
        if( 1 > target.getHP() )
        {
          plan.clearTile = tt;
          vacatedTiles.add(tt);
        }
      }
      return plan;
    }
  }

  public static class WallyCapper extends CapChainActuator<WallyAI>
  {
    private static final long serialVersionUID = 1L;
    public WallyCapper(Army co, WallyAI ai)
    {
      super(co, ai);
    }

    @Override
    public GameAction getUnitAction(Unit unit, GameMap map)
    {
      final GameAction capAction = super.getUnitAction(unit, map);
      if( null == capAction )
        return null;

      XYCoord mc = capAction.getMoveLocation();
      // If there's a threat here, don't assume we can naively capture
      if( 0 < ai.threatMap[mc.xCoord][mc.yCoord].size() )
        return null;

      // If there's path weirdness this early in the game, it's fine to freak out
      ai.updatePlan(this, unit, null, capAction);
      return null;
    }
  }

  public static class SiegeAttacks extends UnitActionFinder<WallyAI>
  {
    private static final long serialVersionUID = 1L;
    public SiegeAttacks(Army army, WallyAI ai)
    {
      super(army, ai);
    }

    @Override
    public GameAction getUnitAction(Unit unit, GameMap gameMap)
    {
      if( ai.plannedUnits.contains(unit) )
        return null;

      // Find the possible destination.
      XYCoord coord = new XYCoord(unit.x, unit.y);

      if( AIUtils.isFriendlyProduction(ai.predMap, myArmy, coord) || !unit.model.hasImmobileWeapon() )
        return null;
      UnitContext resident = ai.mapPlan[coord.xCoord][coord.yCoord].identity;
      // If we've already made plans here, skip evaluation
      if( null != resident )
        return null;

      GameAction bestAttack = null;
      int percentDamage = 0;
      GamePath movePath = GamePath.stayPut(coord);

      // Figure out what I can do here.
      ArrayList<GameActionSet> actionSets = unit.getPossibleActions(ai.predMap, movePath);
      double bestDamage = 0;
      for( GameActionSet actionSet : actionSets )
      {
        // See if we have the option to attack.
        if( actionSet.getSelected().getType() == UnitActionFactory.ATTACK )
        {
          for( GameAction action : actionSet.getGameActions() )
          {
            MapLocation loc = gameMap.getLocation(action.getTargetLocation());
            Unit target = loc.getResident();
            if( null == target ) continue; // Ignore terrain
            final BattleSummary results = CombatEngine.simulateBattleResults(unit, target, gameMap, movePath, CALC);
            double damage = valueUnit(target, loc, false) * Math.min(target.getHP(), results.defender.getPreciseHPDamage());
            if( damage > bestDamage )
            {
              bestDamage = damage;
              bestAttack = action;
              percentDamage = (int) (10 * results.defender.getPreciseHPDamage());
            }
          }
        }
      }
      if( null != bestAttack )
      {
        ai.log(String.format("%s is shooting %s",
            unit.toStringWithLocation(), gameMap.getLocation(bestAttack.getTargetLocation()).getResident()));
      }

      boolean isAttack = true;
      ai.updatePlan(this, unit, movePath, bestAttack, isAttack, percentDamage);

      return null;
    }
  }

  // Try to get confirmed kills with mobile strikes.
  public static class NHitKO implements AIModule
  {
    private static final long serialVersionUID = 1L;
    public Army myArmy;
    public final WallyAI ai;

    public NHitKO(Army army, WallyAI ai)
    {
      myArmy = army;
      this.ai = ai;
    }

    @Override
    public GameAction getNextAction(PriorityQueue<Unit> unitQueue, GameMap gameMap)
    {
      HashSet<XYCoord> industries = new HashSet<XYCoord>();
      for( XYCoord coord : myArmy.getOwnedProperties() )
        if( myArmy.cos[0].unitProductionByTerrain.containsKey(gameMap.getEnvironment(coord).terrainType)
            || TerrainType.HEADQUARTERS == gameMap.getEnvironment(coord).terrainType
            || TerrainType.LAB == gameMap.getEnvironment(coord).terrainType )
          industries.add(coord);

      // Initialize to targeting all spaces on or next to industries+HQ, since those are important spots
      HashSet<XYCoord> targets = new HashSet<XYCoord>();

      HashSet<XYCoord> industryBlockers = new HashSet<XYCoord>();
      for( XYCoord coord : industries )
        industryBlockers.addAll(Utils.findLocationsInRange(ai.predMap, coord, 0, 1));

      for( XYCoord coord : industryBlockers )
      {
        Unit resident = ai.predMap.getResident(coord);
        if( null != resident && myArmy.isEnemy(resident.CO) )
          targets.add(coord);
      }

      ArrayList<Unit> attackerOptions = new ArrayList<>(unitQueue);
      attackerOptions.removeAll(ai.plannedUnits);
      XYCoord targetLoc = null;
      for( XYCoord coord : new ArrayList<XYCoord>(targets) )
      {
        Unit targetID = ai.predMap.getResident(coord);
        if( null == targetID || !myArmy.isEnemy(targetID.CO) )
        {
          ai.log(String.format("    Warning! NHitKO is trying to kill invalid target: %s at %s", targetID, coord));
          continue;
        }
        UnitContext target = new UnitContext(targetID);
        int damageTotal = 0;
        for( int hit : ai.mapPlan[coord.xCoord][coord.yCoord].damageInstances.values() )
          damageTotal += hit;

        targetLoc = coord;
        Map<XYCoord, Unit> neededAttacks = AICombatUtils.findMultiHitKill(ai.predMap, target.unit, attackerOptions, industries);
        if( null == neededAttacks )
          continue;

        for( XYCoord xyc : neededAttacks.keySet() )
        {
          Unit unit = neededAttacks.get(xyc);
          if( unit.isTurnOver || !gameMap.isLocationEmpty(unit, xyc) )
            continue;
          UnitContext resident = ai.mapPlan[xyc.xCoord][xyc.yCoord].identity;
          if( null != resident && resident.unit.isTurnOver )
          {
            ai.log("    Warning: NHitKO ran into an un-evictable unit");
            continue;
          }

          final GamePath movePath = Utils.findShortestPath(unit, xyc, ai.predMap);
          final UnitContext attacker = new UnitContext(gameMap, unit);
          attacker.setPath(movePath);

          BattleSummary results = CombatEngine.simulateBattleResults(attacker, target, ai.predMap, CALC);
          final int percentDamage = (int) (10 * results.defender.getPreciseHPDamage());
          damageTotal += percentDamage;
          ai.log(String.format("    %s hits for %s, total: %s", unit.toStringWithLocation(), percentDamage, target));

          boolean isAttack = true;
          final BattleAction attack = new BattleAction(ai.predMap, unit, movePath, targetLoc.xCoord, targetLoc.yCoord);

          ai.updatePlan(this, unit, movePath, attack, isAttack, percentDamage);
          attackerOptions.remove(unit);
          if( damageTotal >= target.getHP() * UnitModel.MAXIMUM_HP )
            break;
        }
      }

      return null;
    }
  }

  public static class GenerateThreatMap implements AIModule
  {
    private static final long serialVersionUID = 1L;
    public final Army myArmy;
    public final WallyAI ai;

    public GenerateThreatMap(Army co, WallyAI ai)
    {
      myArmy = co;
      this.ai = ai;
    }

    @Override
    public void initTurn(GameMap gameMap) { ai.allThreats.clear(); }
    @Override
    public GameAction getNextAction(PriorityQueue<Unit> unitQueue, GameMap gameMap)
    {
      boolean theUnexpected = ai.checkIfUnexpected(gameMap);
      // If anything we didn't expect happened, scrap and re-plan everything
      if( theUnexpected )
        ai.allThreats.clear();

      // We're already init'd, and nothing unexpected has happened. No need to recalc.
      if( 0 < ai.allThreats.size() )
        return null;

      // Re-initialize our plans
      for( int x = 0; x < gameMap.mapWidth; ++x )
        for( int y = 0; y < gameMap.mapHeight; ++y )
        {
          // Blank out last turn's plan
          ai.threatMap[x][y].clear();
          ai.mapPlan[x][y].identity = null;
          ai.mapPlan[x][y].toAchieve = null;
          ai.mapPlan[x][y].damageInstances.clear();
          Unit resident = gameMap.getResident(x, y);
          if( null == resident )
            continue;
          // If we know we can't move this unit, put it in the plan as semi-final
          if( resident.isTurnOver || myArmy != resident.CO.army )
          {
            ai.mapPlan[x][y].identity = new UnitContext(gameMap, resident);
          }
        }
      ai.plannedUnits.clear();

      Map<Commander, ArrayList<Unit>> unitLists = AIUtils.getEnemyUnitsByCommander(myArmy, gameMap);
      for( Commander co : unitLists.keySet() )
      {
        if( myArmy.isEnemy(co) )
        {
          for( Unit threat : unitLists.get(co) )
          {
            // add each new threat to the existing threats
            ai.allThreats.add(threat);
            populateTileThreats(ai.threatMap, gameMap, ai.mapPlan[threat.x][threat.y].identity);
          }
        }
      }

      return null;
    }
  }
  public static void populateTileThreats(ArrayList<TileThreat>[][] threatMap, GameMap gameMap, UnitContext threat)
  {
    // Temporary cache to re-locate already-threatened tiles' Tile Threats
    Map<XYCoord, TileThreat> shootableTiles = new HashMap<>();
    // We assume the enemy knows how to manage positioning within his turn, and we don't want to recalc when we move units.
    boolean includeOccupiedTiles = true, walkThroughEnemies = true;
    final XYCoord origin = threat.coord;
    ArrayList<XYCoord> destinations = Utils.findFloodFillArea(origin,
        threat.unit, threat.calculateMoveType(), threat.calculateMovePower(),
        gameMap, includeOccupiedTiles, walkThroughEnemies);
    for( WeaponModel wep : threat.model.weapons )
    {
      if( !wep.loaded(threat) )
        continue; // Ignore it if it can't shoot

      threat.setWeapon(wep);
      HashSet<XYCoord> wepTiles = new HashSet<>();
      if( !wep.canFireAfterMoving )
      {
        wepTiles.addAll(Utils.findLocationsInRange(gameMap, origin, threat));
      }
      else
      {
        for( XYCoord dest : destinations )
        {
          UnitContext rangeContext = new UnitContext(threat);
          rangeContext.setPath(Utils.findShortestPath(threat.unit, dest, gameMap));
          wepTiles.addAll(Utils.findLocationsInRange(gameMap, dest, rangeContext));
        }
      }
      // We have our threatened tiles, now copy them to our cache
      for( XYCoord xyc : wepTiles )
      {
        final TileThreat tt;
        if( shootableTiles.containsKey(xyc) )
          tt = shootableTiles.get(xyc);
        else
        {
          tt = new TileThreat();
          tt.identity = threat;
          shootableTiles.put(xyc, tt);
        }
        tt.relevantWeapons.add(wep);
      }
      for( XYCoord xyc : shootableTiles.keySet() )
        threatMap[xyc.xCoord][xyc.yCoord].add(shootableTiles.get(xyc));
    }
  } // ~populateTileThreats

  // Try to get unit value by capture or attack
  public static class FreeRealEstate extends UnitActionFinder<WallyAI>
  {
    private static final long serialVersionUID = 1L;
    private final boolean canEvict, canStepOnProduction;
    public FreeRealEstate(Army co, WallyAI ai, boolean canEvict, boolean canStepOnProduction)
    {
      super(co, ai);
      this.canEvict = canEvict;
      this.canStepOnProduction = canStepOnProduction;
    }

    @Override
    public GameAction getUnitAction(Unit unit, GameMap gameMap)
    {
      if( ai.plannedUnits.contains(unit) )
        return null;

      final UnitContext evicter = ai.mapPlan[unit.x][unit.y].identity;
      boolean mustMove = null != evicter && unit != evicter.unit;
      planValueAction(this, unit.CO, ai, unit, gameMap, mustMove, !canStepOnProduction, canEvict);

      return null;
    }

    public static void planValueAction( AIModule whodunit, Commander co, WallyAI ai,
                                              Unit unit, GameMap gameMap,
                                              boolean mustMove, boolean avoidProduction,
                                              boolean canEvict )
    {
      XYCoord position = new XYCoord(unit.x, unit.y);

      PathCalcParams pcp = new PathCalcParams(unit, ai.predMap);
      pcp.includeOccupiedSpaces = true; // Since we know how to shift friendly units out of the way
      ArrayList<Utils.SearchNode> destinations = pcp.findAllPaths();
      if( mustMove )
        destinations.remove(new XYCoord(unit.x, unit.y));
      boolean attackEviction = mustMove && null != ai.mapPlan[unit.x][unit.y].toAchieve && ai.mapPlan[unit.x][unit.y].toAchieve.action.getType() == UnitActionFactory.ATTACK;
      if( attackEviction ) // Don't assume we can hop into our evicter's target's space, since that won't work
      {
        XYCoord target = ai.mapPlan[unit.x][unit.y].toAchieve.action.getTargetLocation();
        destinations.remove(target);
      }
      destinations.removeAll(AIUtils.findAlliedIndustries(ai.predMap, co.army, destinations, !avoidProduction));
      // sort by furthest away, good for capturing
      Utils.sortLocationsByDistance(position, destinations);
      Collections.reverse(destinations);

      UnitContext actor = new UnitContext(gameMap, unit);
      GamePath bestPath = null;
      GameAction bestAction = null;
      int bestFundsDelta = 0;
      boolean isAttack = false;
      int percentDamage = 0;
      for( Utils.SearchNode moveCoord : destinations )
      {
        // Figure out how to get here.
        GamePath movePath = moveCoord.getMyPath();
        actor.setPath(movePath);
        Unit resident = ai.predMap.getResident(moveCoord);
        ActionPlan  ap = ai.mapPlan[moveCoord.xCoord][moveCoord.yCoord].toAchieve;
        boolean spaceFree = null == resident;
        if( !spaceFree && (!canEvict || null == ap ) )
          continue; // Bail if we can't clear the space

        // Figure out what I can do here.
        ArrayList<GameActionSet> actionSets = unit.getPossibleActions(ai.predMap, movePath, pcp.includeOccupiedSpaces);
        for( GameActionSet actionSet : actionSets )
        {
          // See if we can bag enough damage to be worth sacrificing the unit
          if( actionSet.getSelected().getType() == UnitActionFactory.ATTACK )
          {
            for( GameAction ga : actionSet.getGameActions() )
            {
              XYCoord targetLoc = ga.getTargetLocation();
              UnitContext target = ai.mapPlan[targetLoc.xCoord][targetLoc.yCoord].identity;
              if( null == target )
                continue;

              // Difference in estimation conditions could end up with oscillation between two attacks on the same target
              // Just... be aware, future me, in case that's a problem
              actor.setWeapon(null);
              target.setWeapon(null);
              final AttackValue results = new AttackValue(ai, actor, target, ai.predMap);
              final int fundsDelta = results.fundsDelta;
              if( fundsDelta <= bestFundsDelta )
                continue;

              int opportunityCost = 0;
              if( !spaceFree )
              {
                opportunityCost = valueAction(ai, gameMap, ap);
              }
              if( fundsDelta - opportunityCost <= bestFundsDelta )
                continue;

              bestFundsDelta = fundsDelta - opportunityCost;
              bestPath = movePath;
              bestAction = ga;
              isAttack = true;
              percentDamage = results.hpdamage*10;
            }
          }

          if( actionSet.getSelected().getType() == UnitActionFactory.CAPTURE )
          {
            GameAction ga = actionSet.getSelected();
            final int fundsDelta = ai.valueCapture((CaptureAction) ga, gameMap);
            if( fundsDelta <= bestFundsDelta )
              continue;

            final boolean safe = ai.canWallHere(gameMap, ai.threatMap, unit, moveCoord, null);
            int loss = 0;
            if( !safe )
              loss = unit.CO.getCost(unit.model);
            int opportunityCost = 0;
            if( !spaceFree )
            {
              opportunityCost = valueAction(ai, gameMap, ap);
            }
            if( fundsDelta - opportunityCost - loss <= bestFundsDelta )
              continue;

            bestFundsDelta = fundsDelta - opportunityCost;
            bestAction = ga;
            isAttack = false;
          }
        } // ~for action types
      } // ~for destinations

      ai.updatePlan(whodunit, unit, bestPath, bestAction, isAttack, percentDamage);
    } // ~planValueAction
  } // ~FreeRealEstate

  private static final int EVICTION_DEPTH = 7;
  private HashSet<Unit> evictionStack = new HashSet<>(EVICTION_DEPTH);
  public static class Eviction extends UnitActionFinder<WallyAI>
  {
    private static final long serialVersionUID = 1L;
    public Eviction(Army co, WallyAI ai)
    {
      super(co, ai);
    }

    @Override
    public GameAction getUnitAction(Unit unit, GameMap gameMap)
    {
      if( ai.plannedUnits.contains(unit) )
        return null;
      final UnitContext evicter = ai.mapPlan[unit.x][unit.y].identity;
      if( null == evicter )
        return null;

      ai.log(String.format("Evaluating eviction for %s.", unit.toStringWithLocation()));
      boolean mustMove = true;
      boolean avoidProduction = false;
      boolean ignoreSafety = false;
      ai.planTravelActions(this, gameMap, unit, ignoreSafety, mustMove, avoidProduction, true, EVICTION_DEPTH);

      return null;
    }
  }
  // If no attack/capture actions are available now, just move around
  public static class Travel extends UnitActionFinder<WallyAI>
  {
    private static final long serialVersionUID = 1L;
    public Travel(Army co, WallyAI ai)
    {
      super(co, ai);
    }

    @Override
    public GameAction getUnitAction(Unit unit, GameMap gameMap)
    {
      if( ai.plannedUnits.contains(unit) )
        return null;

      ai.log(String.format("Evaluating travel for %s.", unit.toStringWithLocation()));
      final UnitContext evicter = ai.mapPlan[unit.x][unit.y].identity;
      boolean mustMove = null != evicter && unit != evicter.unit;
      boolean avoidProduction = false;
      boolean ignoreSafety = false;
      boolean shouldWander = false;
      ai.planTravelActions(this, gameMap, unit, ignoreSafety, mustMove, avoidProduction, shouldWander, EVICTION_DEPTH);

      return null;
    }
    @Override
    public String toString()
    {
      return String.format("WallyTravel");
    }
  }

  public static class BuildStuff implements AIModule
  {
    private static final long serialVersionUID = 1L;
    public final Army myArmy;
    public final WallyAI ai;

    public BuildStuff(Army co, WallyAI ai)
    {
      myArmy = co;
      this.ai = ai;
    }

    @Override
    public GameAction getNextAction(PriorityQueue<Unit> unitQueue, GameMap gameMap)
    {
      Map<XYCoord, UnitModel> builds = ai.queueUnitProduction(gameMap);

      for( XYCoord coord : new ArrayList<XYCoord>(builds.keySet()) )
      {
        ai.log(String.format("Attempting to build %s at %s", builds.get(coord), coord));
        MapLocation loc = gameMap.getLocation(coord);
        Commander buyer = loc.getOwner();
        ArrayList<UnitModel> list = buyer.getShoppingList(loc);
        UnitModel toBuy = builds.get(coord);
        if( buyer.getBuyCost(toBuy, coord) <= myArmy.money && list.contains(toBuy) )
        {
          builds.remove(coord);
          GameAction buyAction = new GameAction.UnitProductionAction(buyer, toBuy, coord);
          ai.updatePlan(this, new UnitContext(buyer, toBuy), null, buyAction);
        }
        else
        {
          ai.log(String.format("  Trying to build %s, but it's unavailable at %s", toBuy, coord));
          continue;
        }
      }

      return null;
    }
  }

  /** Produces a list of destinations for the unit, ordered by their relative precedence */
  private TravelPurpose fillTravelDestinations(
                                  GameMap gameMap,
                                  ArrayList<XYCoord> goals,
                                  Unit unit,
                                  boolean avoidProduction )
  {
    UnitContext uc = new UnitContext(gameMap, unit);
    uc.calculateActionTypes();
    TravelPurpose travelPurpose = TravelPurpose.WANDER;

    ArrayList<XYCoord> stations = AIUtils.findRepairDepots(unit);
    Utils.sortLocationsByTravelTime(unit, stations, predMap);

    boolean shouldResupply = false;
    GamePath toClosestStation = null;
    boolean canResupply = stations.size() > 0;
    if( canResupply )
    {
      toClosestStation = new PathCalcParams(unit, predMap).setTheoretical().findShortestPath(stations.get(0));
      canResupply &= null != toClosestStation;
    }
    if( canResupply )
    {
      shouldResupply = unit.getHealth() <= UNIT_HEAL_THRESHOLD;
      shouldResupply |= unit.fuel <= UNIT_REFUEL_THRESHOLD
          * toClosestStation.getFuelCost(unit, predMap);
      shouldResupply |= unit.ammo >= 0 && unit.ammo <= unit.model.maxAmmo * UNIT_REARM_THRESHOLD;
    }

    if( shouldResupply )
    {
      log(String.format("  %s needs supplies.", unit.toStringWithLocation()));
      goals.addAll(stations);
      if( avoidProduction )
        goals.removeAll(AIUtils.findAlliedIndustries(predMap, myArmy, goals, !avoidProduction));
      if( !goals.isEmpty() )
        travelPurpose = TravelPurpose.SUPPLIES;
    }
    if( goals.isEmpty() && uc.actionTypes.contains(UnitActionFactory.CAPTURE) )
    {
      for( XYCoord xyc : futureCapTargets )
      {
        // predMap shouldn't meaningfully diverge from reality here, I think
        boolean validCapDest = !AIUtils.isCapturing(gameMap, myArmy.cos[0], xyc);
        // If the turf is taken by someone else, it's a KILL objective
        validCapDest &= null == predMap.getResident(xyc) || myArmy == predMap.getResident(xyc).CO.army;
        if( validCapDest )
          goals.add(xyc);
      }
      if( !goals.isEmpty() )
        travelPurpose = TravelPurpose.CONQUER;
    }
    if( goals.isEmpty() && uc.actionTypes.contains(UnitActionFactory.ATTACK) )
    {
      goals.addAll(findCombatUnitDestinations(gameMap, allThreats, unit));
      if( !goals.isEmpty() )
        travelPurpose = TravelPurpose.KILL;
    }

    if( goals.isEmpty() ) // If there's really nothing to do, go to MY HQ
      goals.addAll(myArmy.HQLocations);

    Utils.sortLocationsByDistance(new XYCoord(unit.x, unit.y), goals);
    return travelPurpose;
  }

  private ArrayList<XYCoord> findCombatUnitDestinations(GameMap gameMap, ArrayList<Unit> allThreats, Unit unit)
  {
    return findCombatUnitDestinations(gameMap, allThreats, new XYCoord(unit), new ModelForCO(unit));
  }
  private ArrayList<XYCoord> findCombatUnitDestinations(GameMap gameMap, ArrayList<Unit> allThreats, XYCoord start, ModelForCO um)
  {
    ArrayList<XYCoord> goals = new ArrayList<>();
    Map<UnitModel, Double> valueMap = new HashMap<UnitModel, Double>();
    Map<UnitModel, ArrayList<XYCoord>> targetMap = new HashMap<UnitModel, ArrayList<XYCoord>>();
    UnitContext uc = new UnitContext(um.co, um.um);

    // Categorize all enemies by type, and all types by how well we match up vs them
    for( Unit target : allThreats )
    {
      UnitModel model = target.model;
      XYCoord targetCoord = new XYCoord(target.x, target.y);
      double effectiveness = findEffectiveness(um.um, target.model);
      if (0 < Utils.findTheoreticalPath(start, uc.calculateMoveType(), targetCoord, predMap).getPathLength() &&
          AGGRO_EFFECT_THRESHOLD < effectiveness)
      {
        valueMap.put(model, effectiveness*target.getCost());
        if (!targetMap.containsKey(model)) targetMap.put(model, new ArrayList<XYCoord>());
        targetMap.get(model).add(targetCoord);
      }
    }

    // Sort all individual target lists by distance
    for (ArrayList<XYCoord> targetList : targetMap.values())
      Utils.sortLocationsByDistance(start, targetList);

    // Sort all target types by how much we want to shoot them with this unit
    Queue<Entry<UnitModel, Double>> targetTypesInOrder = 
        new PriorityQueue<Entry<UnitModel, Double>>(myArmy.cos[0].unitModels.size(), new UnitModelFundsComparator());
    targetTypesInOrder.addAll(valueMap.entrySet());

    while (!targetTypesInOrder.isEmpty())
    {
      UnitModel model = targetTypesInOrder.poll().getKey(); // peel off the juiciest
      goals.addAll(targetMap.get(model)); // produce a list ordered by juiciness first, then distance TODO: consider a holistic "juiciness" metric that takes into account both matchup and distance?
    }

    if( goals.isEmpty() ) // Send 'em at production facilities if there's nothing to shoot
    {
      for( XYCoord coord : futureCapTargets )
      {
        MapLocation loc = gameMap.getLocation(coord);
        if( um.co.unitProductionByTerrain.containsKey(loc.getEnvironment().terrainType)
            && myArmy.isEnemy(loc.getOwner())
            && 0 < Utils.findTheoreticalPath(start, uc.calculateMoveType(), coord, predMap).getPathLength() )
        {
          goals.add(coord);
        }
      }
    }

    return goals;
  }

  /**
   * Find a good long-term objective for the given unit, and pursue it (with consideration for life-preservation optional)
   */
  private boolean planTravelActions(
                        AIModule whodunit, GameMap gameMap,
                        Unit unit,
                        boolean ignoreSafety, boolean mustMove,
                        boolean avoidProduction, boolean shouldWander,
                        int recurseDepth)
  {
    if( evictionStack.contains(unit) )
      return false;

    boolean success = false;
    boolean ignoreResident = true;
    // Find the possible destinations.
    PathCalcParams pcp = new PathCalcParams(unit, predMap);
    pcp.includeOccupiedSpaces = ignoreResident;
    ArrayList<Utils.SearchNode> destinations = pcp.findAllPaths();
    destinations.removeAll(AIUtils.findAlliedIndustries(gameMap, myArmy, destinations, !avoidProduction));

    // TODO: Jump in a transport, if available, or join?

    XYCoord goal = null;
    GamePath path = null;
    ArrayList<XYCoord> validTargets = new ArrayList<XYCoord>();
    TravelPurpose travelPurpose = fillTravelDestinations(predMap, validTargets, unit, avoidProduction);

    if( !mustMove && !shouldWander && travelPurpose == TravelPurpose.WANDER )
      return success; // Don't clutter the queue with pointless movements

    if( mustMove ) // If we *must* travel, make sure we do actually move.
    {
      destinations.remove(new XYCoord(unit.x, unit.y));
      validTargets.remove(new XYCoord(unit.x, unit.y));
    }
    boolean attackEviction = mustMove && null != mapPlan[unit.x][unit.y].toAchieve && mapPlan[unit.x][unit.y].toAchieve.action.getType() == UnitActionFactory.ATTACK;
    if( attackEviction ) // Don't assume we can hop into our evicter's target's space, since that won't work
    {
      XYCoord target = mapPlan[unit.x][unit.y].toAchieve.action.getTargetLocation();
      destinations.remove(target);
      validTargets.remove(target);
    }

    for( XYCoord target : validTargets )
    {
      path = new PathCalcParams(unit, predMap).setTheoretical().findShortestPath(target);
      if( path != null ) // We can reach it.
      {
        goal = target;
        break;
      }
    }

    if( null == goal ) return success;

    // Choose the point on the path just out of our range as our 'goal', and try to move there.
    // This will allow us to navigate around large obstacles that require us to move away
    // from our intended long-term goal.
    path.snip(unit.getMovePower(predMap) + 1); // Trim the path approximately down to size.
    XYCoord pathPoint = path.getEndCoord(); // Set the last location as our goal.

    // Sort my currently-reachable move locations by distance from the goal,
    // and build a GameAction to move to the closest one.
    Utils.sortLocationsByDistance(pathPoint, destinations);

    boolean isSiege = unit.model.hasImmobileWeapon();
    if( isSiege )
      ignoreSafety = true; // Siege unit safety is handled via walls
    log(String.format("  %s is traveling toward %s at %s via %s  mustMove?: %s  ignoreSafety?: %s",
                          unit.toStringWithLocation(),
                          gameMap.getLocation(goal).getEnvironment().terrainType, goal,
                          pathPoint, mustMove, ignoreSafety));

    // Try to take the most aggressive position where you can cover your blind spots
    GamePath bestPath = null;
    GameAction bestAction = null;
    HashMap<XYCoord, Unit> bestWalls = new HashMap<>();
    int bestFundsDelta = 0;
    boolean isAttack = false;
    int percentDamage = 0;
    for( Utils.SearchNode xyc : destinations )
    {
//      log(String.format("    is it safe to go to %s?", xyc));
      if( !ignoreSafety && !canWallHere(predMap, threatMap, unit, xyc, null) )
        continue;

      Unit plannedResident = predMap.getResident(xyc);
      ActionPlan  ap       = mapPlan[xyc.xCoord][xyc.yCoord].toAchieve;
      // Figure out how to get here.
      boolean spaceFree = null == plannedResident;
      if( !spaceFree &&
          ( null == ap || ap.purpose.priority > travelPurpose.priority) )
        continue; // Bail if:
      // There's no other action to evaluate against ours
      // The other action clears a tile
      // The other action is travel for an equal or greater purpose than ours

      // If whatever's in our landing pad has no plans yet, poke and see if some can be made
      Unit currentResident = gameMap.getResident(xyc);
      if( null != currentResident )
      {
        if( plannedUnits.contains(currentResident) || evictionStack.contains(currentResident) )
          continue;
        boolean residentIsEvictable = !currentResident.isTurnOver && currentResident.CO.army == myArmy;

        boolean evicted = false;
        if( residentIsEvictable && recurseDepth > 0 )
        {
          // Prevent reflexive eviction
          evictionStack.add(unit);
          evicted = planTravelActions(whodunit, gameMap, currentResident,
                                               ignoreSafety, true, // Always move
                                               avoidProduction, true, // Always enable wandering
                                               recurseDepth-1);
          evictionStack.remove(unit);
          if( !evicted )
            continue;
        }
        // If nobody's there, no need to evict.
        // If the resident is evictable, try to evict and bail if we can't.
        // If the resident isn't evictable, we think it will be dead soon, so just keep going.
      } // ~if resident
//      log(String.format("    Yes"));

      int siegeWallingValueOffset = 0;
      HashMap<XYCoord, Unit> wallSlots = new HashMap<>();
      // Handle walling around siege units
      if( isSiege )
      {
        UnitContext uc = new UnitContext(unit);
        uc.coord = xyc;
        int minRange = 0; // Largest min range should be a valid heuristic until I decide to be terrible again
        for( WeaponModel wm : unit.model.weapons )
        {
          uc.setWeapon(wm);
          minRange = Math.max(minRange, uc.rangeMin);
        }

        ArrayList<XYCoord> blindSpots = Utils.findLocationsInRange(gameMap, xyc, 1, minRange-1);
        ArrayList<XYCoord> wallsNeeded = new ArrayList<>();
        for( XYCoord bs : blindSpots )
          if( 0 < threatMap[bs.xCoord][bs.yCoord].size() )
            wallsNeeded.add(bs);

        boolean wallInvalid = false;
        for( XYCoord wallCoord : wallsNeeded )
        {
          Unit planRes = predMap.getResident(wallCoord);
          if( null != planRes )
          {
            if( !planRes.CO.isEnemy(myArmy) )
              continue; // If there's a friend here already, we're happy
            wallInvalid = true;
            break; // If there's an enemy here, don't even try
          }
          wallSlots.put(wallCoord, null);
        }
        if( wallInvalid )
          continue;

        ArrayList<Unit> potentialWalls = new ArrayList<>();
        for( Unit wall : myArmy.getUnits() )
        {
          if( wall.isTurnOver || !gameMap.isLocationValid(wall.x, wall.y) )
            continue; // No actions for units that are stale or out of bounds.
          if( unit.model.hasImmobileWeapon() )
            continue;
          if( plannedUnits.contains(wall) )
            continue;
          potentialWalls.add(unit);
        }

        boolean foundWalls = findWallPlan(predMap, potentialWalls, wallSlots);
        if( !foundWalls )
          siegeWallingValueOffset -= unit.CO.getCost(unit.model);
      }

      GamePath movePath = xyc.getMyPath();
      if( movePath.getPathLength() < xyc.getDistance(unit) )
      {
        log(String.format("      Wally Travel generated bad path: %s", movePath));
        continue;
      }
      if( attackEviction ) // Don't assume we can move through our evicter's target's space, since that also won't work
      {
        XYCoord target = mapPlan[unit.x][unit.y].toAchieve.action.getTargetLocation();
        boolean collisionDetected = false;
        for( PathNode wp : movePath.getWaypoints() )
        {
          if( target.equals(wp.GetCoordinates()) )
          {
            collisionDetected = true;
            break;
          }
        }
        if( collisionDetected )
          continue;
      }

      ArrayList<GameActionSet> actionSets = unit.getPossibleActions(predMap, movePath, ignoreResident);
      if( actionSets.size() > 0 )
      {
        // Since we're moving anyway, might as well try shooting the scenery
        for( GameActionSet actionSet : actionSets )
        {
          if( actionSet.getSelected().getType() == UnitActionFactory.ATTACK )
          {
            for( GameAction attack : actionSet.getGameActions() )
            {
              double damageValue = AICombatUtils.scoreAttackAction(unit, attack, predMap,
                  (results) -> {
                    int loss   = Math.min(unit                 .getHealth(), (int)results.attacker.getPreciseHealthDamage());
                    int damage = Math.min(results.defender.unit.getHealth(), (int)results.defender.getPreciseHealthDamage());

                    if( damage > loss ) // only shoot that which you hurt more than it hurts you
                      return damage * results.defender.unit.getCost() / 10;

                    return 0;
                  }, (terrain, params) -> 1); // Attack terrain, but don't prioritize it over units

              if( damageValue > bestFundsDelta )
              {
                log(String.format("      Best en passant attack deals %s", damageValue));
                bestFundsDelta = (int) damageValue;
                bestPath = movePath;
                bestAction = attack;
                isAttack = true;
                Unit target = gameMap.getResident(attack.getTargetLocation());
                int defLevel = gameMap.getEnvironment(attack.getTargetLocation()).terrainType.getDefLevel();
                int range = attack.getMoveLocation().getDistance(target);
                boolean attackerMoved = 0 < attack.getMoveLocation().getDistance(unit);
                double hpDamage = CombatEngine.calculateOneStrikeDamage(unit, range, target, gameMap, defLevel, attackerMoved);
                percentDamage = (int) (hpDamage * 10);
              }
            }
          }
        } // ~for action types

        int walkScore = movePath.getPathLength() + siegeWallingValueOffset;
        if( bestFundsDelta < walkScore && movePath.getPathLength() > 1 ) // Just wait if we can't do anything cool
        {
          bestFundsDelta = walkScore;
          bestAction = new WaitLifecycle.WaitAction(unit, movePath);
          bestWalls = wallSlots;
          bestPath = movePath;
          isAttack = false;
        }

      } // ~if any action type
    } // ~for destinations


    // Fill the walls with wait actions
    for( XYCoord wallCoord : bestWalls.keySet() )
    {
      Unit wall = bestWalls.get(wallCoord);
      GamePath movePath = Utils.findShortestPath(wall, wallCoord, predMap);
      WaitLifecycle.WaitAction wait = new WaitLifecycle.WaitAction(wall, movePath);
      updatePlan("WallPlanner", wall, movePath, wait);
    }
    updatePlan(whodunit, unit, bestPath, bestAction, isAttack, percentDamage);
    if( null != bestAction )
    {
      XYCoord xyc = bestAction.getMoveLocation();
      ActionPlan travelAP = mapPlan[xyc.xCoord][xyc.yCoord].toAchieve;
      travelAP.purpose = travelPurpose;
      success = true;
    }

    return success;
  }

  public boolean findWallPlan(GameMap gameMap,
                              Collection<Unit> wallCandidates,
                              Map<XYCoord, Unit> wallSlots)
  {
    // If we've found a value for all slots, we're done
    if( !wallSlots.containsValue(null) )
    {
      return true;
    }

    // Iterate through the wall spaces, and try filling all spaces recursively from each one
    for( XYCoord xyc : wallSlots.keySet() )
    {
      if( null != wallSlots.get(xyc) )
        continue;

      ArrayDeque<Unit> wallUnits = new ArrayDeque<>(wallCandidates);
      while (!wallUnits.isEmpty())
      {
        Unit unit = wallUnits.poll();
        if( wallSlots.containsValue(unit) )
          continue; // Consider each unit only once

        // Figure out how to get here.
        GamePath movePath = Utils.findShortestPath(unit, xyc, gameMap);

        if( movePath.getPathLength() > 0 )
        {
          wallSlots.put(xyc, unit);

          boolean done = findWallPlan(gameMap, wallCandidates, wallSlots);

          // If we've found a value for all slots, we're done
          if( done )
            return true;
          // Otherwise, remove the wall from the slot to make room for the next calculation
          wallSlots.put(xyc, null);
          wallCandidates.add(unit);
        }
      }
    }

    return false;
  }

  private boolean isSafe(GameMap gameMap, ArrayList<TileThreat>[][] threatMap, Unit unit, XYCoord xyc, Unit target)
  {
    return isSafe(gameMap, threatMap, unit.model, xyc, target);
  }
  private boolean isSafe(GameMap gameMap, ArrayList<TileThreat>[][] threatMap, UnitModel type, XYCoord xyc, Unit target)
  {
    int threshhold = type.hasDirectFireWeapon() ? DIRECT_THREAT_THRESHOLD : INDIRECT_THREAT_THRESHOLD;
    double threat = 0;
    ArrayList<TileThreat> tileThreats = threatMap[xyc.xCoord][xyc.yCoord];
    for( TileThreat tt : tileThreats )
    {
      final UnitContext threatContext = tt.identity;
      if( target == threatContext.unit )
        continue; // We aren't scared of that which we're about to shoot
      for( WeaponModel wep : tt.relevantWeapons )
      {
        // Does this make sense to do a proper combat calc on?
        // If so, will need to make a UnitContext overload for CombatEngine
        int damagePercent = 0;
        final HashMap<ActionPlan, Integer> damageInstances = mapPlan[threatContext.coord.xCoord][threatContext.coord.yCoord].damageInstances;
        for( int hit : damageInstances.values() )
          damagePercent += hit;
        final double hpFactor = (threatContext.getHP()*10 - damagePercent) / 100.0;

        threat = Math.max(threat, wep.getDamage(type) * hpFactor);
      }
    }
    return (threshhold > threat);
  }

  /**
   * @return whether it's safe or a good place to wall
   * For use after unit building is complete
   */
  private boolean canWallHere(GameMap gameMap, ArrayList<TileThreat>[][] threatMap, Unit unit, XYCoord xyc, Unit target)
  {
    MapLocation destination = gameMap.getLocation(xyc);
    // if we're safe, we're safe
    if( isSafe(gameMap, threatMap, unit, xyc, target) )
      return true;

    // TODO: Determine whether the ally actually needs a wall there. Mechs walling for Tanks vs inf is... silly.
    // if we'd be a nice wall for a worthy ally, we can pretend we're safe there also
    ArrayList<XYCoord> adjacentCoords = Utils.findLocationsInRange(predMap, xyc, 1);
    for( XYCoord coord : adjacentCoords )
    {
      MapLocation loc = gameMap.getLocation(coord);
      if( loc != null )
      {
        Unit resident = loc.getResident();
        if( resident != null && !myArmy.isEnemy(resident.CO)
            && valueUnit(resident, loc, true) > valueUnit(unit, destination, true) )
        {
          return true;
        }
      }
    }
    return false;
  }

  private static int valueUnit(Unit unit, MapLocation locale, boolean includeCurrentHealth)
  {
    int value = unit.getCost();

    if( unit.CO.isEnemy(locale.getOwner()) &&
            unit.hasActionType(UnitActionFactory.CAPTURE)
            && locale.isCaptureable() )
      value += valueTerrain(unit.CO, locale.getEnvironment().terrainType); // Strongly value units that threaten capture

    if( includeCurrentHealth )
      value *= unit.getHealth();
    value -= locale.getEnvironment().terrainType.getDefLevel(); // Value things on lower terrain more, so we wall for equal units if we can get on better terrain

    return value;
  }

  private static int valueTerrain(Commander co, TerrainType terrain)
  {
    int value = 0;
    if( terrain.isProfitable() )
      value += co.gameRules.incomePerCity * TERRAIN_FUNDS_WEIGHT;
    if( co.unitProductionByTerrain.containsKey(terrain) )
      value += TERRAIN_INDUSTRY_WEIGHT;
    if( TerrainType.HEADQUARTERS == terrain
        || TerrainType.LAB == terrain )
      value += TERRAIN_HQ_WEIGHT;
    return value;
  }

  /**
   * Returns the center mass of a given unit type, weighted by HP
   * NOTE: Will violate fog knowledge
   */
  private static XYCoord findAverageDeployLocation(GameMap gameMap, Army co, UnitModel model)
  {
    // init with the center of the map
    int totalX = gameMap.mapWidth / 2;
    int totalY = gameMap.mapHeight / 2;
    int totalPoints = 1;
    for( Unit unit : co.getUnits() )
    {
      if( unit.model == model )
      {
        totalX += unit.x * unit.getHealth();
        totalY += unit.y * unit.getHealth();
        totalPoints += unit.getHealth();
      }
    }

    return new XYCoord(totalX / totalPoints, totalY / totalPoints);
  }


  /**
   * Returns the ideal place to build a unit type or null if it's impossible
   * Kinda-sorta copied from AIUtils
   */
  public XYCoord getLocationToBuild(GameMap gameMap, CommanderProductionInfo CPI, UnitModel model)
  {
    Set<TerrainType> desiredTerrains = CPI.modelToTerrainMap.get(model);
    if( null == desiredTerrains || desiredTerrains.size() < 1 )
      return null;

    ArrayList<XYCoord> candidates = new ArrayList<XYCoord>();
    for( MapLocation loc : CPI.availableProperties )
    {
      if( desiredTerrains.contains(loc.getEnvironment().terrainType) )
      {
        if( model.hasImmobileWeapon() && !isSafe(gameMap, threatMap, model, loc.getCoordinates(), null) )
          continue;
        // If we can get to a target...
        if( 0 < findCombatUnitDestinations(predMap, allThreats, loc.getCoordinates(), new ModelForCO(loc.getOwner(), model))
            .size() )
          candidates.add(loc.getCoordinates());
      }
    }
    if( candidates.isEmpty() )
      return null;

    // Sort locations by how close they are to "center mass" of that unit type, then reverse since we want to distribute our forces
    Utils.sortLocationsByDistance(findAverageDeployLocation(myArmy.myView, myArmy, model), candidates);
    Collections.reverse(candidates);
    return candidates.get(0);
  }

  private Map<XYCoord, UnitModel> queueUnitProduction(GameMap gameMap)
  {
    Map<XYCoord, UnitModel> builds = new HashMap<XYCoord, UnitModel>();
    // Figure out what unit types we can purchase with our available properties.
    boolean includeFriendlyOccupied = true;
    CommanderProductionInfo CPI = new CommanderProductionInfo(myArmy, gameMap, includeFriendlyOccupied);

    HashSet<MapLocation> blockedByActions = new HashSet<>();
    for( MapLocation prop : CPI.availableProperties )
    {
      XYCoord coord = prop.getCoordinates();
      UnitContext resident = mapPlan[coord.xCoord][coord.yCoord].identity;
      if( null != resident )
      {
        log(String.format("  Can't evict unit %s to build", resident));
        blockedByActions.add(prop);
      }
    }
    CPI.availableProperties.removeAll(blockedByActions);
    if( CPI.availableProperties.isEmpty() )
    {
      log("No properties available to build.");
      return builds;
    }

    log("Evaluating Production needs");
    int budget = myArmy.money;
    final UnitModel infModel = myArmy.cos[0].getUnitModel(UnitModel.TROOP);
    // TODO: Fix this
    final int infCost = infModel.costBase;

    // Get a count of enemy forces.
    Map<Commander, ArrayList<Unit>> unitLists = AIUtils.getEnemyUnitsByCommander(myArmy, predMap);
    Map<UnitModel, Double> enemyUnitCounts = new HashMap<UnitModel, Double>();
    for( Commander co : unitLists.keySet() )
    {
      if( myArmy.isEnemy(co) )
      {
        for( Unit u : unitLists.get(co) )
        {
          // Count how many of each model of enemy units are in play.
          if( enemyUnitCounts.containsKey(u.model) )
          {
            enemyUnitCounts.put(u.model, enemyUnitCounts.get(u.model) + (u.getHealth() / 10));
          }
          else
          {
            enemyUnitCounts.put(u.model, u.getHealth() / 10.0);
          }
        }
      }
    }

    // Figure out how well we think we have the existing threats covered
    Map<UnitModel, Double> myUnitCounts = new HashMap<UnitModel, Double>();
    for( Unit u : myArmy.getUnits() )
    {
      // Count how many of each model of enemy units are in play.
      if( myUnitCounts.containsKey(u.model) )
      {
        myUnitCounts.put(u.model, myUnitCounts.get(u.model) + (u.getHealth() / 10));
      }
      else
      {
        myUnitCounts.put(u.model, u.getHealth() / 10.0);
      }
    }

    for( UnitModel threat : enemyUnitCounts.keySet() )
    {
      for( UnitModel counter : myUnitCounts.keySet() ) // Subtract how well we think we counter each enemy from their HP counts
      {
        double counterPower = findEffectiveness(counter, threat);
        enemyUnitCounts.put(threat, enemyUnitCounts.get(threat) - counterPower * myUnitCounts.get(counter));
      }
    }

    // change unit quantity->funds
    for( Entry<UnitModel, Double> ent : enemyUnitCounts.entrySet() )
    {
      // We don't currently have any huge cost-shift COs, so this isn't a big deal at present.
      ent.setValue(ent.getValue() * ent.getKey().costBase);
    }

    Queue<Entry<UnitModel, Double>> enemyModels = 
        new PriorityQueue<Entry<UnitModel, Double>>(myArmy.cos[0].unitModels.size(), new UnitModelFundsComparator());
    enemyModels.addAll(enemyUnitCounts.entrySet());

    // Try to purchase units that will counter the most-represented enemies.
    while (!enemyModels.isEmpty() && !CPI.availableUnitModels.isEmpty())
    {
      // Find the first (most funds-invested) enemy UnitModel, and remove it. Even if we can't find an adequate counter,
      // there is not reason to consider it again on the next iteration.
      UnitModel enemyToCounter = enemyModels.poll().getKey();
      double enemyNumber = enemyUnitCounts.get(enemyToCounter);
      log(String.format("Need a counter for %sx%s", enemyToCounter, enemyNumber / enemyToCounter.costBase / UnitModel.MAXIMUM_HEALTH));
      log(String.format("Remaining budget: %s", budget));

      // Get our possible options for countermeasures.
      ArrayList<UnitModel> availableUnitModels = new ArrayList<>();
      for( ModelForCO coModel : CPI.availableUnitModels )
        availableUnitModels.add(coModel.um);
      while (!availableUnitModels.isEmpty())
      {
        // Sort my available models by their power against this enemy type.
        Collections.sort(availableUnitModels, new UnitPowerComparator(enemyToCounter, this));

        // Grab the best counter.
        UnitModel idealCounter = availableUnitModels.get(0);
        availableUnitModels.remove(idealCounter); // Make sure we don't try to build two rounds of the same thing in one turn.
        // I only want combat units, since I don't understand transports
        if( !idealCounter.weapons.isEmpty() )
        {
          log(String.format("  buy %s?", idealCounter));
          XYCoord coord = getLocationToBuild(gameMap, CPI, idealCounter);
          if (null == coord)
            continue;
          MapLocation loc = gameMap.getLocation(coord);
          Commander buyer = loc.getOwner();
          final int idealCost = buyer.getBuyCost(idealCounter, coord);
          int totalCost = idealCost;

          // Calculate a cost buffer to ensure we have enough money left so that no factories sit idle.
          int costBuffer = (CPI.getNumFacilitiesFor(infModel)) * infCost;
          if(buyer.getShoppingList(gameMap.getLocation(coord)).contains(infModel))
            costBuffer -= infCost;

          if( 0 > costBuffer )
            costBuffer = 0; // No granting ourselves extra moolah.
          if(totalCost <= (budget - costBuffer))
          {
            // Go place orders.
            log(String.format("    I can build %s for a cost of %s (%s remaining, witholding %s)",
                                    idealCounter, totalCost, budget, costBuffer));
            builds.put(coord, idealCounter);
            budget -= idealCost;
            CPI.removeBuildLocation(gameMap.getLocation(coord));
            // We found a counter for this enemy UnitModel; break and go to the next type.
            // This break means we will build at most one type of unit per turn to counter each enemy type.
            break;
          }
          else
          {
            log(String.format("    %s cost %s, I have %s (witholding %s).", idealCounter, idealCost, budget, costBuffer));
          }
        }
      } // ~while( !availableUnitModels.isEmpty() )
    } // ~while( !enemyModels.isEmpty() && !CPI.availableUnitModels.isEmpty())

    // Build infantry from any remaining facilities.
    log("Building infantry to fill out my production");
    XYCoord infCoord = getLocationToBuild(gameMap, CPI, infModel);
    while (infCoord != null)
    {
      MapLocation infLoc = gameMap.getLocation(infCoord);
      Commander infBuyer = infLoc.getOwner();
      int cost = infBuyer.getBuyCost(infModel, infCoord);
      if (cost > budget)
        break;
      builds.put(infCoord, infModel);
      budget -= cost;
      CPI.removeBuildLocation(gameMap.getLocation(infCoord));
      log(String.format("  At %s (%s remaining)", infCoord, budget));
      infCoord = getLocationToBuild(gameMap, CPI, infModel);
    }

    return builds;
  }

  /**
   * Sort units by funds amount in descending order.
   */
  private static class UnitModelFundsComparator implements Comparator<Entry<UnitModel, Double>>
  {
    @Override
    public int compare(Entry<UnitModel, Double> entry1, Entry<UnitModel, Double> entry2)
    {
      double diff = entry2.getValue() - entry1.getValue();
      return (int) (diff * 10); // Multiply by 10 since we return an int, but don't want to lose the decimal-level discrimination.
    }
  }

  /**
   * Arrange UnitModels according to their effective damage/range against a configured UnitModel.
   */
  private static class UnitPowerComparator implements Comparator<UnitModel>
  {
    UnitModel targetModel;
    private WallyAI wally;

    public UnitPowerComparator(UnitModel targetType, WallyAI pWally)
    {
      targetModel = targetType;
      wally = pWally;
    }

    @Override
    public int compare(UnitModel model1, UnitModel model2)
    {
      double eff1 = wally.findEffectiveness(model1, targetModel);
      double eff2 = wally.findEffectiveness(model2, targetModel);

      return (eff1 < eff2) ? 1 : ((eff1 > eff2) ? -1 : 0);
    }
  }

  /** Returns effective power in terms of whole kills per unit, based on respective threat areas and how much damage I deal */
  public double findEffectiveness(UnitModel model, UnitModel target)
  {
    double theirRange = 0;
    for( WeaponModel wm : target.weapons )
    {
      double range = wm.rangeMax();
      // TODO: Fix this!
      if( wm.canFireAfterMoving() )
        range += getEffectiveMove(target);
      theirRange = Math.max(theirRange, range);
    }
    double counterPower = 0;
    for( WeaponModel wm : model.weapons )
    {
      double damage = wm.getDamage(target);
      // Using the WeaponModel values directly for now
      double myRange = wm.rangeMax();
      if( wm.canFireAfterMoving() )
        myRange += getEffectiveMove(model);
      else
        myRange -= (Math.pow(wm.rangeMin(), MIN_SIEGE_RANGE_WEIGHT) - 1); // penalize range based on inner range
      double rangeMod = Math.pow(myRange / theirRange, RANGE_WEIGHT);
      // TODO: account for average terrain defense?
      double effectiveness = damage * rangeMod / 100;
      counterPower = Math.max(counterPower, effectiveness);
    }
    return counterPower;
  }
  public double getEffectiveMove(UnitModel model)
  {
    if( unitEffectiveMove.containsKey(model) )
      return unitEffectiveMove.get(model);

    //TODO
//    MoveType p = model.calculateMoveType();
    MoveType p = model.baseMoveType;
    GameMap map = myArmy.myView;
    double totalCosts = 0;
    int validTiles = 0;
    double totalTiles = map.mapWidth * map.mapHeight; // to avoid integer division
    // Iterate through the map, counting up the move costs of all valid terrain
    for( int w = 0; w < map.mapWidth; ++w )
    {
      for( int h = 0; h < map.mapHeight; ++h )
      {
        Environment terrain = map.getLocation(w, h).getEnvironment();
        if( p.canStandOn(terrain) )
        {
          validTiles++;
          int cost = p.getMoveCost(terrain);
          totalCosts += Math.pow(cost, TERRAIN_PENALTY_WEIGHT);
        }
      }
    }
    //             term for how fast you are   term for map coverage
    double ratio = (validTiles / totalCosts) * (validTiles / totalTiles); // 1.0 is the max expected value

//    double effMove = model.calculateMovePower() * ratio;
    double effMove = model.baseMovePower * ratio;
    unitEffectiveMove.put(model, effMove);
    return effMove;
  }

  private static class AttackValue
  {
    final int hploss, hpdamage, loss, damage;
    final int fundsDelta;

    public AttackValue(WallyAI ai, UnitContext actor, UnitContext target, GameMap gameMap)
    {
      final int actorCost = actor.CO.getCost(actor.model);

      BattleSummary results = CombatEngine.simulateBattleResults(actor, target, gameMap, CALC);
      hploss   = actor .getHP() - Math.max(0, results.attacker.after.getHP());
      hpdamage = target.getHP() - Math.max(0, results.defender.after.getHP());
      loss     = (hploss   * actorCost                      ) / UnitModel.MAXIMUM_HP;
      damage   = (hpdamage * target.CO.getCost(target.model)) / UnitModel.MAXIMUM_HP;

      if( ai.canWallHere(gameMap, ai.threatMap, actor.unit, actor.coord, target.unit) )
      {
        ai.log(String.format("  %s thinks it's safe to attack %s", actor, target));
        //           funds "gained" funds lost     term to favor using cheaper units
        fundsDelta =     damage    -   loss    - (int) (actorCost*AGGRO_CHEAPER_WEIGHT);
      }
      else
      {
        fundsDelta = (int) (damage*AGGRO_FUNDS_WEIGHT) - actorCost;
        ai.log(String.format("  %s is going aggro on %s", actor, target));
        ai.log(String.format("    He plans to deal %s HP damage for a net gain of %s funds", hpdamage, fundsDelta));
      }
    }
    public static AttackValue forPlannedAttack(WallyAI ai, BattleAction ga, GameMap gameMap)
    {
      XYCoord moveCoord = ga.getMoveLocation();
      XYCoord targetXYC = ga.getTargetLocation();
      UnitContext actor  = ai.mapPlan[moveCoord.xCoord][moveCoord.yCoord].identity;
      UnitContext target = ai.mapPlan[targetXYC.xCoord][targetXYC.yCoord].identity;

      if( null == target )
      {
        // TODO: Consider evaluating this as a oneshot
        Unit tu = gameMap.getResident(targetXYC);
        target = new UnitContext(gameMap, tu);
      }

      return new AttackValue(ai, actor, target, gameMap);
    }
  }
  public int valueCapture(CaptureAction ga, GameMap gameMap)
  {
    Unit unit = ga.getActor();
    XYCoord moveCoord = ga.getMoveLocation();
    int capValue = unit.getHP(); // TODO: update for capture boosts
    boolean success = (unit.getCaptureProgress() + capValue) >= gameMap.getEnvironment(moveCoord).terrainType.getCaptureThreshold();

    if( success )
      return Integer.MAX_VALUE/42; // I can't think of very many good reasons to skip finishing a capture

    // Since we can't be certain of a capture, ballpark a day's income per full HP inf's worth of capping
    return (capValue * myArmy.gameRules.incomePerCity) / UnitModel.MAXIMUM_HP;
  }

  private static int valueAction(WallyAI ai, GameMap gameMap, ActionPlan ap)
  {
    if( null == ap || null == ap.action )
    {
      // We shouldn't try to preempt a unit we didn't plan to be there?
      return Integer.MAX_VALUE / 42;
    }

    int opportunityCost = 0;
    final UnitActionFactory apType = ap.action.getType();
    if( apType == UnitActionFactory.ATTACK )
      opportunityCost = AttackValue.forPlannedAttack(ai, (BattleAction) ap.action, gameMap).fundsDelta;
    if( apType == UnitActionFactory.CAPTURE )
      opportunityCost = ai.valueCapture((CaptureAction) ap.action, gameMap);
    if( apType == UnitActionFactory.WAIT )
      opportunityCost = 0;
    return opportunityCost;
  }

  /**
   * This is probably some kind of sin against design
   * <p>But it provides a way to get Utils to assume the map state is what I think it will be, so it seems legit
   * <p>Overrides/implements the base methods for the unit-in-tile fetching behavior
   */
  public static class PredictionMap extends MapPerspective
  {
    private static final long serialVersionUID = 1L;
    private UnitPrediction[][] mapPlan;
    public PredictionMap(Army pViewer, UnitPrediction[][] mapPlan)
    {
      super(pViewer.myView, pViewer);
      this.mapPlan = mapPlan;
    }
    @Override
    public MapLocation getLocation(int x, int y)
    {
      XYCoord coord = new XYCoord(x, y);
      MapLocation masterLoc = master.getLocation(coord);
      MapLocation returnLoc = new MapLocation(masterLoc.getEnvironment(), coord);
      returnLoc.setOwner(masterLoc.getOwner());
      UnitContext resSource = mapPlan[x][y].identity;

      if( null == resSource )
        return returnLoc;

      int damagePercent = 0;
      for( int hit : mapPlan[x][y].damageInstances.values() )
        damagePercent += hit;
      if( damagePercent >= resSource.getHP() * UnitModel.MAXIMUM_HP )
        return returnLoc; // If we think it will be dead, don't report its presence

      Unit resident = resSource.unit;
      if( null == resident )
        // If we've planned a unit that doesn't exist, make stuff up
        resident = new Unit(resSource.CO, resSource.model);
      returnLoc.setResident(resident);
      return returnLoc;
    }
    @Override
    public boolean isLocationEmpty(Unit unit, int x, int y)
    {
      Unit resident = getResident(x, y);
      return null == resident || resident == unit;
    }
  }
}
