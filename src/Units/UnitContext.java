package Units;

import java.util.ArrayList;
import java.util.List;

import CommandingOfficers.Commander;
import Engine.GamePath;
import Engine.XYCoord;
import Engine.UnitMods.UnitModifier;
import Terrain.Environment;
import Terrain.GameMap;

/**
 * A basic struct for details relevant to calculation of unit activities.
 * <p>Intended to be ephemeral, and thus free to modify (potentially destructively)
 */
public class UnitContext extends UnitState
{
  private static final long serialVersionUID = 1L;

  // Groups are set together
  public Unit unit;

  public GameMap map;

  public GamePath path;
  public XYCoord coord;

  public int maxHP;
  public int attackPower;
  public int defensePower;
  public int movePower;
  public int costBase;
  public double costMultiplier;
  public int costShift;

  public Environment env;
  public int terrainStars = 0;

  public WeaponModel weapon;

  public final List<UnitModifier> mods = new ArrayList<>();

  public UnitContext(GameMap map, Unit u, WeaponModel w, int x, int y)
  {
    this(u);
    this.map = map;
    weapon = w;
    coord = new XYCoord(x, y);
    setEnvironment(map.getEnvironment(coord));
  }
  public UnitContext(Unit u)
  {
    super(u);
    unit = u;
    coord = new XYCoord(u.x, u.y);
    heldUnits.addAll(u.heldUnits);
    mods.addAll(u.getModifiers());
    initModel();
  }
  public UnitContext(Commander co, UnitModel um)
  {
    super(co, um);
    initModel();
  }
  public UnitContext(UnitContext other)
  {
    super(other);
    unit = other.unit;
    path = other.path;
    coord = other.coord;
    maxHP = other.maxHP;
    attackPower = other.attackPower;
    defensePower = other.defensePower;
    env = other.env;
    terrainStars = other.terrainStars;
    weapon = other.weapon;
    mods.addAll(other.mods);
  }
  public void initModel()
  {
    maxHP = model.maxHP;
    attackPower = model.getDamageRatio();
    defensePower = model.getDefenseRatio();
    movePower = model.movePower;
    costBase = model.costBase;
    costMultiplier = model.costMultiplier;
    costShift = model.costShift;
  }

  public void setPath(GamePath pPath)
  {
    path = pPath;
    coord = path.getEndCoord();
  }

  public void setEnvironment(Environment input)
  {
    env = input;
    // Air units shouldn't get terrain defense
    if( null != env && !model.isAirUnit() )
      terrainStars = env.terrainType.getDefLevel();
  }

  @Override
  public String toString()
  {
    return model.name;
  }
}
