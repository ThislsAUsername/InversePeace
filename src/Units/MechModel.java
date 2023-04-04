package Units;

import Engine.GameAction.ActionType;
import Units.MoveTypes.FootMech;
import Units.MoveTypes.MoveType;
import Units.Weapons.MechMGun;
import Units.Weapons.MechZooka;
import Units.Weapons.WeaponModel;

public class MechModel extends UnitModel
{
  private static final int UNIT_COST = 2500;
  private static final int MAX_FUEL = 99;
  private static final int IDLE_FUEL_BURN = 0;
  private static final int MOVE_POWER = 2;

  private static final MoveType moveType = new FootMech();
  private static final ActionType[] actions = { ActionType.ATTACK, ActionType.CAPTURE, ActionType.WAIT };
  private static final WeaponModel[] weapons = { new MechZooka(), new MechMGun() };

  public MechModel()
  {
    super("Mech", Units.UnitModel.UnitEnum.MECH, ChassisEnum.TROOP, UNIT_COST, MAX_FUEL, IDLE_FUEL_BURN, MOVE_POWER, moveType, actions, weapons);
  }
}