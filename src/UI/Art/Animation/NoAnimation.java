package UI.Art.Animation;

import java.awt.Graphics;

public class NoAnimation extends GameAnimation
{

  public NoAnimation()
  {
    super(true);
  }

  @Override
  public boolean animate(Graphics g)
  {
    return true;
  }

  @Override
  public void cancel()
  {
  }
}
