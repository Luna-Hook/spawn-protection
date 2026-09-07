package dev.lunahook.spawnprotection;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class SpawnProtectionPlugin extends JavaPlugin implements Listener, TabExecutor {
    private final Map<UUID, Long> combatUntil = new HashMap<>();
    private final Map<UUID, Long> lastNotice = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
        var command = getCommand("spawnguard");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        Player attacker = attacker(event);
        if (attacker == null || attacker.getUniqueId().equals(victim.getUniqueId())) return;

        long expiry = System.currentTimeMillis() + combatSeconds() * 1000L;
        combatUntil.put(attacker.getUniqueId(), expiry);
        combatUntil.put(victim.getUniqueId(), expiry);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null || sameBlock(event.getFrom(), to)) return;

        Player player = event.getPlayer();
        if (player.hasPermission("spawnprotection.bypass") || !inCombat(player)) return;
        if (!protectedWorld(to) || isInside(event.getFrom()) || !isInside(to)) return;
        if (!getConfig().getBoolean("protection.block-teleports-into-region", true)
                && !event.getFrom().getWorld().equals(to.getWorld())) return;

        event.setTo(event.getFrom());
        notifyBlocked(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        combatUntil.remove(event.getPlayer().getUniqueId());
        lastNotice.remove(event.getPlayer().getUniqueId());
    }

    private Player attacker(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player) return player;
        if (event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player player) return player;
        return null;
    }

    private boolean isInside(Location location) {
        if (!protectedWorld(location)) return false;
        RegionManager manager = WorldGuard.getInstance().getPlatform().getRegionContainer()
                .get(BukkitAdapter.adapt(location.getWorld()));
        if (manager == null) return false;
        ProtectedRegion region = manager.getRegion(regionName());
        return region != null && region.contains(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    private boolean protectedWorld(Location location) {
        return location.getWorld() != null
                && location.getWorld().getName().equalsIgnoreCase(getConfig().getString("protection.world", "world"));
    }

    private boolean inCombat(Player player) {
        long expiry = combatUntil.getOrDefault(player.getUniqueId(), 0L);
        if (expiry <= System.currentTimeMillis()) {
            combatUntil.remove(player.getUniqueId());
            return false;
        }
        return true;
    }

    private void notifyBlocked(Player player) {
        long now = System.currentTimeMillis();
        long cooldown = Math.max(0, getConfig().getLong("protection.notify-cooldown-seconds", 2)) * 1000L;
        if (now - lastNotice.getOrDefault(player.getUniqueId(), 0L) < cooldown) return;
        lastNotice.put(player.getUniqueId(), now);

        long seconds = Math.max(1, (combatUntil.getOrDefault(player.getUniqueId(), now) - now + 999L) / 1000L);
        send(player, "entry-denied", Map.of("seconds", String.valueOf(seconds)));
    }

    private long combatSeconds() {
        return Math.max(1, getConfig().getLong("protection.combat-seconds", 15));
    }

    private String regionName() {
        return getConfig().getString("protection.region", "spawn").toLowerCase(Locale.ROOT);
    }

    private boolean sameBlock(Location first, Location second) {
        return first.getWorld().equals(second.getWorld())
                && first.getBlockX() == second.getBlockX()
                && first.getBlockY() == second.getBlockY()
                && first.getBlockZ() == second.getBlockZ();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sender.sendMessage(color("&6&lSpawnProtection &7commands:"));
            sender.sendMessage(color("&e/" + label + " status &7- Show the protected world and region."));
            sender.sendMessage(color("&e/" + label + " reload &7- Reload the configuration."));
            sender.sendMessage(color("&e/" + label + " version &7- Show the plugin version."));
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> sender.sendMessage(color("&6Spawn guard: &f" + getConfig().getString("protection.world", "world")
                    + ":" + regionName() + " &7| PvP tag: &f" + combatSeconds() + "s"));
            case "version" -> sender.sendMessage(color("&6SpawnProtection version: &f" + getPluginMeta().getVersion()));
            case "reload" -> {
                if (!sender.hasPermission("spawnprotection.admin")) {
                    send(sender, "no-permission", Map.of());
                    return true;
                }
                reloadConfig();
                send(sender, "reloaded", Map.of());
            }
            default -> sender.sendMessage(color("&cUnknown subcommand. Use &f/" + label + " help&c."));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        List<String> options = new ArrayList<>(List.of("help", "status", "version"));
        if (sender.hasPermission("spawnprotection.admin")) options.add("reload");
        String input = args[0].toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.startsWith(input)).toList();
    }

    private void send(CommandSender sender, String key, Map<String, String> replacements) {
        String message = getConfig().getString("messages." + key, "&cMissing message: " + key);
        for (var replacement : replacements.entrySet()) {
            message = message.replace("{" + replacement.getKey() + "}", replacement.getValue());
        }
        sender.sendMessage(color(getConfig().getString("messages.prefix", "") + message));
    }

    private String color(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }
}
