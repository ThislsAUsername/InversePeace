package CommandingOfficers;

import java.util.ArrayList;
import CommandingOfficers.Modifiers.CODamageModifier;
import Engine.GameInstance;
import Engine.GameScenario;
import Engine.GameEvents.GameEventListener;
import Engine.GameEvents.GameEventQueue;
import Engine.UnitMods.DamageDealtToIncomeConverter;
import Engine.UnitMods.UnitModifier;
import Terrain.MapLocation;
import Terrain.MapMaster;
import Units.*;

public class Patch extends Commander
{
  private static final long serialVersionUID = 1L;

  private static final CommanderInfo coInfo = new instantiator();
  private static class instantiator extends CommanderInfo
  {
    private static final long serialVersionUID = 1L;
    public instantiator()
    {
      super("Patch");
      infoPages.add(new InfoPage(
          "Commander Patch is a pirate, who does piratey things like lootin' and plunderin'\n"));
      infoPages.add(new InfoPage(
          "Passive:\n" +
          "Instantly gain 1 turn's worth of income from any property he captures\n"));
      infoPages.add(new InfoPage(
          "Plunder ("+PLUNDER_COST+"):\n" +
          "+"+PLUNDER_ATTACK_BUFF+"% attack for all units\n" +
          "Gains "+(100*PLUNDER_INCOME)+"% of the value of any damage dealt\n"));
      infoPages.add(new InfoPage(
          "Pillage ("+PILLAGE_COST+"):\n" +
          "+"+PILLAGE_ATTACK_BUFF+"% attack for all units\n" +
          "Gains "+(100*PILLAGE_INCOME)+"% of the value of any damage dealt\n"));
    }
    @Override
    public Commander create(GameScenario.GameRules rules)
    {
      return new Patch(rules);
    }
  }

  // Variables to characterize Patch's abilities.
  private static final String PLUNDER_NAME = "Plunder";
  private static final int PLUNDER_COST = 3;
  private static final double PLUNDER_INCOME = 0.25;
  private static final int PLUNDER_ATTACK_BUFF = 10;

  private static final String PILLAGE_NAME = "Pillage";
  private static final int PILLAGE_COST = 6;
  private static final double PILLAGE_INCOME = 0.5;
  private static final int PILLAGE_ATTACK_BUFF = 25;

  private LootAbility myLootAbility = null;
  private final ArrayList<PatchAbility> patchPowers;

  public Patch(GameScenario.GameRules rules)
  {
    super(coInfo, rules);

    patchPowers = new ArrayList<>();
    patchPowers.add(new PatchAbility(PLUNDER_NAME, PLUNDER_COST, PLUNDER_INCOME, PLUNDER_ATTACK_BUFF));
    patchPowers.add(new PatchAbility(PILLAGE_NAME, PILLAGE_COST, PILLAGE_INCOME, PILLAGE_ATTACK_BUFF));

    for( PatchAbility pa : patchPowers )
      addCommanderAbility(pa);
  }

  @Override
  public void registerForEvents(GameInstance game)
  {
    super.registerForEvents(game);
    // Passive - Loot
    myLootAbility = new LootAbility(this);
    myLootAbility.registerForEvents(game);
    // Get cash from fightan during powers
    DamageDealtToIncomeConverter incomeTracker = DamageDealtToIncomeConverter.initialize(game, DamageDealtToIncomeConverter.class);
    for( PatchAbility pa : patchPowers )
      pa.init(incomeTracker);
  }
  @Override
  public void unregister(GameInstance game)
  {
    super.unregister(game);
    myLootAbility.unregister(game);
  }

  public static CommanderInfo getInfo()
  {
    return coInfo;
  }

  /**
   * LootAbility is a passive that grants its Commander a day's income immediately upon capturing a
   * property, so long as that property is one that generates income.
   */
  private static class LootAbility implements GameEventListener
  {
    private static final long serialVersionUID = 1L;
    private Patch myCommander = null;

    public LootAbility(Patch myCo)
    {
      myCommander = myCo;
    }

    @Override
    public GameEventQueue receiveCaptureEvent(Unit unit, MapLocation location)
    {
      if( unit.CO == myCommander && location.getOwner() == myCommander && location.isProfitable() )
      {
        // We just successfully captured a property. Loot the place!
        myCommander.money += myCommander.gameRules.incomePerCity;
      }
      return null;
    }
  }

  /** PatchAbility gives Patch a damage bonus, and also grants
   * income based on damage inflicted to enemies. */
  private static class PatchAbility extends CommanderAbility
  {
    private static final long serialVersionUID = 1L;
    private CODamageModifier damageBuff = null;
    private final double myIncomeRatio;
    private DamageDealtToIncomeConverter tracker;

    PatchAbility(String abilityName, int abilityCost, double incomeRatio, int unitBuff)
    {
      super(abilityName, abilityCost);

      myIncomeRatio = incomeRatio;

      // Create a COModifier that we can apply to Patch when needed.
      damageBuff = new CODamageModifier(unitBuff);
    }
    public void init(DamageDealtToIncomeConverter incomeTracker)
    {
      tracker = incomeTracker;
    }

    @Override
    protected void enqueueCOMods(Commander co, MapMaster gameMap, ArrayList<UnitModifier> modList)
    {
      modList.add(damageBuff);
    }

    @Override
    protected void perform(Commander co, MapMaster gameMap)
    {
      tracker.startTracking(co, myIncomeRatio);
    }

    @Override
    public void revert(Commander co, MapMaster gameMap)
    {
      tracker.stopTracking(co, myIncomeRatio);
    }
  }

}
