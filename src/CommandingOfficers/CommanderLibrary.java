package CommandingOfficers;

import java.awt.Color;
import java.util.ArrayList;

public class CommanderLibrary
{  
  private static ArrayList<CommanderInfo> commanderList = null;

  public static ArrayList<CommanderInfo> getCommanderList()
  {
    if( null == commanderList )
    {
      buildCommanderList();
    }
    return commanderList;
  }

  private static void buildCommanderList()
  {
    commanderList = new ArrayList<CommanderInfo>();
    commanderList.add( CommanderStrong.getInfo() );
    commanderList.add( CommanderPatch.getInfo() );
    commanderList.add( CommanderBear_Bull.getInfo() );
    commanderList.add( CommanderCinder.getInfo() );
    
    commanderList.add( OSAndy.getInfo() );
    commanderList.add( OSHachi.getInfo() );
    commanderList.add( OSJake.getInfo() );
    commanderList.add( OSMax.getInfo() );
    commanderList.add( OSRachel.getInfo() );
    commanderList.add( OSSami.getInfo() );
    
    commanderList.add( BMColin.getInfo() );
    commanderList.add( BMGrit.getInfo() );
    commanderList.add( BMOlaf.getInfo() );
    commanderList.add( BMSasha.getInfo() );
    
    commanderList.add( GEEagle.getInfo() );
    commanderList.add( GEJess.getInfo() );
    commanderList.add( GEDrake.getInfo() );
    
    commanderList.add( YCGrimm.getInfo() );
    commanderList.add( YCKanbei.getInfo() );
    commanderList.add( YCSensei.getInfo() );
    
    commanderList.add( BHAdder.getInfo() );
    commanderList.add( BHHawke.getInfo() );
    commanderList.add( BHKindle.getInfo() );
    commanderList.add( BHLash.getInfo() );
    commanderList.add( BHSturm.getInfo() );
    commanderList.add( BHVB.getInfo() );

    commanderList.add( BWBrennerCS.getInfo() );
    commanderList.add( BWBrennerComp.getInfo() );
    commanderList.add( BWBrennerBasic.getInfo() );
    commanderList.add( BWIsabellaCS.getInfo() );
    commanderList.add( BWIsabellaBasic.getInfo() );
    commanderList.add( BWLinCS.getInfo() );
    commanderList.add( BWWillCS.getInfo() );
    commanderList.add( BWWillBasic.getInfo() );

    commanderList.add( LAForsythe.getInfo() );
    commanderList.add( LAGageCS.getInfo() );
    commanderList.add( LAGageBasic.getInfo() );
    commanderList.add( LATasha1star.getInfo() );
    commanderList.add( LATashaLame.getInfo() );
    commanderList.add( LATasha40A.getInfo() );
    commanderList.add( LATashaBasic.getInfo() );

    commanderList.add( RAWaylonCS.getInfo() );
    commanderList.add( RAWaylonBasic.getInfo() );

    commanderList.add( IDSCaulderD2D.getInfo() );
    commanderList.add( IDSCaulderSCOP.getInfo() );
    commanderList.add( IDSPennyCS.getInfo() );
    commanderList.add( IDSPennyRNG.getInfo() );
    commanderList.add( IDSPennyBasic.getInfo() );
    commanderList.add( IDSTabithaCS.getInfo() );
    commanderList.add( IDSTabithaBasic.getInfo() );

    commanderList.add( TheBeast.getInfo() );
  }

  public static Commander makeCommander( CommanderInfo info, Color color, String faction )
  {
    Commander co = info.maker.create();

    co.myColor = color;
    co.factionName = faction;

    return co;
  }
}
