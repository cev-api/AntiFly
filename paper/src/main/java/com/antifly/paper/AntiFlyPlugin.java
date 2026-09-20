package com.antifly.paper;

import com.antifly.common.AntiFlyConstants;
import com.antifly.common.AttemptTracker;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class AntiFlyPlugin extends JavaPlugin {
    private final AttemptTracker attemptTracker = new AttemptTracker();
    private final Map<UUID, PlayerState> states = new ConcurrentHashMap<>();
    private final Set<UUID> exempt = ConcurrentHashMap.newKeySet();
    private final Set<UUID> debug = ConcurrentHashMap.newKeySet();
    private final Set<UUID> notifiedOutdatedOps = ConcurrentHashMap.newKeySet();
    private final Settings settings = new Settings();
    private boolean antiFlyEnabled = true;
    private AntiFlyListener listener;

    @Override
    public void onEnable() {
        loadConfigValues();
        listener = new AntiFlyListener(this);
        Bukkit.getPluginManager().registerEvents(listener, this);
        PluginCommand command = getCommand("antifly");
        if (command == null) {
            getLogger().warning("Could not register /antifly - another plugin already owns that command.");
        } else {
            AntiFlyCommand executor = new AntiFlyCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
        // Evaluate Hunger Mode every tick so accumulated movement debt is paid
        // continuously. The listener still measures elapsed wall-clock time,
        // so this does not increase the configured per-second drain rate.
        PlatformCompat.runGlobalAtFixedRate(this, () -> {
            if (listener != null) {
                listener.hungerModeTick();
            }
        }, 1L, 1L);
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

    boolean isHungerModeEnabled() {
        return settings.hungerModeEnabled;
    }

    void setHungerModeEnabled(boolean enabled) {
        settings.hungerModeEnabled = enabled;
        FileConfiguration config = getConfig();
        config.set("hungerMode.enabled", enabled);
        saveConfig();
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
        return !(sender instanceof Player player) || player.isOp();
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
        PlatformCompat.runForSender(this, sender, task);
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

    /**
     * Re-reads config.yml from disk so operators can edit values by hand
     * without restarting the server. Everything tracked per player is left
     * alone; only the tunables and world/exempt lists are refreshed.
     */
    void reloadAntiFlyConfig() {
        reloadConfig();
        loadConfigValues();
    }

    boolean updateSetting(String key, double value) {
        if (settings.elytraVerifierTuning.update(normalizeSettingKey(key), value)) {
            writeSettingsToConfig(getConfig());
            saveConfig();
            return true;
        }
        switch (normalizeSettingKey(key)) {
            case "groundWalkMax" -> settings.groundWalkMax = value;
            case "groundMountedMax" -> settings.groundMountedMax = value;
            case "waterMax" -> settings.waterMax = value;
            case "waterVerticalMax" -> settings.waterVerticalMax = value;
            case "boatMaxHorizontal" -> settings.boatMaxHorizontal = value;
            case "airGraceTicks" -> settings.airGraceTicks = Math.max(0, (int) Math.round(value));
            case "hoverStartTicks" -> settings.hoverStartTicks = Math.max(0, (int) Math.round(value));
            case "hoverTicksLimit" -> settings.hoverTicksLimit = Math.max(0, (int) Math.round(value));
            case "hoverDeltaY" -> settings.hoverDeltaY = Math.max(0.0, value);
            case "hoverHorizontal" -> settings.hoverHorizontal = Math.max(0.0, value);            case "maxAirHorizontal" -> settings.maxAirHorizontal = value;
            case "maxAirVertical" -> settings.maxAirVertical = value;
            case "bufferDecay" -> settings.bufferDecay = value;
            case "horizontalBufferLimit" -> settings.horizontalBufferLimit = value;
            case "verticalBufferLimit" -> settings.verticalBufferLimit = value;
            case "hoverBufferLimit" -> settings.hoverBufferLimit = value;
            case "noFallDetectionEnabled" -> settings.noFallDetectionEnabled = value > 0.5;
            case "sustainedAirTicksLimit" -> settings.sustainedAirTicksLimit = Math.max(1, (int) Math.round(value));
            case "sustainedAirMinDescent" -> settings.sustainedAirMinDescent = Math.max(0, value);
            case "airNonFallTicksLimit" -> settings.airNonFallTicksLimit = (int) Math.round(value);
            case "antiKickWindowTicks" -> settings.antiKickWindowTicks = (int) Math.round(value);
            case "antiKickMinDescent" -> settings.antiKickMinDescent = value;
            case "setbackCooldownMs" -> settings.setbackCooldownMs = (long) Math.round(value);
            case "vehicleAirGraceTicks" -> settings.vehicleAirGraceTicks = (int) Math.round(value);
            case "boatAirGraceTicks" -> settings.boatAirGraceTicks = (int) Math.round(value);
            case "horseAirGraceTicks" -> settings.horseAirGraceTicks = (int) Math.round(value);
            case "elytraToggleGraceTicks" -> settings.elytraToggleGraceTicks = Math.max(0, (int) Math.round(value));
            case "elytraLandingGraceTicks" -> settings.elytraLandingGraceTicks = Math.max(0, (int) Math.round(value));
            case "elytraStallHorizontalMax" -> settings.elytraStallHorizontalMax = Math.max(0.0, value);
            case "elytraStallVerticalMax" -> settings.elytraStallVerticalMax = Math.max(0.0, value);
            case "elytraNoRocketWindowTicks" -> settings.elytraNoRocketWindowTicks = Math.max(0, (int) Math.round(value));
            case "elytraNoRocketMinDescent" -> settings.elytraNoRocketMinDescent = Math.max(0.0, value);
            case "elytraDurabilityBaseWindowTicks" -> settings.elytraDurabilityBaseWindowTicks = Math.max(1, (int) Math.round(value));
            case "elytraDurabilityUnbreakingMultiplier" -> settings.elytraDurabilityUnbreakingMultiplier = Math.max(0.0, value);
            case "elytraDurabilitySuspicionLimit" -> settings.elytraDurabilitySuspicionLimit = Math.max(1, (int) Math.round(value));
            case "elytraRequireMovementSuspicionForDurabilityPunish" -> settings.elytraRequireMovementSuspicionForDurabilityPunish = value > 0.5;            case "elytraEnabled" -> settings.elytraEnabled = value > 0.5;
            case "elytraBoostGraceTicks" -> settings.elytraBoostGraceTicks = (int) Math.round(value);
            case "elytraStallTicks" -> settings.elytraStallTicks = (int) Math.round(value);
            case "elytraMovementBufferLimit" -> settings.elytraMovementBufferLimit = value;
            case "elytraDurabilityCheckEnabled" -> settings.elytraDurabilityCheckEnabled = value > 0.5;
            case "elytraNoRocketMaxAscent" -> settings.elytraNoRocketMaxAscent = value;
            case "elytraSustainedClimbTicksLimit" -> settings.elytraSustainedClimbTicksLimit = Math.max(1, (int) Math.round(value));
            case "elytraVerifierMode" -> settings.elytraVerifierMode = value > 0.5 ? "enforce" : "off";
            case "elytraGlideSuppressionTicks" -> settings.elytraGlideSuppressionTicks = Math.max(0, (int) Math.round(value));
            case "elytraRequiredDescentForPullup" -> settings.elytraRequiredDescentForPullup = value;
            case "elytraMaxRocketHorizontal" -> settings.elytraMaxRocketHorizontal = value;
            case "elytraMaxRocketUp" -> settings.elytraMaxRocketUp = value;
            case "elytraNoRocketSustainableHorizontal" -> settings.elytraNoRocketSustainableHorizontal = value;
            case "elytraMaxNoRocketUp" -> settings.elytraMaxNoRocketUp = value;
            case "hungerModeMaxBlocksPerSecond" -> settings.hungerModeMaxBlocksPerSecond = Math.max(1.0, value);
            case "hungerModeHungerPerSecondAtMaxSpeed" -> settings.hungerModeHungerPerSecondAtMaxSpeed = Math.max(0.0, value);
            case "hungerModeRocketGraceTicks" -> settings.hungerModeRocketGraceTicks = Math.max(0, (int) Math.round(value));
            case "hungerModeAirborneMinimumBlocksPerSecond" -> settings.hungerModeAirborneMinimumBlocksPerSecond = Math.max(0.0, value);
            case "hungerModeFlightDamageEnabled" -> settings.hungerModeFlightDamageEnabled = value > 0.5;
            case "hungerModeFlightDamageAfterSeconds" -> settings.hungerModeFlightDamageAfterSeconds = Math.max(0.0, value);
            case "hungerModeFlightDamageAfterHungerSeconds" -> settings.hungerModeFlightDamageAfterHungerSeconds = Math.max(0.0, value);
            case "hungerModeFlightDamagePerSecond" -> settings.hungerModeFlightDamagePerSecond = Math.max(0.0, value);
            case "hungerModeElytraFoodEnabled" -> settings.hungerModeElytraFoodEnabled = value > 0.5;
            case "hungerModeElytraFoodMultiplier" -> settings.hungerModeElytraFoodMultiplier = Math.max(0.0, value);
            case "hungerModeElytraSpeedThresholdBps" -> settings.hungerModeElytraSpeedThresholdBps = Math.max(1.0, value);
            case "hungerModeElytraNoRocketAfterSeconds" -> settings.hungerModeElytraNoRocketAfterSeconds = Math.max(1.0, value);
            case "hungerModeElytraDamageEnabled" -> settings.hungerModeElytraDamageEnabled = value > 0.5;
            case "hungerModeRocketResetsDamage" -> settings.hungerModeRocketResetsDamage = value > 0.5;
            case "impulseGraceTicks" -> settings.impulseGraceTicks = Math.max(0, (int) Math.round(value));
            case "groundSpoofTicksLimit" -> settings.groundSpoofTicksLimit = Math.max(1, (int) Math.round(value));
            case "vehicleFallMinDescent" -> settings.vehicleFallMinDescent = value;
            case "vehicleFallMaxHorizontal" -> settings.vehicleFallMaxHorizontal = value;
            case "vehicleFallTicksMax" -> settings.vehicleFallTicksMax = Math.max(1, (int) Math.round(value));
            case "repeatOffenderAlertCount" -> settings.repeatOffenderAlertCount = Math.max(1, (int) Math.round(value));
            default -> {
                return false;
            }
        }
        writeSettingsToConfig(getConfig());
        saveConfig();
        return true;
    }

    void setVoidAccess(boolean enabled) {
        settings.voidAccess = enabled;
        getConfig().set("voidAccess", enabled);
        saveConfig();
    }

    void setElytraVerifierMode(String mode) {
        settings.elytraVerifierMode = mode;
        getConfig().set("elytra.verifierMode", mode);
        saveConfig();
    }

    PlayerState getState(Player player) {
        return states.computeIfAbsent(player.getUniqueId(), ignored -> new PlayerState());
    }

    /**
     * Brings an older config.yml forward. Every entry below is a value that
     * shipped as a default in an earlier release and was later raised because
     * it flagged movement vanilla players actually do. Changing a default in
     * code alone only affects fresh installs, since Bukkit keeps whatever the
     * existing file already stored - which is how an untouched install ends up
     * rubber-banding every normal glide. A value that is still exactly an old
     * default was never tuned, so it is raised; anything else is a deliberate
     * choice and is left alone.
     */
    private void migrateLegacyDefaults(FileConfiguration config) {
        final int currentVersion = 4;
        if (config.getInt("config-version", 0) >= currentVersion) {
            return;
        }
        boolean changed = false;
        // Reinterpreted twice: first as a per-40-tick ascent allowance that could
        // never fire, then a net-altitude cap of 3.0 which real elytra play was
        // measured to exceed (rocket-boosted and momentum pull-ups both pass it).
        // 3.0 was a false-positive threshold, so it is raised to 6.0.
        if (config.getDouble("elytra.noRocketMaxAscent", 0.0) == 3.0) {
            config.set("elytra.noRocketMaxAscent", 6.0);
            changed = true;
        }
        if (config.getDouble("limits.groundWalking", 0.0) == 0.49) {
            config.set("limits.groundWalking", AntiFlyConstants.DEFAULT_GROUND_WALK_MAX);
            changed = true;
        }
        if (config.getDouble("antiFly.maxAirVertical", 0.0) == 0.756) {
            config.set("antiFly.maxAirVertical", AntiFlyConstants.DEFAULT_AIR_VERTICAL_MAX);
            changed = true;
        }
        if (config.getDouble("elytra.noRocketSustainableHorizontal", 0.0) == 2.6) {
            config.set("elytra.noRocketSustainableHorizontal", 6.0);
            changed = true;
        }
        if (config.getDouble("elytra.maxNoRocketUp", 0.0) == 0.55) {
            config.set("elytra.maxNoRocketUp", 4.0);
            changed = true;
        }
        if (config.getDouble("hungerMode.hungerPerSecondAtMaxSpeed", 0.0) == 20.0) {
            config.set("hungerMode.hungerPerSecondAtMaxSpeed", 10.0);
            changed = true;
        }
        config.set("config-version", currentVersion);
        if (changed) {
            getLogger().info("Migrated config.yml to version " + currentVersion
                + " (raised defaults that flagged vanilla behaviour).");
        }
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
        config.addDefault("antiFly.sustainedAirTicksLimit", 150);
        config.addDefault("antiFly.impulseGraceTicks", AntiFlyConstants.IMPULSE_GRACE_TICKS);
        config.addDefault("antiFly.groundSpoofTicksLimit", AntiFlyConstants.GROUND_SPOOF_TICKS);
        config.addDefault("antiFly.vehicleFallMinDescent", AntiFlyConstants.VEHICLE_FALL_MIN_DESCENT);
        config.addDefault("antiFly.vehicleFallMaxHorizontal", AntiFlyConstants.VEHICLE_FALL_MAX_HORIZONTAL);
        config.addDefault("antiFly.vehicleFallTicksMax", AntiFlyConstants.VEHICLE_FALL_TICKS_MAX);
        config.addDefault("antiFly.repeatOffenderAlertCount", 10);
        config.addDefault("antiFly.alertMode", "both");

        config.addDefault("hungerMode.enabled", false);
        config.addDefault("voidAccess", true);
        config.addDefault("hungerMode.maxBlocksPerSecond", 200.0);
        config.addDefault("hungerMode.hungerPerSecondAtMaxSpeed", 10.0);
        config.addDefault("hungerMode.rocketGraceTicks", 80);
        config.addDefault("hungerMode.airborneMinimumBlocksPerSecond", 20.0);
        config.addDefault("hungerMode.flightDamageEnabled", true);
        config.addDefault("hungerMode.flightDamageAfterSeconds", 20.0);
        config.addDefault("hungerMode.flightDamageAfterHungerSeconds", 30.0);
        config.addDefault("hungerMode.flightDamagePerSecond", 1.0);
        config.addDefault("hungerMode.elytraFoodEnabled", true);
        config.addDefault("hungerMode.elytraFoodMultiplier", 0.5);
        config.addDefault("hungerMode.elytraSpeedThresholdBps", 50.0);
        config.addDefault("hungerMode.elytraNoRocketAfterSeconds", 45.0);
        config.addDefault("hungerMode.elytraDamageEnabled", true);
        config.addDefault("hungerMode.rocketResetsDamage", true);

        config.addDefault("elytra.enabled", true);
        config.addDefault("elytra.boostGraceTicks", 80);
        config.addDefault("elytra.toggleGraceTicks", 10);
        config.addDefault("elytra.landingGraceTicks", 8);
        config.addDefault("elytra.stallHorizontalMax", 0.05);
        config.addDefault("elytra.stallVerticalMax", 0.05);
        config.addDefault("elytra.stallTicks", 10);
        config.addDefault("elytra.noRocketWindowTicks", 40);
        config.addDefault("elytra.noRocketMinDescent", 0.60);
        config.addDefault("elytra.noRocketSustainableHorizontal", 6.0);
        config.addDefault("elytra.noRocketMaxAscent", 6.0);
        config.addDefault("elytra.sustainedClimbTicksLimit", AntiFlyConstants.ELYTRA_SUSTAINED_CLIMB_TICKS);
        config.addDefault("elytra.verifierMode", "enforce");
        config.addDefault("elytra.glideSuppressionTicks", AntiFlyConstants.ELYTRA_GLIDE_SUPPRESSION_TICKS);
        config.addDefault("elytra.maxNoRocketUp", 4.0);
        config.addDefault("elytra.maxRocketHorizontal", 6.0);
        config.addDefault("elytra.maxRocketUp", 4.0);
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
        migrateLegacyDefaults(config);
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
        settings.sustainedAirTicksLimit = Math.max(1, config.getInt("antiFly.sustainedAirTicksLimit", 150));
        settings.sustainedAirMinDescent = Math.max(0, config.getDouble("antiFly.sustainedAirMinDescent", 40.0));
        settings.impulseGraceTicks = Math.max(0, config.getInt("antiFly.impulseGraceTicks", AntiFlyConstants.IMPULSE_GRACE_TICKS));
        settings.groundSpoofTicksLimit = Math.max(1, config.getInt("antiFly.groundSpoofTicksLimit", AntiFlyConstants.GROUND_SPOOF_TICKS));
        settings.vehicleFallMinDescent = config.getDouble("antiFly.vehicleFallMinDescent", AntiFlyConstants.VEHICLE_FALL_MIN_DESCENT);
        settings.vehicleFallMaxHorizontal = config.getDouble("antiFly.vehicleFallMaxHorizontal", AntiFlyConstants.VEHICLE_FALL_MAX_HORIZONTAL);
        settings.vehicleFallTicksMax = Math.max(1, config.getInt("antiFly.vehicleFallTicksMax", AntiFlyConstants.VEHICLE_FALL_TICKS_MAX));
        settings.repeatOffenderAlertCount = Math.max(1, config.getInt("antiFly.repeatOffenderAlertCount", 10));
        settings.alertMode = AlertMode.fromString(config.getString("antiFly.alertMode", "both"), AlertMode.BOTH);

        settings.hungerModeEnabled = config.getBoolean("hungerMode.enabled", false);
        settings.voidAccess = config.getBoolean("voidAccess", true);
        settings.hungerModeMaxBlocksPerSecond = Math.max(1.0, config.getDouble("hungerMode.maxBlocksPerSecond", 200.0));
        settings.hungerModeHungerPerSecondAtMaxSpeed = Math.max(0.0, config.getDouble("hungerMode.hungerPerSecondAtMaxSpeed", 10.0));
        settings.hungerModeRocketGraceTicks = Math.max(0, config.getInt("hungerMode.rocketGraceTicks", 80));
        settings.hungerModeAirborneMinimumBlocksPerSecond = Math.max(0.0, config.getDouble("hungerMode.airborneMinimumBlocksPerSecond", 20.0));
        settings.hungerModeFlightDamageEnabled = config.getBoolean("hungerMode.flightDamageEnabled", true);
        settings.hungerModeFlightDamageAfterSeconds = Math.max(0.0, config.getDouble("hungerMode.flightDamageAfterSeconds", 20.0));
        settings.hungerModeFlightDamageAfterHungerSeconds = Math.max(0.0, config.getDouble("hungerMode.flightDamageAfterHungerSeconds", 30.0));
        settings.hungerModeFlightDamagePerSecond = Math.max(0.0, config.getDouble("hungerMode.flightDamagePerSecond", 1.0));
        settings.hungerModeElytraFoodEnabled = config.getBoolean("hungerMode.elytraFoodEnabled", true);
        settings.hungerModeElytraFoodMultiplier = Math.max(0.0, config.getDouble("hungerMode.elytraFoodMultiplier", 0.5));
        settings.hungerModeElytraSpeedThresholdBps = Math.max(1.0, config.getDouble("hungerMode.elytraSpeedThresholdBps", 50.0));
        settings.hungerModeElytraNoRocketAfterSeconds = Math.max(1.0, config.getDouble("hungerMode.elytraNoRocketAfterSeconds", 45.0));
        settings.hungerModeElytraDamageEnabled = config.getBoolean("hungerMode.elytraDamageEnabled", true);
        settings.hungerModeRocketResetsDamage = config.getBoolean("hungerMode.rocketResetsDamage", true);

        settings.elytraEnabled = config.getBoolean("elytra.enabled", true);
        settings.elytraBoostGraceTicks = config.getInt("elytra.boostGraceTicks", 80);
        settings.elytraToggleGraceTicks = config.getInt("elytra.toggleGraceTicks", 10);
        settings.elytraLandingGraceTicks = config.getInt("elytra.landingGraceTicks", 8);
        settings.elytraStallHorizontalMax = config.getDouble("elytra.stallHorizontalMax", 0.05);
        settings.elytraStallVerticalMax = config.getDouble("elytra.stallVerticalMax", 0.05);
        settings.elytraStallTicks = config.getInt("elytra.stallTicks", 10);
        settings.elytraNoRocketWindowTicks = config.getInt("elytra.noRocketWindowTicks", 40);
        settings.elytraNoRocketMinDescent = config.getDouble("elytra.noRocketMinDescent", 0.60);
        settings.elytraNoRocketSustainableHorizontal = config.getDouble("elytra.noRocketSustainableHorizontal", 6.0);
        settings.elytraNoRocketMaxAscent = config.getDouble("elytra.noRocketMaxAscent", 6.0);
        settings.elytraSustainedClimbTicksLimit = Math.max(1, config.getInt("elytra.sustainedClimbTicksLimit", AntiFlyConstants.ELYTRA_SUSTAINED_CLIMB_TICKS));
        settings.elytraVerifierMode = config.getString("elytra.verifierMode", "enforce");
        var verifier = settings.elytraVerifierTuning;
        verifier.gravity = Math.max(0, config.getDouble("elytra.verifier.gravity", 0.08));
        verifier.residualAllowance = Math.max(0, config.getDouble("elytra.verifier.residualAllowance", 0.20));
        verifier.residualPerSpeed = Math.max(0, config.getDouble("elytra.verifier.residualPerSpeed", 0.02));
        verifier.energyAllowance = Math.max(0, config.getDouble("elytra.verifier.energyAllowance", 0.04));
        verifier.steadySpeedDeviation = Math.max(0.0001, config.getDouble("elytra.verifier.steadySpeedDeviation", 0.008));
        verifier.minimumSteadySpeed = Math.max(0, config.getDouble("elytra.verifier.minimumSteadySpeed", 0.45));
        verifier.steadyWindowTicks = Math.max(2, config.getInt("elytra.verifier.steadyWindowTicks", 14));
        verifier.evidenceWindowTicks = Math.max(2, config.getInt("elytra.verifier.evidenceWindowTicks", 20));
        verifier.minimumEvidenceTicks = Math.max(1, config.getInt("elytra.verifier.minimumEvidenceTicks", 8));
        verifier.minimumChannels = Math.max(1, config.getInt("elytra.verifier.minimumChannels", 2));
        verifier.confidenceThreshold = Math.max(0, config.getDouble("elytra.verifier.confidenceThreshold", 0.45));
        verifier.sharpTurnDegrees = Math.max(0, config.getDouble("elytra.verifier.sharpTurnDegrees", 50.0));
        settings.elytraGlideSuppressionTicks = Math.max(0, config.getInt("elytra.glideSuppressionTicks", AntiFlyConstants.ELYTRA_GLIDE_SUPPRESSION_TICKS));
        settings.elytraMaxNoRocketUp = config.getDouble("elytra.maxNoRocketUp", 4.0);
        settings.elytraMaxRocketHorizontal = config.getDouble("elytra.maxRocketHorizontal", 6.0);
        settings.elytraMaxRocketUp = config.getDouble("elytra.maxRocketUp", 4.0);
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

        config.set("antiFly.airGraceTicks", settings.airGraceTicks);
        config.set("antiFly.hoverStartTicks", settings.hoverStartTicks);
        config.set("antiFly.hoverTicksLimit", settings.hoverTicksLimit);
        config.set("antiFly.hoverDeltaY", settings.hoverDeltaY);
        config.set("antiFly.hoverHorizontal", settings.hoverHorizontal);        config.set("antiFly.maxAirHorizontal", settings.maxAirHorizontal);
        config.set("antiFly.maxAirVertical", settings.maxAirVertical);
        config.set("antiFly.bufferDecay", settings.bufferDecay);
        config.set("antiFly.horizontalBufferLimit", settings.horizontalBufferLimit);
        config.set("antiFly.verticalBufferLimit", settings.verticalBufferLimit);
        config.set("antiFly.hoverBufferLimit", settings.hoverBufferLimit);
        config.set("antiFly.noFallDetectionEnabled", settings.noFallDetectionEnabled);
        config.set("antiFly.sustainedAirTicksLimit", settings.sustainedAirTicksLimit);
        config.set("antiFly.sustainedAirMinDescent", settings.sustainedAirMinDescent);
        config.set("antiFly.impulseGraceTicks", settings.impulseGraceTicks);
        config.set("antiFly.groundSpoofTicksLimit", settings.groundSpoofTicksLimit);
        config.set("antiFly.vehicleFallMinDescent", settings.vehicleFallMinDescent);
        config.set("antiFly.vehicleFallMaxHorizontal", settings.vehicleFallMaxHorizontal);
        config.set("antiFly.vehicleFallTicksMax", settings.vehicleFallTicksMax);
        config.set("antiFly.repeatOffenderAlertCount", settings.repeatOffenderAlertCount);
        config.set("antiFly.airNonFallTicksLimit", settings.airNonFallTicksLimit);
        config.set("antiFly.antiKickWindowTicks", settings.antiKickWindowTicks);
        config.set("antiFly.antiKickMinDescent", settings.antiKickMinDescent);
        config.set("antiFly.vehicleAirGraceTicks", settings.vehicleAirGraceTicks);
        config.set("antiFly.boatAirGraceTicks", settings.boatAirGraceTicks);
        config.set("antiFly.horseAirGraceTicks", settings.horseAirGraceTicks);
        config.set("antiFly.setbackCooldownMs", settings.setbackCooldownMs);

        config.set("hungerMode.enabled", settings.hungerModeEnabled);
        config.set("voidAccess", settings.voidAccess);
        config.set("hungerMode.maxBlocksPerSecond", settings.hungerModeMaxBlocksPerSecond);
        config.set("hungerMode.hungerPerSecondAtMaxSpeed", settings.hungerModeHungerPerSecondAtMaxSpeed);
        config.set("hungerMode.rocketGraceTicks", settings.hungerModeRocketGraceTicks);
        config.set("hungerMode.airborneMinimumBlocksPerSecond", settings.hungerModeAirborneMinimumBlocksPerSecond);
        config.set("hungerMode.flightDamageEnabled", settings.hungerModeFlightDamageEnabled);
        config.set("hungerMode.flightDamageAfterSeconds", settings.hungerModeFlightDamageAfterSeconds);
        config.set("hungerMode.flightDamageAfterHungerSeconds", settings.hungerModeFlightDamageAfterHungerSeconds);
        config.set("hungerMode.flightDamagePerSecond", settings.hungerModeFlightDamagePerSecond);
        config.set("hungerMode.elytraFoodEnabled", settings.hungerModeElytraFoodEnabled);
        config.set("hungerMode.elytraFoodMultiplier", settings.hungerModeElytraFoodMultiplier);
        config.set("hungerMode.elytraSpeedThresholdBps", settings.hungerModeElytraSpeedThresholdBps);
        config.set("hungerMode.elytraNoRocketAfterSeconds", settings.hungerModeElytraNoRocketAfterSeconds);
        config.set("hungerMode.elytraDamageEnabled", settings.hungerModeElytraDamageEnabled);
        config.set("hungerMode.rocketResetsDamage", settings.hungerModeRocketResetsDamage);

        config.set("elytra.toggleGraceTicks", settings.elytraToggleGraceTicks);
        config.set("elytra.landingGraceTicks", settings.elytraLandingGraceTicks);
        config.set("elytra.stallHorizontalMax", settings.elytraStallHorizontalMax);
        config.set("elytra.stallVerticalMax", settings.elytraStallVerticalMax);
        config.set("elytra.noRocketWindowTicks", settings.elytraNoRocketWindowTicks);
        config.set("elytra.noRocketMinDescent", settings.elytraNoRocketMinDescent);
        config.set("elytra.durabilityBaseWindowTicks", settings.elytraDurabilityBaseWindowTicks);
        config.set("elytra.durabilityUnbreakingMultiplier", settings.elytraDurabilityUnbreakingMultiplier);
        config.set("elytra.durabilitySuspicionLimit", settings.elytraDurabilitySuspicionLimit);
        config.set("elytra.requireMovementSuspicionForDurabilityPunish", settings.elytraRequireMovementSuspicionForDurabilityPunish);        config.set("elytra.enabled", settings.elytraEnabled);
        config.set("elytra.boostGraceTicks", settings.elytraBoostGraceTicks);
        config.set("elytra.stallTicks", settings.elytraStallTicks);
        config.set("elytra.movementBufferLimit", settings.elytraMovementBufferLimit);
        config.set("elytra.durabilityCheckEnabled", settings.elytraDurabilityCheckEnabled);
        config.set("elytra.maxRocketHorizontal", settings.elytraMaxRocketHorizontal);
        config.set("elytra.maxRocketUp", settings.elytraMaxRocketUp);
        config.set("elytra.noRocketSustainableHorizontal", settings.elytraNoRocketSustainableHorizontal);
        config.set("elytra.noRocketMaxAscent", settings.elytraNoRocketMaxAscent);
        config.set("elytra.sustainedClimbTicksLimit", settings.elytraSustainedClimbTicksLimit);
        config.set("elytra.verifierMode", settings.elytraVerifierMode);
        var verifier = settings.elytraVerifierTuning;
        config.set("elytra.verifier.gravity", verifier.gravity);
        config.set("elytra.verifier.residualAllowance", verifier.residualAllowance);
        config.set("elytra.verifier.residualPerSpeed", verifier.residualPerSpeed);
        config.set("elytra.verifier.energyAllowance", verifier.energyAllowance);
        config.set("elytra.verifier.steadySpeedDeviation", verifier.steadySpeedDeviation);
        config.set("elytra.verifier.minimumSteadySpeed", verifier.minimumSteadySpeed);
        config.set("elytra.verifier.steadyWindowTicks", verifier.steadyWindowTicks);
        config.set("elytra.verifier.evidenceWindowTicks", verifier.evidenceWindowTicks);
        config.set("elytra.verifier.minimumEvidenceTicks", verifier.minimumEvidenceTicks);
        config.set("elytra.verifier.minimumChannels", verifier.minimumChannels);
        config.set("elytra.verifier.confidenceThreshold", verifier.confidenceThreshold);
        config.set("elytra.verifier.sharpTurnDegrees", verifier.sharpTurnDegrees);
        config.set("elytra.glideSuppressionTicks", settings.elytraGlideSuppressionTicks);
        config.set("elytra.maxNoRocketUp", settings.elytraMaxNoRocketUp);
        config.set("elytra.requiredDescentForPullup", settings.elytraRequiredDescentForPullup);
        config.set("disabledWorlds", settings.disabledWorlds.stream().sorted().toList());
    }

    static String normalizeSettingKey(String key) {
        if (key == null) return "";
        String inputKey = key.trim();
        if (inputKey.equalsIgnoreCase("groundWalkMax")) return "groundWalkMax";
        if (inputKey.equalsIgnoreCase("groundMountedMax")) return "groundMountedMax";
        if (inputKey.equalsIgnoreCase("waterMax")) return "waterMax";
        if (inputKey.equalsIgnoreCase("waterVerticalMax")) return "waterVerticalMax";
        return switch (inputKey) {
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
            case "sustained_air_ticks_limit" -> "sustainedAirTicksLimit";
            case "sustained_air_min_descent" -> "sustainedAirMinDescent";
            case "impulse_grace_ticks" -> "impulseGraceTicks";
            case "ground_spoof_ticks_limit" -> "groundSpoofTicksLimit";
            case "vehicle_fall_min_descent" -> "vehicleFallMinDescent";
            case "vehicle_fall_max_horizontal" -> "vehicleFallMaxHorizontal";
            case "vehicle_fall_ticks_max" -> "vehicleFallTicksMax";
            case "repeat_offender_alert_count" -> "repeatOffenderAlertCount";
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
            case "elytra_sustained_climb_ticks_limit" -> "elytraSustainedClimbTicksLimit";
            case "elytra_verifier_mode" -> "elytraVerifierMode";
            case "elytra_glide_suppression_ticks" -> "elytraGlideSuppressionTicks";
            case "elytra_max_no_rocket_up" -> "elytraMaxNoRocketUp";
            case "elytra_required_descent_for_pullup" -> "elytraRequiredDescentForPullup";
            case "hunger_mode_max_blocks_per_second", "hungerModeMaxBps" -> "hungerModeMaxBlocksPerSecond";
            case "hunger_mode_hunger_per_second_at_max_speed", "hungerModeHungerPerSecond" -> "hungerModeHungerPerSecondAtMaxSpeed";
            case "hunger_mode_rocket_grace_ticks" -> "hungerModeRocketGraceTicks";
            case "hunger_mode_airborne_minimum_blocks_per_second", "hungerModeAirborneMinBps" -> "hungerModeAirborneMinimumBlocksPerSecond";
            case "hunger_mode_flight_damage_enabled" -> "hungerModeFlightDamageEnabled";
            case "hunger_mode_flight_damage_after_seconds" -> "hungerModeFlightDamageAfterSeconds";
            case "hunger_mode_flight_damage_after_hunger_seconds" -> "hungerModeFlightDamageAfterHungerSeconds";
            case "hunger_mode_flight_damage_per_second" -> "hungerModeFlightDamagePerSecond";
            case "hunger_mode_elytra_food_enabled" -> "hungerModeElytraFoodEnabled";
            case "hunger_mode_elytra_food_multiplier" -> "hungerModeElytraFoodMultiplier";
            case "hunger_mode_elytra_speed_threshold_bps" -> "hungerModeElytraSpeedThresholdBps";
            case "hunger_mode_elytra_no_rocket_after_seconds" -> "hungerModeElytraNoRocketAfterSeconds";
            case "hunger_mode_elytra_damage_enabled" -> "hungerModeElytraDamageEnabled";
            case "hunger_mode_rocket_resets_damage" -> "hungerModeRocketResetsDamage";
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
            PlatformCompat.runGlobal(this, () -> {
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
                PlatformCompat.runForPlayer(this, player, () -> {
                    if ((player.isOp() || player.hasPermission("antifly.admin")) && notifiedOutdatedOps.add(player.getUniqueId())) {
                        player.sendMessage(org.bukkit.ChatColor.RED + msg);
                    }
                });
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
        int sustainedAirTicksLimit;
        double sustainedAirMinDescent;
        int impulseGraceTicks;
        int groundSpoofTicksLimit;
        double vehicleFallMinDescent;
        double vehicleFallMaxHorizontal;
        int vehicleFallTicksMax;
        int repeatOffenderAlertCount;
        AlertMode alertMode;

        boolean hungerModeEnabled;
        boolean voidAccess;
        double hungerModeMaxBlocksPerSecond;
        double hungerModeHungerPerSecondAtMaxSpeed;
        int hungerModeRocketGraceTicks;
        double hungerModeAirborneMinimumBlocksPerSecond;
        boolean hungerModeFlightDamageEnabled;
        double hungerModeFlightDamageAfterSeconds;
        double hungerModeFlightDamageAfterHungerSeconds;
        double hungerModeFlightDamagePerSecond;
        boolean hungerModeElytraFoodEnabled;
        double hungerModeElytraFoodMultiplier;
        double hungerModeElytraSpeedThresholdBps;
        double hungerModeElytraNoRocketAfterSeconds;
        boolean hungerModeElytraDamageEnabled;
        boolean hungerModeRocketResetsDamage;

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
        int elytraSustainedClimbTicksLimit;
        String elytraVerifierMode;
        final com.antifly.common.ElytraPhysics.Tuning elytraVerifierTuning = new com.antifly.common.ElytraPhysics.Tuning();
        int elytraGlideSuppressionTicks;
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
        org.bukkit.Location lastVoidSafe;
        boolean voidRedirectedMove;
        org.bukkit.Location lastHungerElytraPhysicsSample;
        long lastHungerElytraPhysicsTick = Long.MIN_VALUE;
        boolean hungerElytraExploit;
        final com.antifly.common.ElytraPhysics hungerElytraVerifier = new com.antifly.common.ElytraPhysics();
        final com.antifly.common.ElytraPhysics.Input hungerElytraInput = new com.antifly.common.ElytraPhysics.Input();
        org.bukkit.Location lastPos;

        boolean serverAllowedFlight;
        long lastAllowFlightChangeMs;
        int flightRevokeGraceTicks;

        boolean lastServerOnGround;
        boolean lastClientOnGround;
        int groundSpoofTicks;

        org.bukkit.util.Vector lastVelocity;
        int impulseGraceTicks;
        int failCount;
        long lastFailMs;
        long lastRepeatAlertMs;
        int vehicleFallTicks;
        double vehicleFallHorizontalDistance;

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
        long lastHungerSampleMs;
        org.bukkit.Location lastHungerSamplePos;
        double hungerAcceptedHorizontal;
        boolean hungerAcceptedUnsupported;
        boolean hungerAcceptedSupported;
        double hungerDebt;
        double flightAirborneSeconds;
        double lastFlightDamageAtSeconds;
        double hungerDrainSeconds;
        double voidDamageSeconds;
        long lastVoidDamageMs;
        double glideNoRocketSeconds;
        int teleportGraceTicks;
        int sustainedAirTicks;
        double sustainedAirStartY;
        double glideWindowHorizontal;
        double glideWindowDescent;
        double elytraNetAltitude;
        int glideSustainedClimbTicks;
        int glideSuppressTicks;
        final com.antifly.common.ElytraPhysics elytraVerifier = new com.antifly.common.ElytraPhysics();
        final com.antifly.common.ElytraPhysics.Input elytraInput = new com.antifly.common.ElytraPhysics.Input();
        org.bukkit.Location lastElytraSample;
        long lastElytraSampleTick = Long.MIN_VALUE;
        long lastExternalImpulseTick = Long.MIN_VALUE;
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
