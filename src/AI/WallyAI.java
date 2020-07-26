package AI;

import java.util.*;
import java.util.Map.Entry;

import AI.AIUtils.CommanderProductionInfo;
import CommandingOfficers.Commander;
import CommandingOfficers.CommanderAbility;
import Engine.*;
import Engine.Combat.BattleSummary;
import Engine.Combat.CombatEngine;
import Engine.UnitActionLifecycles.BattleLifecycle;
import Engine.UnitActionLifecycles.WaitLifecycle;
import Terrain.*;
import Units.*;
import Units.MoveTypes.MoveType;

/**
 *  Wally values units based on firepower and the area they can threaten.
 *  He tries to keep units safe by keeping them out of range, but will also meatshield to protect more valuable units.
 */
public class WallyAI extends ModularAI
{
  private static class instantiator implements AIMaker
  {
    @Override
    public AIController create(Commander co)
    {
      return new WallyAI(co);
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
  private static final int INDIRECT_THREAT_THRESHHOLD = 7;
  private static final int DIRECT_THREAT_THRESHHOLD = 13;
  private static final int    UNIT_HEAL_THRESHHOLD = 6; // HP at which units heal
  private static final double UNIT_REFUEL_THRESHHOLD = 1.3; // Factor of cost to get to fuel to start worrying about fuel
  private static final double UNIT_REARM_THRESHHOLD = 0.25; // Fraction of ammo in any weapon below which to consider resupply
  private static final double AGGRO_EFFECT_THRESHHOLD = 0.42; // How effective do I need to be against a unit to target it?
  private static final double AGGRO_FUNDS_WEIGHT = 0.9; // Multiplier on damage I need to get before a sacrifice is worth it
  private static final double RANGE_WEIGHT = 1; // Exponent for how powerful range is considered to be
  private static final double TERRAIN_PENALTY_WEIGHT = 3; // Exponent for how crippling we think high move costs are
  private static final double MIN_SIEGE_RANGE_WEIGHT = 0.8; // Exponent for how much to penalize siege weapon ranges for their min ranges 
  
  private Map<UnitModel, Map<XYCoord, Double>> threatMap;
  private ArrayList<Unit> allThreats;
  private HashMap<UnitModel, Double> unitEffectiveMove = null; // How well the unit can move, on average, on this map
  public double getEffectiveMove(UnitModel model)
  {
    if( unitEffectiveMove.containsKey(model) )
      return unitEffectiveMove.get(model);

    MoveType p = model.propulsion;
    GameMap map = myCo.myView;
    double totalCosts = 0;
    int validTiles = 0;
    double totalTiles = map.mapWidth * map.mapHeight; // to avoid integer division
    // Iterate through the map, counting up the move costs of all valid terrain
    for( int w = 0; w < map.mapWidth; ++w )
    {
      for( int h = 0; h < map.mapHeight; ++h )
      {
        Environment terrain = map.getLocation(w, h).getEnvironment();
        if( p.canTraverse(terrain) )
        {
          validTiles++;
          int cost = p.getMoveCost(terrain);
          totalCosts += Math.pow(cost, TERRAIN_PENALTY_WEIGHT);
        }
      }
    }
    //             term for how fast you are   term for map coverage
    double ratio = (validTiles / totalCosts) * (validTiles / totalTiles); // 1.0 is the max expected value
    
    double effMove = model.movePower * ratio;
    unitEffectiveMove.put(model, effMove);
    return effMove;
  }


  public WallyAI(Commander co)
  {
    super(co);
    aiPhases = new ArrayList<AIModule>(
        Arrays.asList(
            new PowerActivator(co, CommanderAbility.PHASE_TURN_START),
            new GenerateThreatMap(co, this),
            new CaptureFinisher(co, this),

            new SiegeAttacks(co, this),
            new NHitKO(co, this),
            new PowerActivator(co, CommanderAbility.PHASE_BUY),
            new BuildStuff(co, this),
            new FreeRealEstate(co, this),
            new Travel(co, this),

            new PowerActivator(co, CommanderAbility.PHASE_TURN_END)
            ));
  }

  private void init(GameMap map)
  {
    unitEffectiveMove = new HashMap<UnitModel, Double>();
    // init all move multipliers before powers come into play
    for( Commander co : map.commanders )
    {
      for( UnitModel model : co.unitModels )
      {
        getEffectiveMove(model);
      }
    }
  }

  @Override
  public void initTurn(GameMap gameMap)
  {
    super.initTurn(gameMap);
    if( null == unitEffectiveMove )
      init(gameMap);
    log(String.format("[======== Wally initializing turn %s for %s =========]", turnNum, myCo));
  }

  @Override
  public void endTurn()
  {
    log(String.format("[======== Wally ending turn %s for %s =========]", turnNum, myCo));
  }

  public static class SiegeAttacks extends UnitActionFinder
  {
    private static final long serialVersionUID = 1L;
    public SiegeAttacks(Commander co, ModularAI ai)
    {
      super(co, ai);
    }

    @Override
    public GameAction getUnitAction(Unit unit, GameMap gameMap)
    {
      GameAction bestAttack = null;
      // Find the possible destination.
      XYCoord coord = new XYCoord(unit.x, unit.y);

      if( AIUtils.isFriendlyProduction(gameMap, myCo, coord) || !unit.model.hasImmobileWeapon() )
        return bestAttack;

      // Figure out how to get here.
      Path movePath = Utils.findShortestPath(unit, coord, gameMap);

      // Figure out what I can do here.
      ArrayList<GameActionSet> actionSets = unit.getPossibleActions(gameMap, movePath);
      double bestDamage = 0;
      for( GameActionSet actionSet : actionSets )
      {
        // See if we have the option to attack.
        if( actionSet.getSelected().getType() == UnitActionFactory.ATTACK )
        {
          for( GameAction action : actionSet.getGameActions() )
          {
            Location loc = gameMap.getLocation(action.getTargetLocation());
            Unit target = loc.getResident();
            if( null == target ) continue; // Ignore terrain
            double damage = valueUnit(target, loc, false) * Math.min(target.getHP(), CombatEngine.simulateBattleResults(unit, target, gameMap, unit.x, unit.y).defenderHPLoss);
            if( damage > bestDamage )
            {
              bestDamage = damage;
              bestAttack = action;
            }
          }
        }
      }
      if( null != bestAttack )
      {
        ai.log(String.format("%s is shooting %s",
            unit.toStringWithLocation(), gameMap.getLocation(bestAttack.getTargetLocation()).getResident()));
      }
      return bestAttack;
    }
  }

  // Try to get confirmed kills with mobile strikes.
  public static class NHitKO implements AIModule
  {
    private static final long serialVersionUID = 1L;
    public Commander myCo;
    public final WallyAI ai;

    public NHitKO(Commander co, WallyAI ai)
    {
      myCo = co;
      this.ai = ai;
    }

    XYCoord targetLoc;
    Map<XYCoord, Unit> neededAttacks;
    double damageSum = 0;
    public void reset()
    {
      targetLoc = null;
      neededAttacks = null;
      damageSum = 0;
    }

    @Override
    public GameAction getNextAction(PriorityQueue<Unit> unitQueue, GameMap gameMap)
    {
      GameAction nextAction = nextAttack(gameMap);
      if( null != nextAction )
        return nextAction;

      // Get a count of enemy forces.
      Map<Commander, ArrayList<Unit>> unitLists = AIUtils.getEnemyUnitsByCommander(myCo, gameMap);
      for( Commander co : unitLists.keySet() )
      {
        // log(String.format("Hunting CO %s's units", co.coInfo.name));
        if( myCo.isEnemy(co) )
        {
          Queue<Unit> targetQueue = new PriorityQueue<Unit>(unitLists.get(co).size(), new AIUtils.UnitCostComparator(false));
          targetQueue.addAll(unitLists.get(co)); // We want to kill the most expensive enemy units
          for( Unit target : targetQueue )
          {
            if( target.getHP() < 1 ) // Try not to pick fights with zombies
              continue;
            // log(String.format("  Would like to kill: %s", target.toStringWithLocation()));
            ArrayList<XYCoord> coordsToCheck = Utils.findLocationsInRange(gameMap, new XYCoord(target.x, target.y), 1, AIUtils.findMaxStrikeWeaponRange(myCo));
            neededAttacks = new HashMap<XYCoord, Unit>();
            double damage = 0;

            // Figure out where we can attack from, and include attackers already in range by default.
            for( XYCoord xyc : coordsToCheck )
            {
              Location loc = gameMap.getLocation(xyc);
              Unit resident = loc.getResident();

              // Units who can attack from their current position volunteer themselves. Probably not smart sometimes, but oh well.
              if( null != resident && resident.CO == myCo && !resident.isTurnOver
                  && resident.canAttack(target.model, xyc.getDistance(target.x, target.y), false) )
              {
                damage += CombatEngine.simulateBattleResults(resident, target, gameMap, xyc.xCoord, xyc.yCoord).defenderHPLoss;
                neededAttacks.put(xyc, resident);
                if( damage >= target.getHP() )
                  break;
              }
              // Check that we could potentially move into this space. Also we're scared of fog
              else if( (null == resident) && !AIUtils.isFriendlyProduction(gameMap, myCo, xyc)
                  && !gameMap.isLocationFogged(xyc) )
                neededAttacks.put(xyc, null);
            }
            damage = ai.findAssaultKills(gameMap, unitQueue, neededAttacks, target, damage);
            if( damage >= target.getHP() && neededAttacks.size() > 1 )
            {
              ai.log(String.format("Found %s-Hit KO dealing %s HP to %s, who has %s", neededAttacks.size(), (int)damage, target.toStringWithLocation(), target.getHP()));
              for( XYCoord space : neededAttacks.keySet() )
              {
                Unit attacker = neededAttacks.get(space);
                if( null == attacker )
                  continue;
                double thisShot = CombatEngine.simulateBattleResults(attacker, target, gameMap, space.xCoord, space.yCoord).defenderHPLoss;
                ai.log(String.format("  Can I prune %s (dealing %s)?", attacker.toStringWithLocation(), thisShot));
                if( target.getHP() <= damage - thisShot )
                {
                  neededAttacks.put(space, null);
                  damage -= thisShot;
                  ai.log(String.format("    Yes! Total damage is now %s", damage));
                }
              }
              targetLoc = new XYCoord(target.x, target.y);
              return nextAttack(gameMap);
            }
            else
            {
              // log(String.format("  Can't kill %s, oh well", target.toStringWithLocation()));
            }
          }
        }
      }

      return null;
    }

    private GameAction nextAttack(GameMap gameMap)
    {
      if( null == targetLoc || null == neededAttacks )
        return null;

      Unit target = gameMap.getLocation(targetLoc).getResident();
      if( null == target )
      {
        ai.log(String.format("    NHitKO target is ded. Ayy."));
        reset();
        return null;
      }

      for( XYCoord xyc : neededAttacks.keySet() )
      {
        Unit unit = neededAttacks.get(xyc);
        if( null == unit || unit.isTurnOver )
          continue;

        damageSum += CombatEngine.simulateBattleResults(unit, target, gameMap, xyc.xCoord, xyc.yCoord).defenderHPLoss;
        ai.log(String.format("    %s brings the damage total to %s", unit.toStringWithLocation(), damageSum));
        return new BattleLifecycle.BattleAction(gameMap, unit, Utils.findShortestPath(unit, xyc, gameMap), target.x, target.y);
      }
      ai.log(String.format("    NHitKO ran out of attacks to do"));
      reset();
      return null;
    }
  }

  public static class GenerateThreatMap implements AIModule
  {
    private static final long serialVersionUID = 1L;
    public Commander myCo;
    public final WallyAI ai;

    public GenerateThreatMap(Commander co, WallyAI ai)
    {
      myCo = co;
      this.ai = ai;
    }

    @Override
    public GameAction getNextAction(PriorityQueue<Unit> unitQueue, GameMap gameMap)
    {
      ai.allThreats = new ArrayList<Unit>();
      ai.threatMap = new HashMap<UnitModel, Map<XYCoord, Double>>();
      Map<Commander, ArrayList<Unit>> unitLists = AIUtils.getEnemyUnitsByCommander(myCo, gameMap);
      for( UnitModel um : myCo.unitModels )
      {
        ai.threatMap.put(um, new HashMap<XYCoord, Double>());
        for( Commander co : unitLists.keySet() )
        {
          if( myCo.isEnemy(co) )
          {
            for( Unit threat : unitLists.get(co) )
            {
              // add each new threat to the existing threats
              ai.allThreats.add(threat);
              Map<XYCoord, Double> threatArea = ai.threatMap.get(um);
              for( Entry<XYCoord, Double> newThreat : AIUtils.findThreatPower(gameMap, threat, um).entrySet() )
              {
                if( null == threatArea.get(newThreat.getKey()) )
                  threatArea.put(newThreat.getKey(), newThreat.getValue());
                else
                  threatArea.put(newThreat.getKey(), newThreat.getValue() + threatArea.get(newThreat.getKey()));
              }
            }
          }
        }
      }

      return null;
    }
  }

  // Try to get unit value by capture or attack
  public static class FreeRealEstate extends UnitActionFinder
  {
    private static final long serialVersionUID = 1L;
    private final WallyAI ai;
    public FreeRealEstate(Commander co, WallyAI ai)
    {
      super(co, ai);
      this.ai = ai;
    }

    @Override
    public GameAction getUnitAction(Unit unit, GameMap gameMap)
    {
      boolean mustMove = false;
      boolean avoidProduction = false;
      return findValueAction(myCo, ai, unit, gameMap, mustMove, avoidProduction);
    }

    public static GameAction findValueAction( Commander co, WallyAI ai,
                                              Unit unit, GameMap gameMap,
                                              boolean mustMove, boolean avoidProduction )
    {
      XYCoord position = new XYCoord(unit.x, unit.y);
      Location unitLoc = gameMap.getLocation(position);

      boolean includeOccupiedSpaces = true; // Since we know how to shift friendly units out of the way
      ArrayList<XYCoord> destinations = Utils.findPossibleDestinations(unit, gameMap, includeOccupiedSpaces);
      if( mustMove )
        destinations.remove(new XYCoord(unit.x, unit.y));
      destinations.removeAll(AIUtils.findAlliedIndustries(gameMap, co, destinations, !avoidProduction));
      // sort by furthest away, good for capturing
      Utils.sortLocationsByDistance(position, destinations);
      Collections.reverse(destinations);

      for( XYCoord moveCoord : destinations )
      {
        // Figure out how to get here.
        Path movePath = Utils.findShortestPath(unit, moveCoord, gameMap);

        // Figure out what I can do here.
        ArrayList<GameActionSet> actionSets = unit.getPossibleActions(gameMap, movePath, includeOccupiedSpaces);
        for( GameActionSet actionSet : actionSets )
        {
          boolean spaceFree = gameMap.isLocationEmpty(unit, moveCoord);
          Unit resident = gameMap.getLocation(moveCoord).getResident();
          if( !spaceFree && (unit.CO != resident.CO || resident.isTurnOver) )
            continue;

          // See if we can bag enough damage to be worth sacrificing the unit
          if( actionSet.getSelected().getType() == UnitActionFactory.ATTACK )
          {
            for( GameAction ga : actionSet.getGameActions() )
            {
              Location targetLoc = gameMap.getLocation(ga.getTargetLocation());
              Unit target = targetLoc.getResident();
              if( null == target )
                continue;
              BattleSummary results =
                  CombatEngine.simulateBattleResults(unit, target, gameMap, moveCoord.xCoord, moveCoord.yCoord);
              double loss   = Math.min(unit  .getHP(), (int)results.attackerHPLoss);
              double damage = Math.min(target.getHP(), (int)results.defenderHPLoss);
              
              boolean goForIt = false;
              if( valueUnit(target, targetLoc, false) * Math.floor(damage) * AGGRO_FUNDS_WEIGHT > valueUnit(unit, unitLoc, true) )
              {
                ai.log(String.format("  %s is going aggro on %s", unit.toStringWithLocation(), target.toStringWithLocation()));
                ai.log(String.format("    He plans to deal %s HP damage for a net gain of %s funds", damage, (target.model.getCost() * damage - unit.model.getCost() * unit.getHP())/10));
                goForIt = true;
              }
              else if( damage > loss
                     && ai.isSafe(gameMap, ai.threatMap, unit, ga.getMoveLocation()) )
              {
                ai.log(String.format("  %s thinks it's safe to attack %s", unit.toStringWithLocation(), target.toStringWithLocation()));
                goForIt = true;
              }

              if( goForIt )
              {
                if( !spaceFree )
                  return ai.evictUnit(gameMap, ai.allThreats, ai.threatMap, unit, resident);
                return ga;
              }
            }
          }

          // Only consider capturing if we can sit still or go somewhere safe.
          if( actionSet.getSelected().getType() == UnitActionFactory.CAPTURE
              && (moveCoord.getDistance(unit.x, unit.y) == 0 || ai.canWallHere(gameMap, ai.threatMap, unit, moveCoord)) )
          {
            if( !spaceFree )
              return ai.evictUnit(gameMap, ai.allThreats, ai.threatMap, unit, resident);
            return actionSet.getSelected();
          }
        }
      }
      return null;
    }
  }

  // If no attack/capture actions are available now, just move around
  public static class Travel extends UnitActionFinder
  {
    private static final long serialVersionUID = 1L;
    private final WallyAI ai;
    public Travel(Commander co, WallyAI ai)
    {
      super(co, ai);
      this.ai = ai;
    }

    @Override
    public GameAction getUnitAction(Unit unit, GameMap gameMap)
    {
      ai.log(String.format("Evaluating travel for %s.", unit.toStringWithLocation()));
      boolean avoidProduction = false;
      return ai.findTravelAction(gameMap, ai.allThreats, ai.threatMap, unit, false, avoidProduction);
    }
  }

  public static class BuildStuff implements AIModule
  {
    private static final long serialVersionUID = 1L;
    public final Commander myCo;
    public final WallyAI ai;

    public BuildStuff(Commander co, WallyAI ai)
    {
      myCo = co;
      this.ai = ai;
    }

    Map<XYCoord, UnitModel> builds;

    @Override
    public GameAction getNextAction(PriorityQueue<Unit> unitQueue, GameMap gameMap)
    {
      if( null == builds )
        builds = ai.queueUnitProductionActions(gameMap);

      for( XYCoord coord : new ArrayList<XYCoord>(builds.keySet()) )
      {
        ai.log(String.format("Attempting to build %s at %s", builds.get(coord), coord));
        Unit resident = gameMap.getResident(coord);
        if( null != resident )
        {
          boolean avoidProduction = true;
          if( resident.CO == myCo && !resident.isTurnOver )
            return ai.evictUnit(gameMap, ai.allThreats, ai.threatMap, null, resident, avoidProduction);
          else
          {
            ai.log(String.format("  Can't evict unit %s to build %s", resident.toStringWithLocation(), builds.get(coord)));
            builds.remove(coord);
            continue;
          }
        }
        ArrayList<UnitModel> list = myCo.getShoppingList(gameMap.getLocation(coord)); // COs expect to see their shopping lists fetched before a purchase
        UnitModel toBuy = builds.get(coord);
        if( toBuy.getCost() <= myCo.money && list.contains(toBuy) )
        {
          builds.remove(coord);
          return new GameAction.UnitProductionAction(myCo, toBuy, coord);
        }
        else
        {
          ai.log(String.format("  Trying to build %s, but it's unavailable at %s", toBuy, coord));
          continue;
        }
      }

      builds = null;
      return null;
    }
  }

  /** Produces a list of destinations for the unit, ordered by their relative precedence */
  private ArrayList<XYCoord> findTravelDestinations(
                                  GameMap gameMap,
                                  ArrayList<Unit> allThreats, Map<UnitModel, Map<XYCoord, Double>> threatMap,
                                  Unit unit,
                                  boolean avoidProduction )
  {
    ArrayList<XYCoord> goals = new ArrayList<XYCoord>();

    ArrayList<XYCoord> stations = AIUtils.findRepairDepots(unit);
    Utils.sortLocationsByDistance(new XYCoord(unit.x, unit.y), stations);
    if( stations.size() > 0 )
    {
      boolean shouldResupply = unit.getHP() <= UNIT_HEAL_THRESHHOLD;
      shouldResupply |= unit.fuel <= UNIT_REFUEL_THRESHHOLD
          * Utils.findShortestPath(unit, stations.get(0), gameMap).getFuelCost(unit.model, gameMap);
      shouldResupply |= unit.ammo >= 0 && unit.ammo <= unit.model.maxAmmo * UNIT_REARM_THRESHHOLD;

      if( shouldResupply )
      {
        log(String.format("  %s needs supplies.", unit.toStringWithLocation()));
        goals.addAll(stations);
      }
      if( avoidProduction )
        goals.removeAll(AIUtils.findAlliedIndustries(gameMap, myCo, goals, !avoidProduction));
    }
    else if( unit.model.possibleActions.contains(UnitActionFactory.CAPTURE) )
    {
      for( XYCoord xyc : unownedProperties )
        if( !AIUtils.isCapturing(gameMap, myCo, xyc) )
          goals.add(xyc);
    }
    else if( unit.model.possibleActions.contains(UnitActionFactory.ATTACK) )
    {
      Map<UnitModel, Double> valueMap = new HashMap<UnitModel, Double>();
      Map<UnitModel, ArrayList<XYCoord>> targetMap = new HashMap<UnitModel, ArrayList<XYCoord>>();

      // Categorize all enemies by type, and all types by how well we match up vs them
      for( Unit target : allThreats )
      {
        UnitModel model = target.model;
        XYCoord targetCoord = new XYCoord(target.x, target.y);
        double effectiveness = findEffectiveness(unit.model, target.model);
        if (Utils.findShortestPath(unit, targetCoord, gameMap, true) != null &&
            AGGRO_EFFECT_THRESHHOLD > effectiveness)
        {
          valueMap.put(model, effectiveness*model.getCost());
          if (!targetMap.containsKey(model)) targetMap.put(model, new ArrayList<XYCoord>());
          targetMap.get(model).add(targetCoord);
        }
      }

      // Sort all individual target lists by distance
      for (ArrayList<XYCoord> targetList : targetMap.values())
        Utils.sortLocationsByDistance(new XYCoord(unit.x, unit.y), targetList);

      // Sort all target types by how much we want to shoot them with this unit
      Queue<Entry<UnitModel, Double>> targetTypesInOrder = 
          new PriorityQueue<Entry<UnitModel, Double>>(myCo.unitModels.size(), new UnitModelFundsComparator());
      targetTypesInOrder.addAll(valueMap.entrySet());

      while (!targetTypesInOrder.isEmpty())
      {
        UnitModel model = targetTypesInOrder.poll().getKey(); // peel off the juiciest
        goals.addAll(targetMap.get(model)); // produce a list ordered by juiciness first, then distance TODO: consider a holistic "juiciness" metric that takes into account both matchup and distance?
      }
    }

    if( goals.isEmpty() ) // Send 'em at production facilities if they haven't got anything better to do
    {
      for( XYCoord coord : unownedProperties )
      {
        Location loc = gameMap.getLocation(coord);
        if( myCo.unitProductionByTerrain.containsKey(loc.getEnvironment().terrainType)
            && myCo.isEnemy(loc.getOwner()) )
        {
          goals.add(coord);
        }
      }
    }

    if( goals.isEmpty() ) // If there's really nothing to do, go to MY HQ
      goals.add(myCo.HQLocation);

    Utils.sortLocationsByDistance(new XYCoord(unit.x, unit.y), goals);
    return goals;
  }

  /** Functions as working memory to prevent eviction cycles */
  private transient Set<Unit> evictionStack;
  /**
   * Queue the first action required to move a unit out of the way
   * For use after unit building is complete
   * Can recurse based on the other functions it calls.
   */
  private GameAction evictUnit(
                        GameMap gameMap,
                        ArrayList<Unit> allThreats, Map<UnitModel, Map<XYCoord, Double>> threatMap,
                        Unit evicter, Unit unit)
  {
    return evictUnit(gameMap, allThreats, threatMap, evicter, unit, false);
  }
  private GameAction evictUnit(
                        GameMap gameMap,
                        ArrayList<Unit> allThreats, Map<UnitModel, Map<XYCoord, Double>> threatMap,
                        Unit evicter, Unit unit,
                        boolean avoidProduction )
  {
    boolean isBase = false;
    if( null == evictionStack )
    {
      evictionStack = new HashSet<Unit>();
      isBase = true;
    }
    String spacing = "";
    for( int i = 0; i < evictionStack.size(); ++i ) spacing += "  ";
    log(String.format("%sAttempting to evict %s", spacing, unit.toStringWithLocation()));
    if( evicter != null )
      evictionStack.add(evicter);

    if( evictionStack.contains(unit) )
    {
      log(String.format("%s  Eviction cycle! Bailing.", spacing));
      return null;
    }
    evictionStack.add(unit);

    boolean mustMove = true;
    GameAction result = FreeRealEstate.findValueAction(myCo, this, unit, gameMap, mustMove, avoidProduction);
    if( null == result )
    {
      boolean ignoreSafety = true;
      result = findTravelAction(gameMap, allThreats, threatMap, unit, ignoreSafety, avoidProduction);
    }

    if( isBase )
      evictionStack = null;
    log(String.format("%s  Eviction of %s success? %s", spacing, unit.toStringWithLocation(), null != result));
    return result;
  }

  /**
   * Find a good long-term objective for the given unit, and pursue it (with consideration for life-preservation optional)
   */
  private GameAction findTravelAction(
                        GameMap gameMap,
                        ArrayList<Unit> allThreats, Map<UnitModel, Map<XYCoord, Double>> threatMap,
                        Unit unit,
                        boolean ignoreSafety,
                        boolean avoidProduction )
  {
    // Find the possible destinations.
    boolean ignoreResident = true;
    ArrayList<XYCoord> destinations = Utils.findPossibleDestinations(unit, gameMap, ignoreResident);
    if( ignoreSafety ) // If we *must* travel, make sure we do actually move.
      destinations.remove(new XYCoord(unit.x, unit.y));
    destinations.removeAll(AIUtils.findAlliedIndustries(gameMap, myCo, destinations, !avoidProduction));

    // TODO: Jump in a transport, if available, or join?

    XYCoord goal = null;
    Path path = null;
    ArrayList<XYCoord> validTargets = findTravelDestinations(gameMap, allThreats, threatMap, unit, avoidProduction);

    for( XYCoord target : validTargets )
    {
      path = Utils.findShortestPath(unit, target, gameMap, true);
      if( path.getPathLength() > 0 ) // We can reach it.
      {
        goal = target;
        break;
      }
    }

    if( null == goal ) return null;

    // Choose the point on the path just out of our range as our 'goal', and try to move there.
    // This will allow us to navigate around large obstacles that require us to move away
    // from our intended long-term goal.
    path.snip(unit.model.movePower + 1); // Trim the path approximately down to size.
    XYCoord pathPoint = path.getEndCoord(); // Set the last location as our goal.

    // Sort my currently-reachable move locations by distance from the goal,
    // and build a GameAction to move to the closest one.
    Utils.sortLocationsByDistance(pathPoint, destinations);
    log(String.format("  %s is traveling toward %s at %s via %s  Forced?: %s",
                          unit.toStringWithLocation(),
                          gameMap.getLocation(goal).getEnvironment().terrainType, goal,
                          pathPoint, ignoreSafety));
    for( XYCoord xyc : destinations )
    {
      log(String.format("    is it safe to go to %s?", xyc));
      if( !ignoreSafety && !canWallHere(gameMap, threatMap, unit, xyc) )
        continue;
      log(String.format("    Yes"));

      GameAction action = null;
      Unit resident = gameMap.getLocation(xyc).getResident();
      if( null != resident && unit != resident )
      {
        if( unit.CO == resident.CO && !resident.isTurnOver )
          action = evictUnit(gameMap, allThreats, threatMap, unit, resident);
        if( null != action ) return action;
        continue;
      }

      Path movePath = Utils.findShortestPath(unit, xyc, gameMap);
      ArrayList<GameActionSet> actionSets = unit.getPossibleActions(gameMap, movePath, ignoreResident);
      if( actionSets.size() > 0 )
      {
        // Since we're moving anyway, might as well try shooting the scenery
        for( GameActionSet actionSet : actionSets )
        {
          if( actionSet.getSelected().getType() == UnitActionFactory.ATTACK )
          {
            double bestDamage = 0;
            for( GameAction attack : actionSet.getGameActions() )
            {
              double damageValue = AIUtils.scoreAttackAction(unit, attack, gameMap,
                  (results) -> {
                    double loss   = Math.min(unit            .getHP(), (int)results.attackerHPLoss);
                    double damage = Math.min(results.defender.getHP(), (int)results.defenderHPLoss);

                    if( damage > loss ) // only shoot that which you hurt more than it hurts you
                      return damage * results.defender.model.getCost();

                    return 0.;
                  }, (terrain, params) -> 0.01); // Attack terrain, but don't prioritize it over units

              if( damageValue > bestDamage )
              {
                log(String.format("      Best en passant attack deals %s", damageValue));
                bestDamage = damageValue;
                action = attack;
              }
            }
          }
        }

        if( null == action && movePath.getPathLength() > 1) // Just wait if we can't do anything cool
          action = new WaitLifecycle.WaitAction(unit, movePath);
        return action;
      }
    }
    return null;
  }

  private boolean isSafe(GameMap gameMap, Map<UnitModel, Map<XYCoord, Double>> threatMap, Unit unit, XYCoord xyc)
  {
    Double threat = threatMap.get(unit.model).get(xyc);
    int threshhold = unit.model.hasDirectFireWeapon() ? DIRECT_THREAT_THRESHHOLD : INDIRECT_THREAT_THRESHHOLD;
    return (null == threat || threshhold > threat);
  }

  /**
   * @return whether it's safe or a good place to wall
   * For use after unit building is complete
   */
  private boolean canWallHere(GameMap gameMap, Map<UnitModel, Map<XYCoord, Double>> threatMap, Unit unit, XYCoord xyc)
  {
    Location destination = gameMap.getLocation(xyc);
    // if we're safe, we're safe
    if( isSafe(gameMap, threatMap, unit, xyc) )
      return true;

    // TODO: Determine whether the ally actually needs a wall there. Mechs walling for Tanks vs inf is... silly.
    // if we'd be a nice wall for a worthy ally, we can pretend we're safe there also
    ArrayList<XYCoord> adjacentCoords = Utils.findLocationsInRange(gameMap, xyc, 1);
    for( XYCoord coord : adjacentCoords )
    {
      Location loc = gameMap.getLocation(coord);
      if( loc != null )
      {
        Unit resident = loc.getResident();
        if( resident != null && !myCo.isEnemy(resident.CO)
            && valueUnit(resident, loc, true) > valueUnit(unit, destination, true) )
        {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Attempts to find a combination of attacks that will create a kill.
   * Recursive.
   */
  private double findAssaultKills(GameMap gameMap, Queue<Unit> unitQueue, Map<XYCoord, Unit> neededAttacks, Unit target, double pDamage)
  {
    // base case; we found a kill
    if( pDamage >= target.getPreciseHP() )
    {
      return pDamage;
    }

    double damage = pDamage;
    for( XYCoord xyc : neededAttacks.keySet() )
    {
      // Don't try to attack from the same space twice.
      if( null != neededAttacks.get(xyc) )
        continue;

      // Attack with the cheapest assault units, if possible.
      Queue<Unit> assaultQueue = new PriorityQueue<Unit>(11, new AIUtils.UnitCostComparator(true));
      assaultQueue.addAll(unitQueue);
      while (!assaultQueue.isEmpty())
      {
        Unit unit = assaultQueue.poll();
        if( !unit.model.hasDirectFireWeapon() || neededAttacks.containsValue(unit) ) // don't try to attack twice with one unit
          continue;

        int dist = xyc.getDistance(target.x, target.y);

        // Figure out how to get here.
        Path movePath = Utils.findShortestPath(unit, xyc, gameMap);

        if( movePath.getPathLength() > 0 && unit.canAttack(target.model, dist, true) )
        {
          neededAttacks.put(xyc, unit);
          double thisDamage = CombatEngine.simulateBattleResults(unit, target, gameMap, xyc.xCoord, xyc.yCoord).defenderHPLoss;

          if( thisDamage > target.getPreciseHP() )
            continue; // OHKOs should be decided using different logic

//          log(String.format("  Use %s to deal %sHP?", unit.toStringWithLocation(), thisDamage));
          thisDamage = findAssaultKills(gameMap, unitQueue, neededAttacks, target, thisDamage);

          // Base case, stop iterating.
          if( thisDamage >= target.getPreciseHP() )
          {
//            log(String.format("    Yes, shoot %s", target.toStringWithLocation()));
            damage = thisDamage;
            break;
          }
          else
          {
//            log(String.format("    Nope"));
            neededAttacks.put(xyc, null);
          }
        }
      }
    }

    return damage;
  }

  private static int valueUnit(Unit unit, Location locale, boolean includeCurrentHealth)
  {
    int value = unit.model.getCost();

    if( includeCurrentHealth )
      value *= unit.getHP();
    value *= (unit.getCaptureProgress() > 0 && locale.isCaptureable()) ? 8 : 1; // Strongly value capturing units
    value -= locale.getEnvironment().terrainType.getDefLevel(); // Value things on lower terrain more, so we wall for equal units if we can get on better terrain

    return value;
  }

  /**
   * Returns the center mass of a given unit type, weighted by HP
   * NOTE: Will violate fog knowledge
   */
  private static XYCoord findAverageDeployLocation(GameMap gameMap, Commander co, UnitModel model)
  {
    // init with the center of the map
    int totalX = gameMap.mapWidth / 2;
    int totalY = gameMap.mapHeight / 2;
    int totalPoints = 1;
    for( Unit unit : co.units )
    {
      if( unit.model == model )
      {
        totalX += unit.x * unit.getHP();
        totalY += unit.y * unit.getHP();
        totalPoints += unit.getHP();
      }
    }

    return new XYCoord(totalX / totalPoints, totalY / totalPoints);
  }

  /**
   * Returns the ideal place to build a unit type or null if it's impossible
   * Kinda-sorta copied from AIUtils
   */
  public XYCoord getLocationToBuild(CommanderProductionInfo CPI, UnitModel model)
  {
    Set<TerrainType> desiredTerrains = CPI.modelToTerrainMap.get(model);
    ArrayList<XYCoord> candidates = new ArrayList<XYCoord>();
    for( Location loc : CPI.availableProperties )
    {
      if( desiredTerrains.contains(loc.getEnvironment().terrainType) )
      {
        candidates.add(loc.getCoordinates());
      }
    }
    if( candidates.isEmpty() )
      return null;

    // Sort locations by how close they are to "center mass" of that unit type, then reverse since we want to distribute our forces
    Utils.sortLocationsByDistance(findAverageDeployLocation(myCo.myView, myCo, model), candidates);
    Collections.reverse(candidates);
    return candidates.get(0);
  }

  private Map<XYCoord, UnitModel> queueUnitProductionActions(GameMap gameMap)
  {
    Map<XYCoord, UnitModel> builds = new HashMap<XYCoord, UnitModel>();
    // Figure out what unit types we can purchase with our available properties.
    boolean includeFriendlyOccupied = true;
    AIUtils.CommanderProductionInfo CPI = new AIUtils.CommanderProductionInfo(myCo, gameMap, includeFriendlyOccupied);

    if( CPI.availableProperties.isEmpty() )
    {
      log("No properties available to build.");
      return builds;
    }

    log("Evaluating Production needs");
    int budget = myCo.money;
    UnitModel infModel = myCo.getUnitModel(UnitModel.TROOP);

    // Get a count of enemy forces.
    Map<Commander, ArrayList<Unit>> unitLists = AIUtils.getEnemyUnitsByCommander(myCo, gameMap);
    Map<UnitModel, Double> enemyUnitCounts = new HashMap<UnitModel, Double>();
    for( Commander co : unitLists.keySet() )
    {
      if( myCo.isEnemy(co) )
      {
        for( Unit u : unitLists.get(co) )
        {
          // Count how many of each model of enemy units are in play.
          if( enemyUnitCounts.containsKey(u.model) )
          {
            enemyUnitCounts.put(u.model, enemyUnitCounts.get(u.model) + (u.getHP() / 10));
          }
          else
          {
            enemyUnitCounts.put(u.model, u.getHP() / 10.0);
          }
        }
      }
    }

    // Figure out how well we think we have the existing threats covered
    Map<UnitModel, Double> myUnitCounts = new HashMap<UnitModel, Double>();
    for( Unit u : myCo.units )
    {
      // Count how many of each model of enemy units are in play.
      if( myUnitCounts.containsKey(u.model) )
      {
        myUnitCounts.put(u.model, myUnitCounts.get(u.model) + (u.getHP() / 10));
      }
      else
      {
        myUnitCounts.put(u.model, u.getHP() / 10.0);
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
      ent.setValue(ent.getValue() * ent.getKey().getCost());
    }

    Queue<Entry<UnitModel, Double>> enemyModels = 
        new PriorityQueue<Entry<UnitModel, Double>>(myCo.unitModels.size(), new UnitModelFundsComparator());
    enemyModels.addAll(enemyUnitCounts.entrySet());

    // Try to purchase units that will counter the most-represented enemies.
    while (!enemyModels.isEmpty() && !CPI.availableUnitModels.isEmpty())
    {
      // Find the first (most funds-invested) enemy UnitModel, and remove it. Even if we can't find an adequate counter,
      // there is not reason to consider it again on the next iteration.
      UnitModel enemyToCounter = enemyModels.poll().getKey();
      double enemyNumber = enemyUnitCounts.get(enemyToCounter);
      log(String.format("Need a counter for %sx%s", enemyToCounter, enemyNumber / enemyToCounter.getCost() / enemyToCounter.maxHP));
      log(String.format("Remaining budget: %s", budget));

      // Get our possible options for countermeasures.
      ArrayList<UnitModel> availableUnitModels = new ArrayList<UnitModel>(CPI.availableUnitModels);
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
          int totalCost = idealCounter.getCost();

          // Calculate a cost buffer to ensure we have enough money left so that no factories sit idle.
          int costBuffer = (CPI.getNumFacilitiesFor(infModel) - 1) * infModel.getCost(); // The -1 assumes we will build this unit from a factory. Possibly untrue.
          if( 0 > costBuffer )
            costBuffer = 0; // No granting ourselves extra moolah.
          if(totalCost <= (budget - costBuffer))
          {
            // Go place orders.
            log(String.format("    I can build %s for a cost of %s (%s remaining, witholding %s)",
                                    idealCounter, totalCost, budget, costBuffer));
            XYCoord coord = getLocationToBuild(CPI, idealCounter);
            builds.put(coord, idealCounter);
            budget -= idealCounter.getCost();
            CPI.removeBuildLocation(gameMap.getLocation(coord));
            // We found a counter for this enemy UnitModel; break and go to the next type.
            // This break means we will build at most one type of unit per turn to counter each enemy type.
            break;
          }
          else
          {
            log(String.format("    %s cost %s, I have %s (witholding %s).", idealCounter, idealCounter.getCost(), budget,
                costBuffer));
          }
        }
      } // ~while( !availableUnitModels.isEmpty() )
    } // ~while( !enemyModels.isEmpty() && !CPI.availableUnitModels.isEmpty())

    // Build infantry from any remaining facilities.
    log("Building infantry to fill out my production");
    while ((budget >= infModel.getCost()) && (CPI.availableUnitModels.contains(infModel)))
    {
      XYCoord coord = getLocationToBuild(CPI, infModel);
      builds.put(coord, infModel);
      budget -= infModel.getCost();
      CPI.removeBuildLocation(gameMap.getLocation(coord));
      log(String.format("  At %s (%s remaining)", coord, budget));
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
      double range = wm.maxRange;
      if( wm.canFireAfterMoving )
        range += getEffectiveMove(target);
      theirRange = Math.max(theirRange, range);
    }
    double counterPower = 0;
    for( WeaponModel wm : model.weapons )
    {
      double damage = wm.getDamage(target);
      double myRange = wm.maxRange;
      if( wm.canFireAfterMoving )
        myRange += getEffectiveMove(model);
      else
        myRange -= (Math.pow(wm.minRange, MIN_SIEGE_RANGE_WEIGHT) - 1); // penalize range based on inner range
      double rangeMod = Math.pow(myRange / theirRange, RANGE_WEIGHT);
      // TODO: account for average terrain defense?
      double effectiveness = damage * rangeMod / 100;
      counterPower = Math.max(counterPower, effectiveness);
    }
    return counterPower;
  }
}
