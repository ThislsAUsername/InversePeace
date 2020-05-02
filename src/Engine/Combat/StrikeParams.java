package Engine.Combat;

import Terrain.GameMap;

/**
 * Utility struct used to facilitate calculating battle results.
 * Parameters are public to allow modification in Commander.applyCombatModifiers()
 * One BattleParams is a single attack from a single unit; any counterattack is a second instance. 
 */
public class StrikeParams
{
  public final Combatant attacker;

  // Stuff inherited for reference from CombatContext
  public final int battleRange;

  public double baseDamage;
  public double attackerHP;
  public double attackPower;
  public final boolean isCounter;

  public static BattleParams getAttack(final CombatContext ref)
  {
    return new BattleParams(
        new Combatant(ref.attacker, ref.attackerWeapon, ref.attackerX, ref.attackerY),
        new Combatant(ref.defender, ref.defenderWeapon, ref.defenderX, ref.defenderY),
        ref.gameMap, ref.battleRange,
        ref.attacker.model.getDamageRatio(),
        ref.defender.model.getDefenseRatio(), ref.defenderTerrainStars,
        false);
  }
  public static BattleParams getCounterAttack(final CombatContext ref)
  {
    return new BattleParams(
        new Combatant(ref.defender, ref.defenderWeapon, ref.defenderX, ref.defenderY),
        new Combatant(ref.attacker, ref.attackerWeapon, ref.attackerX, ref.attackerY),
        ref.gameMap, ref.battleRange,
        ref.defender.model.getDamageRatio(),
        ref.attacker.model.getDefenseRatio(), ref.attackerTerrainStars,
        true);
  }

  public StrikeParams(
      Combatant attacker,
      int battleRange,
      double attackPower, double baseDamage,
      boolean isCounter)
  {
    this.attacker = attacker;

    this.battleRange = battleRange;
    this.attackPower = attackPower;
    this.isCounter = isCounter;
    this.baseDamage = baseDamage;

    attackerHP = attacker.body.getHP();

    // Apply any last-minute adjustments.
    attacker.body.CO.buffStrike(this);
  }

  public double calculateDamage()
  {
    //    [B*ACO/100+R]*(AHP/10)*[(200-(DCO+DTR*DHP))/100]
    double overallPower = (baseDamage * attackPower / 100/*+Random factor?*/) * attackerHP / 10;
    return overallPower / 10; // original formula was % damage, now it must be HP of damage
  }

  public static class BattleParams extends StrikeParams
  {
    public final Combatant defender;
    public final GameMap map; // for reference, not weirdness

    public double defenderHP;
    public double defensePower;
    public double terrainStars;

    public BattleParams(
        Combatant attacker, Combatant defender,
        GameMap map, int battleRange,
        double attackPower,
        double defensePower, double terrainStars,
        boolean isCounter)
    {
      super(attacker,
          battleRange,
          attackPower, (null == attacker.gun)? 0 : attacker.gun.getDamage(defender.body.model),
          isCounter);
      this.defender = defender;
      this.map = map;

      this.attackPower = attackPower;

      this.defensePower = defensePower;
      this.terrainStars = terrainStars;
      attackerHP = attacker.body.getHP();
      defenderHP = defender.body.getHP();

      // Apply any last-minute adjustments.
      attacker.body.CO.buffAttack(this);
      defender.body.CO.buffDefense(this);
    }

    @Override
    public double calculateDamage()
    {
      double overallDefense = ((200 - (defensePower + terrainStars * defenderHP)) / 100);
      return super.calculateDamage() * overallDefense;
    }
  }
}
