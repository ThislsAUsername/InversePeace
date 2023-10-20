package UI.Art.SpriteArtist;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Polygon;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;

import CommandingOfficers.CommanderInfo;
import Engine.IController;
import Engine.OptionSelector;
import Engine.XYCoord;
import UI.InputHandler.InputAction;
import UI.PlayerSetupCommanderController;
import UI.SlidingValue;
import UI.UIUtils;
import UI.UIUtils.COSpriteSpec;
import UI.UIUtils.SourceGames;

public class PlayerSetupCommanderArtist
{
  private static HashMap<Integer, CommanderPanel> coPanels = new HashMap<Integer, CommanderPanel>();

  private static PlayerSetupCommanderController myControl;
  private static SlidingValue panelOffsetY = new SlidingValue(0);
  private static int panelDrawW = CommanderPanel.eyesWidth + 2;
  private static SlidingValue panelDrawX = new SlidingValue(0);

  private static SlidingValue tagPickerOffsetX = new SlidingValue(0);

  static final BufferedImage kickTip = SpriteUIUtils.makeTextFrame("Q: Kick CO", 3, 2);
  static final BufferedImage toGameTip = SpriteUIUtils.makeTextFrame("Q: To Game", 3, 2);
  static final BufferedImage toArmyTip = SpriteUIUtils.makeTextFrame("Q: To Army", 3, 2);
  static final BufferedImage etip = SpriteUIUtils.makeTextFrame("E: CO Info", 3, 2);

  static final int textWidth = SpriteLibrary.getLettersSmallCaps().getFrame(0).getWidth();
  static final int textHeight = SpriteLibrary.getLettersSmallCaps().getFrame(0).getHeight();

  public static void draw(Graphics g, IController controller, ArrayList<CommanderInfo> infos, Color playerColor)
  {
    PlayerSetupCommanderController control = (PlayerSetupCommanderController)controller;
    if( null == control )
    {
      System.out.println("WARNING! PlayerSetupCommanderController was given the wrong controller!");
    }
    boolean snapCursor = myControl != control;

    myControl = control;
    if( snapCursor )
      initFromControl();

    // Define the draw space
    int drawScale = SpriteOptions.getDrawScale();
    Dimension dimensions = SpriteOptions.getScreenDimensions();
    int myWidth = dimensions.width / drawScale;
    int myHeight = dimensions.height / drawScale;
    BufferedImage image = SpriteLibrary.createTransparentSprite(myWidth, myHeight);
    Graphics myG = image.getGraphics();

    /////////////// Commander Panels //////////////////////
    drawCmdrPickerPanels(myG, myHeight, myWidth, infos, playerColor, snapCursor);

    /////////////// Tag Picker Panels //////////////////////
    if( amPickingTagIndex )
    {
      drawTagPickerPanels(myG, myWidth, infos, playerColor, snapCursor);
    }

    /////////////// Tooltip ////////////////////////////
    myG.drawImage(etip, myWidth - etip.getWidth(), 3, null);
    BufferedImage qtip = kickTip;
    if( !amPickingTagIndex )
      if( sortByGameThenFaction )
        qtip = toGameTip;
      else
        qtip = toArmyTip;
    myG.drawImage(qtip, myWidth - qtip.getWidth(), 5 + qtip.getHeight(), null);

    // Draw the composed image to the window at scale.
    g.drawImage(image, 0, 0, myWidth*drawScale, myHeight*drawScale, null);
  }

  static final int leftMargin = 13;
  static final int mugPanelBuffer = 3;
  public static void drawCmdrPickerPanels(
                       Graphics myG, int myHeight, int myWidth,
                       ArrayList<CommanderInfo> infos, Color playerColor,
                       boolean snapCursor)
  {
    // Selected horizontal bin on the screen
    int binIndex        = innerCategorySelector.getSelectionNormalized();
    // Index into that bin that's selected
    int coIndex         = cmdrInBinSelector.getSelectionNormalized();
    // Value of that selection; index into the list of CO infos
    int highlightedCmdr = cmdrBins.get(binIndex).cmdrs.get(coIndex);
    CommanderInfo highlightedCmdrInfo = infos.get(highlightedCmdr);

    int mmpr = getMaxMugsPerRow();

    // We're drawing the panels to align with the vertically-fixed cursor,
    // so figure out where the zeroth bin panel should be drawn.
    int totalShift = 0;
    for( int i = 0; i < binIndex; ++i )
      totalShift += cmdrBins.get(i).calcPanelHeight(mmpr) + mugPanelBuffer;

    // X offset to start drawing CO faces from
    int baseDrawX = leftMargin;// + SpriteLibrary.getCursorSprites().getFrame(0).getWidth(); // Make sure we have room to draw the cursor around the frame.

    XYCoord mugCoord = cmdrBins.get(binIndex).calcMugPosition(mmpr, coIndex);
    panelDrawX.set(baseDrawX + mugCoord.xCoord, snapCursor);
    panelOffsetY.set(totalShift + mugCoord.yCoord, snapCursor);

    int drawY = myHeight / 2 - panelOffsetY.geti() - CommanderPanel.PANEL_HEIGHT/2;
    final int startY = drawY;

    for(int binToDraw = 0; drawY - CommanderPanel.PANEL_HEIGHT/2 < myHeight
        && binToDraw < cmdrBins.size();
        ++binToDraw )
    {
      InnerCategoryPanel currentPanel = cmdrBins.get(binToDraw);
      BufferedImage panelImage = currentPanel.update(infos, myWidth, mmpr);

      myG.drawImage(panelImage, baseDrawX, drawY, null);
      int currentPanelBottomY = drawY + panelImage.getHeight();

      drawY = currentPanelBottomY + mugPanelBuffer;
    }

    // Draw the outer level's side panel
    final COSpriteSpec outerSpriteSpec = outerBinColorSpec.get(outerCategorySelector.getSelectionNormalized());
    Color[] outerPalette = UIUtils.defaultMapColors;
    String outerName = "MISC";
    if( Color.LIGHT_GRAY != outerSpriteSpec.color )
    {
      outerPalette = UIUtils.getMapUnitColors(outerSpriteSpec.color).paletteColors;
      if( !sortByGameThenFaction )
        outerName = UIUtils.getCanonicalFactionName(outerSpriteSpec);
      else // Our inner category is games, so just pull the hardcoded faction name
        outerName = outerSpriteSpec.faction.name;
    }
    drawSideBin(myG, outerName, outerPalette[5], outerPalette[3], startY, drawY-startY-mugPanelBuffer);

    final int cursorY = myHeight / 2 - CommanderPanel.PANEL_HEIGHT / 2;

    // Draw stuff for the selected option.
    if( !amPickingTagIndex )
    {
      String coNameText = highlightedCmdrInfo.name;
      BufferedImage coNameFrame = SpriteUIUtils.makeTextFrame(coNameText, 2, 2);
      int drawNameX = panelDrawX.geti() + panelDrawW / 2;
      int drawNameY = cursorY + CommanderPanel.PANEL_HEIGHT + coNameFrame.getHeight() / 2 + 2;
      SpriteUIUtils.drawImageCenteredOnPoint(myG, coNameFrame, drawNameX, drawNameY);

      SpriteCursor.draw(myG, panelDrawX.geti(), cursorY, panelDrawW, CommanderPanel.PANEL_HEIGHT, playerColor);
    }
  }

  static final int txtBuf = 2;
  /**
   * Draws a little side bin with beveled edges
   *  /
   * |A
   * |W
   * |1
   *  \
   */
  public static int drawSideBin(Graphics g, String label, Color bg,  Color frame, int y, int totalHeight)
  {
    //                    *      - 2 triangles
    int bodyHeight = totalHeight - 2*(leftMargin) + 2;
    final int drawBodyY = y+leftMargin-1;
    // Throw in a +/-adjustment because triangles are weird

    Polygon triangleUp = new Polygon();
    triangleUp.addPoint(leftMargin, y-1);       // top right
    triangleUp.addPoint(0         , drawBodyY); // bottom left
    triangleUp.addPoint(leftMargin, drawBodyY); // bottom right
    Polygon triangleDown = new Polygon();
    triangleDown.addPoint(leftMargin, y+totalHeight-leftMargin); // top right
    triangleDown.addPoint(0         , y+totalHeight-leftMargin); // top left
    triangleDown.addPoint(leftMargin, y+totalHeight);            // bottom right

    g.setColor(frame);
    g.fillPolygon(triangleUp);
    g.fillPolygon(triangleDown);

    g.setColor(bg);
    for( int i = 0; i < 3; ++i )
    {
      triangleUp.ypoints[i]   += 1; // Shift down to expose the frame
      triangleDown.ypoints[i] -= 1; // ditto, up
    }
    g.fillPolygon(triangleUp);
    g.fillPolygon(triangleDown);

    // Draw the rectangle afterwards because the triangles exist only to bring me pain
    g.setColor(frame);
    g.fillRect(0, drawBodyY, leftMargin, bodyHeight);
    g.setColor(bg);
    g.fillRect(1, drawBodyY, leftMargin-1, bodyHeight);

    BufferedImage textScratch = SpriteUIUtils.getTextAsImage(label, true);
    BufferedImage text = SpriteUIUtils.rotateCounterClockwise90(textScratch);
    g.drawImage(text, leftMargin-textHeight-txtBuf, drawBodyY + (bodyHeight-text.getHeight())/2, null);

    return drawBodyY + bodyHeight;
  }

  public static void drawTagPickerPanels(
                       Graphics myG, int myWidth,
                       ArrayList<CommanderInfo> infos, Color playerColor,
                       boolean snapCursor)
  {
    // Calculate the vertical space each player panel will consume.
    final int panelThickness = 1;
    final int panelBuffer = 2*panelThickness;
    final int panelWidth = CommanderPanel.eyesWidth+panelBuffer;
    final int panelHeight = CommanderPanel.eyesHeight+panelBuffer;

    // Take over the top of the screen
    SpriteUIUtils.drawMenuFrame(myG, SpriteUIUtils.MENUBGCOLOR, SpriteUIUtils.MENUFRAMECOLOR,
        0, -1, myWidth, panelHeight*3/2, 2);

    final int panelSpacing = 1;
    final int panelXShift = panelWidth + panelSpacing;

    // Draw the list of COs in your tag from left to right
    final int drawY = 4;
    final ArrayList<Integer> taggedCOs = tagCmdrList;
    for( int tagToDraw = 0; tagToDraw < taggedCOs.size(); ++tagToDraw )
    {
      CommanderInfo coInfo = infos.get(taggedCOs.get(tagToDraw));

      BufferedImage playerImage = SpriteLibrary.getCommanderSprites( coInfo.name ).eyes;

      int drawX = 4 + (tagToDraw*panelXShift);
      myG.setColor(Color.BLACK);
      myG.fillRect(drawX, drawY, panelWidth, panelHeight);
      int dx = drawX+panelThickness, dy = drawY+panelThickness;

      myG.setColor(playerColor);
      myG.fillRect(dx, dy, panelWidth-panelThickness-1, panelHeight-panelThickness-1);

      if( tagToDraw+1 == taggedCOs.size() )
      {
        myG.setColor(Color.BLACK);
        // Draw a little plus sign
        myG.drawLine(drawX + 2*panelWidth/7, drawY +   panelHeight/2,
                     drawX + 5*panelWidth/7, drawY +   panelHeight/2);
        myG.drawLine(drawX +   panelWidth/2, drawY + 2*panelHeight/7,
                     drawX +   panelWidth/2, drawY + 5*panelHeight/7);
      }
      else
        myG.drawImage(playerImage, dx, dy, null);
    }

    // Throw in a done button
    BufferedImage readyButton = SpriteUIUtils.makeTextFrame("done", 3, 2);

    int drawX = 4 + (taggedCOs.size()*panelXShift);
    int dx = drawX+panelThickness, dy = drawY+panelThickness;
    // Center it
    dx += (panelWidth  - readyButton.getWidth() ) / 2;
    dy += (panelHeight - readyButton.getHeight()) / 2;
    myG.drawImage(readyButton, dx, dy, null);

    // Draw the cursor over the selected option.
    final int selTagIndex = tagIndexSelector.getSelectionNormalized();
    tagPickerOffsetX.set(4 + (selTagIndex*panelXShift));
    SpriteCursor.draw(myG, tagPickerOffsetX.geti(), drawY, panelWidth, panelHeight, playerColor);
  }

  /**
   * Renders itself into an image like this, with no scaling applied.
   * +----------------+
   * |                |
   * |   Cmdr Eyes    |
   * |                |
   * +----------------+
   */
  private static class CommanderPanel
  {
    // A couple of helper quantities.
    public static final int eyesWidth = SpriteLibrary.getCommanderSprites( "STRONG" ).eyes.getWidth();
    public static final int eyesHeight = SpriteLibrary.getCommanderSprites( "STRONG" ).eyes.getHeight();

    // Total vertical panel space, sans scaling.
    public static final int PANEL_HEIGHT = eyesHeight + 2; // Eyes plus 1 above and below.
    public static final int PANEL_WIDTH  = eyesWidth  + 2; // ditto

    private BufferedImage myImage;

    private SpriteUIUtils.ImageFrame commanderFace;

    // Stored values.
    Color myColor;

    public CommanderPanel(CommanderInfo info, Color color)
    {
      update(info, color);
    }

    public BufferedImage update(CommanderInfo coInfo, Color color)
    {
      if( !color.equals(myColor))
      {
        myColor = color;
        commanderFace = new SpriteUIUtils.ImageFrame(1, 1, eyesWidth, eyesHeight, color,
            color, true, SpriteLibrary.getCommanderSprites( coInfo.name ).eyes);

        // Re-render the panel.
        myImage = SpriteLibrary.createTransparentSprite( commanderFace.width + 2, PANEL_HEIGHT );
        Graphics g = myImage.getGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, commanderFace.width + 2, myImage.getHeight());
        commanderFace.render(g);
      }

      return myImage;
    }
  }

  /**
   * Renders itself into an image like this, with no scaling applied.
   * +-----------------\
   * | Name of Category \___________________
   * |
   * |   Cmdr      Cmdr      Cmdr
   * |
   * +--------------------------------------
   */
  private static class InnerCategoryPanel
  {
    // A couple of helper quantities.
    public static final int TEXT_MARGIN_X = 2;
    public static final int TEXT_MARGIN_Y = 2;
    public static final int CMDR_MARGIN_LEFT = 6; // Extra margin on the left before drawing COs
    public static final int CMDR_MARGIN_X = 2;
    public static final int CMDR_MARGIN_Y = 2;

    private int calcPanelHeight(int mmpr)
    {
      int total = 0;
      total += 2 * TEXT_MARGIN_Y;
      total += textHeight;
      int mugRows = 1;
      while (mugRows * mmpr < cmdrs.size())
        ++mugRows;
      total += mugRows * (2 * CMDR_MARGIN_Y + CommanderPanel.PANEL_HEIGHT);
      return total;
    }
    public XYCoord calcMugPosition(int mmpr, int mugIndex)
    {
      int y = 0;
      y += 2 * TEXT_MARGIN_Y;
      y += textHeight;
      y += CMDR_MARGIN_Y; // upper margin
      int mugRows = 0;
      while (mugRows * mmpr <= mugIndex - mmpr)
        ++mugRows;
      // mugs above, plus their lower/upper margins
      y += mugRows * (2 * CMDR_MARGIN_Y + CommanderPanel.PANEL_HEIGHT);

      int mugCountFromLeft = mugIndex - mugRows * mmpr;
      int x = CMDR_MARGIN_LEFT;
      x += mugCountFromLeft * (2 * CMDR_MARGIN_X + CommanderPanel.PANEL_WIDTH);
      return new XYCoord(x, y);
    }

    public final ArrayList<Integer> cmdrs;
    private Color border, fill;
    private String canonName;
    private BufferedImage myImage;
    private int minMMPR;

    public InnerCategoryPanel(ArrayList<Integer> cmdrs, Color[] palette, String canonName)
    {
      this.cmdrs = cmdrs;
      minMMPR = cmdrs.size();
      border = palette[3];
      fill = palette[5];
      this.canonName = canonName;
    }

    public BufferedImage update(ArrayList<CommanderInfo> infos, int drawWidth, int mmpr)
    {
      int panelHeight = calcPanelHeight(mmpr);
      if( null == myImage
          || minMMPR > mmpr
          || panelHeight != myImage.getHeight()
          || drawWidth > myImage.getWidth() )
      {
        minMMPR = Math.min(cmdrs.size(), mmpr);
        // Re-render the panel.
        myImage = SpriteLibrary.createTransparentSprite(drawWidth, panelHeight);
        Graphics g = myImage.getGraphics();

        int indexInBin = 0;
        drawCmdrBin(g, canonName, fill, border, drawWidth, 0, panelHeight);

        // Actually draw the CO mugs
        while (indexInBin < cmdrs.size())
        {
          int coToDraw = cmdrs.get(indexInBin);
          CommanderInfo coInfo = infos.get(coToDraw);
          Integer key = coToDraw;

          // Get the relevant PlayerPanel.
          if( !coPanels.containsKey(key) )
            coPanels.put(key, new CommanderPanel(coInfo, coInfo.baseFaction.color));
          CommanderPanel panel = coPanels.get(key);

          // Update the PlayerPanel and render it to an image.
          BufferedImage playerImage = panel.update(coInfo, coInfo.baseFaction.color);

          XYCoord mugPos = calcMugPosition(mmpr, indexInBin);
          g.drawImage(playerImage, mugPos.xCoord, mugPos.yCoord, null);
          ++indexInBin;
        }
      }

      return myImage;
    }
    static final int textToastHeight = textHeight + TEXT_MARGIN_Y; // upper buffer only
    private static void drawCmdrBin(Graphics g, String label, Color bg,  Color frame, int screenWidth, int y, int drawHeight)
    {
      final int textToastWidth  = textWidth*label.length();
      final int xBufT = TEXT_MARGIN_X;
      final int yBufT = TEXT_MARGIN_Y;
      final int bodyHeight = drawHeight - textToastHeight;

      // Smooths between the label backing to the CO face holder
      Polygon triangle = new Polygon();
      triangle.addPoint(xBufT+textToastWidth                , y);                 // top left
      triangle.addPoint(xBufT+textToastWidth                , y+textToastHeight); // bottom left
      triangle.addPoint(xBufT+textToastWidth+textToastHeight, y+textToastHeight); // right

      g.setColor(frame);
      g.fillPolygon(triangle);
      g.fillRect(0, y                 , yBufT+textToastWidth, bodyHeight); // behind text
      g.fillRect(0, y+textToastHeight , screenWidth         , bodyHeight); // main body

      g.setColor(bg);
      for( int i = 0; i < 3; ++i )
        triangle.ypoints[i] += 1; // Shift one pixel down to expose the frame
      g.fillPolygon(triangle);
      g.fillRect(0, y+1                , yBufT+textToastWidth, bodyHeight-2);
      g.fillRect(0, y+1+textToastHeight, screenWidth         , bodyHeight-2);

      SpriteUIUtils.drawTextSmallCaps(g, label, xBufT, y + txtBuf);
    }
  }

////////// Input control be beyond here ///////////////

  public static boolean amPickingTagIndex;
  // Range: [0, tag count], to handle the "done" button.
  public static OptionSelector tagIndexSelector;

  public static boolean sortByGameThenFaction = true;
  public static OptionSelector outerCategorySelector;
  public static OptionSelector innerCategorySelector;

  public static ArrayList<InnerCategoryPanel> cmdrBins;
  public static ArrayList<COSpriteSpec> outerBinColorSpec;
  public static ArrayList<COSpriteSpec> binColorSpec;
  public static OptionSelector cmdrInBinSelector;
  public static ArrayList<Integer> tagCmdrList;
  public static int rightGlueColumn;

  public static void initFromControl()
  {
    boolean shouldSelectMultiCO = myControl.shouldSelectMultiCO;
    amPickingTagIndex = shouldSelectMultiCO;

    tagCmdrList = myControl.getInitialCmdrs();

    final int firstCO = tagCmdrList.get(0);

    // Pad with an extra No CO so we can add tag partners
    if( shouldSelectMultiCO && myControl.noCmdr != firstCO )
      tagCmdrList.add(myControl.noCmdr);

    // Clean up state to avoid weirdness
    innerCategorySelector = null;
    initBins(firstCO);
  }

  /** Set up bins for our newly-selected outer category */
  public static void initBins(final int selectedCO)
  {
    final CommanderInfo selectedInfo = myControl.cmdrInfos.get(selectedCO);

    ArrayList<Integer>[] innerCategoryCmdrLists;
    outerBinColorSpec = new ArrayList<>();
    binColorSpec = new ArrayList<>();
    int outerMax, outerSel;
    if( sortByGameThenFaction )
    {
      outerMax = UIUtils.SourceGames.values().length;
      // Pivot our *previous* inner selection to be our new outer selection
      if( null == innerCategorySelector )
        outerSel = selectedInfo.game.ordinal();
      else
        outerSel = innerCategorySelector.getSelectionNormalized();

      // outer bin = game
      for( SourceGames game : UIUtils.SourceGames.values() )
        outerBinColorSpec.add(game.uiColorSpec);
      // Factions for both
      innerCategoryCmdrLists = myControl.cosByGameFaction[outerSel];
      for( COSpriteSpec spec : myControl.canonFactions )
        binColorSpec.add(spec);
    }
    else
    {
      outerMax = myControl.canonFactions.length;
      outerSel = 0;
      // Pivot our previous *inner* selection to be our new outer selection
      if( null == innerCategorySelector )
      {
        for( ; outerSel < myControl.canonFactions.length; ++outerSel )
          if( selectedInfo.baseFaction == myControl.canonFactions[outerSel] )
            break;
      }
      else
        outerSel = innerCategorySelector.getSelectionNormalized();

      // outer bin = faction
      for( COSpriteSpec spec : myControl.canonFactions )
        outerBinColorSpec.add(spec);
      // Games for both
      innerCategoryCmdrLists = myControl.cosByFactionGame[outerSel];
      for( SourceGames game : UIUtils.SourceGames.values() )
        binColorSpec.add(game.uiColorSpec);
    }
    outerCategorySelector = new OptionSelector(outerMax);
    outerCategorySelector.setSelectedOption   (outerSel);

    cmdrBins = new ArrayList<>();

    for( int innerIdX = 0; innerIdX < innerCategoryCmdrLists.length; ++innerIdX )
    {
      if( 0 >= innerCategoryCmdrLists[innerIdX].size() )
        continue;

      ArrayList<Integer> innerBin = new ArrayList<>(innerCategoryCmdrLists[innerIdX]);
      final COSpriteSpec spriteSpec = binColorSpec.get(innerIdX);
      Color[] palette = UIUtils.defaultMapColors;
      String canonName = "MISC";
      if( Color.LIGHT_GRAY != spriteSpec.color )
      {
        palette = UIUtils.getMapUnitColors(spriteSpec.color).paletteColors;
        if( sortByGameThenFaction )
          canonName = UIUtils.getCanonicalFactionName(spriteSpec);
        else // Our inner category is games, so just pull the hardcoded faction name
          canonName = spriteSpec.faction.name;
      }
      InnerCategoryPanel icp = new InnerCategoryPanel(innerBin, palette, canonName);
      cmdrBins.add(icp);
    }

    int innerMax = cmdrBins.size();
    int innerSel = 0;
    int cmdrInBinSel = -1;
    for( ; innerSel < innerMax; ++innerSel )
    {
      cmdrInBinSel = cmdrBins.get(innerSel).cmdrs.indexOf(selectedCO);
      if( -1 != cmdrInBinSel )
        break;
    }
    innerCategorySelector = new OptionSelector(innerMax);
    innerCategorySelector.setSelectedOption   (innerSel);

    tagIndexSelector = new OptionSelector(1);
    syncTagIndexSelector();

    rightGlueColumn = cmdrInBinSel;

    cmdrInBinSelector = new OptionSelector(cmdrBins.get(innerSel).cmdrs.size());
    cmdrInBinSelector.setSelectedOption(cmdrInBinSel);
  }

  public static boolean handleInput(InputAction action)
  {
    boolean exitMenu = false;
    if( amPickingTagIndex )
      exitMenu = handleTagChoiceInput(action);
    else
      exitMenu = handleCmdrChoiceInput(action);
    return exitMenu;
  }

  private static boolean handleTagChoiceInput(InputAction action)
  {
    boolean done = false;
    final int selTagIndex = tagIndexSelector.getSelectionNormalized();
    switch(action)
    {
      case SELECT:
        amPickingTagIndex = false;

        if( selTagIndex >= tagCmdrList.size() )
        {
          // User says we're done - apply changes and get out.

          // Handle the pesky No CO at the end.
          if( 1 < tagCmdrList.size() )
            tagCmdrList.remove(tagCmdrList.size() - 1);

          myControl.applyCmdrChoices(tagCmdrList);
          done = true;
        }
        break;
      case UP:
      case DOWN:
      case LEFT:
      case RIGHT:
      {
        tagIndexSelector.handleInput(action);
      }
      break;
      case BACK:
        // Cancel: return control without applying changes.
        done = true;
        break;
      case SEEK:
        // Kick out the selected CO
        if( selTagIndex+1 < tagCmdrList.size() )
        {
          tagCmdrList.remove(selTagIndex);
          syncTagIndexSelector();
        }
        break;
      case VIEWMODE:
        int selectedCO = tagCmdrList.get(selTagIndex);
        myControl.startViewingCmdrInfo(selectedCO);
        break;
      default:
        // Do nothing.
    }
    return done;
  } // ~handleTagChoiceInput

  private static boolean handleCmdrChoiceInput(InputAction action)
  {
    boolean done = false;

    int mmpr = getMaxMugsPerRow();

    final int selectedBin    = innerCategorySelector.getSelectionNormalized();
    final int selectedColumn = cmdrInBinSelector.getSelectionNormalized();
    // Value of selection; index into the list of CO infos
    final int selectedCO     = cmdrBins.get(selectedBin).cmdrs.get(selectedColumn);
    switch(action)
    {
      case SELECT:
        // handleTagChoiceInput() should ensure this index is in [0, tag count)
        final int selTagIndex = tagIndexSelector.getSelectionNormalized();

        tagCmdrList.set(selTagIndex, selectedCO);
        // Are we bimodal?
        if( !myControl.shouldSelectMultiCO )
        {
          // No; apply change and return control.
          myControl.applyCmdrChoices(tagCmdrList);
          done = true;
        }
        else // Yes
        {
          amPickingTagIndex = true;

          // Add/remove if appropriate
          if( selTagIndex + 1 >= tagCmdrList.size() )
          {
            tagCmdrList.add(myControl.noCmdr); // Extend the list if we just added a new tag partner
            syncTagIndexSelector();
            tagIndexSelector.handleInput(InputAction.DOWN); // Auto-pick the plus again
          }
        }
        break;
      case UP:
        handleVerticalMove(action, cmdrBins.get(selectedBin).cmdrs, selectedColumn, -mmpr);
        break;
      case DOWN:
        handleVerticalMove(action, cmdrBins.get(selectedBin).cmdrs, selectedColumn, mmpr);
        break;
      case LEFT:
      case RIGHT:
      {
        rightGlueColumn = cmdrInBinSelector.handleInput(action) % mmpr;
      }
      break;
      case BACK:
        if( !myControl.shouldSelectMultiCO )
        {
          // Cancel: return control without applying changes.
          done = true;
        }
        else
        {
          amPickingTagIndex = true;
        }
        break;
      case SEEK:
        // Flip our filtering around
        sortByGameThenFaction = !sortByGameThenFaction;
        initBins(selectedCO);
        break;
      case VIEWMODE:
        myControl.startViewingCmdrInfo(selectedCO);
        break;
      default:
        // Do nothing.
    }
    return done;
  } // ~handleCmdrChoiceInput
  private static void handleVerticalMove(InputAction action, ArrayList<Integer> currentBin, int selectedColumn, int jump)
  {
    int jumpValue = Math.abs(jump);
    int maxVisualBin = (currentBin.size() - 1) / jumpValue + 1;
    int currentVisualBin = selectedColumn / jumpValue;
    int destShift = Math.max(selectedColumn % jumpValue, rightGlueColumn % jumpValue);
    if (jump > 0)
      ++currentVisualBin;
    else
      --currentVisualBin;
    if( 0 <= currentVisualBin && currentVisualBin < maxVisualBin ) // We're visually wrapping, so go down *within* this bin
    {
      final int destBinSize = cmdrBins.get(innerCategorySelector.getSelectionNormalized()).cmdrs.size();
      int destColumn = Math.min(destBinSize-1, currentVisualBin*jumpValue + destShift);

      cmdrInBinSelector.setSelectedOption(destColumn);
      rightGlueColumn = destShift;
      return;
    }

    final int binPicked = innerCategorySelector.handleInput(action);
    final int destBinSize = cmdrBins.get(binPicked).cmdrs.size();
    // Selection column clamps to the max for the new bin
    cmdrInBinSelector.reset(destBinSize);

    currentVisualBin = 0;
    if( jump < 0 )
      currentVisualBin = (destBinSize / jump) * jump;

    int destColumn = Math.min(destBinSize-1, currentVisualBin*jumpValue + destShift);
    destColumn = Math.min(destBinSize-1, destColumn);
    cmdrInBinSelector.setSelectedOption(destColumn);
  }

  private static int getMaxMugsPerRow()
  {
    int drawScale = SpriteOptions.getDrawScale();
    Dimension dimensions = SpriteOptions.getScreenDimensions();
    int myWidth = dimensions.width / drawScale - leftMargin - SpriteLibrary.getCursorSprites().getFrame(0).getWidth();
    int xPerMug = CommanderPanel.PANEL_WIDTH + mugPanelBuffer;
    return (int) Math.floor(myWidth * 1.0 / xPerMug);
  }


  public static void syncTagIndexSelector()
  {
    final int tagIndex = tagIndexSelector.getSelectionNormalized();
    tagIndexSelector.reset(tagCmdrList.size() + 1);
    tagIndexSelector.setSelectedOption(tagIndex);
  }

}
