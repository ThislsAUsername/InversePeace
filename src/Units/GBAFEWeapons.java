package Units;

import Engine.Combat.CombatContext;
import Engine.UnitMods.UnitModifierWithDefaults;
import Terrain.TerrainType;
import Units.GBAFEUnits.GBAFEStats;
import Units.GBAFEUnits.GBAFEUnitModel;

public class GBAFEWeapons
{
  // Speed - defender speed >= this value -> double-attack
  public final static int PURSUIT_THRESHOLD     = 4;
  // Effective-damage weapon might multiplier
  // (Nerfed to 2x in some of the games... in English; 2x always goes for dragons, but I don't believe in dragons)
  public final static int SLAYER_BONUS          = 3;
  // Go with the FE6 value for the memes (it's 15 in the other two)
  public final static int CRIT_BOOST_BONUS      = 30;
  public final static int SKILL_ACTIVATION_PERCENT = 20;

  public final static int TERRAIN_DURABILITY = 99;

  // Since units can have more than one weapon type available to them, we need to scale between maximum WTA and none
  // Also, "Might" is right - it applies to effective damage
  public enum WTATier
  {
    NONE(0,  0),
    // Treat the 1 damage as the initial 50% loss, so the remainder is 10% ~= 3 hit
    MAX (1, 15),
    badMAX(MAX),
    // ~25% advantage to the one who beats the shared weapon (if not paired equally)
    _2v2(0, 7),
    bad2v2(_2v2),
    // ~40% advantage
    _3v2(0, 12),
    bad3v2(_3v2);
    public final int might, hit;
    WTATier(int might, int hit)
    {
      this.might = might;
      this.hit   = hit;
    }
    WTATier(WTATier toInvert)
    {
      this.might = -1 * toInvert.might;
      this.hit   = -1 * toInvert.hit;
    }
  }

  private static class GBAFECombatStats
  {
    public int attack = 0;
    public int hit    = 0;
    public int crit   = 0;
    public int hitCount = 1;
    public boolean sureShot  = false;
    public boolean pierce    = false;
    public boolean lethality = false;
    // Defender attributes
    public int hp        = 0;
    public int defense   = 0;
    public int critAvoid = 0;
    public int avoid     = 0;
    public boolean pavise    = false;
    public static GBAFECombatStats vsTerrain(GBAFEWeapon wep)
    {
      GBAFECombatStats result = new GBAFECombatStats();
      result.attack = wep.stats.Str + wep.might;
      result.hit    = 100;
      result.hp = TERRAIN_DURABILITY;
      return result;
    }
    public static GBAFECombatStats build(GBAFEWeapon wep, GBAFEUnitModel defender, GBAFEWeapon defWep)
    {
      WTATier wta = wep.calcWTA(defWep);
      GBAFECombatStats result = new GBAFECombatStats();
      result.attack = wep.might + wta.might;
      if(    (wep.slaysAir   && defender.isAirUnit())
          || (wep.slaysArmor && defender.isArmor)
          || (wep.slaysHorse && defender.isHorse)
          )
        result.attack *= SLAYER_BONUS;
      result.attack += wep.stats.Str;
      result.hit    = wep.stats.calcHitFromStats()  + wep.hit + wta.hit;
      result.crit   = wep.stats.calcCritFromStats() + wep.crit;
      if( wep.stats.critBoost )
        result.crit += CRIT_BOOST_BONUS;
      result.sureShot  = wep.stats.sureShot ;
      result.pierce    = wep.stats.pierce   ;
      result.lethality = wep.stats.lethality;
      if( wep.canCounter && (wep.stats.Spd - defender.stats.Spd) >= PURSUIT_THRESHOLD )
        result.hitCount = 2;
      result.hp        = defender.stats.HP;
      // Skip terrain bonuses since AW has its own math for that...
      if( !wep.luna )
      {
        if( wep.isMagic() )
          result.defense = defender.stats.Res;
        else
          result.defense = defender.stats.Def;
      }
      result.critAvoid = defender.stats.calcCritAvoidFromStats();
      result.avoid     = defender.stats.calcAvoid();
      result.pavise    = defender.stats.pavise;
      return result;
    }

    public int calcDamage()
    {
      final int feGUIhit = Math.min(100, Math.max(0, hit - avoid));
      int trueHitPercent = calcTrueHit(feGUIhit);
      if( sureShot )
        trueHitPercent += (100 - trueHitPercent) * SKILL_ACTIVATION_PERCENT/100;

      int rawDamage = calcRawDamage();
      int finalDamage = rawDamage * trueHitPercent/100;
      if( rawDamage > 0 && feGUIhit > 0 )
        return Math.max(1, finalDamage);
      return finalDamage;
    }
    // Credit https://www.reddit.com/r/fireemblem/comments/4jpw4f/true_hit_formula_2rn_system/
    public static int calcTrueHit(int hit)
    {
      if( hit <= 50 )
        return (2*hit*hit + hit) / 100;
      return (-2*hit*hit  + 399*hit - 9900) / 100;
    }
    private int calcRawDamage()
    {
      final int feGUIdamage = Math.max(0, attack - defense);
      int damage = feGUIdamage;

      final int feGUIcrit = Math.min(100, Math.max(0, crit - critAvoid));
      damage = diluteInDamage(feGUIdamage, feGUIcrit, feGUIdamage*3);

      if( pierce )
      {
        int pierceDamage = attack;
        pierceDamage = diluteInDamage(attack, feGUIcrit, attack*3); // Account for pierce-crits
        damage = diluteInDamage(damage, SKILL_ACTIVATION_PERCENT, pierceDamage);
      }
      if( pavise ) // Pavise/Great Shield takes priority over sources of ordinary damage, but cannot stop Silencer
      {
        damage = diluteInDamage(damage, SKILL_ACTIVATION_PERCENT, 0);
      }
      if( lethality && hp > feGUIdamage*3 )
      {
        int lethalDamage = hp;
        int lethalChance = feGUIcrit/2;
        damage = diluteInDamage(damage, lethalChance, lethalDamage);
      }

      return damage * hitCount * 100/hp;
    }
    // Returns the initial damage modified by the expected change in damage when you do rareDamage chance% of the time 
    private static int diluteInDamage(int damage, int chance, int rareDamage)
    {
      int damageDiff = rareDamage - damage;
      int modifiedDamage = damage + (damageDiff * chance/100);
      return modifiedDamage;
    }
  }

  private static class GBAFEWeapon extends WeaponModel
  {
    private static final long serialVersionUID = 1L;
    private final static boolean infiniteAmmo = true;

    public int might = 0;
    public int hit   = 0;
    public int crit  = 0;
    public final GBAFEStats stats;
    public boolean hitsAir    = true;
    public boolean slaysAir   = false;
    public boolean slaysArmor = false;
    public boolean slaysHorse = false;
    public boolean canCounter = true;
    public boolean luna       = false;
    protected GBAFEWeapon(GBAFEStats stats, int might, int hit, int crit, int minRange, int maxRange)
    {
      super(infiniteAmmo, minRange, maxRange);
      this.stats = stats;
      this.might = might;
      this.hit   = hit;
      this.crit  = crit;
      canFireAfterMoving = true;
    }
    protected GBAFEWeapon(GBAFEStats stats, int might, int hit, int crit)
    {
      this(stats, might, hit, crit, 1, 1);
    }
    public GBAFEWeapon(GBAFEWeapon other)
    {
      this(other.stats, other.might, other.hit, other.crit, other.rangeMin, other.rangeMax);
      canFireAfterMoving = other.canFireAfterMoving;
      hitsAir    = other.hitsAir   ;
      slaysAir   = other.slaysAir  ;
      slaysArmor = other.slaysArmor;
      slaysHorse = other.slaysHorse;
      canCounter = other.canCounter;
      luna       = other.luna;
      axe   = other.axe  ;
      lance = other.lance;
      sword = other.sword;
      anima = other.anima;
      light = other.light;
      dark  = other.dark ;
    }
    public GBAFEWeapon(GBAFEStats stats, GBAFEWeapon[] subweapons)
    {
      super(infiniteAmmo, subweapons[0].rangeMin, subweapons[0].rangeMax);
      this.stats = stats;
      for(GBAFEWeapon wep : subweapons)
      {
        might += wep.might;
        hit   += wep.hit  ;
        crit  += wep.crit ;
      }
      might /= subweapons.length;
      hit   /= subweapons.length;
      crit  /= subweapons.length;
      canFireAfterMoving = true;
    }
    @Override
    public WeaponModel clone()
    {
      return new GBAFEWeapon(this);
    }

    @Override
    public double getDamage(GBAFEUnitModel defender)
    {
      if( !hitsAir && defender.isAirUnit() )
        return 0;
      // To simplify, we will just use the first weapon in the list
      // This can give wrong answers in obvious cases like "attack a Nomad Trooper at 2 range with a hand axe; take weapon triangle disadvantage and then eat a bow counter"
      // However, this gives stable numbers in a system that's already super overcomplicated, so we're rolling with it.
      GBAFEWeapon defWep = null;
      if(defender.weapons.size() > 0)
        defWep = (GBAFEWeapon) defender.weapons.get(0);

      GBAFECombatStats combat = GBAFECombatStats.build(this, defender, defWep);
      return combat.calcDamage();
    }

    @Override
    public double getDamage(TerrainType target)
    {
      if( TerrainType.METEOR == target )
      {
        GBAFECombatStats combat = GBAFECombatStats.vsTerrain(this);
        return combat.calcDamage();
      }
      return 0;
    }

    // A "weapon" here actually represents *all* of the available weapon types at this range.
    // We assume all weapons owned by a unit that shoot at a given range will have the same triangle coverage,
    //   since otherwise the game logic has to make hard decisions like "Do I take the slayer weapon, or the one with WTA?".
    // ...and don't even talk to me about triangle-reversing weapons.
    // For the same reason, we omit weapon weight as a mechanic entirely.
    public boolean axe   = false;
    public boolean lance = false;
    public boolean sword = false;
    public boolean anima = false;
    public boolean light = false;
    public boolean dark  = false;
    public boolean isMagic()
    {
      return anima || light || dark;
    }
    public WTATier calcWTA(GBAFEWeapon other)
    {
      WTACalc myCalc = new WTACalc(this);
      return myCalc.calcWTA(other);
    }
  }

  public static class IronLance extends GBAFEWeapon
  {
    private static final long serialVersionUID = 1L;
    public IronLance(GBAFEStats stats)
    {
      // 70 hit in FE6
      super(stats, 7, 80, 0);
      lance = true;
    }
  }
  public static class IronSword extends GBAFEWeapon
  {
    private static final long serialVersionUID = 1L;
    public IronSword(GBAFEStats stats)
    {
      // 85 hit in FE6
      super(stats, 5, 90, 0);
      sword = true;
    }
  }
  public static class IronAxe extends GBAFEWeapon
  {
    private static final long serialVersionUID = 1L;
    public IronAxe(GBAFEStats stats)
    {
      // 65 hit in FE6
      super(stats, 8, 75, 0);
      axe = true;
    }
  }
  public static class IronHero extends GBAFEWeapon
  {
    private static final long serialVersionUID = 1L;
    private static final GBAFEWeapon[] subWeapons = {
        new GBAFEWeapons.IronAxe(null),
        new GBAFEWeapons.IronSword(null),
        };
    public IronHero(GBAFEStats stats)
    {
      super(stats, subWeapons);
      axe = true;
      sword = true;
    }
  }
  public static class IronCav extends GBAFEWeapon
  {
    private static final long serialVersionUID = 1L;
    private static final GBAFEWeapon[] subWeapons = {
        new GBAFEWeapons.IronLance(null),
        new GBAFEWeapons.IronSword(null),
        };
    public IronCav(GBAFEStats stats)
    {
      super(stats, subWeapons);
      lance = true;
      sword = true;
    }
  }
  public static class IronTriangle extends GBAFEWeapon
  {
    private static final long serialVersionUID = 1L;
    private static final GBAFEWeapon[] subWeapons = {
        new GBAFEWeapons.IronAxe(null),
        new GBAFEWeapons.IronLance(null),
        new GBAFEWeapons.IronSword(null),
        };
    public IronTriangle(GBAFEStats stats)
    {
      super(stats, subWeapons);
      axe = true;
      lance = true;
      sword = true;
    }
  }

  public static class Javelin extends GBAFEWeapon
  {
    private static final long serialVersionUID = 1L;
    private static final int MIN_RANGE = 1;
    private static final int MAX_RANGE = 2;
    public Javelin(GBAFEStats stats)
    {
      // 55 hit in FE6
      super(stats, 6, 65, 0, MIN_RANGE, MAX_RANGE);
      lance = true;
    }
  }
  public static class HandAxe extends GBAFEWeapon
  {
    private static final long serialVersionUID = 1L;
    private static final int MIN_RANGE = 1;
    private static final int MAX_RANGE = 2;
    public HandAxe(GBAFEStats stats)
    {
      // 50 hit in FE6
      super(stats, 7, 60, 0, MIN_RANGE, MAX_RANGE);
      axe = true;
    }
  }
  public static class Javelina extends GBAFEWeapon
  {
    private static final long serialVersionUID = 1L;
    private static final GBAFEWeapon[] subWeapons = {
        new GBAFEWeapons.Javelin(null),
        new GBAFEWeapons.HandAxe(null),
        };
    public Javelina(GBAFEStats stats)
    {
      super(stats, subWeapons);
      axe = true;
      lance = true;
    }
  }

  public static class IronBow extends GBAFEWeapon
  {
    private static final long serialVersionUID = 1L;
    private static final int MIN_RANGE = 2;
    private static final int MAX_RANGE = 2;
    public IronBow(GBAFEStats stats)
    {
      // 80 hit in FE6
      super(stats, 6, 85, 0, MIN_RANGE, MAX_RANGE);
      slaysAir = true;
    }
  }

  public static class Fire extends GBAFEWeapon
  {
    private static final long serialVersionUID = 1L;
    private static final int MIN_RANGE = 1;
    private static final int MAX_RANGE = 2;
    public Fire(GBAFEStats stats)
    {
      // 95 hit in FE6
      super(stats, 5, 90, 0, MIN_RANGE, MAX_RANGE);
      anima = true;
    }
  }
  public static class Flux extends GBAFEWeapon
  {
    private static final long serialVersionUID = 1L;
    private static final int MIN_RANGE = 1;
    private static final int MAX_RANGE = 2;
    public Flux(GBAFEStats stats)
    {
      // 8 might 70 hit in FE6
      super(stats, 7, 80, 0, MIN_RANGE, MAX_RANGE);
      dark = true;
    }
  }
  public static class Lightning extends GBAFEWeapon
  {
    private static final long serialVersionUID = 1L;
    private static final int MIN_RANGE = 1;
    private static final int MAX_RANGE = 2;
    public Lightning(GBAFEStats stats)
    {
      // 5 might 75 hit 0 crit in FE6
      super(stats, 4, 95, 5, MIN_RANGE, MAX_RANGE);
      light = true;
    }
  }

  // Specialty weapons

  public static class Hammer extends GBAFEWeapon
  {
    private static final long serialVersionUID = 1L;
    public Hammer(GBAFEStats stats)
    {
      // 8 might 45 hit in FE6
      super(stats, 10, 55, 0);
      axe = true;
      slaysArmor = true;
    }
  }

  public static class Poleaxe extends GBAFEWeapon
  {
    private static final long serialVersionUID = 1L;
    public Poleaxe(GBAFEStats stats)
    {
      // 55 hit in FE6
      super(stats, 10, 60, 0);
      axe = true;
      slaysHorse = true;
    }
  }
  public static class Horseslayer extends GBAFEWeapon
  {
    private static final long serialVersionUID = 1L;
    public Horseslayer(GBAFEStats stats)
    {
      // 11 might 75 hit in FE6
      super(stats, 7, 70, 0);
      lance = true;
      slaysHorse = true;
    }
  }
  public static class GeneralWeapons extends GBAFEWeapon
  {
    private static final long serialVersionUID = 1L;
    private static final GBAFEWeapon[] subWeapons = {
        new GBAFEWeapons.Poleaxe(null),
        new GBAFEWeapons.Horseslayer(null),
        };
    public GeneralWeapons(GBAFEStats stats)
    {
      super(stats, subWeapons);
      axe = true;
      lance = true;
    }
  }

  public static class Longbow extends GBAFEWeapon
  {
    private static final long serialVersionUID = 1L;
    private static final int MIN_RANGE = 2;
    private static final int MAX_RANGE = 3;
    public Longbow(GBAFEStats stats)
    {
      // 55 hit in FE6
      super(stats, 5, 65, 0, MIN_RANGE, MAX_RANGE);
      slaysAir = true;
    }
  }

  public static class KillingEdge extends GBAFEWeapon
  {
    private static final long serialVersionUID = 1L;
    public KillingEdge(GBAFEStats stats)
    {
      // 80 hit in FE6
      super(stats, 9, 75, 30);
      sword = true;
    }
  }
  public static class KillerBow extends GBAFEWeapon
  {
    private static final long serialVersionUID = 1L;
    private static final int MIN_RANGE = 2;
    private static final int MAX_RANGE = 2;
    public KillerBow(GBAFEStats stats)
    {
      // 80 hit in FE6
      super(stats, 9, 75, 30, MIN_RANGE, MAX_RANGE);
      slaysAir = true;
    }
  }

  public static class Luna extends GBAFEWeapon
  {
    private static final long serialVersionUID = 1L;
    private static final int MIN_RANGE = 1;
    private static final int MAX_RANGE = 2;
    public Luna(GBAFEStats stats)
    {
      // 95 hit in FE7, 10 crit in international FE8
      super(stats, 0, 50, 20, MIN_RANGE, MAX_RANGE);
      dark = true;
      luna = true;
    }
  }

  // Siege weapons

  public static class Bolting extends GBAFEWeapon
  {
    private static final long serialVersionUID = 1L;
    private static final int MIN_RANGE = 3;
    private static final int MAX_RANGE = 10;
    public Bolting(GBAFEStats stats)
    {
      // 70 hit in FE6
      super(stats, 12, 60, 0, MIN_RANGE, MAX_RANGE);
      anima = true;
      canCounter = false;
    }
  }
  public static class Ballista extends GBAFEWeapon
  {
    private static final long serialVersionUID = 1L;
    private static final int MIN_RANGE = 3;
    private static final int MAX_RANGE = 10;
    public Ballista(GBAFEStats stats)
    {
      // 60 hit in FE8
      super(stats, 8, 70, 0, MIN_RANGE, MAX_RANGE);
      canCounter = false;
      slaysAir   = true;
    }
  }
  public static class KillerBallista extends GBAFEWeapon
  {
    private static final long serialVersionUID = 1L;
    private static final int MIN_RANGE = 3;
    private static final int MAX_RANGE = 10;
    public KillerBallista(GBAFEStats stats)
    {
      super(stats, 12, 60, 10, MIN_RANGE, MAX_RANGE);
      canCounter = false;
      slaysAir   = true;
    }
  }
  public static class Trebuchet extends GBAFEWeapon
  {
    private static final long serialVersionUID = 1L;
    private static final int MIN_RANGE = 3;
    private static final int MAX_RANGE = 10;
    public Trebuchet(GBAFEStats stats)
    {
      // Custom; intent is to be unreasonably threatening, since this is the Bship/can't hit air
      super(stats, 8, 90, 30, MIN_RANGE, MAX_RANGE);
      canCounter = false;
      hitsAir    = false;
      slaysArmor = true;
      slaysHorse = true;
    }
  }

  public static class GBAFEFightMod implements UnitModifierWithDefaults
  {
    private static final long serialVersionUID = 1L;

    @Override
    public void changeCombatContext(CombatContext cc)
    {
      GBAFEWeapon startGun = (GBAFEWeapon) cc.attacker.weapon;
      GBAFEWeapon endGun   = (GBAFEWeapon) cc.defender.weapon;
      cc.canCounter = startGun.canCounter;
      cc.canCounter &= null != endGun && endGun.canCounter;
      if( cc.canCounter )
      {
        cc.canCounter &= endGun.rangeMax >= cc.battleRange;
        cc.canCounter &= endGun.rangeMin <= cc.battleRange;
      }
    }
  }

  /**
   * Cordon this code off in its own little class so I don't have to look at it again?
   * <p>Please?
   */
  private static class WTACalc
  {
    // Since magic and physical weapons don't actually interact, we can use abstract unions for our weapon triangle points
    public final boolean isMagic;
    public final boolean paper   ;
    public final boolean rock    ;
    public final boolean scissors;
    public WTACalc(GBAFEWeapon source)
    {
      isMagic = source.isMagic();
      // We just require that each pair beats the same pair
      paper    = source.axe   || source.anima;
      rock     = source.lance || source.light; // Rock should be axe, but rock beats scissors... oh, well.
      scissors = source.sword || source.dark;
    }
    // I really wish this was cleaner, but I can't think of anything that'd do it.
    public WTATier calcWTA(GBAFEWeapon target)
    {
      if( null == target )
        return WTATier.NONE; // Can't WTA against no weapon
      WTACalc other = new WTACalc(target);
      if( isMagic != other.isMagic )
        return WTATier.NONE; // We assume there's no physical/magic mix weapons

      final int myCount = triangleCount();
      final int otherCount = other.triangleCount();

      if( 0 == myCount || 0 == otherCount )
        return WTATier.NONE; // If either doesn't participate in the triangle, ignore the triangle

      if( 3 == myCount )
      {
        switch (otherCount)
        {
          case 3:  return WTATier.NONE;
          case 2:  return WTATier._3v2;
          default: return WTATier.MAX;
        }
      }

      if( 1 == myCount )
      {
        switch (otherCount)
        {
          case 3:  return WTATier.badMAX;
          case 2:
            boolean iLose = false;
            //       My weapon exists   Enemy can beat my weapon
            iLose |= paper           && other.scissors;
            iLose |= rock            && other.paper   ;
            iLose |= scissors        && other.rock    ;
            if( iLose )
              return WTATier.badMAX;
            return WTATier.NONE; // If I don't lose, it's a tie
          default:
            // We both have one weapon
            if( // Check for ties
                   (paper    && other.paper   )
                || (rock     && other.rock    )
                || (scissors && other.scissors)
              )
              return WTATier.NONE;
            // Someone wins. Is it me?
            boolean iWin = false;
            //      Target weapon exists      Can beat target weapon
            iWin |= other.paper            && scissors;
            iWin |= other.rock             && paper   ;
            iWin |= other.scissors         && rock    ;
            if( iWin )
              return WTATier.MAX;
            return WTATier.badMAX;
        }
      }

      // I definitely have 2 weapon types at this point.
      switch (otherCount)
      {
        case 3:  return WTATier.bad3v2;
        case 2:
        {
          boolean iWin = false;
          //       Share target weapon            Can beat target weapon
          iWin |= (paper    && other.paper   ) && scissors;
          iWin |= (rock     && other.rock    ) && paper   ;
          iWin |= (scissors && other.scissors) && rock    ;
          if( iWin )
            return WTATier._2v2;
          return WTATier.bad2v2;
        }
        default:
        {
          boolean iWin = false;
          //      Target weapon exists   Can beat target weapon
          iWin |= other.paper         && scissors;
          iWin |= other.rock          && paper   ;
          iWin |= other.scissors      && rock    ;
          if( iWin )
            return WTATier.MAX;
          return WTATier.NONE;
        }
      }
    }
    private int triangleCount()
    {
      int total = 0;
      if( paper    ) ++total;
      if( rock     ) ++total;
      if( scissors ) ++total;
      return total;
    }
  }

} //~GBAFEWeapons
