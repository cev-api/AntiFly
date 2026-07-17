package com.antifly.paper;

import com.antifly.common.AntiFlyConstants;
import com.antifly.common.AttemptTracker;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class AntiFlyPlugin extends JavaPlugin {
    private final AttemptTracker attemptTracker = new AttemptTracker();
    private final Map<UUID, PlayerState> states = new ConcurrentHashMap<>();
    private final Set<UUID> exempt = ConcurrentHashMap.newKeySet();
    private final Set<UUID> debug = ConcurrentHashMap.newKeySet();
    private final Set<UUID> notifiedOutdatedOps = new HashSet<>();
    private final Settings settings = new Settings();
    private boolean antiFlyEnabled = true;

    @Override
    public void onEnable() {
        loadConfigValues();
        Bukkit.getPluginManager().registerEvents(new AntiFlyListener(this), this);
        getCommand("antifly").setExecutor(new AntiFlyCommand(this));
        getCommand("antifly").setTabCompleter(new AntiFlyCommand(this));
        checkModrinthVersionAndAlertOps();
    }

    @Override
    public void onDisable() {
        states.clear();
    }

    AttemptTracker getAttemptTracker() {
        return attemptTracker;
    }

    Settings getSettings() {
        return settings;
    }

    boolean isAntiFlyEnabled() {
        return antiFlyEnabled;
    }

    boolean isWorldEnabled(String worldName) {
        if (worldName == null) {
            return true;
        }
        return !settings.disabledWorlds.contains(worldName.toLowerCase(Locale.ROOT));
    }

    Set<String> getDisabledWorlds() {
        return settings.disabledWorlds;
    }

    void setWorldDisabled(String worldName, boolean disabled) {
        if (worldName == null) {
            return;
        }
        String normalized = worldName.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return;
        }
        java.util.LinkedHashSet<String> updated = new java.util.LinkedHashSet<>(settings.disabledWorlds);
        if (disabled) {
            updated.add(normalized);
        } else {
            updated.remove(normalized);
        }
        settings.disabledWorlds = java.util.Set.copyOf(updated);
        FileConfiguration config = getConfig();
        config.set("disabledWorlds", settings.disabledWorlds.stream().sorted().toList());
        saveConfig();
    }

    void setAntiFlyEnabled(boolean enabled) {
        antiFlyEnabled = enabled;
        FileConfiguration config = getConfig();
        config.set("enabled", enabled);
        saveConfig();
    }

    boolean isExempt(Player player) {
        return exempt.contains(player.getUniqueId());
    }

    Set<UUID> getExemptPlayers() {
        return Set.copyOf(exempt);
    }

    boolean isDebug(Player player) {
        return debug.contains(player.getUniqueId());
    }

    void setDebug(Player player, boolean enabled) {
        if (enabled) {
            debug.add(player.getUniqueId());
        } else {
            debug.remove(player.getUniqueId());
        }
    }

    boolean canUseAdminCommands(org.bukkit.command.CommandSender sender) {
        return !(sender instanceof Player player) || player.isOp() || sender.hasPermission("antifly.admin");
    }

    void checkModrinthVersion(org.bukkit.command.CommandSender sender) {
        String currentVersion = getDescription().getVersion();
        String slug = settings.modrinthProjectSlug;
        sender.sendMessage(org.bukkit.ChatColor.GRAY + "Checking Modrinth for " + slug + " ...");
        ModrinthVersionChecker.checkLatest(this, slug, result -> {
            runOnSenderContext(sender, () -> {
            if (!result.ok) {
                sender.sendMessage(org.bukkit.ChatColor.RED + "Modrinth check failed: " + result.error);
                return;
            }
            int cmp = ModrinthVersionChecker.compareVersions(currentVersion, result.latestVersion);
            if (cmp < 0) {
                sender.sendMessage(org.bukkit.ChatColor.RED + "Outdated: running " + currentVersion + ", Modrinth has " + result.latestVersion);
            } else if (cmp > 0) {
                sender.sendMessage(org.bukkit.ChatColor.YELLOW + "Ahead of Modrinth: running " + currentVersion + ", latest hosted is " + result.latestVersion);
            } else {
                sender.sendMessage(org.bukkit.ChatColor.GREEN + "Up to date with Modrinth: " + currentVersion);
            }
            });
        });
    }

    private void runOnSenderContext(org.bukkit.command.CommandSender sender, Runnable task) {
        if (sender instanceof Player player) {
            player.getScheduler().run(this, scheduledTask -> task.run(), null);
        } else {
            Bukkit.getGlobalRegionScheduler().run(this, scheduledTask -> task.run());
        }
    }

    void addExempt(UUID uuid) {
        exempt.add(uuid);
        persistExempt();
    }

    void removeExempt(UUID uuid) {
        exempt.remove(uuid);
        persistExempt();
    }

    void resetPlayer(UUID uuid) {
        states.remove(uuid);
        attemptTracker.reset(uuid);
    }

    void updateSetting(String key, double value) {
        switch (normalizeSettingKey(key)) {
            case "groundWalkMax" -> settings.groundWalkMax = value;
            case "groundMountedMax" -> settings.groundMountedMax = value;
            case "waterMax" -> settings.waterMax = value;
            case "waterVerticalMax" -> settings.waterVerticalMax = value;
            case "boatMaxHorizontal" -> settings.boatMaxHorizontal = value;
            case "maxAirHorizontal" -> settings.maxAirHorizontal = value;
            case "maxAirVertical" -> settings.maxAirVertical = value;
            case "bufferDecay" -> settings.bufferDecay = value;
            case "horizontalBufferLimit" -> settings.horizontalBufferLimit = value;
            case "verticalBufferLimit" -> settings.verticalBufferLimit = value;
            case "hoverBufferLimit" -> settings.hoverBufferLimit = value;
            case "noFallDetectionEnabled" -> settings.noFallDetectionEnabled = value > 0.5;
            case "airNonFallTicksLimit" -> settings.airNonFallTicksLimit = (int) Math.round(value);
            case "antiKickWindowTicks" -> settings.antiKickWindowTicks = (int) Math.round(value);
            case "antiKickMinDescent" -> settings.antiKickMinDescent = value;
            case "setbackCooldownMs" -> settings.setbackCooldownMs = (long) Math.round(value);
            case "vehicleAirGraceTicks" -> settings.vehicleAirGraceTicks = (int) Math.round(value);
            case "boatAirGraceTicks" -> settings.boatAirGraceTicks = (int) Math.round(value);
            case "horseAirGraceTicks" -> settings.horseAirGraceTicks = (int) Math.round(value);
            case "elytraEnabled" -> settings.elytraEnabled = value > 0.5;
            case "elytraBoostGraceTicks" -> settings.elytraBoostGraceTicks = (int) Math.round(value);
            case "elytraStallTicks" -> settings.elytraStallTicks = (int) Math.round(value);
            case "elytraMovementBufferLimit" -> settings.elytraMovementBufferLimit = value;
            case "elytraDurabilityCheckEnabled" -> settings.elytraDurabilityCheckEnabled = value > 0.5;
            case "elytraNoRocketMaxAscent" -> settings.elytraNoRocketMaxAscent = value;
            case "elytraRequiredDescentForPullup" -> settings.elytraRequiredDescentForPullup = value;
            case "elytraMaxRocketHorizontal" -> settings.elytraMaxRocketHorizontal = value;
            case "elytraMaxRocketUp" -> settings.elytraMaxRocketUp = value;
            case "elytraNoRocketSustainableHorizontal" -> settings.elytraNoRocketSustainableHorizontal = value;
            case "elytraMaxNoRocketUp" -> settings.elytraMaxNoRocketUp = value;
            default -> {
                return;
            }
        }
        writeSettingsToConfig(getConfig());
        saveConfig();
    }

    PlayerState getState(Player player) {
        return states.computeIfAbsent(player.getUniqueId(), ignored -> new PlayerState());
    }

    private void loadConfigValues() {
        FileConfiguration config = getConfig();
        config.addDefault("enabled", true);

        config.addDefault("antiFly.airGraceTicks", 4);
        config.addDefault("antiFly.maxAirHorizontal", AntiFlyConstants.DEFAULT_AIR_MAX);
        config.addDefault("antiFly.maxAirVertical", AntiFlyConstants.DEFAULT_AIR_VERTICAL_MAX);
        config.addDefault("antiFly.hoverStartTicks", 12);
        config.addDefault("antiFly.hoverTicksLimit", 6);
        config.addDefault("antiFly.hoverDeltaY", 0.001);
        config.addDefault("antiFly.hoverHorizontal", 0.03);
        config.addDefault("antiFly.airNonFallTicksLimit", 20);
        config.addDefault("antiFly.antiKickWindowTicks", 40);
        config.addDefault("antiFly.antiKickMinDescent", 0.35);
        config.addDefault("antiFly.vehicleAirGraceTicks", 10);
        config.addDefault("antiFly.boatAirGraceTicks", 0);
        config.addDefault("antiFly.horseAirGraceTicks", 24);
        config.addDefault("antiFly.horizontalBufferLimit", 3.0);
        config.addDefault("antiFly.verticalBufferLimit", 2.0);
        config.addDefault("antiFly.hoverBufferLimit", 3.0);
        config.addDefault("antiFly.bufferDecay", 0.25);
        config.addDefault("antiFly.setbackCooldownMs", 500L);
        config.addDefault("antiFly.noFallDetectionEnabled", true);
        config.addDefault("antiFly.alertMode", "both");

        config.addDefault("elytra.enabled", true);
        config.addDefault("elytra.boostGraceTicks", 80);
        config.addDefault("elytra.toggleGraceTicks", 10);
        config.addDefault("elytra.landingGraceTicks", 8);
        config.addDefault("elytra.stallHorizontalMax", 0.05);
        config.addDefault("elytra.stallVerticalMax", 0.05);
        config.addDefault("elytra.stallTicks", 10);
        config.addDefault("elytra.noRocketWindowTicks", 40);
        config.addDefault("elytra.noRocketMinDescent", 0.60);
        config.addDefault("elytra.noRocketSustainableHorizontal", 1.20);
        config.addDefault("elytra.noRocketMaxAscent", 0.80);
        config.addDefault("elytra.maxNoRocketUp", 0.45);
        config.addDefault("elytra.maxRocketHorizontal", 3.50);
        config.addDefault("elytra.maxRocketUp", 1.50);
        config.addDefault("elytra.requiredDescentForPullup", 0.75);
        config.addDefault("elytra.movementBufferLimit", 6.0);
        config.addDefault("elytra.durabilityCheckEnabled", true);
        config.addDefault("elytra.durabilityBaseWindowTicks", 80);
        config.addDefault("elytra.durabilityUnbreakingMultiplier", 2.5);
        config.addDefault("elytra.durabilitySuspicionLimit", 3);
        config.addDefault("elytra.requireMovementSuspicionForDurabilityPunish", true);

        config.addDefault("limits.groundWalking", AntiFlyConstants.DEFAULT_GROUND_WALK_MAX);
        config.addDefault("limits.groundMounted", AntiFlyConstants.DEFAULT_GROUND_MOUNT_MAX);
        config.addDefault("limits.water", AntiFlyConstants.DEFAULT_WATER_MAX);
        config.addDefault("limits.waterVertical", AntiFlyConstants.DEFAULT_WATER_VERTICAL_MAX);
        config.addDefault("limits.boatHorizontal", AntiFlyConstants.BOAT_MAX_HORIZONTAL);

        config.addDefault("modrinth.projectSlug", "antiflight");
        config.addDefault("disabledWorlds", java.util.List.of());
        config.addDefault("exempt", java.util.List.of());
        config.options().copyDefaults(true);
        saveConfig();

        antiFlyEnabled = config.getBoolean("enabled", true);

        settings.groundWalkMax = config.getDouble("limits.groundWalking", AntiFlyConstants.DEFAULT_GROUND_WALK_MAX);
        settings.groundMountedMax = config.getDouble("limits.groundMounted", AntiFlyConstants.DEFAULT_GROUND_MOUNT_MAX);
        settings.waterMax = config.getDouble("limits.water", AntiFlyConstants.DEFAULT_WATER_MAX);
        settings.waterVerticalMax = config.getDouble("limits.waterVertical", AntiFlyConstants.DEFAULT_WATER_VERTICAL_MAX);
        settings.boatMaxHorizontal = config.getDouble("limits.boatHorizontal", AntiFlyConstants.BOAT_MAX_HORIZONTAL);

        settings.airGraceTicks = config.getInt("antiFly.airGraceTicks", 4);
        settings.maxAirHorizontal = config.getDouble("antiFly.maxAirHorizontal", AntiFlyConstants.DEFAULT_AIR_MAX);
        settings.maxAirVertical = config.getDouble("antiFly.maxAirVertical", AntiFlyConstants.DEFAULT_AIR_VERTICAL_MAX);
        settings.hoverStartTicks = config.getInt("antiFly.hoverStartTicks", 12);
        settings.hoverTicksLimit = config.getInt("antiFly.hoverTicksLimit", 6);
        settings.hoverDeltaY = config.getDouble("antiFly.hoverDeltaY", 0.001);
        settings.hoverHorizontal = config.getDouble("antiFly.hoverHorizontal", 0.03);
        settings.airNonFallTicksLimit = config.getInt("antiFly.airNonFallTicksLimit", 20);
        settings.antiKickWindowTicks = config.getInt("antiFly.antiKickWindowTicks", 40);
        settings.antiKickMinDescent = config.getDouble("antiFly.antiKickMinDescent", 0.35);
        settings.vehicleAirGraceTicks = config.getInt("antiFly.vehicleAirGraceTicks", 10);
        settings.boatAirGraceTicks = config.getInt("antiFly.boatAirGraceTicks", 0);
        settings.horseAirGraceTicks = config.getInt("antiFly.horseAirGraceTicks", 24);
        config.set("antiFly.boatAirGraceTicks", settings.boatAirGraceTicks);
        settings.horizontalBufferLimit = config.getDouble("antiFly.horizontalBufferLimit", 3.0);
        settings.verticalBufferLimit = config.getDouble("antiFly.verticalBufferLimit", 2.0);
        settings.hoverBufferLimit = config.getDouble("antiFly.hoverBufferLimit", 3.0);
        settings.bufferDecay = config.getDouble("antiFly.bufferDecay", 0.25);
        settings.setbackCooldownMs = config.getLong("antiFly.setbackCooldownMs", 500L);
        settings.noFallDetectionEnabled = config.getBoolean("antiFly.noFallDetectionEnabled", true);
        settings.alertMode = AlertMode.fromString(config.getString("antiFly.alertMode", "both"), AlertMode.BOTH);

        settings.elytraEnabled = config.getBoolean("elytra.enabled", true);
        settings.elytraBoostGraceTicks = config.getInt("elytra.boostGraceTicks", 80);
        settings.elytraToggleGraceTicks = config.getInt("elytra.toggleGraceTicks", 10);
        settings.elytraLandingGraceTicks = config.getInt("elytra.landingGraceTicks", 8);
        settings.elytraStallHorizontalMax = config.getDouble("elytra.stallHorizontalMax", 0.05);
        settings.elytraStallVerticalMax = config.getDouble("elytra.stallVerticalMax", 0.05);
        settings.elytraStallTicks = config.getInt("elytra.stallTicks", 10);
        settings.elytraNoRocketWindowTicks = config.getInt("elytra.noRocketWindowTicks", 40);
        settings.elytraNoRocketMinDescent = config.getDouble("elytra.noRocketMinDescent", 0.60);
        settings.elytraNoRocketSustainableHorizontal = config.getDouble("elytra.noRocketSustainableHorizontal", 1.20);
        settings.elytraNoRocketMaxAscent = config.getDouble("elytra.noRocketMaxAscent", 0.80);
        settings.elytraMaxNoRocketUp = config.getDouble("elytra.maxNoRocketUp", 0.45);
        settings.elytraMaxRocketHorizontal = config.getDouble("elytra.maxRocketHorizontal", 3.50);
        settings.elytraMaxRocketUp = config.getDouble("elytra.maxRocketUp", 1.50);
        settings.elytraRequiredDescentForPullup = config.getDouble("elytra.requiredDescentForPullup", 0.75);
        settings.elytraMovementBufferLimit = config.getDouble("elytra.movementBufferLimit", 6.0);
        settings.elytraDurabilityCheckEnabled = config.getBoolean("elytra.durabilityCheckEnabled", true);
        settings.elytraDurabilityBaseWindowTicks = config.getInt("elytra.durabilityBaseWindowTicks", 80);
        settings.elytraDurabilityUnbreakingMultiplier = config.getDouble("elytra.durabilityUnbreakingMultiplier", 2.5);
        settings.elytraDurabilitySuspicionLimit = config.getInt("elytra.durabilitySuspicionLimit", 3);
        settings.elytraRequireMovementSuspicionForDurabilityPunish = config.getBoolean("elytra.requireMovementSuspicionForDurabilityPunish", true);

        settings.modrinthProjectSlug = config.getString("modrinth.projectSlug", "antiflight");
        settings.disabledWorlds = config.getStringList("disabledWorlds").stream()
            .map(name -> name == null ? "" : name.trim().toLowerCase(Locale.ROOT))
            .filter(name -> !name.isEmpty())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

        exempt.clear();
        for (String entry : config.getStringList("exempt")) {
            try {
                exempt.add(UUID.fromString(entry));
            } catch (IllegalArgumentException ignored) {
                getLogger().warning("Invalid exempt UUID: " + entry);
            }
        }
    }

    private void writeSettingsToConfig(FileConfiguration config) {
        config.set("limits.groundWalking", settings.groundWalkMax);
        config.set("limits.groundMounted", settings.groundMountedMax);
        config.set("limits.water", settings.waterMax);
        config.set("limits.waterVertical", settings.waterVerticalMax);
        config.set("limits.boatHorizontal", settings.boatMaxHorizontal);

        config.set("antiFly.maxAirHorizontal", settings.maxAirHorizontal);
        config.set("antiFly.maxAirVertical", settings.maxAirVertical);
        config.set("antiFly.bufferDecay", settings.bufferDecay);
        config.set("antiFly.horizontalBufferLimit", settings.horizontalBufferLimit);
        config.set("antiFly.verticalBufferLimit", settings.verticalBufferLimit);
        config.set("antiFly.hoverBufferLimit", settings.hoverBufferLimit);
        config.set("antiFly.noFallDetectionEnabled", settings.noFallDetectionEnabled);
        config.set("antiFly.airNonFallTicksLimit", settings.airNonFallTicksLimit);
        config.set("antiFly.antiKickWindowTicks", settings.antiKickWindowTicks);
        config.set("antiFly.antiKickMinDescent", settings.antiKickMinDescent);
        config.set("antiFly.vehicleAirGraceTicks", settings.vehicleAirGraceTicks);
        config.set("antiFly.boatAirGraceTicks", settings.boatAirGraceTicks);
        config.set("antiFly.horseAirGraceTicks", settings.horseAirGraceTicks);
        config.set("antiFly.setbackCooldownMs", settings.setbackCooldownMs);

        config.set("elytra.enabled", settings.elytraEnabled);
        config.set("elytra.boostGraceTicks", settings.elytraBoostGraceTicks);
        config.set("elytra.stallTicks", settings.elytraStallTicks);
        config.set("elytra.movementBufferLimit", settings.elytraMovementBufferLimit);
        config.set("elytra.durabilityCheckEnabled", settings.elytraDurabilityCheckEnabled);
        config.set("elytra.maxRocketHorizontal", settings.elytraMaxRocketHorizontal);
        config.set("elytra.maxRocketUp", settings.elytraMaxRocketUp);
        config.set("elytra.noRocketSustainableHorizontal", settings.elytraNoRocketSustainableHorizontal);
        config.set("elytra.noRocketMaxAscent", settings.elytraNoRocketMaxAscent);
        config.set("elytra.maxNoRocketUp", settings.elytraMaxNoRocketUp);
        config.set("elytra.requiredDescentForPullup", settings.elytraRequiredDescentForPullup);
        config.set("disabledWorlds", settings.disabledWorlds.stream().sorted().toList());
    }

    static String normalizeSettingKey(String key) {
        return switch (key) {
            case "airSpeed", "maxAirHorizontal" -> "maxAirHorizontal";
            case "airVertical", "maxAirVertical" -> "maxAirVertical";
            case "airNonFallTicks", "airNonFallTicksLimit" -> "airNonFallTicksLimit";
            case "elytraMovementLimit", "elytraMovementBufferLimit" -> "elytraMovementBufferLimit";
            case "vehicleAirGrace", "vehicleAirGraceTicks" -> "vehicleAirGraceTicks";
            case "boatAirGrace", "boatAirGraceTicks" -> "boatAirGraceTicks";
            case "horseAirGrace", "horseAirGraceTicks" -> "horseAirGraceTicks";
            case "boatSpeed", "boatMaxHorizontal" -> "boatMaxHorizontal";
            case "vehicle_air_grace_ticks" -> "vehicleAirGraceTicks";
            case "boat_air_grace_ticks" -> "boatAirGraceTicks";
            case "horse_air_grace_ticks" -> "horseAirGraceTicks";
            case "boat_max_horizontal" -> "boatMaxHorizontal";
            case "groundSpeed", "groundSpeedWalking", "groundWalkMax" -> "groundWalkMax";
            case "groundSpeedMounted", "groundMountedMax" -> "groundMountedMax";
            case "waterSpeed", "waterMax" -> "waterMax";
            case "waterVertical", "waterVerticalMax" -> "waterVerticalMax";
            case "ground_walk_max" -> "groundWalkMax";
            case "ground_mounted_max" -> "groundMountedMax";
            case "water_max" -> "waterMax";
            case "water_vertical_max" -> "waterVerticalMax";
            case "boat_horizontal_max" -> "boatMaxHorizontal";
            case "max_air_horizontal" -> "maxAirHorizontal";
            case "max_air_vertical" -> "maxAirVertical";
            case "buffer_decay" -> "bufferDecay";
            case "horizontal_buffer_limit" -> "horizontalBufferLimit";
            case "vertical_buffer_limit" -> "verticalBufferLimit";
            case "hover_buffer_limit" -> "hoverBufferLimit";
            case "no_fall_detection_enabled" -> "noFallDetectionEnabled";
            case "air_non_fall_ticks_limit" -> "airNonFallTicksLimit";
            case "anti_kick_window_ticks" -> "antiKickWindowTicks";
            case "anti_kick_min_descent" -> "antiKickMinDescent";
            case "setback_cooldown_ms" -> "setbackCooldownMs";
            case "elytra_enabled" -> "elytraEnabled";
            case "elytra_boost_grace_ticks" -> "elytraBoostGraceTicks";
            case "elytra_stall_ticks" -> "elytraStallTicks";
            case "elytra_movement_buffer_limit" -> "elytraMovementBufferLimit";
            case "elytra_durability_check_enabled" -> "elytraDurabilityCheckEnabled";
            case "elytra_max_rocket_horizontal" -> "elytraMaxRocketHorizontal";
            case "elytra_max_rocket_up" -> "elytraMaxRocketUp";
            case "elytra_no_rocket_sustainable_horizontal" -> "elytraNoRocketSustainableHorizontal";
            case "elytra_no_rocket_max_ascent" -> "elytraNoRocketMaxAscent";
            case "elytra_max_no_rocket_up" -> "elytraMaxNoRocketUp";
            case "elytra_required_descent_for_pullup" -> "elytraRequiredDescentForPullup";
            default -> key;
        };
    }

    private void persistExempt() {
        FileConfiguration config = getConfig();
        java.util.List<String> list = exempt.stream().map(UUID::toString).sorted().toList();
        config.set("exempt", list);
        saveConfig();
    }

    private void checkModrinthVersionAndAlertOps() {
        String currentVersion = getDescription().getVersion();
        ModrinthVersionChecker.checkLatest(this, settings.modrinthProjectSlug, result -> {
            Bukkit.getGlobalRegionScheduler().run(this, scheduledTask -> {
            if (!result.ok) {
                getLogger().warning("Modrinth version check failed: " + result.error);
                return;
            }
            int cmp = ModrinthVersionChecker.compareVersions(currentVersion, result.latestVersion);
            if (cmp >= 0) {
                return;
            }
            String msg = "AntiFly is outdated: running " + currentVersion + ", Modrinth has " + result.latestVersion;
            getLogger().warning(msg);
            for (Player player : Bukkit.getOnlinePlayers()) {
                if ((player.isOp() || player.hasPermission("antifly.admin")) && notifiedOutdatedOps.add(player.getUniqueId())) {
                    player.sendMessage(org.bukkit.ChatColor.RED + msg);
                }
            }
            });
        });
    }

    static final class Settings {
        double groundWalkMax;
        double groundMountedMax;
        double waterMax;
        double waterVerticalMax;
        double boatMaxHorizontal;

        int airGraceTicks;
        double maxAirHorizontal;
        double maxAirVertical;
        int hoverStartTicks;
        int hoverTicksLimit;
        double hoverDeltaY;
        double hoverHorizontal;
        int airNonFallTicksLimit;
        int antiKickWindowTicks;
        double antiKickMinDescent;
        int vehicleAirGraceTicks;
        int boatAirGraceTicks;
        int horseAirGraceTicks;
        double horizontalBufferLimit;
        double verticalBufferLimit;
        double hoverBufferLimit;
        double bufferDecay;
        long setbackCooldownMs;
        boolean noFallDetectionEnabled;
        AlertMode alertMode;

        boolean elytraEnabled;
        int elytraBoostGraceTicks;
        int elytraToggleGraceTicks;
        int elytraLandingGraceTicks;
        double elytraStallHorizontalMax;
        double elytraStallVerticalMax;
        int elytraStallTicks;
        int elytraNoRocketWindowTicks;
        double elytraNoRocketMinDescent;
        double elytraNoRocketSustainableHorizontal;
        double elytraNoRocketMaxAscent;
        double elytraMaxNoRocketUp;
        double elytraMaxRocketHorizontal;
        double elytraMaxRocketUp;
        double elytraRequiredDescentForPullup;
        double elytraMovementBufferLimit;
        boolean elytraDurabilityCheckEnabled;
        int elytraDurabilityBaseWindowTicks;
        double elytraDurabilityUnbreakingMultiplier;
        int elytraDurabilitySuspicionLimit;
        boolean elytraRequireMovementSuspicionForDurabilityPunish;

        String modrinthProjectSlug;
        Set<String> disabledWorlds = Set.of();
    }

    enum AlertMode {
        OFF,
        GAME,
        CONSOLE,
        BOTH;

        static AlertMode fromString(String value, AlertMode fallback) {
            if (value == null) {
                return fallback;
            }
            return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "off" -> OFF;
                case "game", "ingame", "in-game" -> GAME;
                case "console" -> CONSOLE;
                case "both", "all" -> BOTH;
                default -> fallback;
            };
        }
    }

    void setAlertMode(AlertMode mode) {
        settings.alertMode = mode;
        FileConfiguration config = getConfig();
        config.set("antiFly.alertMode", mode.name().toLowerCase(java.util.Locale.ROOT));
        saveConfig();
    }

    static final class PlayerState {
        org.bukkit.Location lastGround;
        org.bukkit.Location lastSupport;
        org.bukkit.Location lastPos;

        boolean serverAllowedFlight;
        long lastAllowFlightChangeMs;
        int flightRevokeGraceTicks;

        boolean lastServerOnGround;
        boolean lastClientOnGround;
        int groundSpoofTicks;

        double airHorizontalBuffer;
        double airVerticalBuffer;
        double hoverBuffer;
        double antiKickBuffer;
        double groundSpoofBuffer;
        double elytraBuffer;

        int airTicks;
        int vehicleAirTicks;
        int airNonFallTicks;
        int hoverTicks;
        int antiKickWindowTicks;
        double airWindowDescent;
        double airWindowAscent;

        boolean wasGliding;
        int glideTicks;
        int glideToggleGraceTicks;
        int glideLandingGraceTicks;
        int fluidExitGraceTicks;
        int glideHoverTicks;
        int glideControlTicks;
        int glideStallTicks;
        int glideNoRocketWindowTicks;
        int lastRocketTick;
        long lastRocketUseMs;
        double glideWindowHorizontal;
        double glideWindowDescent;
        double glideWindowAscent;
        double lastGlideHorizontal;
        double peakGlideHorizontal;
        double elytraMovementBuffer;

        int lastElytraDurability = -1;
        int elytraDurabilityTicks;
        int elytraNoDurabilityDropWindows;
        int lastElytraDurabilityDropTick;
        boolean durabilitySuspicious;

        int tick;
        boolean lastInFluid;
        long lastSetbackAtMs;
        long lastRubberBandAtMs;
    }
}
