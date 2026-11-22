package com.example.deathban;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class DeathBanPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    /** A single deathban. until == 0 means permanent. */
    private record Ban(String name, long until) {
        boolean expired() {
            return until != 0 && System.currentTimeMillis() >= until;
        }
    }

    private final Map<UUID, Ban> bans = new ConcurrentHashMap<>();
    private File dataFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "bans.yml");
        loadBans();

        getServer().getPluginManager().registerEvents(this, this);
        for (String cmd : new String[]{"deathpardon", "deathbans"}) {
            if (getCommand(cmd) != null) {
                getCommand(cmd).setExecutor(this);
                getCommand(cmd).setTabCompleter(this);
            }
        }
        getLogger().info("DeathBan enabled. " + bans.size() + " active deathban(s) loaded.");
    }

    @Override
    public void onDisable() {
        saveBans();
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (player.hasPermission("deathban.bypass")) {
            return;
        }

        Location loc = player.getLocation();
        World world = loc.getWorld();

        // Lightning effect (no damage, no fire)
        if (world != null && getConfig().getBoolean("lightning.enabled", true)) {
            world.strikeLightningEffect(loc);
        }

        // Warden sound
        if (world != null && getConfig().getBoolean("sound.enabled", true)) {
            String keyString = getConfig().getString("sound.key", "entity.warden.roar");
            float volume = (float) getConfig().getDouble("sound.volume", 2.0);
            float pitch = (float) getConfig().getDouble("sound.pitch", 1.0);
            try {
                Sound sound = Sound.sound(Key.key(keyString), Sound.Source.MASTER, volume, pitch);
                if (getConfig().getBoolean("sound.play-for-everyone", true)) {
                    for (Player online : Bukkit.getOnlinePlayers()) {
                        online.playSound(sound, Sound.Emitter.self());
                    }
                } else {
                    world.playSound(sound, loc.getX(), loc.getY(), loc.getZ());
                }
            } catch (Exception ex) {
                getLogger().warning("Invalid sound key in config.yml: " + keyString);
            }
        }

        // Record the ban
        long minutes = getConfig().getLong("ban-duration-minutes", 0);
        long until = minutes <= 0 ? 0 : System.currentTimeMillis() + minutes * 60_000L;
        Ban ban = new Ban(player.getName(), until);
        bans.put(player.getUniqueId(), ban);
        saveBans();

        String broadcast = getConfig().getString("messages.broadcast", "");
        if (!broadcast.isEmpty()) {
            Bukkit.broadcast(color(broadcast.replace("{player}", player.getName())));
        }

        // Kick after a short delay so the effects are seen/heard
        long delay = Math.max(1, getConfig().getLong("kick-delay-ticks", 40));
        UUID id = player.getUniqueId();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline() && bans.containsKey(id)) {
                p.kick(banMessage(ban));
            }
        }, delay);
    }

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        UUID id = event.getUniqueId();
        Ban ban = bans.get(id);
        if (ban == null) {
            return;
        }
        if (ban.expired()) {
            bans.remove(id);
            saveBans();
            return;
        }
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, banMessage(ban));
    }

    // ---------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);

        if (name.equals("deathpardon")) {
            if (args.length != 1) {
                sender.sendMessage(msg("messages.usage-pardon", null));
                return true;
            }
            UUID id = findBannedByName(args[0]);
            if (id == null) {
                sender.sendMessage(msg("messages.not-banned", args[0]));
                return true;
            }
            Ban removed = bans.remove(id);
            saveBans();
            sender.sendMessage(msg("messages.pardoned", removed.name()));
            String bc = getConfig().getString("messages.pardoned-broadcast", "");
            if (!bc.isEmpty()) {
                Bukkit.broadcast(color(bc.replace("{player}", removed.name())));
            }
            return true;
        }

        if (name.equals("deathbans")) {
            String sub = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "reload" -> {
                    reloadConfig();
                    loadBans();
                    sender.sendMessage(msg("messages.reloaded", null));
                }
                case "check" -> {
                    if (args.length < 2) {
                        sender.sendMessage(Component.text("Usage: /deathbans check <player>", NamedTextColor.RED));
                        return true;
                    }
                    UUID id = findBannedByName(args[1]);
                    if (id == null) {
                        sender.sendMessage(msg("messages.not-banned", args[1]));
                    } else {
                        Ban b = bans.get(id);
                        sender.sendMessage(Component.text(b.name(), NamedTextColor.RED)
                                .append(Component.text(" is deathbanned. Remaining: ", NamedTextColor.GRAY))
                                .append(Component.text(remaining(b), NamedTextColor.WHITE)));
                    }
                }
                default -> {
                    purgeExpired();
                    if (bans.isEmpty()) {
                        sender.sendMessage(msg("messages.no-bans", null));
                        return true;
                    }
                    sender.sendMessage(Component.text("Deathbanned players (" + bans.size() + "):", NamedTextColor.GOLD));
                    for (Ban b : bans.values()) {
                        sender.sendMessage(Component.text(" - " + b.name(), NamedTextColor.RED)
                                .append(Component.text(" (" + remaining(b) + ")", NamedTextColor.GRAY)));
                    }
                }
            }
            return true;
        }
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("deathpardon") && args.length == 1) {
            addMatchingNames(out, args[0]);
        } else if (name.equals("deathbans")) {
            if (args.length == 1) {
                for (String s : new String[]{"list", "check", "reload"}) {
                    if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
                }
            } else if (args.length == 2 && args[0].equalsIgnoreCase("check")) {
                addMatchingNames(out, args[1]);
            }
        }
        return out;
    }

    // ----------------------------------------------------------------- helpers

    private void addMatchingNames(List<String> out, String prefix) {
        for (Ban b : bans.values()) {
            if (b.name().toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
                out.add(b.name());
            }
        }
    }

    private UUID findBannedByName(String name) {
        for (Map.Entry<UUID, Ban> e : bans.entrySet()) {
            if (e.getValue().name().equalsIgnoreCase(name) && !e.getValue().expired()) {
                return e.getKey();
            }
        }
        return null;
    }

    private void purgeExpired() {
        if (bans.values().removeIf(Ban::expired)) {
            saveBans();
        }
    }

    private Component banMessage(Ban ban) {
        String raw = getConfig().getString("messages.banned", "&cYou died and are deathbanned.");
        return color(raw.replace("{time}", remaining(ban)).replace("{player}", ban.name()));
    }

    private String remaining(Ban ban) {
        if (ban.until() == 0) {
            return "permanent (until pardoned)";
        }
        long ms = Math.max(0, ban.until() - System.currentTimeMillis());
        long totalMinutes = (ms + 59_999) / 60_000;
        long h = totalMinutes / 60;
        long m = totalMinutes % 60;
        return h > 0 ? h + "h " + m + "m" : m + "m";
    }

    private Component msg(String path, String player) {
        String raw = getConfig().getString(path, "");
        if (player != null) {
            raw = raw.replace("{player}", player);
        }
        return color(raw);
    }

    private static Component color(String s) {
        return LEGACY.deserialize(s);
    }

    // ------------------------------------------------------------- persistence

    private void loadBans() {
        bans.clear();
        if (!dataFile.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection section = yaml.getConfigurationSection("bans");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                String name = section.getString(key + ".name", "unknown");
                long until = section.getLong(key + ".until", 0);
                Ban ban = new Ban(name, until);
                if (!ban.expired()) {
                    bans.put(id, ban);
                }
            } catch (IllegalArgumentException ignored) {
                getLogger().warning("Skipping invalid UUID in bans.yml: " + key);
            }
        }
    }

    private synchronized void saveBans() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, Ban> e : bans.entrySet()) {
            String base = "bans." + e.getKey();
            yaml.set(base + ".name", e.getValue().name());
            yaml.set(base + ".until", e.getValue().until());
        }
        try {
            getDataFolder().mkdirs();
            yaml.save(dataFile);
        } catch (IOException ex) {
            getLogger().severe("Could not save bans.yml: " + ex.getMessage());
        }
    }
}
