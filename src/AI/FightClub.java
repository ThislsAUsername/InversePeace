package AI;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import CommandingOfficers.Commander;
import CommandingOfficers.CommanderInfo;
import CommandingOfficers.Patch;
import Engine.GameAction;
import Engine.GameInstance;
import Engine.GameScenario;
import Engine.GameEvents.GameEvent;
import Engine.GameEvents.GameEventListener;
import Engine.GameEvents.GameEventQueue;
import Terrain.Environment.Weathers;
import Terrain.MapInfo;
import Terrain.MapLibrary;
import Terrain.MapMaster;
import UI.UIUtils;

public class FightClub
{
  public static void main(String[] args)
  {
    MapLibrary.getMapList();
    System.out.println();

    // Select map(s).
    //List<MapInfo> maps = MapLibrary.getMapList();
    List<MapInfo> maps = Arrays.asList(MapLibrary.getByName("Firing Range"), MapLibrary.getByName("Aria of War"));
//    List<MapInfo> maps = Arrays.asList(MapLibrary.getByName("Shadows Chase You Endlessly"),
//                                       MapLibrary.getByName("Blood on my Hands"),
//                                       MapLibrary.getByName("Aria of War"));
    // How many bouts per map?
    int numGamesPerSet = 3;
    // Select CO(s).
    List<CommanderInfo> COs = Arrays.asList(Patch.getInfo(), Patch.getInfo());
    // Select AI(s).
    List<AIMaker> AIs = Arrays.asList(Muriel.info, Muriel.info);

    // Run a set of games on each map.
    for( int setNum = 0; setNum < maps.size(); ++setNum )
    {
      MapInfo setMap = maps.get(setNum);
      System.out.println("Starting set " + setNum + " on " + setMap.mapName);
      GameSet set = new GameSet(new GameSetParams(setMap, numGamesPerSet, COs, AIs));
      set.run();
    }

    System.out.println("All sets complete!");
  }

  static class ContestantInfo
  {
    AIMaker myAi;
    CommanderInfo myCo;
    ContestantInfo(CommanderInfo co, AIMaker ai)
    {
      myCo = co;
      myAi = ai;
    }
  }

  static class GameSetResults
  {
    String mapName;
    HashMap<ContestantInfo, Integer> scores = new HashMap<ContestantInfo, Integer>();

    public GameSetResults(String mapName, List<ContestantInfo> contestants)
    {
      this.mapName = mapName;
      for(ContestantInfo cc : contestants)
        scores.put(cc, 0);
    }

    public void incrementScore(ContestantInfo cInfo)
    {
      scores.put(cInfo, scores.get(cInfo) + 1);
    }

    @Override
    public String toString()
    {
      StringBuffer sb = new StringBuffer();
      ContestantInfo[] cInfos = scores.keySet().toArray(new ContestantInfo[0]);
      sb.append(String.format("%s (%s)", cInfos[0].myAi.getName(), cInfos[0].myCo.name));
      for( int cc = 1; cc < cInfos.length; ++cc)
        sb.append(String.format(" vs %s (%s)", cInfos[0].myAi.getName(), cInfos[cc].myCo.name));
      sb.append(String.format(" on %s\n", mapName));
      sb.append(String.format("  %d", scores.get(cInfos[0])));
      for( int cc = 1; cc < cInfos.length; ++cc)
        sb.append(String.format(" to %d", scores.get(cInfos[cc])));
      sb.append('\n');
      return sb.toString();
    }
  }

  static class GameSetParams
  {
    // Primary settings; must be provided via the constructor.
    MapInfo mapInfo;
    int numGames = 3;
    List<CommanderInfo> COs;
    List<AIMaker> AIs;

    // Additional settings to mess with. Optional to provide, but public so defaults can be overridden.
    boolean isFogOn = false;
    Weathers defaultWeather = Weathers.CLEAR;

    public GameSetParams(MapInfo map, int nGames, List<CommanderInfo> cos, List<AIMaker> ais)
    {
      mapInfo = map;
      numGames = nGames;
      COs = cos;
      AIs = ais;
    }
  }

  static class GameSet
  {
    GameSetParams params;

    public GameSet(GameSetParams gameSetParams)
    {
      params = gameSetParams;
    }

    public void run()
    {
      // suppress normal printing to avoid spam and speed stuff up a lil'
      PrintStream defaultOut = System.out;
      System.setOut(new PrintStream(new OutputStream(){
        @Override
        public void write(int b) throws IOException{}
      }));

      MapInfo mi = params.mapInfo;
      List<ContestantInfo> contestants = new ArrayList<ContestantInfo>();
      for( int cc = 0; cc < params.COs.size(); cc++)
        contestants.add(new ContestantInfo(params.COs.get(cc), params.AIs.get(cc)));
      GameSetResults results = new GameSetResults(mi.mapName, contestants);

      for( int gameIndex = 0; gameIndex < params.numGames; ++gameIndex )
      {
        GameScenario scenario = new GameScenario(mi.getValidUnitModelSchemes()[0],
            GameScenario.DEFAULT_INCOME, GameScenario.DEFAULT_STARTING_FUNDS, false);

        int numCos = mi.getNumCos();

        // Create all of the combatants.
        HashMap<Integer, ContestantInfo> teamMapping = new HashMap<Integer, ContestantInfo>(); // TODO: This currently doesn't work for team games. 
        List<Commander> combatants = new ArrayList<Commander>();
        for( int cc = 0; cc < contestants.size(); ++cc){
          ContestantInfo cInfo = contestants.get(cc);
          Commander com = cInfo.myCo.create(scenario.rules);
          com.team = cc;
          com.myColor = UIUtils.getCOColors()[cc];
          com.faction = UIUtils.getFactions()[cc];
          com.setAIController(cInfo.myAi.create(com));
          combatants.add(com);
          teamMapping.put(cc, cInfo);
          defaultOut.println("Adding " + com.coInfo.name);
        }

        if( numCos != combatants.size() )
        {
          defaultOut.println(String.format("WARNING: Wrong number of COs specified for this map (expected %d, got %d)!", numCos, combatants.size()));
          return;
        }

        defaultOut.println("  Starting game on map " + mi.mapName + " with combatants:");
        for( int i = 0; i < numCos; ++i )
          defaultOut.println("    team " + combatants.get(i).team + ": "
                               + contestants.get(i).myAi.getName() + " controlling " + contestants.get(i).myCo.name);

        // Build the CO list and the new map and create the game instance.
        MapMaster map = new MapMaster(combatants.toArray(new Commander[0]), mi);
        GameInstance newGame = null;
        if( map.initOK() )
        {
          newGame = new GameInstance(map, params.defaultWeather, scenario, false);
        }

        List<Commander> winners = runGame(newGame, defaultOut);
        int winningTeam = winners.get(0).team;
        defaultOut.println("  Game " + gameIndex + " complete; winning team is: " + winningTeam);
        defaultOut.println();
//        defaultOut.println("Winners:");
//        for( Commander winner : winners )
//          defaultOut.println("\t" + winner.coInfo.name);

        results.incrementScore(teamMapping.get(winningTeam));
      }

      defaultOut.println("Set results:");
      defaultOut.println(results);
      System.setOut(defaultOut);
    }

    /**
     * @return The winning team
     */
    public List<Commander> runGame(GameInstance game, PrintStream defaultOut)
    {
      boolean isGameOver = false;
      while (!isGameOver)
      {
        startNextTurn(game, defaultOut);

        GameEventQueue actionEvents = new GameEventQueue();
        boolean endAITurn = false;
        while (!endAITurn && !isGameOver)
        {
          GameAction aiAction = game.activeCO.getNextAIAction(game.gameMap);
          if( aiAction != null )
          {
            if( !executeGameAction(aiAction, actionEvents, game, defaultOut) )
            {
              // If aiAction fails to execute, the AI's turn is over. We don't want
              // to waste time getting more actions if it can't build them properly.
              defaultOut.println("WARNING! AI Action " + aiAction.toString() + " Failed to execute!");
              endAITurn = true;
            }
          }
          else
          {
            endAITurn = true;
          }

          while (!isGameOver && !actionEvents.isEmpty())
          {
            executeEvent(actionEvents.poll(), actionEvents, game, defaultOut);
          }

          // If we are done animating the last action, check to see if the game is over.
          // Count the number of COs that are left.
          int activeNum = 0;
          for( int i = 0; i < game.commanders.length; ++i )
          {
            if( !game.commanders[i].isDefeated )
            {
              activeNum++;
            }
          }

          // If fewer than two COs yet survive, the game is over.
          isGameOver = activeNum < 2;
        }

        // Map should-ish be covered in units by turncount == map area
        if(game.getCurrentTurn() > game.gameMap.mapWidth * game.gameMap.mapHeight)
        {
          if(game.commanders[0].units.size()/2 > game.commanders[1].units.size() )
          {
            game.commanders[1].isDefeated = true;
            isGameOver = true;
          }
          if(game.commanders[1].units.size()/2 > game.commanders[0].units.size() )
          {
            game.commanders[0].isDefeated = true;
            isGameOver = true;
          }
        }
      }

      ArrayList<Commander> winners = new ArrayList<Commander>();
      for( int i = 0; i < game.commanders.length; ++i )
      {
        if( !game.commanders[i].isDefeated )
          winners.add(game.commanders[i]);
      }
      return winners;
    }

    /**
     * Execute the provided action and evaluate any aftermath.
     */
    private boolean executeGameAction(GameAction action, GameEventQueue eventQueue, GameInstance game, PrintStream defaultOut)
    {
      boolean actionOK = false; // Not sure if it's a well-formed action yet.
      if( null != action )
      {
        // Compile the GameAction to its component events.
        GameEventQueue events = action.getEvents(game.gameMap);

        if( events.size() > 0 )
        {
          actionOK = true; // Invalid actions don't produce events.
          eventQueue.addAll(events);
        }
      }
      else
      {
        defaultOut.println("WARNING! Attempting to execute null GameAction.");
      }
      return actionOK;
    }

    private void startNextTurn(GameInstance game, PrintStream defaultOut)
    {
      // Tell the game a turn has changed. This will update the active CO.
      GameEventQueue turnEvents = new GameEventQueue();
      boolean turnOK = game.turn(turnEvents);
      if( !turnOK )
      {
        defaultOut.println("WARNING: Turn init failed for some reason");
      }

      while (!turnEvents.isEmpty())
      {
        executeEvent(turnEvents.pop(), turnEvents, game, defaultOut);
      }

//      defaultOut.println("Started turn " + game.getCurrentTurn() + " for CO " + game.activeCO.coInfo.name);
//      defaultOut.println(new COStateInfo(game.activeCO.myView, game.activeCO).getFullStatus());
    }

    public void executeEvent(GameEvent event, GameEventQueue eventQueue, GameInstance game, PrintStream defaultOut)
    {
      if( null != event )
      {
        event.performEvent(game.gameMap);

        // Now that the event has been completed, let the world know.
        GameEventListener.publishEvent(event, game);
      }

      for( Commander co : game.commanders )
        co.pollForEvents(eventQueue);
    }
  } // ~GameSet

}
