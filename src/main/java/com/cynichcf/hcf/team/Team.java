package com.cynichcf.hcf.team;

import com.cheatbreaker.api.CheatBreakerAPI;
import com.cheatbreaker.api.object.CBWaypoint;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;

import com.cynichcf.hcf.HCF;
import com.cynichcf.hcf.chat.enums.ChatMode;
import com.cynichcf.hcf.events.region.cavern.CavernHandler;
import com.cynichcf.hcf.events.region.glowmtn.GlowHandler;
import com.cynichcf.hcf.persist.maps.DeathbanMap;
import com.cynichcf.hcf.persist.maps.KillsMap;
import com.cynichcf.hcf.team.claims.Claim;
import com.cynichcf.hcf.team.claims.LandBoard;
import com.cynichcf.hcf.team.claims.Subclaim;
import com.cynichcf.hcf.team.dtr.DTRBitmask;
import com.cynichcf.hcf.team.dtr.DTRHandler;
import com.cynichcf.hcf.team.enums.Tier;
import com.cynichcf.hcf.team.enums.Upgrades;
import com.cynichcf.hcf.team.track.TeamActionTracker;
import com.cynichcf.hcf.team.track.TeamActionType;
import com.cynichcf.hcf.util.CC;
import com.cynichcf.hcf.util.ChatUtils;
import com.cynichcf.hcf.util.CuboidRegion;
import com.mongodb.BasicDBList;
import com.mongodb.BasicDBObject;
import com.mongodb.DBCollection;
import lombok.Getter;
import lombok.Setter;
import mkremins.fanciful.FancyMessage;
import rip.lazze.libraries.Library;
import rip.lazze.libraries.framework.economy.FrozenEconomyHandler;
import rip.lazze.libraries.framework.redis.RedisCommand;
import rip.lazze.libraries.serializable.LocationSerializer;
import rip.lazze.libraries.util.TimeUtils;
import rip.lazze.libraries.util.UUIDUtils;
import rip.lazze.libraries.uuid.FrozenUUIDCache;
import net.minecraft.util.org.apache.commons.lang3.StringUtils;
import org.bson.types.ObjectId;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import redis.clients.jedis.Jedis;
import java.awt.*;
import java.text.DecimalFormat;
import java.util.*;
import java.util.List;

public class Team {

    // Constants //
    public static DecimalFormat DTR_FORMAT = new DecimalFormat("0.00");
    public static String GRAY_LINE = ChatColor.GRAY.toString() + ChatColor.STRIKETHROUGH + StringUtils.repeat("-", 53);
    public static ChatColor ALLY_COLOR = ChatColor.BLUE;
    public static int MAX_CLAIMS = 2;
    public static int MAX_FORCE_INVITES = 5;
    @Getter  private int abilityCooldownUpgrade = 1;

    // Internal //
    @Getter private boolean needsSave = false;
    @Getter private boolean loading = false;

    // Persisted //
    @Getter @Setter private ObjectId uniqueId;
    @Getter private String name;
    @Getter private Location HQ;
    protected boolean open;
    @Getter private RallyPoint Rally;
    @Getter private CBWaypoint HQWaypoint;
    @Getter private CBWaypoint TeamHQWaypoint;
    @Getter private CBWaypoint FocusedTeamHQWaypoint;
    @Getter private double balance;
    @Getter private double DTR;
    @Getter private long DTRCooldown;
    @Getter private List<Claim> claims = new ArrayList<>();
    @Getter private List<Subclaim> subclaims = new ArrayList<>();
    @Getter private UUID owner = null;
    @Getter private Set<UUID> members = new HashSet<>();
    @Getter private Set<UUID> captains = new HashSet<>();
    @Getter private Set<UUID> coleaders = new HashSet<>();
    @Getter private Set<UUID> invitations = new HashSet<>();
    @Getter private Set<UUID> subclaimPermissions = new HashSet<>();
    @Getter private Set<UUID> rallyPermissions = new HashSet<>();
    @Getter private Set<UUID> displayPermissions = new HashSet<>();
    @Getter private Set<ObjectId> allies = new HashSet<>();
    @Getter private Set<ObjectId> requestedAllies = new HashSet<>();
    @Getter private Set<String> usedUpgrades = new HashSet<>();
    @Getter private String announcement;
    @Getter private int maxOnline = -1;
    @Getter @Setter private int teamfightsWon = 0;
    @Getter private boolean powerFaction = false;
    @Getter private int lives = 0;
    @Getter private int points = 0;
    @Getter private int kills = 0;
    @Getter private int kothCaptures = 0;
    @Getter private int diamondsMined = 0;
    @Getter private int deaths = 0;
    @Getter private int citadelsCapped = 0;
    @Getter private int killstreakPoints = 0;

    @Getter private int spawnersInClaim = 0;
    @Getter private int spentPoints = 0; // points spent on faction upgrades (kinda aids)

    @Getter private Map<String, Integer> upgradeToTier = new HashMap<>();

    @Getter private int forceInvites = MAX_FORCE_INVITES;
    @Getter private Set<UUID> historicalMembers = new HashSet<>(); // this will store all players that were once members

    // Not persisted //
    @Getter @Setter private ChatColor teamColor;
    @Getter @Setter private UUID focused;
    @Getter @Setter private long lastRequestReport;

    @Getter @Setter private int bards;
    @Getter @Setter private int archers;
    @Getter @Setter private int rogues;

    private Team factionFocused;

    public Team(String name) {
        this.name = name;
    }

    public void setDTR(double newDTR) {
        setDTR(newDTR, null);
    }

    public void setDTR(double newDTR, Player actor) {
        if (DTR == newDTR) {
            return;
        }

        if (DTR <= 0 && newDTR > 0) {
            TeamActionTracker.logActionAsync(this, TeamActionType.TEAM_NO_LONGER_RAIDABLE, ImmutableMap.of());
        }

        if (0 < DTR && newDTR <= 0) {
            TeamActionTracker.logActionAsync(this, TeamActionType.TEAM_NOW_RAIDABLE, actor == null ? ImmutableMap.of() : ImmutableMap.of("actor", actor.getName()));
        }

      /*  if (!isLoading()) {
            if (actor != null) {
                HCF.getInstance().getLogger().info("[DTR Change] " + getName() + ": " + DTR + " --> " + newDTR + ". Actor: " + actor.getName());
            } else {
                HCF.getInstance().getLogger().info("[DTR Change] " + getName() + ": " + DTR + " --> " + newDTR);
            }
        } */

        this.DTR = newDTR;
        flagForSave();
    }

    public void setName(String name) {
        this.name = name;
        flagForSave();
    }

    public String getName(Player player) {
        if (name.equals(GlowHandler.getGlowTeamName()) && this.getMembers().size() == 0) {
            return ChatColor.GOLD + "Glowstone Mountain"; // override team name
        } else if (name.equals(CavernHandler.getCavernTeamName()) && this.getMembers().size() == 0) {
            return ChatColor.AQUA + "Cavern";
        } else if (owner == null) {
            if (hasDTRBitmask(DTRBitmask.SAFE_ZONE)) {
                switch (player.getWorld().getEnvironment()) {
                    case NETHER:
                        return (ChatColor.GREEN + "Nether Spawn");
                    case THE_END:
                        return (ChatColor.GREEN + "The End Safezone");
                }

                return (ChatColor.GREEN + "Spawn");
            } else if (hasDTRBitmask(DTRBitmask.KOTH)) {
                return (ChatColor.AQUA + getName() + ChatColor.GOLD + " KOTH");
            } else if (hasDTRBitmask(DTRBitmask.CITADEL)) {
                return (ChatColor.DARK_PURPLE + "Citadel");
            } else if (hasDTRBitmask(DTRBitmask.ROAD)) {
                return (ChatColor.GOLD + getName().replace("Road", " Road"));
            } else if (hasDTRBitmask(DTRBitmask.CONQUEST)) {
                return (ChatColor.YELLOW + "Conquest");
            }
        }

        if (isMember(player.getUniqueId())) {
            return (ChatColor.GREEN + getName());
        } else if (isAlly(player.getUniqueId())) {
            return (Team.ALLY_COLOR + getName());
        } else {
            return (ChatColor.RED + getName());
        }
    }

    public void setRally(RallyPoint rally) {
        if(this.Rally != null) getOnlineMembers().forEach(player -> CheatBreakerAPI.getInstance().removeWaypoint(player, this.Rally.getCbWaypoint()));
        if(rally != null) getOnlineMembers().forEach(player -> CheatBreakerAPI.getInstance().sendWaypoint(player, rally.getCbWaypoint()));
        this.Rally = rally;
    }

    public void setFactionHQRally(CBWaypoint teamhq) {
        if (this.TeamHQWaypoint != null) getOnlineMembers().forEach(player -> CheatBreakerAPI.getInstance().removeWaypoint(player, this.TeamHQWaypoint));
        if (teamhq != null) getOnlineMembers().forEach(player -> CheatBreakerAPI.getInstance().sendWaypoint(player, teamhq));
        this.TeamHQWaypoint = teamhq;
    }

    public void setFocusedFactionHQ(CBWaypoint focusedhq){
        if (this.FocusedTeamHQWaypoint != null) getOnlineMembers().forEach(player -> CheatBreakerAPI.getInstance().removeWaypoint(player, this.FocusedTeamHQWaypoint));
        if (focusedhq != null) getOnlineMembers().forEach(player -> CheatBreakerAPI.getInstance().sendWaypoint(player, focusedhq));
        this.FocusedTeamHQWaypoint = focusedhq;
    }

    public void setHQWaypoint(CBWaypoint rally) {
        if(this.HQWaypoint != null) getOnlineMembers().forEach(player -> CheatBreakerAPI.getInstance().removeWaypoint(player, this.HQWaypoint));
        if(rally != null) getOnlineMembers().forEach(player -> CheatBreakerAPI.getInstance().sendWaypoint(player, rally));

        this.HQWaypoint = rally;
    }


    public void addMember(UUID member) {
        if (members.add(member)) {
            sendWaypoint(member);
            historicalMembers.add(member);

            if (this.loading) return;
            TeamActionTracker.logActionAsync(this, TeamActionType.PLAYER_JOINED, ImmutableMap.of(
                    "playerId", member
            ));

            flagForSave();
        }
    }

    public void addCaptain(UUID captain) {
        if (captains.add(captain) && !this.isLoading()) {
            TeamActionTracker.logActionAsync(this, TeamActionType.PROMOTED_TO_CAPTAIN, ImmutableMap.of(
                    "playerId", captain
            ));

            flagForSave();
        }
    }

    public void addCoLeader(UUID co) {
        if (coleaders.add(co) && !this.isLoading()) {
            TeamActionTracker.logActionAsync(this, TeamActionType.PROMOTED_TO_CO_LEADER, ImmutableMap.of(
                    "playerId", co
            ));

            flagForSave();
        }
    }

    public void setBalance(double balance) {
        this.balance = balance;
        flagForSave();
    }

    public void setDTRCooldown(long dtrCooldown) {
        this.DTRCooldown = dtrCooldown;
        flagForSave();
    }

    public void removeCaptain(UUID captain) {
        if (captains.remove(captain)) {
            TeamActionTracker.logActionAsync(this, TeamActionType.DEMOTED_FROM_CAPTAIN, ImmutableMap.of(
                    "playerId", captain
            ));

            flagForSave();
        }
    }

    public void removeCoLeader(UUID co) {
        if (coleaders.remove(co)) {
            TeamActionTracker.logActionAsync(this, TeamActionType.DEMOTED_FROM_CO_LEADER, ImmutableMap.of(
                    "playerId", co
            ));

            flagForSave();
        }
    }

    public void setOwner(UUID owner) {
        this.owner = owner;

        if (owner != null) {
            members.add(owner);
            coleaders.remove(owner);
            captains.remove(owner);
        }

        if (this.loading) return;
        TeamActionTracker.logActionAsync(this, TeamActionType.LEADER_CHANGED, ImmutableMap.of(
                "playerId", owner
        ));

        flagForSave();
    }

    public void setMaxOnline(int maxOnline) {
        this.maxOnline = maxOnline;
        flagForSave();
    }

    public void setAnnouncement(String announcement) {
        this.announcement = announcement;

        if (this.loading) return;
        TeamActionTracker.logActionAsync(this, TeamActionType.ANNOUNCEMENT_CHANGED, ImmutableMap.of(
                "newAnnouncement", announcement
        ));

        flagForSave();
    }

    public void setHQ(Location hq) {
        String oldHQ = this.HQ == null ? "None" : (getHQ().getBlockX() + ", " + getHQ().getBlockY() + ", " + getHQ().getBlockZ());
        String newHQ = hq == null ? "None" : (hq.getBlockX() + ", " + hq.getBlockY() + ", " + hq.getBlockZ());
        this.HQ = hq;
        setHQWaypoint(hq == null ? null : new CBWaypoint("HQ", hq, Color.BLUE.getRGB(), true, true));
        if (this.loading) return;
        TeamActionTracker.logActionAsync(this, TeamActionType.HEADQUARTERS_CHANGED, ImmutableMap.of(
                "oldHq", oldHQ,
                "newHq", newHQ
        ));

        flagForSave();
    }

    public void setPowerFaction( boolean bool ) {
        this.powerFaction = bool;
        if( bool ) {
            TeamHandler.addPowerFaction(this);
        } else {
            TeamHandler.removePowerFaction(this);
        }

        if (this.loading) return;
        TeamActionTracker.logActionAsync(this, TeamActionType.POWER_FAC_STATUS_CHANGED, ImmutableMap.of(
                "powerFaction", bool
        ));

        flagForSave();
    }

    public void setLives( int lives ) {
        this.lives = lives;
        flagForSave();
    }

    public boolean addLives( int lives ) {
        if( lives < 0 ) {
            return false;
        }
        this.lives += lives;
        flagForSave();
        return true;
    }

    public boolean removeLives( int lives ) {
        if( this.lives < lives || lives < 0) {
            return false; //You twat.
        }
        this.lives -= lives;
        flagForSave();
        return true;
    }

    public void setRallyPermission(UUID changer, UUID uuid, boolean allowed) {
        if(allowed)
            getRallyPermissions().add(uuid);
        else
            getRallyPermissions().remove(uuid);

        if (this.loading) return;
        TeamActionTracker.logActionAsync(this, allowed ? TeamActionType.RALLY_PERMISSION_ADD : TeamActionType.RALLY_PERMISSION_REMOVE, ImmutableMap.of(
                "changer", changer.toString(),
                "uuid", uuid.toString()
        ));

        flagForSave();
    }

    public void setDisplayPermission(UUID changer, UUID uuid, boolean allowed) {
        if(allowed)
            getDisplayPermissions().add(uuid);
        else
            getDisplayPermissions().remove(uuid);

        if (this.loading) return;
        TeamActionTracker.logActionAsync(this, allowed ? TeamActionType.DISPLAY_PERMISSION_ADD : TeamActionType.DISPLAY_PERMISSION_REMOVE, ImmutableMap.of(
                "changer", changer.toString(),
                "uuid", uuid.toString()
        ));

        flagForSave();
    }

    public boolean hasRallyPermission(UUID uuid) {
        return isCoLeader(uuid) || isOwner(uuid) || isCaptain(uuid) || getRallyPermissions().contains(uuid);
    }

    public boolean hasDisplayPermission(UUID uuid) {
        return isCoLeader(uuid) || isOwner(uuid) || isCaptain(uuid) || getRallyPermissions().contains(uuid);
    }

    public void setSubclaimPermission(UUID changer, UUID uuid, boolean allowed) {
        if(allowed)
            getSubclaimPermissions().add(uuid);
        else
            getSubclaimPermissions().remove(uuid);

        if (this.loading) return;
        TeamActionTracker.logActionAsync(this, allowed ? TeamActionType.SUBCLAIM_PERMISSION_ADD : TeamActionType.SUBCLAIM_PERMISSION_REMOVE, ImmutableMap.of(
                "changer", changer.toString(),
                "uuid", uuid.toString()
        ));

        flagForSave();
    }

    public boolean hasSubclaimPermission(UUID uuid) {
        return isCoLeader(uuid) || isOwner(uuid) || isCaptain(uuid) || getSubclaimPermissions().contains(uuid);
    }

    public void addFactionUpgrade(String upgrade, boolean enabled) {
        if (Upgrades.valueOf(upgrade) == null) return;
        Upgrades.valueOf(upgrade).setEnabled(enabled);

    }

    public void disband() {
        try {
            if (owner != null) {
                double refund = balance;

                for (Claim claim : claims) {
                    refund += Claim.getPrice(claim, this, false);
                }

                FrozenEconomyHandler.deposit(owner, refund);
                HCF.getInstance().getWrappedBalanceMap().setBalance(owner, FrozenEconomyHandler.getBalance(owner));
                HCF.getInstance().getLogger().info("Economy Logger: Depositing " + refund + " into " + UUIDUtils.name(owner) + "'s account: Disbanded team");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        for (ObjectId allyId : getAllies()) {
            Team ally = HCF.getInstance().getTeamHandler().getTeam(allyId);

            if (ally != null) {
                ally.getAllies().remove(getUniqueId());
            }
        }

        for (UUID uuid : members) {
            HCF.getInstance().getChatModeMap().setChatMode(uuid, ChatMode.PUBLIC);
            clearWaypoint(uuid);
        }

        HCF.getInstance().getTeamHandler().removeTeam(this);
        LandBoard.getInstance().clear(this);

        new BukkitRunnable() {

            public void run() {
                Library.getInstance().runRedisCommand(new RedisCommand<Object>() {

                    
                    public Object execute(Jedis redis) {
                        redis.del("fox_teams." + name.toLowerCase());
                        return (null);
                    }

                });

                DBCollection teamsCollection = HCF.getInstance().getMongoPool().getDB(HCF.MONGO_DB_NAME).getCollection("Teams");
                teamsCollection.remove(getJSONIdentifier());
            }

        }.runTaskAsynchronously(HCF.getInstance());

        needsSave = false;
    }

    public void rename(String newName) {
        String oldName = name;

        HCF.getInstance().getTeamHandler().removeTeam(this);

        this.name = newName;

        HCF.getInstance().getTeamHandler().setupTeam(this);

        Library.getInstance().runRedisCommand(new RedisCommand<Object>() {

            
            public Object execute(Jedis redis) {
                redis.del("fox_teams." + oldName.toLowerCase());
                return (null);
            }

        });

        // We don't need to do anything here as all we're doing is changing the name, not the Unique ID (which is what Mongo uses)
        // therefore, Mongo will be notified of this once the 'flagForSave()' down below gets processed.

        for (Claim claim : getClaims()) {
            claim.setName(claim.getName().replaceAll(oldName, newName));
        }

        flagForSave();
    }

    public void setForceInvites(int forceInvites) {
        this.forceInvites = forceInvites;
        flagForSave();
    }

    public void setPoints(int points) {
        this.points = points;
        flagForSave();
    }

    public void setWonTeamfights(int teamfightsWon) {
        this.teamfightsWon = teamfightsWon;
        recalculatePoints();
        flagForSave();
    }

    public void setKills(int kills) {
        this.kills = kills;
        recalculatePoints();
        flagForSave();
    }

    public void setDeaths(int deaths) {
        this.deaths = deaths;
        recalculatePoints();
        flagForSave();
    }

    public void setKothCaptures(int kothCaptures) {
        this.kothCaptures = kothCaptures;
        recalculatePoints();
        flagForSave();
    }

    public void setDiamondsMined(int diamondsMined) {
        this.diamondsMined = diamondsMined;
       // recalculatePoints();
        flagForSave();
    }

    public void setCitadelsCapped(int citadels) {
        this.citadelsCapped = citadels;
        recalculatePoints();
        flagForSave();
    }

    public void setKillstreakPoints(int killstreakPoints) {
        this.killstreakPoints = killstreakPoints;
        recalculatePoints();
        flagForSave();
    }

    public void addKillstreakPoints(int killstreakPoints) {
        this.killstreakPoints += killstreakPoints;
        recalculatePoints();
        flagForSave();
    }

    public void addSpawnersInClaim(int amount) {
        spawnersInClaim += amount;

        if (spawnersInClaim < 0) {
            spawnersInClaim = 0;
        }

        recalculatePoints();
        flagForSave();
    }

    public void removeSpawnersInClaim(int amount) {
        spawnersInClaim -= amount;

        if (spawnersInClaim < 0) {
            spawnersInClaim = 0;
        }

        recalculatePoints();
        flagForSave();
    }

    public void setSpawnersInClaim(int amount) {
        if (amount < 0) {
            amount = 0;
        }

        spawnersInClaim = amount;
        recalculatePoints();
        flagForSave();
    }

    public void recalculateSpawnersInClaims() {
        new BukkitRunnable() {
            
            public void run() {
                setSpawnersInClaim(findSpawners().size());
            }
        }.runTaskAsynchronously(HCF.getInstance());
    }

    public List<CreatureSpawner> findSpawners() {
        if (Bukkit.isPrimaryThread()) {
            throw new RuntimeException("Cannot call Team#findSpawners on main thread");
        }

        List<CreatureSpawner> list = new ArrayList<>();

        // Iterate through chunks' tile entities rather than every block
        for (Claim claim : getClaims()) {
            World world = Bukkit.getWorld(claim.getWorld());
            Location minPoint = claim.getMinimumPoint();
            Location maxPoint = claim.getMaximumPoint();
            int minChunkX = ((int) minPoint.getX()) >> 4;
            int minChunkZ = ((int) minPoint.getZ()) >> 4;
            int maxChunkX = ((int) maxPoint.getX()) >> 4;
            int maxChunkZ = ((int) maxPoint.getZ()) >> 4;

            for (int chunkX = minChunkX; chunkX < maxChunkX + 1; chunkX++) {
                for (int chunkZ = minChunkZ; chunkZ < maxChunkZ + 1; chunkZ++) {
                    Chunk chunk = world.getChunkAt(chunkX, chunkZ);

                    for (BlockState blockState : chunk.getTileEntities()) {
                        // Check if the block is a mob spawner
                        if (blockState instanceof CreatureSpawner) {
                            // Even though we're iterating through chunks' tile entities
                            // we need to make sure that the block's location is within
                            // the claim (because claims don't have to align with chunks)
                            Location loc = blockState.getLocation();

                            if (loc.getX() >= minPoint.getX() && loc.getZ() >= minPoint.getZ() &&
                                    loc.getX() <= maxPoint.getX() && loc.getZ() <= maxPoint.getZ()) {
                                list.add((CreatureSpawner) blockState);
                            }
                        }
                    }
                }
            }
        }

        return list;
    }

    public void spendPoints(int points) {
        spentPoints += points;
        recalculatePoints();
        flagForSave();
    }

    public void setSpentPoints(int points) {
        spentPoints = points;
        recalculatePoints();
        flagForSave();
    }

    public void recalculatePoints() {
        int basePoints = 0;
        basePoints += kills + 1;
        basePoints -= deaths + 1;
        basePoints += points + 0;
        //basePoints += (diamondsMined / 500) * 15;
        basePoints += citadelsCapped * 0 + 50;
        basePoints += spawnersInClaim * 0 + 5;
        basePoints += killstreakPoints;
        basePoints -= spentPoints;


        if (HCF.getInstance().getConfig().getBoolean("tiers")) {
            basePoints += teamfightsWon * 5;
        }

        basePoints += kills;
        basePoints -= deaths;

        if (basePoints < 0) {
            basePoints = 0;
        }

        this.points = basePoints;
    }

    public String[] getPointBreakDown() {
        int basePoints = 0;

        basePoints += (Math.floor(kills / 5.0D)) * 10;
        basePoints -= (Math.floor(deaths / 5.0D)) * 15;
        basePoints += points + 50;
        basePoints += citadelsCapped * 125;
      //  basePoints += (diamondsMined / 500) * 15;
        basePoints += spawnersInClaim * 5;
        basePoints += killstreakPoints;
        basePoints -= spentPoints;

        if (HCF.getInstance().getConfig().getBoolean("tiers")) {
            basePoints += teamfightsWon * 5;
        }

        basePoints += kills;
        basePoints -= deaths;

        if (basePoints < 0) {
            basePoints = 0;
        }

        return new String[]{
                "Base Points: " + basePoints,
                "Kills Points: (" + kills + " kills / 5) * 10 = " + ((Math.floor(kills / 5.0D)) * 10),
                "Deaths Points: (" + deaths + " deaths / 5) * 15 = " + ((Math.floor(deaths / 5.0D)) * 15),
            //    "KOTH Captures Points: (" + points + " caps) * 50 = " + (kothCaptures * 50),
                "Citadel Captures Points: (" + citadelsCapped + " caps) * 125 = " + (citadelsCapped * 125),
             //   "Diamonds Mined Points: (" + diamondsMined + " mined / 500) * 15 = " + ((diamondsMined / 500) * 15),
                "Spawners Points: (" + spawnersInClaim + " spawners) * 5 = " + (spawnersInClaim * 5),
                "Killstreaks Points: " + killstreakPoints,
                "Spent Points: " + spentPoints,
                "(Tier Maps Only) Teamfight Points: (" + teamfightsWon + " teamfights won * 5 = " + teamfightsWon * 5
        };
    }

    public void flagForSave() {
        needsSave = true;
    }

    public boolean isOwner(UUID check) {
        return (check.equals(owner));
    }

    public boolean isMember(UUID check) {
        return members.contains(check);
    }

    public boolean isCaptain(UUID check) {
        return captains.contains(check);
    }

    public boolean isCoLeader(UUID check) {
        return coleaders.contains(check);
    }

    public void validateAllies() {
        Iterator<ObjectId> allyIterator = getAllies().iterator();

        while (allyIterator.hasNext()) {
            ObjectId ally = allyIterator.next();
            Team checkTeam = HCF.getInstance().getTeamHandler().getTeam(ally);

            if (checkTeam == null) {
                allyIterator.remove();
            }
        }
    }

    public boolean isAlly(UUID check) {
        Team checkTeam = HCF.getInstance().getTeamHandler().getTeam(check);
        return (checkTeam != null && isAlly(checkTeam));
    }

    public boolean isAlly(Team team) {
        return (getAllies().contains(team.getUniqueId()));
    }

    public boolean ownsLocation(Location location) {
        return (LandBoard.getInstance().getTeam(location) == this);
    }

    public boolean ownsClaim(Claim claim) {
        return (claims.contains(claim));
    }

    public void sendWaypoint(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if(player != null) {
            if (this.Rally != null) CheatBreakerAPI.getInstance().sendWaypoint(player, this.Rally.getCbWaypoint());
            if (this.HQWaypoint != null) CheatBreakerAPI.getInstance().sendWaypoint(player, this.HQWaypoint);
        }
    }

    public void clearWaypoint(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if(player != null) {
            if (this.Rally != null) CheatBreakerAPI.getInstance().removeWaypoint(player, this.Rally.getCbWaypoint());
            if (this.HQWaypoint != null) CheatBreakerAPI.getInstance().removeWaypoint(player, this.HQWaypoint);
        }
    }

    public boolean removeMember(UUID member) {
        clearWaypoint(member);
        members.remove(member);
        captains.remove(member);
        coleaders.remove(member);

        // If the owner leaves (somehow)
        if (isOwner(member)) {
            Iterator<UUID> membersIterator = members.iterator();
            this.owner = membersIterator.hasNext() ? membersIterator.next() : null;
        }

        try {
            for (Subclaim subclaim : subclaims) {
                if (subclaim.isMember(member)) {
                    subclaim.removeMember(member);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (DTR > getMaxDTR()) {
            DTR = getMaxDTR();
        }

        if (this.loading) return false;
        TeamActionTracker.logActionAsync(this, TeamActionType.MEMBER_REMOVED, ImmutableMap.of(
                "playerId", member
        ));

        flagForSave();
        return (owner == null || members.size() == 0);
    }

    public boolean hasDTRBitmask(DTRBitmask bitmaskType) {
        if (getOwner() != null) {
            return (false);
        }

        int dtrInt = (int) DTR;
        return (((dtrInt & bitmaskType.getBitmask()) == bitmaskType.getBitmask()));
    }

    public int getOnlineMemberAmount() {
        int amt = 0;

        for (UUID member : getMembers()) {
            Player exactPlayer = HCF.getInstance().getServer().getPlayer(member);

            if (exactPlayer != null && !exactPlayer.hasMetadata("invisible")) {
                amt++;
            }
        }

        return (amt);
    }

    public Collection<Player> getOnlineMembers() {
        List<Player> players = new ArrayList<>();

        for (UUID member : getMembers()) {
            Player exactPlayer = HCF.getInstance().getServer().getPlayer(member);

            if (exactPlayer != null && !exactPlayer.hasMetadata("invisible")) {
                players.add(exactPlayer);
            }
        }

        return (players);
    }

    public Collection<UUID> getOfflineMembers() {
        List<UUID> players = new ArrayList<>();

        for (UUID member : getMembers()) {
            Player exactPlayer = HCF.getInstance().getServer().getPlayer(member);

            if (exactPlayer == null || exactPlayer.hasMetadata("invisible")) {
                players.add(member);
            }
        }

        return (players);
    }

    public Subclaim getSubclaim(String name) {
        for (Subclaim subclaim : subclaims) {
            if (subclaim.getName().equalsIgnoreCase(name)) {
                return (subclaim);
            }
        }

        return (null);
    }

    public Subclaim getSubclaim(Location location) {
        for (Subclaim subclaim : subclaims) {
            if (new CuboidRegion(subclaim.getName(), subclaim.getLoc1(), subclaim.getLoc2()).contains(location)) {
                return (subclaim);
            }
        }

        return (null);
    }

    public int getSize() {
        return (getMembers().size());
    }

    public boolean isRaidable() {
        return (DTR <= 0);
    }

    public void playerDeath(String playerName, double dtrLoss) {
        double newDTR = Math.max(DTR - dtrLoss, -.99);

        TeamActionTracker.logActionAsync(this, TeamActionType.MEMBER_DEATH, ImmutableMap.of(
                "playerName", playerName,
                "dtrLoss", dtrLoss,
                "oldDtr", DTR,
                "newDtr", newDTR
        ));

        for (Player player : HCF.getInstance().getServer().getOnlinePlayers()) {
            if (isMember(player.getUniqueId())) {
                player.sendMessage(ChatColor.RED + "Member Death: " + ChatColor.WHITE + playerName);
                player.sendMessage(ChatColor.RED + "DTR: " + ChatColor.WHITE + DTR_FORMAT.format(newDTR));
            }
        }

        HCF.getInstance().getLogger().info("[TeamDeath] " + name + " > " + "Player death: [" + playerName + "]");
        setDTR(newDTR);

        if (isRaidable()) {
            TeamActionTracker.logActionAsync(this, TeamActionType.TEAM_NOW_RAIDABLE, ImmutableMap.of());
            DTRCooldown = System.currentTimeMillis() + HCF.getInstance().getMapHandler().getRegenTimeRaidable();
        } else {
            DTRCooldown = System.currentTimeMillis() + HCF.getInstance().getMapHandler().getRegenTimeDeath();
        }

        DTRHandler.markOnDTRCooldown(this);
    }

    public double getDTRIncrement() {
        return (getDTRIncrement(getOnlineMemberAmount()));
    }

    public double getDTRIncrement(int playersOnline, boolean offline) {
        double dtrPerHour = DTRHandler.getMaxDTR(getSize());
        return (dtrPerHour / 60);
    }

    public double getDTRIncrement(int playersOnline) {
        return getDTRIncrement(playersOnline, false);
    }

    public double getMaxDTR(boolean offline) {
        return (DTRHandler.getMaxDTR(getSize()));
    }


    public double getMaxDTR() {
        return (DTRHandler.getMaxDTR(getSize()));
    }

    public void load(BasicDBObject obj) {
        loading = true;
        setUniqueId(obj.getObjectId("_id"));
        setOwner(obj.getString("Owner") == null ? null : UUID.fromString(obj.getString("Owner")));
        if (obj.containsKey("CoLeaders")) for (Object coLeader : (BasicDBList) obj.get("CoLeaders")) addCoLeader(UUID.fromString((String) coLeader));
        if (obj.containsKey("Captains")) for (Object captain : (BasicDBList) obj.get("Captains")) addCaptain(UUID.fromString((String) captain));
        if (obj.containsKey("Members")) for (Object member : (BasicDBList) obj.get("Members")) addMember(UUID.fromString((String) member));
        if (obj.containsKey("SubclaimPermissions")) for (Object sub : (BasicDBList) obj.get("SubclaimPermissions")) getSubclaimPermissions().add(UUID.fromString((String) sub));
        if (obj.containsKey("RallyPermissions")) for (Object rally : (BasicDBList) obj.get("RallyPermissions")) getRallyPermissions().add(UUID.fromString((String) rally));
        if (obj.containsKey("DisplayPermissions")) for (Object rally : (BasicDBList) obj.get("DisplayPermissions")) getDisplayPermissions().add(UUID.fromString((String) rally));
        if (obj.containsKey("Invitations")) for (Object invite : (BasicDBList) obj.get("Invitations")) getInvitations().add(UUID.fromString((String) invite));
        if (obj.containsKey("DTR")) setDTR(obj.getDouble("DTR"));
        if (obj.containsKey("DTRCooldown")) setDTRCooldown(obj.getDate("DTRCooldown").getTime());
        if (obj.containsKey("Balance")) setBalance(obj.getDouble("Balance"));
        if (obj.containsKey("Points")) setPoints((int) obj.getDouble("Points"));
        if (obj.containsKey("MaxOnline")) setMaxOnline(obj.getInt("MaxOnline"));
        if (obj.containsKey("HQ")) setHQ(LocationSerializer.deserialize((BasicDBObject) obj.get("HQ")));
        if (obj.containsKey("Announcement")) setAnnouncement(obj.getString("Announcement"));
        if (obj.containsKey("PowerFaction")) setPowerFaction(obj.getBoolean("PowerFaction"));
        if (obj.containsKey("Lives")) setLives(obj.getInt("Lives"));
        if (obj.containsKey("Claims")) for (Object claim : (BasicDBList) obj.get("Claims")) getClaims().add(Claim.fromJson((BasicDBObject) claim));
        if (obj.containsKey("Subclaims")) for (Object subclaim : (BasicDBList) obj.get("Subclaims")) getSubclaims().add(Subclaim.fromJson((BasicDBObject) subclaim));
        if (obj.containsKey("SpentPoints")) setSpentPoints(obj.getInt("SpentPoints"));
        if (obj.containsKey("SpawnersInClaim")) setSpawnersInClaim(obj.getInt("SpawnersInClaim"));

        // Load team upgrades if they exist
        if (obj.containsKey("Upgrades")) for (Object upgrade : (BasicDBList) obj.get("Upgrades")) upgradeToTier.put(((BasicDBObject) upgrade).getString("UpgradeName"), ((BasicDBObject) upgrade).getInt("Tier"));

        loading = false;
    }

    public void load(String str) {
        load(str, false);
    }

    public void load(String str, boolean forceSave) {
        loading = true;
        String[] lines = str.split("\n");

        for (String line : lines) {
            if (line.indexOf(':') == -1) {
                System.out.println("Found an invalid line... `" + line + "`");
                continue;
            }

            String identifier = line.substring(0, line.indexOf(':'));
            String[] lineParts = line.substring(line.indexOf(':') + 1).split(",");

            if (identifier.equalsIgnoreCase("Owner")) {
                if (!lineParts[0].equals("null")) {
                    setOwner(UUID.fromString(lineParts[0].trim()));
                }
            } else if (identifier.equalsIgnoreCase("UUID")) {
                uniqueId = new ObjectId(lineParts[0].trim());
            } else if (identifier.equalsIgnoreCase("Members")) {
                for (String name : lineParts) {
                    if (name.length() >= 2) {
                        addMember(UUID.fromString(name.trim()));
                    }
                }
            } else if(identifier.equalsIgnoreCase("CoLeaders")) {
                for (String name : lineParts) {
                    if (name.length() >= 2) {
                        addCoLeader(UUID.fromString(name.trim()));
                    }
                }
            } else if (identifier.equalsIgnoreCase("Captains")) {
                for (String name : lineParts) {
                    if (name.length() >= 2) {
                        addCaptain(UUID.fromString(name.trim()));
                    }
                }
            } else if (identifier.equalsIgnoreCase("Invited")) {
                for (String name : lineParts) {
                    if (name.length() >= 2) {
                        getInvitations().add(UUID.fromString(name.trim()));
                    }
                }
            }  else if (identifier.equalsIgnoreCase("RallyPerms")) {
                for (String name : lineParts) {
                    if (name.length() >= 2) {
                        getRallyPermissions().add(UUID.fromString(name.trim()));
                    }
                }
            }  else if (identifier.equalsIgnoreCase("SubclaimPerms")) {
                for (String name : lineParts) {
                    if (name.length() >= 2) {
                        getInvitations().add(UUID.fromString(name.trim()));
                    }
                }
            } else if (identifier.equalsIgnoreCase("HistoricalMembers")) {
                for (String name : lineParts) {
                    if (name.length() >= 2) {
                        getHistoricalMembers().add(UUID.fromString(name.trim()));
                    }
                }
            } else if (identifier.equalsIgnoreCase("HQ")) {
                setHQ(parseLocation(lineParts));
            } else if (identifier.equalsIgnoreCase("DTR")) {
                setDTR(Double.valueOf(lineParts[0]));
            } else if (identifier.equalsIgnoreCase("Balance")) {
                setBalance(Double.valueOf(lineParts[0]));
            } else if (identifier.equalsIgnoreCase("MaxOnline")) {
                setMaxOnline(Integer.valueOf(lineParts[0]));
            } else if (identifier.equalsIgnoreCase("ForceInvites")) {
                setForceInvites(Integer.valueOf(lineParts[0]));
            } else if (identifier.equalsIgnoreCase("DTRCooldown")) {
                setDTRCooldown(Long.parseLong(lineParts[0]));
            } else if (identifier.equalsIgnoreCase("FriendlyName")) {
                setName(lineParts[0]);
            } else if (identifier.equalsIgnoreCase("Claims")) {
                for (String claim : lineParts) {
                    claim = claim.replace("[", "").replace("]", "");

                    if (claim.contains(":")) {
                        String[] split = claim.split(":");

                        int x1 = Integer.parseInt(split[0].trim());
                        int y1 = Integer.parseInt(split[1].trim());
                        int z1 = Integer.parseInt(split[2].trim());
                        int x2 = Integer.parseInt(split[3].trim());
                        int y2 = Integer.parseInt(split[4].trim());
                        int z2 = Integer.parseInt(split[5].trim());
                        String name = split[6].trim();
                        String world = split[7].trim();

                        Claim claimObj = new Claim(world, x1, y1, z1, x2, y2, z2);
                        claimObj.setName(name);

                        getClaims().add(claimObj);
                    }
                }
            } else if (identifier.equalsIgnoreCase("Allies")) {
                // Just cancel loading of allies if they're disabled (for switching # of allowed allies mid-map)
                if (HCF.getInstance().getMapHandler().getAllyLimit() == 0) {
                    continue;
                }

                for (String ally : lineParts) {
                    ally = ally.replace("[", "").replace("]", "");

                    if (ally.length() != 0) {
                        allies.add(new ObjectId(ally.trim()));
                    }
                }
            } else if (identifier.equalsIgnoreCase("RequestedAllies")) {
                // Just cancel loading of allies if they're disabled (for switching # of allowed allies mid-map)
                if (HCF.getInstance().getMapHandler().getAllyLimit() == 0) {
                    continue;
                }

                for (String requestedAlly : lineParts) {
                    requestedAlly = requestedAlly.replace("[", "").replace("]", "");

                    if (requestedAlly.length() != 0) {
                        requestedAllies.add(new ObjectId(requestedAlly.trim()));
                    }
                }
            } else if (identifier.equalsIgnoreCase("Subclaims")) {
                for (String subclaim : lineParts) {
                    subclaim = subclaim.replace("[", "").replace("]", "");

                    if (subclaim.contains(":")) {
                        String[] split = subclaim.split(":");

                        int x1 = Integer.parseInt(split[0].trim());
                        int y1 = Integer.parseInt(split[1].trim());
                        int z1 = Integer.parseInt(split[2].trim());
                        int x2 = Integer.parseInt(split[3].trim());
                        int y2 = Integer.parseInt(split[4].trim());
                        int z2 = Integer.parseInt(split[5].trim());
                        String name = split[6].trim();
                        String membersRaw = "";

                        if (split.length >= 8) {
                            membersRaw = split[7].trim();
                        }

                        Location location1 = new Location(HCF.getInstance().getServer().getWorld("world"), x1, y1, z1);
                        Location location2 = new Location(HCF.getInstance().getServer().getWorld("world"), x2, y2, z2);
                        List<UUID> members = new ArrayList<>();

                        for (String uuidString : membersRaw.split(", ")) {
                            if (uuidString.isEmpty()) {
                                continue;
                            }

                            members.add(UUID.fromString(uuidString.trim()));
                        }

                        Subclaim subclaimObj = new Subclaim(location1, location2, name);
                        subclaimObj.setMembers(members);

                        getSubclaims().add(subclaimObj);
                    }
                }
            } else if (identifier.equalsIgnoreCase("Announcement")) {
                setAnnouncement(lineParts[0]);
            } else if(identifier.equalsIgnoreCase("PowerFaction")) {
                setPowerFaction(Boolean.valueOf(lineParts[0]));
            } else if(identifier.equalsIgnoreCase("Lives")) {
                setLives(Integer.valueOf(lineParts[0]));
            } else if (identifier.equalsIgnoreCase("Kills")) {
                setKills(Integer.valueOf(lineParts[0]));
            } else if (identifier.equalsIgnoreCase("Deaths")) {
                setDeaths(Integer.valueOf(lineParts[0]));
            } else if (identifier.equalsIgnoreCase("KothCaptures")) {
                setKothCaptures(Integer.valueOf(lineParts[0]));
            } /*else if (identifier.equalsIgnoreCase("DiamondsMined")) {
                setDiamondsMined(Integer.valueOf(lineParts[0]));
            } */else if (identifier.equalsIgnoreCase("CitadelsCapped")) {
                setCitadelsCapped(Integer.valueOf(lineParts[0]));
            } else if (identifier.equalsIgnoreCase("KillstreakPoints")) {
                setKillstreakPoints(Integer.valueOf(lineParts[0]));
            } else if (identifier.equalsIgnoreCase("Points")) {
                setPoints(Integer.valueOf(lineParts[0]));
            } else if (identifier.equalsIgnoreCase("SpentPoints")) {
                setSpentPoints(Integer.valueOf(lineParts[0]));
            } else  if (identifier.equalsIgnoreCase("TeamfightsWon")) {
                setTeamfightsWon(Integer.valueOf(lineParts[0]));
            } else if (identifier.equalsIgnoreCase("SpawnersInClaim")) {
                setSpawnersInClaim(Integer.valueOf(lineParts[0]));
            } else if (identifier.equalsIgnoreCase("Upgrades")) {
                for (String name : lineParts) {
                    if (name.length() >= 2) {
                        String[] nameSplit = name.split(";");
                        upgradeToTier.put(nameSplit[0].trim(), Integer.valueOf(nameSplit[1].trim()));
                    }
                }
            }
        }

        for (UUID member : members) {
            FrozenUUIDCache.ensure(member);
        }

        if (uniqueId == null) {
            uniqueId = new ObjectId();
            HCF.getInstance().getLogger().info("Generating UUID for team " + getName() + "...");
        }
        setTeamColor(ChatUtils.randomChatColor());
        loading = false;
        needsSave = forceSave;
    }

    public boolean isRallyActive() {
        return getRemainingRallyTime() > 0L;
    }

    public long getRemainingRallyTime() {
        if(getRally() == null) return 0L;
        return Math.max(0L, getRally().getTimeCreated() - System.currentTimeMillis());
    }

    public String saveString(boolean toJedis) {

        if (toJedis) {
            needsSave = false;
        }

        if (loading) {
            return (null);
        }

        StringBuilder teamString = new StringBuilder();

        StringBuilder members = new StringBuilder();
        StringBuilder captains = new StringBuilder();
        StringBuilder coleaders = new StringBuilder();
        StringBuilder invites = new StringBuilder();
        StringBuilder rallyPerms = new StringBuilder();
        StringBuilder subclaimPerms = new StringBuilder();
        StringBuilder historicalMembers = new StringBuilder();

        for (UUID member : getMembers()) {
            members.append(member.toString()).append(", ");
        }

        for (UUID captain : getCaptains()) {
            captains.append(captain.toString()).append(", ");
        }

        for (UUID co : getColeaders()) {
            coleaders.append(co.toString()).append(", ");
        }

        for (UUID invite : getInvitations()) {
            invites.append(invite.toString()).append(", ");
        }

        for (UUID sub : getSubclaimPermissions()) {
            subclaimPerms.append(sub.toString()).append(", ");
        }

        for (UUID rally : getRallyPermissions()) {
            rallyPerms.append(rally.toString()).append(", ");
        }

        for (UUID member : getHistoricalMembers()) {
            historicalMembers.append(member.toString()).append(", ");
        }

        if (members.length() > 2) {
            members.setLength(members.length() - 2);
        }

        if (captains.length() > 2) {
            captains.setLength(captains.length() - 2);
        }

        if (invites.length() > 2) {
            invites.setLength(invites.length() - 2);
        }

        if (historicalMembers.length() > 2) {
            historicalMembers.setLength(historicalMembers.length() - 2);
        }

        StringBuilder upgrades = new StringBuilder();

        for (Map.Entry<String, Integer> entry : upgradeToTier.entrySet()) {
            upgrades.append(entry.getKey()).append(";").append(entry.getValue()).append(", ");
        }

        if (upgrades.length() > 2) {
            upgrades.setLength(upgrades.length() - 2);
        }

        teamString.append("UUID:").append(getUniqueId().toString()).append("\n");
        teamString.append("Owner:").append(getOwner()).append('\n');
        teamString.append("CoLeaders:").append(coleaders.toString()).append('\n');
        teamString.append("Captains:").append(captains.toString()).append('\n');
        teamString.append("Members:").append(members.toString()).append('\n');
        teamString.append("Invited:").append(invites.toString().replace("\n", "")).append('\n');
        teamString.append("SubclaimPerms:").append(subclaimPerms.toString().replace("\n", "")).append('\n');
        teamString.append("RallyPerms:").append(rallyPerms.toString().replace("\n", "")).append('\n');
        teamString.append("DisplayPerms:").append(displayPermissions.toString().replace("\n", "")).append('\n');
        teamString.append("Subclaims:").append(getSubclaims().toString().replace("\n", "")).append('\n');
        teamString.append("Claims:").append(getClaims().toString().replace("\n", "")).append('\n');
        teamString.append("Allies:").append(getAllies().toString()).append('\n');
        teamString.append("RequestedAllies:").append(getRequestedAllies().toString()).append('\n');
        teamString.append("HistoricalMembers:").append(historicalMembers.toString()).append('\n');
        teamString.append("DTR:").append(getDTR()).append('\n');
        teamString.append("Balance:").append(getBalance()).append('\n');
        teamString.append("MaxOnline:").append(getMaxOnline()).append('\n');
        teamString.append("ForceInvites:").append(getForceInvites()).append('\n');
        teamString.append("DTRCooldown:").append(getDTRCooldown()).append('\n');
        teamString.append("FriendlyName:").append(getName().replace("\n", "")).append('\n');
        teamString.append("Announcement:").append(String.valueOf(getAnnouncement()).replace("\n", "")).append("\n");
        teamString.append("PowerFaction:").append(isPowerFaction()).append("\n");
        teamString.append("Lives:").append(getLives()).append("\n");
        teamString.append("Kills:").append(getKills()).append("\n");
        teamString.append("Deaths:").append(getDeaths()).append("\n");
        teamString.append("DiamondsMined:").append(getDiamondsMined()).append("\n");
        teamString.append("KothCaptures:").append(getKothCaptures()).append("\n");
        teamString.append("CitadelsCapped:").append(getCitadelsCapped()).append("\n");
        teamString.append("KillstreakPoints:").append(getKillstreakPoints()).append("\n");
        teamString.append("Points:").append(getPoints()).append("\n");
        teamString.append("TeamfightsWon").append(getTeamfightsWon()).append("\n");
        teamString.append("SpentPoints:").append(getSpentPoints()).append("\n");
        teamString.append("SpawnersInClaim:").append(getSpawnersInClaim()).append("\n");
        teamString.append("Upgrades:").append(upgrades.toString()).append("\n");
        teamString.append("Points:").append(getPoints()).append('\n');


        if (getHQ() != null) {
            teamString.append("HQ:").append(getHQ().getWorld().getName()).append(",").append(getHQ().getX()).append(",").append(getHQ().getY()).append(",").append(getHQ().getZ()).append(",").append(getHQ().getYaw()).append(",").append(getHQ().getPitch()).append('\n');
        }

        return (teamString.toString());
    }

    public BasicDBObject toJSON() {
        BasicDBObject dbObject = new BasicDBObject();

        dbObject.put("Owner", getOwner() == null ? null : getOwner().toString());
        dbObject.put("CoLeaders", UUIDUtils.uuidsToStrings(getColeaders()));
        dbObject.put("Captains", UUIDUtils.uuidsToStrings(getCaptains()));
        dbObject.put("Members", UUIDUtils.uuidsToStrings(getMembers()));
        dbObject.put("Invitations", UUIDUtils.uuidsToStrings(getInvitations()));
        dbObject.put("SubclaimPermissions", UUIDUtils.uuidsToStrings(getSubclaimPermissions()));
        dbObject.put("RallyPermissions", UUIDUtils.uuidsToStrings(getRallyPermissions()));
        dbObject.put("Allies", getAllies());
        dbObject.put("RequestedAllies", getRequestedAllies());
        dbObject.put("DTR", getDTR());
        dbObject.put("DTRCooldown", new Date(getDTRCooldown()));
        dbObject.put("Balance", getBalance());
        dbObject.put("MaxOnline", getMaxOnline());
        dbObject.put("Name", getName());
        dbObject.put("HQ", LocationSerializer.serialize(getHQ()));
        dbObject.put("Announcement", getAnnouncement());
        dbObject.put("PowerFaction", isPowerFaction());
        dbObject.put("Lives", getLives());
        dbObject.put("Points", getPoints());

        BasicDBList claims = new BasicDBList();
        BasicDBList subclaims = new BasicDBList();

        for (Claim claim : getClaims()) {
            claims.add(claim.json());
        }

        for (Subclaim subclaim : getSubclaims()) {
            subclaims.add(subclaim.json());
        }

        dbObject.put("Claims", claims);
        dbObject.put("Subclaims", subclaims);
        dbObject.put("Kills", this.kills);
        dbObject.put("Deaths", this.deaths);
        dbObject.put("DiamondsMined", this.diamondsMined);
        dbObject.put("CitadelsCaptured", this.citadelsCapped);
        dbObject.put("KillstreakPoints", this.killstreakPoints);
        dbObject.put("KothCaptures", this.kothCaptures);
        dbObject.put("Points", this.points);
        dbObject.put("TeamfightsWon", this.teamfightsWon);
        dbObject.put("SpentPoints", this.spentPoints);
        dbObject.put("SpawnersInClaim", this.spawnersInClaim);

        BasicDBList upgrades = new BasicDBList();

        for (Map.Entry<String, Integer> entry : upgradeToTier.entrySet()) {
            BasicDBObject upgradeDBObject = new BasicDBObject();
            upgradeDBObject.put("UpgradeName", entry.getKey());
            upgradeDBObject.put("Tier", entry.getValue());

            upgrades.add(upgradeDBObject);
        }

        dbObject.put("Upgrades", upgrades);

        return (dbObject);
    }

    public BasicDBObject getJSONIdentifier() {
        return (new BasicDBObject("_id", getUniqueId().toHexString()));
    }

    private Location parseLocation(String[] args) {
        if (args.length != 6) {
            return (null);
        }

        World world = HCF.getInstance().getServer().getWorld(args[0]);
        double x = Double.parseDouble(args[1]);
        double y = Double.parseDouble(args[2]);
        double z = Double.parseDouble(args[3]);
        float yaw = Float.parseFloat(args[4]);
        float pitch = Float.parseFloat(args[5]);

        return (new Location(world, x, y, z, yaw, pitch));
    }

    public Tier getTier(Team team) {
        if(team.getPoints() > 200) return Tier.TIERI;
        else if(team.getPoints() > 175) return Tier.TIERII;
        else if(team.getPoints() > 100) return Tier.TIERIII;
        else if(team.getPoints() > 50) return Tier.TIERIV;

        return Tier.TIERV;
    }

    public String getTierPrefix (Team team) {
        if (getTier(team) == Tier.TIERI) return CC.translate("&6(Tier I) ");
        else if(getTier(team) == Tier.TIERII) return CC.translate("&e(Tier II) ");
        else if (getTier(team) == Tier.TIERIII) return CC.translate("&b(Tier III) ");
        else if (getTier(team) == Tier.TIERIV) return CC.translate("&3(Tier IV) ");
        else if (getTier(team) == Tier.TIERV) return CC.translate("&9(Tier V) ");

        return CC.translate("&eCouldn't load data");
    }

    public void sendMessage(String message) {
        for (Player player : HCF.getInstance().getServer().getOnlinePlayers()) {
            if (isMember(player.getUniqueId())) {
                player.sendMessage(message);
            }
        }
    }

    public void sendTeamInfo(Player player) {
        // Don't make our null teams have DTR....
        // @HCFactions
        if (getOwner() == null) {
            player.sendMessage(GRAY_LINE);
            player.sendMessage(getName(player));

            if ( HQ != null && HQ.getWorld().getEnvironment() != World.Environment.NORMAL) {
                String world = HQ.getWorld().getEnvironment() == World.Environment.NETHER ? "Nether" : "End"; // if it's not the nether, it's the end
                player.sendMessage(ChatColor.YELLOW + "Location: " + ChatColor.WHITE + (HQ == null ? "None" : HQ.getBlockX() + ", " + HQ.getBlockZ() + " (" + world + ")"));
            } else {
                player.sendMessage(ChatColor.YELLOW + "Location: " + ChatColor.WHITE + (HQ == null ? "None" : HQ.getBlockX() + ", " + HQ.getBlockZ()));
            }

            if (getName().equalsIgnoreCase("Citadel")) {
                Set<ObjectId> cappers = HCF.getInstance().getCitadelHandler().getCappers();
                Set<String> capperNames = new HashSet<>();

                for (ObjectId capper : cappers) {
                    Team capperTeam = HCF.getInstance().getTeamHandler().getTeam(capper);

                    if (capperTeam != null) {
                        capperNames.add(capperTeam.getName());
                    }
                }

                if (!cappers.isEmpty()) {
                    player.sendMessage(ChatColor.YELLOW + "Currently captured by: " + ChatColor.RED + Joiner.on(", ").join(capperNames));
                }
            }

            player.sendMessage(GRAY_LINE);
            return;
        }

        KillsMap killsMap = HCF.getInstance().getKillsMap();
        DeathbanMap deathbanMap = HCF.getInstance().getDeathbanMap();
        Player owner = HCF.getInstance().getServer().getPlayer(getOwner());
        StringBuilder allies = new StringBuilder();

        FancyMessage coleadersJson = new FancyMessage("Co-Leaders: ").color(ChatColor.YELLOW);

        FancyMessage captainsJson = new FancyMessage("Captains: ").color(ChatColor.YELLOW);

        if (player.hasPermission("foxtrot.manage")) {
            captainsJson.command("/manageteam demote " + getName()).tooltip("§bClick to demote captains");
        }

        FancyMessage membersJson = new FancyMessage("Members: ").color(ChatColor.YELLOW);

        if (player.hasPermission("foxtrot.manage")) {
            membersJson.command("/manageteam promote " + getName()).tooltip("§bClick to promote members");
        }

        int onlineMembers = 0;

        for (ObjectId allyId : getAllies()) {
            Team ally = HCF.getInstance().getTeamHandler().getTeam(allyId);

            if (ally != null) {
                allies.append(ally.getName(player)).append(ChatColor.YELLOW).append("[").append(ChatColor.GREEN).append(ally.getOnlineMemberAmount()).append("/").append(ally.getSize()).append(ChatColor.YELLOW).append("]").append(ChatColor.GRAY).append(", ");
            }
        }


        for (Player onlineMember : getOnlineMembers()) {
            onlineMembers++;

            // There can only be one owner, so we special case it.
            if (isOwner(onlineMember.getUniqueId())) {
                continue;
            }

            FancyMessage appendTo = membersJson;
            if(isCoLeader(onlineMember.getUniqueId())) {
                appendTo = coleadersJson;
            } else if(isCaptain(onlineMember.getUniqueId())) {
                appendTo = captainsJson;
            }

            if (!ChatColor.stripColor(appendTo.toOldMessageFormat()).endsWith("s: ")) {
                appendTo.then(", ").color(ChatColor.GRAY);
            }

            appendTo.then(onlineMember.getName()).color(ChatColor.GREEN).then("[").color(ChatColor.YELLOW);
            appendTo.then(killsMap.getKills(onlineMember.getUniqueId()) + "").color(ChatColor.GREEN);
            appendTo.then("]").color(ChatColor.YELLOW);
        }

        for (UUID offlineMember : getOfflineMembers()) {
            if (isOwner(offlineMember)) {
                continue;
            }

            FancyMessage appendTo = membersJson;
            if(isCoLeader(offlineMember)) {
                appendTo = coleadersJson;
            } else if(isCaptain(offlineMember)) {
                appendTo = captainsJson;
            }

            if (!ChatColor.stripColor(appendTo.toOldMessageFormat()).endsWith("s: ")) {
                appendTo.then(", ").color(ChatColor.GRAY);
            }

            appendTo.then(UUIDUtils.name(offlineMember)).color(deathbanMap.isDeathbanned(offlineMember) ? ChatColor.RED : ChatColor.GRAY);
            appendTo.then("[").color(ChatColor.YELLOW).then("" + killsMap.getKills(offlineMember)).color(ChatColor.GREEN);
            appendTo.then("]").color(ChatColor.YELLOW);

        }

        // Now we can actually send all that info we just processed.
        player.sendMessage(GRAY_LINE);

        FancyMessage teamLine = new FancyMessage();

        teamLine.text(ChatColor.BLUE + getName()).link("http://www." + HCF.getInstance().getServerHandler().getStatsWebsiteRoot() + "/teams/" + getName()).tooltip(ChatColor.GREEN + "Click to view team on the " + HCF.getInstance().getServerHandler().getServerName() + " website!");
        teamLine.then().text(ChatColor.GRAY + " [" + onlineMembers + "/" + getSize() + "]" + ChatColor.DARK_AQUA + " - ");
        teamLine.then().text(ChatColor.YELLOW + "HQ: " + ChatColor.WHITE + (HQ == null ? "None" : HQ.getBlockX() + ", " + HQ.getBlockZ()));

        if (HQ != null && player.hasPermission("basic.staff")) {
            teamLine.command("/tppos " + HQ.getBlockX() + " " + HQ.getBlockY() + " " + HQ.getBlockZ());
            teamLine.tooltip("§aClick to warp to HQ");
        }

        if (player.hasPermission("foxtrot.manage")) {
            teamLine.then().text("§3 - §e[Manage]").color(ChatColor.YELLOW).command("/manageteam manage " + getName()).tooltip("§bClick to manage team");
        }

        teamLine.send(player);

        if (allies.length() > 2) {
            allies.setLength(allies.length() - 2);
            player.sendMessage(ChatColor.YELLOW + "Allies: " + allies.toString());
        }

        FancyMessage leader = new FancyMessage(ChatColor.YELLOW + "Leader: " + (owner == null || owner.hasMetadata("invisible") ? (deathbanMap.isDeathbanned(getOwner()) ? ChatColor.RED : ChatColor.GRAY) : ChatColor.GREEN) + UUIDUtils.name(getOwner()) + ChatColor.YELLOW + "[" + ChatColor.GREEN + killsMap.getKills(getOwner()) + ChatColor.YELLOW + "]");


        if (player.hasPermission("foxtrot.manage")) {
            leader.command("/manageteam leader " + getName()).tooltip("§bClick to change leader");
        }

        leader.send(player);

        if (!ChatColor.stripColor(coleadersJson.toOldMessageFormat()).endsWith("s: ")) {
            coleadersJson.send(player);
        }

        if (!ChatColor.stripColor(captainsJson.toOldMessageFormat()).endsWith("s: ")) {
            captainsJson.send(player);
        }


        if (!ChatColor.stripColor(membersJson.toOldMessageFormat()).endsWith("s: ")) {
            membersJson.send(player);
        }


        FancyMessage balance = new FancyMessage(ChatColor.YELLOW + "Balance: " + ChatColor.BLUE + "$" + Math.round(getBalance()));

        if (player.hasPermission("foxtrot.manage")) {
            balance.command("/manageteam balance " + getName()).tooltip("§bClick to modify team balance");
        }

        balance.send(player);


        FancyMessage dtrMessage = new FancyMessage(ChatColor.YELLOW + "Deaths until Raidable: " + getDTRColor() + DTR_FORMAT.format(getDTR()) + getDTRSuffix());


        if (player.hasPermission("foxtrot.manage")) {
            dtrMessage.command("/manageteam dtr " + getName()).tooltip("§bClick to modify team DTR");
        }

        dtrMessage.send(player);

        FancyMessage pointsMessage = new FancyMessage(CC.translate("§ePoints: §c" + getPoints()));

        if (player.hasPermission("foxtrot.manage")) {
            pointsMessage.command("/manageteam points " + getName()).tooltip("§bClick to modify team points");
        }
        pointsMessage.send(player);

        if (isMember(player.getUniqueId()) || player.hasPermission("foxtrot.manage")) {
            if (HCF.getInstance().getServerHandler().isForceInvitesEnabled()) {
                player.sendMessage(ChatColor.YELLOW + "Force Invites: " + ChatColor.RED + getForceInvites());
            }
            player.sendMessage(ChatColor.YELLOW + "KOTH Captures: " + ChatColor.RED + getKothCaptures());
            player.sendMessage(ChatColor.YELLOW + "Lives: " + ChatColor.RED + getLives());
            player.sendMessage(ChatColor.YELLOW + "Spawners: " + ChatColor.RED + getSpawnersInClaim());
        }

        if (DTRHandler.isOnCooldown(this)) {
            if (!player.isOp()) {
                player.sendMessage(ChatColor.YELLOW + "Time Until Regen: " + ChatColor.BLUE + TimeUtils.formatIntoDetailedString(((int) (getDTRCooldown() - System.currentTimeMillis())) / 1000).trim());
            } else {
                FancyMessage message = new FancyMessage(ChatColor.YELLOW + "Time Until Regen: ")
                        .tooltip(ChatColor.GREEN + "Click to remove regeneration timer").command("/startdtrregen " + getName());

                message.then(TimeUtils.formatIntoDetailedString(((int) (getDTRCooldown() - System.currentTimeMillis())) / 1000)).color(ChatColor.BLUE)
                        .tooltip(ChatColor.GREEN + "Click to remove regeneration timer").command("/startdtrregen " + getName());

                message.send(player);
            }
        }

        if(player.hasPermission("foxtrot.powerfactions")) {
            FancyMessage powerFactionLine = new FancyMessage();
            powerFactionLine.text(ChatColor.YELLOW + "Power Faction: ");
            if( isPowerFaction() ) {
                powerFactionLine.then().text(ChatColor.GREEN + "True");
                powerFactionLine.command("/powerfaction remove " + getName());
                powerFactionLine.tooltip("§bClick change faction to a non power faction.");
            } else {
                powerFactionLine.then().text(ChatColor.RED + "False");
                powerFactionLine.command("/powerfaction add " + getName());
                powerFactionLine.tooltip("§bClick change faction to a power faction.");
            }
            powerFactionLine.send(player);
        }

        // Only show this if they're a member.
        if (isMember(player.getUniqueId()) && announcement != null && !announcement.equals("null")) {
            player.sendMessage(ChatColor.YELLOW + "Announcement: " + ChatColor.LIGHT_PURPLE + announcement);
        }

        player.sendMessage(GRAY_LINE);
        // .... and that is how we do a /f who.
    }

    
    public int hashCode() {
        return uniqueId.hashCode();
    }

    
    public boolean equals(Object obj) {
        if (!(obj instanceof Team)) {
            return false;
        }

        Team other = (Team) obj;
        return other.uniqueId.equals(uniqueId);
    }

    public ChatColor getDTRColor() {
        ChatColor dtrColor = ChatColor.GREEN;

        if (DTR / getMaxDTR() <= 0.25) {
            if (isRaidable()) {
                dtrColor = ChatColor.DARK_RED;
            } else {
                dtrColor = ChatColor.YELLOW;
            }
        }

        return (dtrColor);
    }

    public String getDTRSuffix() {
        if (DTRHandler.isRegenerating(this)) {
            if (getOnlineMemberAmount() == 0) {
                return (ChatColor.GRAY + "◀");
            } else {
                return (ChatColor.GREEN + "▲");
            }
        } else if (DTRHandler.isOnCooldown(this)) {
            return (ChatColor.RED + "■");
        } else {
            return (ChatColor.GREEN + "◀");
        }
    }
    public String getDTRWithColor() {
        String dtrColored;
        double dtr = Double.parseDouble((new DecimalFormat("#.##")).format(getDTR()));
        if (dtr >= 1.01D) {
            dtrColored = ChatColor.GREEN + String.valueOf(dtr);
        } else if (dtr <= 0.0D) {
            dtrColored = ChatColor.RED + String.valueOf(dtr);
        } else {
            dtrColored = ChatColor.YELLOW + String.valueOf(dtr);
        }
        return dtrColored;
    }
    public Team getFactionFocused(){
        return this.factionFocused;
    }

    public void setFactionFocus(Team factionFocused){
        this.factionFocused = factionFocused;
    }

    public void setFactionFocusedWP(Team targetTeam){
        if (this.factionFocused != null){
            CBWaypoint focusedTeamWaypoint = new CBWaypoint(targetTeam.getName() + "'s HQ", targetTeam.getHQ().getBlockX(), targetTeam.getHQ().getBlockY(), targetTeam.getHQ().getBlockZ(), targetTeam.getHQ().getWorld().getUID().toString(), -43521, true, true);
            getOnlineMembers().forEach(player -> {CheatBreakerAPI.getInstance().removeWaypoint(player, focusedTeamWaypoint);});
            getOnlineMembers().forEach(player -> {CheatBreakerAPI.getInstance().sendWaypoint(player, focusedTeamWaypoint);});
        }
    }


    public boolean isOpen() {
        return this.open;
    }
    public void addPoints(int pointsToAdd) {
        this.setPoints(this.getPoints() + pointsToAdd);
    }
    public void setOpen(boolean open) {
        this.open = open;
    }
}
