package UI;

import java.awt.Color;
import java.util.ArrayList;

import AI.AILibrary;
import CommandingOfficers.Commander;
import CommandingOfficers.CommanderInfo;
import CommandingOfficers.CommanderLibrary;
import Engine.Driver;
import Engine.GameInstance;
import Engine.IController;
import Engine.IView;
import Engine.MapController;
import Engine.OptionSelector;
import Terrain.MapMaster;
import UI.InputHandler.InputAction;

/**
 * Controller for choosing COs and colors after the map has been chosen.
 * Left/Right changes the CO selector with focus.
 * Up/Down changes the CO selected for that slot.
 * LS/RS changes the color of the selected CO?
 */
public class PlayerSetupController implements IController
{
  // This OptionSelector determines which player we have under the cursor.
  private OptionSelector playerSelector;
  private OptionSelector categorySelector;

  private GameBuilder gameBuilder = null;
  PlayerSetupInfo[] coSelectors;

  public enum SelectionCategories { COMMANDER, COLOR_FACTION, TEAM, AI, START };

  public PlayerSetupController( GameBuilder builder )
  {
    // Once we hit go, we plug all the COs we chose into our gameBuilder.
    gameBuilder = builder;

    // Set up our row/col selectors.
    int numCos = gameBuilder.mapInfo.getNumCos();
    playerSelector = new OptionSelector(numCos);
    categorySelector = new OptionSelector(SelectionCategories.values().length);
    categorySelector.setSelectedOption(SelectionCategories.START.ordinal()); // Best case is that no changes are needed.

    // Create objects to keep track of the selected options for each player.
    coSelectors = new PlayerSetupInfo[numCos];

    // Start by making default CO/color selections.
    for(int co = 0; co < numCos; ++co)
    {
      // Set up our option selection framework
      coSelectors[co] = new PlayerSetupInfo(numCos, co, CommanderLibrary.getCommanderList(), UIUtils.getCOColors(), UIUtils.getFactions(), AILibrary.getAIList());
    }
  }

  @Override
  public boolean handleInput(InputAction action)
  {
    boolean exitMenu = false;
    switch(action)
    {
      case ENTER:
        if( categorySelector.getSelectionNormalized() == SelectionCategories.START.ordinal() )
        {
          // We have locked in our selection. Stuff it into the GameBuilder and then kick off the game.
          for(int i = 0; i < coSelectors.length; ++i)
          {
            gameBuilder.addCO(coSelectors[i].makeCommander());
          }
  
          // Build the CO list and the new map and create the game instance.
          Commander[] cos = gameBuilder.commanders.toArray(new Commander[gameBuilder.commanders.size()]);
          MapMaster map = new MapMaster( cos, gameBuilder.mapInfo );
          if( map.initOK() )
          {
            GameInstance newGame = new GameInstance(map);
  
            MapView mv = Driver.getInstance().gameGraphics.createMapView(newGame);
            MapController mapController = new MapController(newGame, mv);
  
            // Mash the big red button and start the game.
            Driver.getInstance().changeGameState(mapController, mv);
          }
          exitMenu = true;
        }
        else
        {
          // Open a sub-menu based on which player attribute is selected.
          ArrayList<CommanderInfo> infos = CommanderLibrary.getCommanderList();
          
          CO_InfoController coInfoMenu = new CO_InfoController(infos);
          IView infoView = Driver.getInstance().gameGraphics.createInfoView(coInfoMenu);

          // Get the info menu to select the current CO
          for( int i = 0; i < infos.indexOf(coSelectors[getHighlightedPlayer()].getCurrentCO()); i++ )
          {
            coInfoMenu.handleInput(UI.InputHandler.InputAction.DOWN);
          }
          

          // Give the new controller/view the floor
          Driver.getInstance().changeGameState(coInfoMenu, infoView);
        }
        break;
      case BACK:
        exitMenu = true;
        break;
      case DOWN:
      case UP:
        // Move to the next/previous player panel, but keep focus on the same category to maintain continuity.
        // i.e., if we have player 3's "team" attribute selected, hitting DOWN should move to player 4's "team" attribute.
        playerSelector.handleInput(action);
        break;
      case LEFT:
      case RIGHT:
        categorySelector.handleInput(action);
        break;
      case NO_ACTION:
        break;
        default:
          System.out.println("Warning: Unsupported input " + action + " in CO setup menu.");
    }
    return exitMenu;
  }

  public int getHighlightedPlayer()
  {
    return playerSelector.getSelectionNormalized();
  }

  public int getHighlightedCategory()
  {
    return categorySelector.getSelectionNormalized();
  }

  public PlayerSetupInfo getPlayerInfo(int p)
  {
    return coSelectors[p];
  }

  public CommanderInfo getPlayerCo(int p)
  {
    return coSelectors[p].getCurrentCO();
  }

  public Color getPlayerColor(int p)
  {
    return coSelectors[p].getCurrentColor();
  }
}