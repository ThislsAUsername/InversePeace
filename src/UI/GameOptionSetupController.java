package UI;

import Engine.IController;
import Engine.OptionSelector;
import Terrain.Environment.Weathers;
import UI.InputHandler.InputAction;

public class GameOptionSetupController implements IController
{
  private GameOption fowOption = new GameOption("Fog of War", false);
  private GameOption startingFundsOption = new GameOption("Starting Funds", 0, 50000, 1000, 0);
  private GameOption incomeOption = new GameOption("Income", 500, 20000, 500, 1000);
  private GameOption weatherOption = new GameOption("Weather", Weathers.values(), 0);

  // Get a list of all GameOptions.
  public GameOption[] gameOptions = {fowOption, startingFundsOption, incomeOption, weatherOption};

  public OptionSelector optionSelector = new OptionSelector( gameOptions.length );
  private PlayerSetupController coSelectMenu;

  private boolean isInSubmenu = false;

  // GameBuilder to set options.
  private GameBuilder gameBuilder = null;

  public GameOptionSetupController(GameBuilder aGameBuilder)
  {
    gameBuilder = aGameBuilder;
  }

  @Override
  public boolean handleInput(InputAction action)
  {
    boolean exitMenu = false;

    if(isInSubmenu)
    {
      exitMenu = coSelectMenu.handleInput(action);
      if(exitMenu)
      {
        isInSubmenu = false;

        // If BACK was chosen for the child menu, then we take control again (if
        // the child menu exited via entering a game, then action will be ENTER).
        if(action == InputAction.BACK)
        {
          // Don't pass control back up the chain.
          exitMenu = false;
        }
        else
        {
          optionSelector.setSelectedOption(0);
        }
      }
    }
    else
    {
      exitMenu = handleGameOptionInput(action);
    }
    return exitMenu;
  }

  private boolean handleGameOptionInput(InputAction action)
  {
    boolean exitMenu = false;
    switch(action)
    {
      case ENTER:
        // Set the selected options and transition to the player setup screen.
        for( GameOption go : gameOptions ) go.storeCurrentValue();
        gameBuilder.isFowEnabled = fowOption.getSelectedObject().equals("On");
        gameBuilder.startingFunds = Integer.parseInt(startingFundsOption.getSelectedObject().toString());
        gameBuilder.incomePerCity = Integer.parseInt(incomeOption.getSelectedObject().toString());
        gameBuilder.defaultWeather = (Weathers)weatherOption.getSelectedObject();
        coSelectMenu = new PlayerSetupController( gameBuilder );
        isInSubmenu = true;
        break;
      case BACK:
        exitMenu = true;
        break;
      case DOWN:
      case UP:
        optionSelector.handleInput(action);
        break;
      case LEFT:
      case RIGHT:
        int opt = optionSelector.getSelectionNormalized();
        gameOptions[opt].handleInput(action);
        break;
      case NO_ACTION:
        break;
        default:
          System.out.println("Warning: Unsupported input " + action + " in map select menu.");
    }
    return exitMenu;
  }

  public boolean inSubmenu()
  {
    return isInSubmenu;
  }

  public PlayerSetupController getSubController()
  {
    return coSelectMenu;
  }
}
