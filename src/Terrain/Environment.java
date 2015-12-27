package Terrain;

/**
 * Tile is a flyweight class - each Terrain/Weather combination is instantiated only once.
 * Subsequent calls to retrieve that tile will receive the same copy.
 */
public class Environment {
	public enum Terrains{PLAIN, FOREST, MOUNTAIN, ROAD, CITY, FACTORY, HQ, SHOAL, WATER, REEF};
	public enum Weathers{CLEAR, RAIN, SNOW, SANDSTORM};

	public final Terrains terrainType;
	public final Weathers weatherType;
	public final int defLevel;

	private static Environment[][] tileInstances = new Environment[Terrains.values().length][Weathers.values().length];

	/**
	 * Private constructor so that Tile can manage all of its flyweights.
	 */
	private Environment(Terrains tileType, Weathers weather)
	{
		terrainType = tileType;
		weatherType = weather;
		// If there's a better way to do this, I'm all ears
		switch(terrainType){
		case PLAIN:
			defLevel = 1;
			break;
		case FOREST:
			defLevel = 2;
			break;
		case MOUNTAIN:
			defLevel = 4;
			break;
		case ROAD:
			defLevel = 0;
			break;
		case CITY:
			defLevel = 3;
			break;
		case FACTORY:
			defLevel = 4;
			break;
		case HQ:
			defLevel = 5;
			break;
		case SHOAL:
			defLevel = 0;
			break;
		case WATER:
			defLevel = 1;
			break;
		case REEF:
			defLevel = 2;
			break;
		default:
			defLevel = -1;
			break;
		}
	}

	/**
	 * Returns the Tile flyweight matching the input parameters, creating it first if needed.
	 * @return
	 */
	public static Environment getTile(Terrains terrain, Weathers weather)
	{
		// If we don't have the desired Tile built already, create it.
		if(tileInstances[terrain.ordinal()][weather.ordinal()] == null)
		{
			System.out.println("Creating new Tile " + weather + ", " + terrain);
			tileInstances[terrain.ordinal()][weather.ordinal()] = new Environment(terrain, weather);
		}

		return tileInstances[terrain.ordinal()][weather.ordinal()];
	}
}
