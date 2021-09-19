package Engine.UnitMods;

import Engine.XYCoord;
import Engine.Combat.CombatContext;
import Engine.Combat.StrikeParams;
import Engine.Combat.StrikeParams.BattleParams;
import Units.UnitModel;

/**
 * UnitModifiers exist to represent transient or conditional changes in a unit's properties.
 * <p>They are expected to have some external framework in place to manage their lifetimes.
 * <p>This provides a dynamic alternative to COModifiers' static stat modification.
 */
public interface UnitModifier
{
  /**
   * Allows a UnitModifier to make drastic combat changes like counterattacking first or at 2+ range.
   * <p>Prefer using the other combat hooks when feasible.
   */
  default void changeCombatContext(CombatContext instance)
  {
  }

  /**
   * Called any time a unit makes a weapon attack;
   *   applies to all potential targets, whether they be units or not.
   * <p>Should be used to modify attacks from units
   *   any time you do not need specific information about the target.
   */
  default void modifyUnitAttack(StrikeParams params)
  {
  }

  /**
   * Called any time you are attacking a unit, always after {@link #modifyUnitAttack(StrikeParams)}
   * <p>Applies only when attacking a unit.
   * <p>Should be used only when you need specific information about your target.
   */
  default void modifyUnitAttackOnUnit(BattleParams params)
  {
  }

  /**
   * Called any time your unit is being attacked, after {@link #modifyUnitAttackOnUnit(BattleParams)}
   * <p>Should be used to modify attacks made against your units.
   */
  default void modifyUnitDefenseAgainstUnit(BattleParams params)
  {
  }

  default int getPriceOffset(XYCoord coord, UnitModel um, int currentPrice)
  {
    return 0;
  }

}