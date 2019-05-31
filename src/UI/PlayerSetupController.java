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

  private IController subMenu;

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
    if(null != subMenu)
    {
      boolean exitSub = subMenu.handleInput(action);
      if(exitSub)
      {
        // Only apply the change from the sub-menu if it exited via ENTER.
        if(action == InputAction.ENTER)
        {
          final int category = categorySelector.getSelectionNormalized();
          if( category == SelectionCategories.COMMANDER.ordinal() )
          {
            PlayerSetupCommanderController cmdrMenu = (PlayerSetupCommanderController)subMenu;
            PlayerSetupInfo info = getPlayerInfo(playerSelector.getSelectionNormalized());
            info.currentCO.setSelectedOption(cmdrMenu.getSelectedCommander());
          }
          if( category == SelectionCategories.COLOR_FACTION.ordinal() )
          {
            PlayerSetupColorFactionController unitMenu = (PlayerSetupColorFactionController)subMenu;
            PlayerSetupInfo info = getPlayerInfo(playerSelector.getSelectionNormalized());
            info.currentColor.setSelectedOption(unitMenu.getSelectedColor());
            info.currentFaction.setSelectedOption(unitMenu.getSelectedFaction());
          }
          if( category == SelectionCategories.TEAM.ordinal() )
          { /* Do nothing - the sub-menu does the change on its own. */ }
          if( category == SelectionCategories.AI.ordinal() )
          {
            PlayerSetupAiController aiMenu = (PlayerSetupAiController)subMenu;
            PlayerSetupInfo info = getPlayerInfo(playerSelector.getSelectionNormalized());
            info.currentAI.setSelectedOption(aiMenu.getSelectedAiIndex());
          }
        }
        subMenu = null;
      }
    }
    else
    {
      // No sub-menu is active - handle the input here.
      exitMenu = handlePlayerSetupInput(action);
    }
    return exitMenu;
  }

  /** Returs the currently-active sub-menu, or null if control is held locally. */
  public IController getSubMenu()
  {
    return subMenu;
  }

  private boolean handlePlayerSetupInput(InputAction action)
  {
    boolean exitMenu = false;

    switch(action)
    {
      case ENTER:
        // Open a sub-menu based on which player attribute is selected, or start the game.
        if( categorySelector.getSelectionNormalized() == SelectionCategories.COMMANDER.ordinal() )
        {
          ArrayList<CommanderInfo> infos = CommanderLibrary.getCommanderList();
          subMenu = new PlayerSetupCommanderController(infos, getPlayerInfo(playerSelector.getSelectionNormalized()).currentCO.getSelectionNormalized());
        }
        else if( categorySelector.getSelectionNormalized() == SelectionCategories.COLOR_FACTION.ordinal() )
        {
          Color color = getPlayerInfo(playerSelector.getSelectionNormalized()).getCurrentColor();
          UIUtils.Faction faction = getPlayerInfo(playerSelector.getSelectionNormalized()).getCurrentFaction();
          subMenu = new PlayerSetupColorFactionController(color, faction);
        }
        else if( categorySelector.getSelectionNormalized() == SelectionCategories.TEAM.ordinal() )
        {
          subMenu = new PlayerSetupTeamController(coSelectors, playerSelector.getSelectionNormalized());
        }
        else if( categorySelector.getSelectionNormalized() == SelectionCategories.AI.ordinal() )
        {
          subMenu = new PlayerSetupAiController(getPlayerInfo(playerSelector.getSelectionNormalized()).currentAI.getSelectionNormalized());
        }
        else // ( categorySelector.getSelectionNormalized() == SelectionCategories.START.ordinal() )
        {
          /////////////////////////////////////////////////////////////////////////////////////////////
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
