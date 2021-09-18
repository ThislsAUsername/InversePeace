package Engine.Combat;

import java.util.HashMap;
import java.util.Map;
import Engine.GamePath;
import Engine.XYCoord;
import Engine.Combat.StrikeParams.BattleParams;
import Engine.UnitActionLifecycles.BattleLifecycle.BattleEvent;
import Terrain.GameMap;
import Terrain.MapLocation;
import Terrain.MapMaster;
import Units.Unit;
import Units.UnitContext;
import Units.WeaponModel;

/**
 * CombatEngine serves as the general-purpose interface into the combat calculation logic.
 */
public class CombatEngine
{
  /**
   * Static function to calculate the outcome of a battle between two units. It builds an
   * object to represent the specific combat instance and returns the result it calculates.
   * Requires perfect map info just in case the COs need to get weird.
   */
  public static BattleSummary calculateBattleResults( UnitContext attacker, UnitContext defender, MapMaster map )
  {
    return calculateBattleResults(attacker, defender, map, false);
  }

  /**
   * Assuming Commanders get weird, this allows for you to check the results of combat without perfect map info.
   * TODO: Check on ability power charge!
   * This also provides un-capped damage estimates, so perfect HP info isn't revealed by the map.
   */
  public static BattleSummary simulateBattleResults( Unit attacker, Unit defender, GameMap map, int attackerX, int attackerY )
  {
    UnitContext attackerContext = new UnitContext(map, attacker, null, attackerX, attackerY );
    UnitContext defenderContext = new UnitContext(map, defender, null, defender.x, defender.y );
    return calculateBattleResults(attackerContext, defenderContext, map, true);
  }
  public static BattleSummary simulateBattleResults( Unit attacker, Unit defender, GameMap map, XYCoord moveCoord )
  {
    return simulateBattleResults(attacker, defender, map, moveCoord.xCoord, moveCoord.yCoord);
  }

  public static StrikeParams calculateTerrainDamage( Unit attacker, GamePath path, MapLocation target, GameMap map )
  {
    int battleRange = path.getEndCoord().getDistance(target.getCoordinates());
    boolean attackerMoved = path.getPathLength() > 1;
    WeaponModel weapon = attacker.chooseWeapon(target, battleRange, attackerMoved);
    UnitContext uc = new UnitContext(map, attacker, weapon, path.getEnd().x, path.getEnd().y);
    return StrikeParams.buildStrikeParams(uc, target, map, battleRange, false);
  }

  /**
   * Calculate and return the results of a battle.
   * <p>This will not actually apply the damage taken; that is done later in {@link BattleEvent}.
   * <p>Requires the coord field be defined for both attacker and defender.
   * @param isSim Determines whether to cap damage at the HP of the victim in question
   * @return A BattleSummary object containing all relevant details from this combat instance.
   */
  public static BattleSummary calculateBattleResults(UnitContext attacker, UnitContext defender,
                                                     GameMap map, boolean isSim)
  {
    int attackerX = attacker.coord.xCoord;
    int attackerY = attacker.coord.yCoord;
    int defenderX = defender.coord.xCoord;
    int defenderY = defender.coord.yCoord;

    int battleRange = Math.abs(attackerX - defenderX) + Math.abs(attackerY - defenderY);

    boolean attackerMoved = false;
    if( null != attacker.path )
      attackerMoved = attacker.path.getPathLength() > 1;
    else if( null != attacker.unit )
      attackerMoved = attacker.unit.x != attacker.coord.xCoord || attacker.unit.y != attacker.coord.yCoord;

    if( null == attacker.weapon )
    {
      attacker.weapon = attacker.unit.chooseWeapon(defender.model, battleRange, attackerMoved);
    }
    if( null == defender.weapon )
    {
      defender.weapon = defender.unit.chooseWeapon(attacker.model, battleRange, false);
    }

    if( attacker.mods.isEmpty() )
      attacker.mods.addAll(attacker.unit.getModifiers());
    if( defender.mods.isEmpty() )
      defender.mods.addAll(defender.unit.getModifiers());

    CombatContext context = new CombatContext(map, attacker, defender, battleRange);

    // unitDamageMap provides an order- and perspective-agnostic view of how much damage was done
    // This is necessary to pass information coherently between this function's local context
    //   and the context of the CombatContext (which can be altered in unpredictable ways).
    Map<UnitContext, Double> unitDamageMap = new HashMap<UnitContext, Double>();
    unitDamageMap.put(attacker, 0.0);
    unitDamageMap.put(defender, 0.0);

    // From here on in, use context variables only

    BattleParams attackInstance = context.getAttack();

    double damage = attackInstance.calculateDamage();
    unitDamageMap.put(context.attacker, damage);

    // New battle instance with defender counter-attacking.
    BattleParams defendInstance = context.getCounterAttack(damage);
    if( null != defendInstance )
    {
      double counterDamage = defendInstance.calculateDamage();
      unitDamageMap.put(context.defender, counterDamage);
    }

    // Calculations complete.
    // Since we are setting up our BattleSummary, use non-CombatContext variables
    //   so consumers of the Summary will see results consistent with the current board/map state
    //   (e.g. the Unit 'attacker' actually belongs to the CO whose turn it currently is)
    return new BattleSummary(attacker.unit, attacker.weapon,
                             defender.unit, defender.weapon,
                             map.getEnvironment(attackerX, attackerY).terrainType,
                             map.getEnvironment(defenderX, defenderY).terrainType,
                             unitDamageMap.get(defender), unitDamageMap.get(attacker));
  }

  public static double calculateOneStrikeDamage( Unit attacker, int battleRange, Unit defender, GameMap map, int terrainStars, boolean attackerMoved )
  {
    return StrikeParams.buildBattleParams(
        new UnitContext(map, attacker, attacker.chooseWeapon(defender.model, battleRange, attackerMoved), attacker.x, attacker.y),
        new UnitContext(map, defender, null, defender.x, defender.y),
        map, battleRange,
        false).calculateDamage();
  }
}
