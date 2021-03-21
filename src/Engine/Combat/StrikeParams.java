package Engine.Combat;

import java.util.List;

import Engine.UnitMods.UnitModifier;
import Terrain.GameMap;

/**
 * Utility struct used to facilitate calculating battle results.
 * Parameters are public to allow modification by UnitModifiers.
 * One BattleParams is a single attack from a single unit; any counterattack is a second instance. 
 */
public class StrikeParams
{
  public final Combatant attacker;
  public final GameMap map; // for reference, not weirdness

  // Stuff inherited for reference from CombatContext
  public final int battleRange;

  public double baseDamage;
  public double attackerHP;
  public double attackPower;
  public final boolean isCounter;

  public double defenderHP = 0;
  public double defensePower = 100;
  public double terrainStars = 0;

  public static BattleParams getAttack(final CombatContext ref)
  {
    return new BattleParams(
        new Combatant(ref.attacker, ref.attackerWeapon, ref.attackerX, ref.attackerY), ref.attackerMods,
        new Combatant(ref.defender, ref.defenderWeapon, ref.defenderX, ref.defenderY), ref.defenderMods,
        ref.gameMap, ref.battleRange,
        ref.attacker.model.getDamageRatio(), ref.attacker.getHP(),
        ref.defender.model.getDefenseRatio(), ref.defenderTerrainStars,
        false);
  }
  public static BattleParams getCounterAttack(final CombatContext ref, double counterHP)
  {
    return new BattleParams(
        new Combatant(ref.defender, ref.defenderWeapon, ref.defenderX, ref.defenderY), ref.defenderMods,
        new Combatant(ref.attacker, ref.attackerWeapon, ref.attackerX, ref.attackerY), ref.attackerMods,
        ref.gameMap, ref.battleRange,
        ref.defender.model.getDamageRatio(), counterHP,
        ref.attacker.model.getDefenseRatio(), ref.attackerTerrainStars,
        true);
  }

  public StrikeParams(
      Combatant attacker, List<UnitModifier> attackerMods,
      GameMap map, int battleRange,
      double attackPower, double attackerHP,
      double baseDamage,
      boolean isCounter)
  {
    this.attacker = attacker;
    this.map = map;

    this.battleRange = battleRange;
    this.attackPower = attackPower;
    this.isCounter = isCounter;
    this.baseDamage = baseDamage;

    this.attackerHP = attackerHP;

    // Apply any last-minute adjustments.
    for(UnitModifier mod : attackerMods)
      mod.modifyUnitAttack(this);
  }

  public double calculateDamage()
  {
    //    [B*ACO/100+R]*(AHP/10)*[(200-(DCO+DTR*DHP))/100]
    double overallPower = (baseDamage * attackPower / 100/*+Random factor?*/) * attackerHP / 10;
    double overallDefense = ((200 - (defensePower + terrainStars * defenderHP)) / 100);
    return overallPower * overallDefense / 10; // original formula was % damage, now it must be HP of damage
  }

  public static class BattleParams extends StrikeParams
  {
    public final Combatant defender;

    public BattleParams(
        Combatant attacker, List<UnitModifier> attackerMods,
        Combatant defender, List<UnitModifier> defenderMods,
        GameMap map, int battleRange,
        double attackPower, double attackerHP,
        double defensePower, double terrainStars,
        boolean isCounter)
    {
      super(attacker, attackerMods,
          map, battleRange,
          attackPower, attackerHP,
          (null == attacker.gun)? 0 : attacker.gun.getDamage(defender.body.model),
          isCounter);
      this.defender = defender;

      this.defensePower = defensePower;
      this.terrainStars = terrainStars;
      defenderHP = defender.body.getHP();

      // Apply any last-minute adjustments.
      for(UnitModifier mod : attackerMods)
        mod.modifyUnitAttackOnUnit(this);
      for(UnitModifier mod : defenderMods)
        mod.modifyUnitDefenseAgainstUnit(this);
    }
  }
}
