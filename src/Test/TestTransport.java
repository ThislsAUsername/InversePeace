package Test;

import java.util.ArrayList;

import CommandingOfficers.Commander;
import CommandingOfficers.Patch;
import CommandingOfficers.Strong;
import Engine.GameInstance;
import Engine.GameScenario;
import Engine.Utils;
import Engine.XYCoord;
import Engine.UnitActionLifecycles.LoadLifecycle;
import Engine.UnitActionLifecycles.UnloadLifecycle;
import Terrain.MapLibrary;
import Terrain.MapMaster;
import Units.Unit;
import Units.UnitModel;

public class TestTransport extends TestCase
{
  private static Commander testCo1;
  private static Commander testCo2;
  private static MapMaster testMap;
  private static GameInstance testGame;

  /** Make two COs and a MapMaster to use with this test case. */
  private void setupTest()
  {
    GameScenario scn = new GameScenario();
    testCo1 = new Strong(scn.rules);
    testCo2 = new Patch(scn.rules);
    Commander[] cos = { testCo1, testCo2 };

    testMap = new MapMaster(cos, MapLibrary.getByName("Firing Range"));
    testGame = new GameInstance(testMap);
  }

  @Override
  public boolean runTest()
  {
    setupTest();

    boolean testPassed = validate(testLoadUnloadAPC(), "  Transport test failed.");
    return testPassed;
  }

  /**
   * Basic load/unload APC transport test.
   * @return
   */
  private static boolean testLoadUnloadAPC()
  {
    // Add a couple of units to drive this test.
    Unit cargo = addUnit(testMap, testCo1, UnitModel.TROOP, 4, 1);
    Unit apc = addUnit(testMap, testCo1, UnitModel.TRANSPORT, 4, 2);

    boolean testPassed = true;

    // Try a basic load/move/unload order.
    cargo.initTurn(testMap); // Get him ready.
    testPassed &= validate(Utils.findPossibleDestinations(cargo, testMap, true).contains(new XYCoord(apc.x, apc.y)), "    Cargo can't actually enter transport's square.");
    performGameAction(new LoadLifecycle.LoadAction(testMap, cargo, Utils.findShortestPath(cargo, 4, 2, testMap)), testGame);
    testPassed &= validate(testMap.getLocation(4, 2).getResident() != cargo, "    Cargo is still on the map.");
    testPassed &= validate(apc.heldUnits.size() == 1, "    APC is not holding a unit.");
    apc.initTurn(testMap); // Get him ready.
    performGameAction(new UnloadLifecycle.UnloadAction(testMap, apc, Utils.findShortestPath(apc, 7, 3, testMap), cargo, 7, 4), testGame);
    testPassed &= validate(testMap.getLocation(7, 4).getResident() == cargo, "    Cargo was not dropped off correctly.");
    testPassed &= validate(apc.heldUnits.isEmpty(), "    APC is not empty when it should be.");

    // Make sure the unit knows it can unload to its own position.
    ArrayList<XYCoord> unloadLocs = Utils.findUnloadLocations( testMap, apc, new XYCoord(7, 4), cargo);
    testPassed &= validate(unloadLocs.contains(new XYCoord(apc.x, apc.y) ), "    APC doesn't know it can unload to its own position.");

    // Make sure we can unload a unit on the apc's current location.
    cargo.initTurn(testMap);
    apc.initTurn(testMap);
    performGameAction(new LoadLifecycle.LoadAction(testMap, cargo, Utils.findShortestPath(cargo, 7, 3, testMap)), testGame);
    performGameAction(new UnloadLifecycle.UnloadAction(testMap, apc, Utils.findShortestPath(apc, 7, 4, testMap), cargo, 7, 3), testGame);
    testPassed &= validate(testMap.getLocation(7, 4).getResident() == apc, "    APC is not where it belongs.");
    testPassed &= validate(testMap.getLocation(7, 3).getResident() == cargo, "    Cargo is not at dropoff location");

    // Try to init a damaged unit inside the transport.
    cargo.alterHP(-5);
    testPassed &= validate( cargo.getHP() == 5, "    Cargo has the wrong amount of HP(" + cargo.getHP() + ")");
    cargo.initTurn(testMap);
    performGameAction(new LoadLifecycle.LoadAction(testMap, cargo, Utils.findShortestPath(cargo, 7, 4, testMap)), testGame);
    testPassed &= validate(testMap.getLocation(7, 4).getResident() != cargo, "    Cargo is not in the APC.");
    testPassed &= validate(apc.heldUnits.size() == 1, "    APC has the wrong cargo size (");

    // Calling init on the cargo caused a NPE before, so let's test that case.
    try
    {
      apc.initTurn(testMap);
      cargo.initTurn(testMap);
    }
    catch( NullPointerException npe )
    {
      testPassed = false;
      validate( testPassed, "    NPE encountered during unit init. Details:");
      npe.printStackTrace();
    }

    // Clean up
    testMap.removeUnit(cargo);
    testMap.removeUnit(apc);

    return testPassed;
  }
}
