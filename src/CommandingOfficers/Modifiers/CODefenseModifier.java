package CommandingOfficers.Modifiers;

import Units.UnitModel;

import java.util.ArrayList;

import CommandingOfficers.Commander;
import CommandingOfficers.Modifiers.COModifier.GenericUnitModifier;

public class CODefenseModifier extends GenericUnitModifier
{
  private int defenseModifier = 0;

  public CODefenseModifier(int percentChange)
  {
    defenseModifier = percentChange;
  }

  @Override
  public void modifyUnits(Commander commander, ArrayList<UnitModel> models)
  {
    for( UnitModel um : models )
    {
      if( um.weaponModels != null )
      {
        um.modifyDefenseRatio(defenseModifier);
      }
    }
  }

  @Override
  public void restoreUnits(Commander commander, ArrayList<UnitModel> models)
  {
    for( UnitModel um : models )
    {
      if( um.weaponModels != null )
      {
        um.modifyDefenseRatio(-defenseModifier);
      }
    }
  }
}
