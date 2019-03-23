package UI;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;

import javax.imageio.ImageIO;

import UI.Art.SpriteArtist.ColorPalette;

public class UIUtils
{  
  public static final String DEFAULT_FACTION_NAME = "Thorn";

  // Define extra colors as needed.
  private static final Color PURPLE = new Color(231, 123, 255 );

  // Map Building colors.
  public static final Color[] defaultMapColors = { new Color(40, 40, 40), new Color(70, 70, 70), new Color(110, 110, 110), new Color(160, 160, 160),
      new Color(200, 200, 200), new Color(231, 231, 231) };
  private static Color[] pinkMapBuildingColors = { new Color(142, 26, 26), new Color(255, 219, 74), new Color(190, 90, 90), new Color(240, 140, 140),
      new Color(250, 190, 190), new Color(255, 245, 245) };
  private static Color[] cyanMapBuildingColors = { new Color(0, 105, 105), new Color(255, 219, 74), new Color(77, 157, 157), new Color(130, 200, 200),
      new Color(200, 230, 230), new Color(245, 255, 255) };
  private static Color[] orangeMapBuildingColors = { new Color(130, 56, 0), new Color(255, 237, 29), new Color(139, 77, 20), new Color(231, 139, 41),
      new Color(243, 186, 121), new Color(255, 234, 204) };
  private static Color[] purpleMapBuildingColors = { new Color(90, 14, 99), new Color(255, 207, 95), new Color(133, 65, 130), new Color(174, 115, 189),
    new Color(222, 171, 240), new Color(255, 231, 255) };

  // Map Unit colors.
  private static Color[] pinkMapUnitColors = { new Color(142, 26, 26), new Color(199, 62, 62), new Color(248, 100, 100), new Color(255, 136, 136),
      new Color(255, 175, 175), new Color(255, 201, 201) };
  private static Color[] cyanMapUnitColors = { new Color(0, 105, 105), new Color(0, 170, 170), new Color(0, 215, 215), new Color(0, 245, 245),
      new Color(121, 255, 255), new Color(195, 255, 255), };
  private static Color[] orangeMapUnitColors = { new Color(130, 56, 0), new Color(204, 103, 7), new Color(245, 130, 14), new Color(255, 160, 30),
      new Color(255, 186, 60), new Color(255, 225, 142), };
  private static Color[] purpleMapUnitColors = { new Color(90, 14, 99), new Color(132, 41, 148), new Color(181, 62, 198), new Color(201, 98, 223),
    new Color(231, 123, 255), new Color(243, 180, 255), };

  private static HashMap<Color, ColorPalette> buildingColorPalettes;
  private static HashMap<Color, ColorPalette> mapUnitColorPalettes;
  private static HashMap<Color, String> colorNames;
  private static ArrayList<Faction> factions;

  
  /**
   * Parses the available palettes and faction unit images.
   * Both of the above are parsed from res/unit/faction.
   * For palettes:
   *   Reads in the first 6 colors on each row.
   *   Bottom colors are buildings, top are units.
   *   The key color is the last color on the top row.
   * For factions:
   *   The name of the folder is simply collected; no further work is done until units are drawn.
   */
  private static void initCosmetics()
  {
    if (null == mapUnitColorPalettes )
    {
      buildingColorPalettes = new HashMap<Color, ColorPalette>();
      mapUnitColorPalettes = new HashMap<Color, ColorPalette>();
      colorNames = new HashMap<Color, String>();
      factions = new ArrayList<Faction>();

      // Create a mapping of game colors to the fine-tuned colors that will be used for map sprites.
      buildingColorPalettes.put(Color.PINK, new ColorPalette(pinkMapBuildingColors));
      buildingColorPalettes.put(Color.CYAN, new ColorPalette(cyanMapBuildingColors));
      buildingColorPalettes.put(Color.ORANGE, new ColorPalette(orangeMapBuildingColors));
      buildingColorPalettes.put(PURPLE, new ColorPalette(purpleMapBuildingColors));

      mapUnitColorPalettes.put(Color.PINK, new ColorPalette(pinkMapUnitColors));
      mapUnitColorPalettes.put(Color.CYAN, new ColorPalette(cyanMapUnitColors));
      mapUnitColorPalettes.put(Color.ORANGE, new ColorPalette(orangeMapUnitColors));
      mapUnitColorPalettes.put(PURPLE, new ColorPalette(purpleMapUnitColors));

      // Throw some color names in there for the defaults
      // toString() is not user-friendly
      colorNames.put(Color.PINK, "salmon");
      colorNames.put(Color.CYAN, "cyan");
      colorNames.put(Color.ORANGE, "citrus");
      colorNames.put(PURPLE, "sparking");

      // We want to be able to use the normal units, as well as any others
      factions.add(new Faction(DEFAULT_FACTION_NAME,DEFAULT_FACTION_NAME));

      final File folder = new File("res/unit/faction");

      if (folder.canRead())
      {
        for( final File fileEntry : folder.listFiles() )
        {
          // If it's a file, we assume it's a palette
          if( !fileEntry.isDirectory() )
          {
            String colorName = fileEntry.getAbsolutePath();
            if( colorName.contains(".png") )
            {
              try
              {
                BufferedImage bi = ImageIO.read(new File(colorName));
                // Grab the last color on the first row as our "banner" color
                Color key = new Color(bi.getRGB(bi.getWidth() - 1, 0));
                Color[] cUnits = new Color[defaultMapColors.length];
                Color[] cStructs = new Color[defaultMapColors.length];
                for( int i = 0; i < defaultMapColors.length; i++ )
                {
                  cUnits[i] = new Color(bi.getRGB(i, 0));
                  cStructs[i] = new Color(bi.getRGB(i, 1));
                }
                buildingColorPalettes.put(key, new ColorPalette(cStructs));
                mapUnitColorPalettes.put(key, new ColorPalette(cUnits));
                colorNames.put(key, fileEntry.getName().replace(".png", ""));
              }
              catch (IOException ioex)
              {
                System.out.println("WARNING! Exception loading palette " + colorName);
              }
            }
          }
          // If it's a directory, we assume it's a set of map sprites, i.e. a faction.
          else if(fileEntry.canRead() && !fileEntry.getName().endsWith(DEFAULT_FACTION_NAME)) // However, we don't wanna add our default twice
          {
            String basis = DEFAULT_FACTION_NAME;
            String basisPath = fileEntry.getAbsolutePath() + "/basis.txt";
            if( new File(basisPath).canRead() )
            {
              try
              {
                BufferedReader br = new BufferedReader(new FileReader(basisPath));
                basis = br.readLine().trim();
                br.close();
              }
              catch (IOException ioex)
              {
                System.out.println("WARNING! Exception loading faction basis " + basisPath + ". Defaulting to sprites from " + DEFAULT_FACTION_NAME);
              }
            }
            
            factions.add(new Faction(fileEntry.getName(), basis));
          }
        }
      }
    }
  }
  
  public static Color[] getCOColors()
  {
    initCosmetics();
    return mapUnitColorPalettes.keySet().toArray(new Color[0]);
  }

  public static ColorPalette getBuildingColors(Color colorKey)
  {
    initCosmetics();
    ColorPalette palette = buildingColorPalettes.get(colorKey);
    if (null == palette) // Uh oh, the player's messing with us. Make stuff up so we don't crash.
    {
      buildingColorPalettes.put(colorKey, buildingColorPalettes.get(Color.PINK));
      palette = buildingColorPalettes.get(colorKey);
    }
    return palette;
  }

  public static ColorPalette getMapUnitColors(Color colorKey)
  {
    initCosmetics();
    ColorPalette palette = mapUnitColorPalettes.get(colorKey);
    if (null == palette) // Uh oh, the player's messing with us. Make stuff up so we don't crash.
    {
      mapUnitColorPalettes.put(colorKey, mapUnitColorPalettes.get(Color.PINK));
      palette = mapUnitColorPalettes.get(colorKey);
    }
    return palette;
  }

  public static String getColorName(Color colorKey)
  {
    initCosmetics();
    return colorNames.get(colorKey);
  }

  public static Faction[] getFactions()
  {
    initCosmetics();
    return factions.toArray(new Faction[0]);
  }
  
  public static class Faction implements Serializable
  {
    public String name;
    public String basis;
    
    public Faction(String pName, String pBasis)
    {
      name = pName;
      basis = pBasis;
    }
  }
}