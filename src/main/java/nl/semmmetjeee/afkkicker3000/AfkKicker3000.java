package nl.semmmetjeee.afkkicker3000;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Deliberately treats activity as meaningful horizontal travel, not as packets or
 * interactions. This prevents common false activity sources such as head-spinning,
 * auto-clickers, jumping in place and AFK mining from resetting the clock.
 */
public final class AfkKicker3000 extends JavaPlugin implements Listener {
    private final Map<UUID, ActivityState> states = new HashMap<>();
    private long warningMillis;
    private long kickMillis;
    private long pathMemoryMillis;
    private double minimumMovement;
    private double repeatRadiusSquared;
    private boolean detectRepeatingPaths;
    private int repeatsBeforeBlocking;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, this::checkPlayers, 20L, getConfig().getLong("check-interval-seconds", 2) * 20L);
    }

    private void loadSettings() {
        reloadConfig();
        warningMillis = getConfig().getLong("warning-after-seconds", 1140) * 1000L;
        kickMillis = getConfig().getLong("kick-after-seconds", 1200) * 1000L;
        if (warningMillis >= kickMillis) {
            getLogger().warning("warning-after-seconds must be lower than kick-after-seconds; using 19/20 minutes.");
            warningMillis = 1140_000L;
            kickMillis = 1200_000L;
        }
        minimumMovement = Math.max(0.1, getConfig().getDouble("minimum-horizontal-movement", 2.0));
        detectRepeatingPaths = getConfig().getBoolean("detect-repeating-paths", true);
        pathMemoryMillis = Math.max(1, getConfig().getLong("path-memory-seconds", 90)) * 1000L;
        double radius = Math.max(0.1, getConfig().getDouble("repeat-location-radius", 0.75));
        repeatRadiusSquared = radius * radius;
        repeatsBeforeBlocking = Math.max(1, getConfig().getInt("repeats-before-blocking-reset", 3));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        states.put(event.getPlayer().getUniqueId(), new ActivityState(event.getPlayer().getLocation()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        states.put(event.getPlayer().getUniqueId(), new ActivityState(event.getPlayer().getLocation()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null || event.getFrom().getWorld() != to.getWorld()) return;

        Player player = event.getPlayer();
        ActivityState state = states.computeIfAbsent(player.getUniqueId(), ignored -> new ActivityState(event.getFrom()));
        if (horizontalDistanceSquared(state.lastActivityLocation, to) < minimumMovement * minimumMovement) return;

        long now = System.currentTimeMillis();
        state.prune(now - pathMemoryMillis);
        boolean repeatsPath = detectRepeatingPaths && state.wasRecentlyVisited(to, repeatRadiusSquared);
        if (repeatsPath) {
            state.repeatedPathCount++;
            state.remember(to, now);
            // A short turn-back is normal play. Several returns in the same recent
            // path window are characteristic of a water/current loop or macro route.
            if (state.repeatedPathCount >= repeatsBeforeBlocking) return;
        }

        if (!repeatsPath) state.repeatedPathCount = 0;
        state.lastActivityLocation = to.clone();
        state.lastActivityMillis = now;
        state.warned = false;
        state.remember(to, now);
    }

    private void checkPlayers() {
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("afkkicker.bypass")) continue;
            ActivityState state = states.computeIfAbsent(player.getUniqueId(), ignored -> new ActivityState(player.getLocation()));
            long inactiveFor = now - state.lastActivityMillis;
            if (inactiveFor >= kickMillis) {
                player.kickPlayer(colour(getConfig().getString("kick-message", "&cKicked for being AFK for 20 minutes.")));
            } else if (inactiveFor >= warningMillis && !state.warned) {
                state.warned = true;
                player.sendMessage(colour(getConfig().getString("warning-message", "&eYou have been inactive for 19 minutes.")));
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("afkkicker.reload")) return true;
            loadSettings();
            sender.sendMessage("AfkKicker3000 reloaded.");
            return true;
        }
        if (!(sender instanceof Player player)) return true;
        ActivityState state = states.computeIfAbsent(player.getUniqueId(), ignored -> new ActivityState(player.getLocation()));
        if (args.length > 0 && args[0].equalsIgnoreCase("reset")) {
            if (!player.hasPermission("afkkicker.reset")) return true;
            state.reset(player.getLocation());
            player.sendMessage("Your AFK timer has been reset.");
            return true;
        }
        long remaining = Math.max(0, kickMillis - (System.currentTimeMillis() - state.lastActivityMillis));
        player.sendMessage(colour(getConfig().getString("status-message", "&7AFK kick in: &f{seconds}s")
                .replace("{seconds}", String.valueOf((remaining + 999) / 1000))));
        return true;
    }

    private static double horizontalDistanceSquared(Location a, Location b) {
        if (a.getWorld() != b.getWorld()) return Double.MAX_VALUE;
        double x = a.getX() - b.getX();
        double z = a.getZ() - b.getZ();
        return x * x + z * z;
    }

    private static String colour(String input) {
        return ChatColor.translateAlternateColorCodes('&', input);
    }

    private static final class ActivityState {
        private long lastActivityMillis = System.currentTimeMillis();
        private Location lastActivityLocation;
        private boolean warned;
        private int repeatedPathCount;
        private final Deque<Visit> visits = new ArrayDeque<>();

        private ActivityState(Location location) {
            lastActivityLocation = location.clone();
            remember(location, lastActivityMillis);
        }

        private void reset(Location location) {
            lastActivityMillis = System.currentTimeMillis();
            lastActivityLocation = location.clone();
            warned = false;
            repeatedPathCount = 0;
            visits.clear();
            remember(location, lastActivityMillis);
        }

        private void prune(long oldestAllowed) {
            while (!visits.isEmpty() && visits.peekFirst().time < oldestAllowed) visits.removeFirst();
        }

        private boolean wasRecentlyVisited(Location location, double radiusSquared) {
            return visits.stream().anyMatch(visit -> horizontalDistanceSquared(visit.location, location) <= radiusSquared);
        }

        private void remember(Location location, long time) {
            visits.addLast(new Visit(location.clone(), time));
        }
    }

    private record Visit(Location location, long time) { }
}