package Engine.Combat;

import java.util.ArrayList;
import java.util.List;

import Engine.XYCoord;
import Engine.UnitMods.UnitModifier;
import Terrain.GameMap;
import Units.ITargetable;
import Units.UnitContext;

/**
 * Utility struct used to facilitate calculating battle results.
 * Parameters are public to allow modification by UnitModifiers.
 * One BattleParams is a single attack from a single unit; any counterattack is a second instance. 
 */
public class StrikeParams
{
  public static BattleParams buildBattleParams(
      UnitContext attacker, UnitContext defender,
      CombatContext combatContext,
      boolean isCounter)
  {
    List<UnitModifier> aMods = new ArrayList<>(attacker.mods);
    List<UnitModifier> dMods = new ArrayList<>(defender.mods);

    BattleParams params = new BattleParams(
        buildStrikeParams(attacker, defender.model,
                          combatContext.gameMap, combatContext.battleRange,
                          defender.coord,
                          isCounter),
        combatContext,
        defender);

    for( UnitModifier mod : aMods )
      mod.modifyUnitAttackOnUnit(params);
    for( UnitModifier mod : dMods )
      mod.modifyUnitDefenseAgainstUnit(params);

    return params;
  }

  public static StrikeParams buildStrikeParams(
      UnitContext attacker, ITargetable defender,
      GameMap gameMap, int battleRange, XYCoord target,
      boolean isCounter)
  {
    List<UnitModifier> aMods = new ArrayList<>(attacker.mods);

    StrikeParams params = new StrikeParams(
        attacker, gameMap, battleRange,
        target,
        ( null == attacker.weapon ) ? 0 : attacker.weapon.getDamage(defender),
        isCounter);

    for( UnitModifier mod : aMods )
      mod.modifyUnitAttack(params);

    return params;
  }


  public final UnitContext attacker;
  public final GameMap map; // for reference, not weirdness

  // Stuff inherited for reference from CombatContext
  public final int battleRange;

  public int baseDamage;
  public int attackerHP;
  public int attackPower;
// These three variables are needed to simulate different games' luck implementations - 1 RN vs 2 RNs
  public int luckBase = 0; // Luck value if you roll 0
  public int luckRolled = 0; // The number we plug into the RNG for luck damage
  public int luckRolledBad = 0; // The number we plug into the RNG for negative luck damage
  public final boolean isCounter;

  public int damageMultiplier = 100;

  public int defenderHP = 0;
  public final XYCoord targetCoord;
  public int defenseSubtraction = 100;
  public int defenseDivision    = 100;
  public int terrainStars = 0;
  public boolean terrainGivesSubtraction = true;

  protected StrikeParams(
      UnitContext attacker,
      GameMap map, int battleRange, XYCoord target,
      int baseDamage,
      boolean isCounter)
  {
    this.attacker = attacker;
    this.map = map;

    this.battleRange = battleRange;

    this.baseDamage = baseDamage;
    this.attackerHP = attacker.getHealth();
    this.attackPower = attacker.attackPower;
    this.luckRolled = attacker.CO.luck;
    this.isCounter = isCounter;

    this.targetCoord = target;
  }
  protected StrikeParams(StrikeParams other)
  {
    this.attacker = other.attacker;
    this.map = other.map;

    this.battleRange = other.battleRange;

    this.baseDamage = other.baseDamage;
    this.attackerHP = other.attackerHP;
    this.attackPower = other.attackPower;
    this.luckRolled = other.luckRolled;
    this.isCounter = other.isCounter;

    this.targetCoord = other.targetCoord;
  }

  public int calculateDamage()
  {
    int luckDamage = getLuck();
    final int rawDamage = baseDamage * attackPower * damageMultiplier / 100 / 100;

    // Apply terrain defense to the correct defense number
    int finalDefenseSubtraction = defenseSubtraction;
    int finalDefenseDivision    = defenseDivision;
    if( terrainGivesSubtraction )
      finalDefenseSubtraction += terrainStars * defenderHP / 10;
    else
      finalDefenseDivision    += terrainStars * defenderHP / 10;
    final int subtractionMultiplier = 200 - finalDefenseSubtraction;

    int overallPower = (rawDamage + luckDamage) * attackerHP / 100;
    overallPower = overallPower * subtractionMultiplier /        100;
    overallPower = overallPower *          100          / finalDefenseDivision;

    return overallPower; // % damage
  }

  protected int getLuck()
  {
    return 0;
  }

  public static class BattleParams extends StrikeParams
  {
    public final CombatContext combatContext;
    public final UnitContext defender;

    protected BattleParams(
        StrikeParams base,
        CombatContext combatContext,
        UnitContext defender)
    {
      super(base);
      this.combatContext = combatContext;
      this.defender = defender;

      defenderHP = defender.getHealth();
      this.defenseSubtraction = defender.defensePower;
      this.terrainStars = defender.terrainStars;
    }

    @Override
    protected int getLuck()
    {
      int luckDamage = 0;
      switch (combatContext.calcType)
      {
        case NO_LUCK:
        case DEMOLITION:
          break;
        case COMBAT:
          luckDamage = getLuckReal();
          break;
        case OPTIMISTIC:
          if( !isCounter )
            luckDamage = getLuckOptimistic();
          else // If the attacker is being optimistic, the defender (countering) should be pessimistic
            luckDamage = getLuckPessimistic();
          break;
        case PESSIMISTIC:
          if( !isCounter ) // Vice versa
            luckDamage = getLuckPessimistic();
          else
            luckDamage = getLuckOptimistic();
          break;
      }
      return luckDamage;
    }
    private int getLuckOptimistic()
    {
      int luckDamage = luckBase;
      luckDamage += luckRolled;
      return luckDamage;
    }
    private int getLuckPessimistic()
    {
      int luckDamage = luckBase;
      luckDamage -= luckRolledBad;
      return luckDamage;
    }
    private int getLuckReal()
    {
      int luckDamage = luckBase;
      if( 0 != luckRolled )
        luckDamage += combatContext.gameInstance.getRN(luckRolled);
      if( 0 != luckRolledBad )
        luckDamage -= combatContext.gameInstance.getRN(luckRolledBad);
      return luckDamage;
    }
  }
}
