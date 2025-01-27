package Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import CommandingOfficers.Commander;
import CommandingOfficers.DefendPeace.CyanOcean.Patch;
import CommandingOfficers.DefendPeace.RoseThorn.Strong;
import Engine.Army;
import Engine.GameInstance;
import Engine.GameScenario;
import Terrain.MapMaster;
import Units.Unit;
import Units.UnitModel;

public class TestSaveLoad extends TestCase
{
  private static Commander strong = null;
  private static Commander patch = null;
  private static MapMaster testMap = null;
  private static GameInstance game = null;

  /** Make two COs and a MapMaster to use with this test case. */
  private void setupTest()
  {
    GameScenario scn = new GameScenario();
    strong = new Strong(scn.rules);
    patch = new Patch(scn.rules);
    Army[] cos = { new Army(scn, strong), new Army(scn, patch) };

    testMap = new MapMaster(cos, Terrain.Maps.FiringRange.getMapInfo());

    game = new GameInstance(cos, testMap);
  }

  @Override
  public boolean runTest()
  {
    setupTest();

    boolean testPassed = true;
    testPassed &= validate(testSaveLoad(), "  Save/load test failed!");
    
    return testPassed;
  }

  private boolean testSaveLoad()
  {
    Unit fool = addUnit(testMap, strong, UnitModel.TRANSPORT, 7, 2); fool.initTurn(testMap);
    Unit scout = addUnit(testMap, strong, UnitModel.RECON, 7, 3); scout.initTurn(testMap);
    Unit punch = addUnit(testMap, strong, UnitModel.SIEGE, 4, 5); punch.initTurn(testMap);
    Unit resupplyable = addUnit(testMap, strong, UnitModel.TRANSPORT, 8, 8); resupplyable.initTurn(testMap);
    resupplyable.fuel = 0;
    
    Unit bait = addUnit(testMap, patch, UnitModel.TRANSPORT, 6, 5);
    Unit meaty = addUnit(testMap, patch, UnitModel.ASSAULT, 7, 5);
    
    // It's Strong's turn. Set up his fog goggles.
    turn(game);
    boolean testPassed = true;

    // Since running actual combat would be complicated, just grab some RNs
    for( int i = 0; i < 42; ++i )
    {
      game.getRN(42);
    }

    byte[] bytes = null;
    try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
         ObjectOutputStream out = new ObjectOutputStream(baos);)
    {
      game.writeSave(out, false);
      bytes = baos.toByteArray();
    }
    catch (IOException ex)
    {
      System.out.println(ex.toString());
      testPassed = false;
    }
    testPassed &= validate(null != bytes && bytes.length > 0,  "    Failed to generate serialized game instance.");

    if( !testPassed )
      return testPassed;

    // test save compatibility
    try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
         ObjectInputStream in = new ObjectInputStream(bais);)
    {
      testPassed &= validate(GameInstance.getSaveWarnings(in).length() == 0,  "    We are incompatible with the save we just made");
    }
    catch (Exception ex)
    {
      System.out.println(ex.toString());
      testPassed = false;
    }

    GameInstance loaded = null;
    try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
         ObjectInputStream in = new ObjectInputStream(bais);)
    {
      in.readObject(); // Pull out and discard our version info
      loaded = (GameInstance) in.readObject();
    }
    catch (Exception ex)
    {
      System.out.println(ex.toString());
      testPassed = false;
    }
    testPassed &= validate(null != loaded,  "    The save didn't actually load");

    for( int i = 0; i < 42; ++i )
    {
      int oldRN = game.getRN(42);
      int newRN = loaded.getRN(42);
      testPassed &= validate(oldRN == newRN,  "    The #"+i+" RN didn't match for seed "+game.rngSeed+" vs "+loaded.rngSeed);
    }

    // Clean up
    testMap.removeUnit(fool);
    testMap.removeUnit(scout);
    testMap.removeUnit(punch);
    testMap.removeUnit(resupplyable);
    
    testMap.removeUnit(bait);
    testMap.removeUnit(meaty);

    return testPassed;
  }
}
