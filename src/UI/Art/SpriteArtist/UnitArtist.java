package UI.Art.SpriteArtist;

import java.awt.Graphics;

import Engine.GameInstance;
import UI.Art.Animation.GameAnimation.AnimState;
import Units.Unit;

public class UnitArtist
{
  private GameInstance myGame;
  private SpriteMapView myView;

  public UnitArtist(GameInstance game, SpriteMapView view)
  {
    myGame = game;
    myView = view;
  }

  /**
   * Draws any applicable unit icons for the unit, at the specified real location.
   * "Real" means that the specified x and y are that of the game's
   * underlying data model, not of the draw-space.
   */
  public void drawUnitIcons(Graphics g, Unit unit, int x, int y, int animIndex)
  {
    // Convert "real" location into a draw-space location, then draw icons.
    int drawX = (int) (myView.getTileSize() * x);
    int drawY = (int) (myView.getTileSize() * y);
    drawUnitIconsInDrawSpace(g, unit, drawX, drawY, animIndex);
  }
  /**
   * Draws any applicable unit icons for the unit, at the specified draw-space location.
   */
  public void drawUnitIconsInDrawSpace(Graphics g, Unit unit, int drawX, int drawY, int animIndex)
  {
    SpriteLibrary.getMapUnitSpriteSet(unit).drawUnitIcons(g, myGame.commanders, unit, animIndex, drawX, drawY);
  }

  /**
   * Allows drawing of a single unit, at a specified real location.
   * "Real" means that the specified x and y are that of the game's
   * underlying data model, not of the draw-space.
   */
  public void drawUnit(Graphics g, Unit unit, int x, int y, int animIndex)
  {
    // Convert "real" game-model location to a draw-space location.
    int drawX = (int) (myView.getTileSize() * x);
    int drawY = (int) (myView.getTileSize() * y);
    drawUnitInDrawSpace(g, unit, drawX, drawY, null, animIndex);
  }
  /**
   * Allows drawing of a single unit, at a specified draw-space location.
   */
  public void drawUnitInDrawSpace(Graphics g, Unit unit, int drawX, int drawY, AnimState state, int animIndex)
  {
    if( null == state )
    {
      boolean tired = unit.isStunned || (unit.isTurnOver && unit.CO == myGame.activeCO);
      state = (tired)? AnimState.TIRED : AnimState.IDLE;
    }

    // Draw the unit at the specified location.
    SpriteLibrary.getMapUnitSpriteSet(unit).drawUnit(g, state,
    animIndex, drawX, drawY, myView.getFlipUnitFacing(unit.CO));
  }
}
