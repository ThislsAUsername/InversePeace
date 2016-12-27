package CommandingOfficers;

import Units.UnitModel;
import CommandingOfficers.Modifiers.CODamageModifier;
import CommandingOfficers.Modifiers.COModifier;
import CommandingOfficers.Modifiers.COMovementModifier;
import Engine.Combat.CombatEngine;
import Engine.Combat.StrongCombatModifier;

public class CommanderStrong extends Commander
{
  private static final CommanderInfo coInfo = new CommanderInfo("Strong", CommanderLibrary.CommanderEnum.STRONG);
  StrongCombatModifier strongMod;
  
  public CommanderStrong()
  {
    super(coInfo);

    // TODO: Super alpha values. Need balance.
    starsMinor = 3;
    starsMax = 6;
    starsCurrent = 6;

    // Set Cmdr Strong up with a base damage buff and long-range APCs. These COModifiers are
    // not added to the modifers collection so they will not be reverted.
    // COModifier strongMod = new CODamageModifier(20); // Give us a nice base power boost.
    // strongMod.apply(this);
    strongMod = new StrongCombatModifier(this,20);
    CombatEngine.modifiers.add(strongMod);
    

    COMovementModifier moveMod = new COMovementModifier();
    moveMod.addApplicableUnitType(UnitModel.UnitEnum.APC);
    moveMod.apply(this);
  }

  public void doAbilityMinor()
  {
    //COModifier strongerMod = new CODamageModifier(20); // Another 20% with the minor ability active.
    //strongerMod.apply(this);
    //modifiers.add(strongerMod); // Add to the list so the modifier can be reverted next turn.
    starsCurrent -= starsMinor;
    powerActive = true;
    strongMod.multiplyBoost(2);
  }

  public void doAbilityMajor()
  {
    //COModifier strongestMod = new CODamageModifier(40); // An extra 40% with the major ability active.
    //strongestMod.apply(this);
    //modifiers.add(strongestMod); // Add to the list so the modifier can be reverted next turn.
    starsCurrent = 0;
    powerActive = true;
    strongMod.multiplyBoost(3);
  }

  public static CommanderInfo getInfo()
  {
    return coInfo;
  }
}
