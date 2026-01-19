package com.antifly.paper;

import com.antifly.common.AttemptTracker;
import com.antifly.common.AntiFlyConstants;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class AntiFlyPlugin extends JavaPlugin {
    private final AttemptTracker attemptTracker = new AttemptTracker();
    private final Map<UUID, PlayerState> states = new ConcurrentHashMap<>();
    private final Set<UUID> exempt = ConcurrentHashMap.newKeySet();
    private final Settings settings = new Settings();
    private boolean antiFlyEnabled = true;

    @Override
    public void onEnable() {
        loadConfigValues();
        Bukkit.getPluginManager().registerEvents(new AntiFlyListener(this), this);
        getCommand("antifly").setExecutor(new AntiFlyCommand(this));
        getCommand("antifly").setTabCompleter(new AntiFlyCommand(this));
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

    void setAntiFlyEnabled(boolean enabled) {
        antiFlyEnabled = enabled;
        FileConfiguration config = getConfig();
        config.set("enabled", enabled);
        saveConfig();
    }

    boolean isExempt(Player player) {
        return exempt.contains(player.getUniqueId());
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
        FileConfiguration config = getConfig();
        switch (key) {
            case "groundSpeed" -> settings.groundMax = value;
            case "airSpeed" -> settings.airMax = value;
            case "airVertical" -> settings.airVerticalMax = value;
            case "waterSpeed" -> settings.waterMax = value;
            case "waterVertical" -> settings.waterVerticalMax = value;
            case "elytraEnabled" -> settings.elytraChecksEnabled = value > 0.5;
            case "elytraMaxHorizontal" -> settings.elytraMaxHorizontal = value;
            case "elytraMaxUp" -> settings.elytraMaxUp = value;
            case "elytraMaxDown" -> settings.elytraMaxDown = value;
            case "elytraStallHorizontalMax" -> settings.elytraStallHorizontalMax = value;
            case "elytraStallVerticalMax" -> settings.elytraStallVerticalMax = value;
            case "elytraStallTicks" -> settings.elytraStallTicks = (int) Math.round(value);
            case "elytraSlowdownMinSpeed" -> settings.elytraSlowdownMinSpeed = value;
            case "elytraSlowdownMinScale" -> settings.elytraSlowdownMinScale = value;
            case "elytraSlowdownGraceTicks" -> settings.elytraSlowdownGraceTicks = (int) Math.round(value);
            default -> {
                return;
            }
        }
        config.set("limits.ground", settings.groundMax);
        config.set("limits.air", settings.airMax);
        config.set("limits.airVertical", settings.airVerticalMax);
        config.set("limits.water", settings.waterMax);
        config.set("limits.waterVertical", settings.waterVerticalMax);
        config.set("elytra.enabled", settings.elytraChecksEnabled);
        config.set("elytra.maxHorizontal", settings.elytraMaxHorizontal);
        config.set("elytra.maxUp", settings.elytraMaxUp);
        config.set("elytra.maxDown", settings.elytraMaxDown);
        config.set("elytra.stallHorizontalMax", settings.elytraStallHorizontalMax);
        config.set("elytra.stallVerticalMax", settings.elytraStallVerticalMax);
        config.set("elytra.stallTicks", settings.elytraStallTicks);
        config.set("elytra.slowdownMinSpeed", settings.elytraSlowdownMinSpeed);
        config.set("elytra.slowdownMinScale", settings.elytraSlowdownMinScale);
        config.set("elytra.slowdownGraceTicks", settings.elytraSlowdownGraceTicks);
        saveConfig();
    }

    PlayerState getState(Player player) {
        return states.computeIfAbsent(player.getUniqueId(), ignored -> new PlayerState());
    }

    private void loadConfigValues() {
        FileConfiguration config = getConfig();
        config.addDefault("enabled", true);
        config.addDefault("limits.ground", AntiFlyConstants.BASE_GROUND_MAX + AntiFlyConstants.GROUND_BUFFER);
        config.addDefault("limits.air", AntiFlyConstants.BASE_AIR_MAX + AntiFlyConstants.AIR_BUFFER);
        config.addDefault("limits.airVertical", AntiFlyConstants.BASE_AIR_VERTICAL_MAX + AntiFlyConstants.AIR_VERTICAL_BUFFER);
        config.addDefault("limits.water", AntiFlyConstants.BASE_WATER_MAX + AntiFlyConstants.WATER_BUFFER);
        config.addDefault("limits.waterVertical", AntiFlyConstants.WATER_VERTICAL_MAX + AntiFlyConstants.WATER_VERTICAL_BUFFER);
        config.addDefault("elytra.enabled", true);
        config.addDefault("elytra.maxHorizontal", AntiFlyConstants.ELYTRA_MAX_HORIZONTAL);
        config.addDefault("elytra.maxUp", AntiFlyConstants.ELYTRA_MAX_UP);
        config.addDefault("elytra.maxDown", AntiFlyConstants.ELYTRA_MAX_DOWN);
        config.addDefault("elytra.stallHorizontalMax", AntiFlyConstants.ELYTRA_STALL_HORIZONTAL_MAX);
        config.addDefault("elytra.stallVerticalMax", AntiFlyConstants.ELYTRA_STALL_VERTICAL_MAX);
        config.addDefault("elytra.stallTicks", AntiFlyConstants.ELYTRA_STALL_TICKS);
        config.addDefault("elytra.slowdownMinSpeed", AntiFlyConstants.ELYTRA_SLOWDOWN_MIN_SPEED);
        config.addDefault("elytra.slowdownMinScale", AntiFlyConstants.ELYTRA_SLOWDOWN_MIN_SCALE);
        config.addDefault("elytra.slowdownGraceTicks", AntiFlyConstants.ELYTRA_SLOWDOWN_GRACE_TICKS);
        config.addDefault("exempt", java.util.List.of());
        config.options().copyDefaults(true);
        saveConfig();

        antiFlyEnabled = config.getBoolean("enabled", true);
        settings.groundMax = config.getDouble("limits.ground", AntiFlyConstants.BASE_GROUND_MAX + AntiFlyConstants.GROUND_BUFFER);
        settings.airMax = config.getDouble("limits.air", AntiFlyConstants.BASE_AIR_MAX + AntiFlyConstants.AIR_BUFFER);
        settings.airVerticalMax = config.getDouble("limits.airVertical", AntiFlyConstants.BASE_AIR_VERTICAL_MAX + AntiFlyConstants.AIR_VERTICAL_BUFFER);
        settings.waterMax = config.getDouble("limits.water", AntiFlyConstants.BASE_WATER_MAX + AntiFlyConstants.WATER_BUFFER);
        settings.waterVerticalMax = config.getDouble("limits.waterVertical", AntiFlyConstants.WATER_VERTICAL_MAX + AntiFlyConstants.WATER_VERTICAL_BUFFER);
        settings.elytraChecksEnabled = config.getBoolean("elytra.enabled", true);
        settings.elytraMaxHorizontal = config.getDouble("elytra.maxHorizontal", AntiFlyConstants.ELYTRA_MAX_HORIZONTAL);
        settings.elytraMaxUp = config.getDouble("elytra.maxUp", AntiFlyConstants.ELYTRA_MAX_UP);
        settings.elytraMaxDown = config.getDouble("elytra.maxDown", AntiFlyConstants.ELYTRA_MAX_DOWN);
        settings.elytraStallHorizontalMax = config.getDouble("elytra.stallHorizontalMax", AntiFlyConstants.ELYTRA_STALL_HORIZONTAL_MAX);
        settings.elytraStallVerticalMax = config.getDouble("elytra.stallVerticalMax", AntiFlyConstants.ELYTRA_STALL_VERTICAL_MAX);
        settings.elytraStallTicks = config.getInt("elytra.stallTicks", AntiFlyConstants.ELYTRA_STALL_TICKS);
        settings.elytraSlowdownMinSpeed = config.getDouble("elytra.slowdownMinSpeed", AntiFlyConstants.ELYTRA_SLOWDOWN_MIN_SPEED);
        settings.elytraSlowdownMinScale = config.getDouble("elytra.slowdownMinScale", AntiFlyConstants.ELYTRA_SLOWDOWN_MIN_SCALE);
        settings.elytraSlowdownGraceTicks = config.getInt("elytra.slowdownGraceTicks", AntiFlyConstants.ELYTRA_SLOWDOWN_GRACE_TICKS);

        exempt.clear();
        for (String entry : config.getStringList("exempt")) {
            try {
                exempt.add(UUID.fromString(entry));
            } catch (IllegalArgumentException ignored) {
                getLogger().warning("Invalid exempt UUID: " + entry);
            }
        }
    }

    private void persistExempt() {
        FileConfiguration config = getConfig();
        java.util.List<String> list = exempt.stream().map(UUID::toString).sorted().toList();
        config.set("exempt", list);
        saveConfig();
    }

    static final class Settings {
        double groundMax;
        double airMax;
        double airVerticalMax;
        double waterMax;
        double waterVerticalMax;
        boolean elytraChecksEnabled;
        double elytraMaxHorizontal;
        double elytraMaxUp;
        double elytraMaxDown;
        double elytraStallHorizontalMax;
        double elytraStallVerticalMax;
        int elytraStallTicks;
        double elytraSlowdownMinSpeed;
        double elytraSlowdownMinScale;
        int elytraSlowdownGraceTicks;
    }

    static final class PlayerState {
        org.bukkit.Location lastGround;
        org.bukkit.Location lastSupport;
        org.bukkit.Location lastPos;
        int airTicks;
        int hoverTicks;
        int voidTicks;
        int glideStallTicks;
        int groundSpoofTicks;
        int glideGroundGraceTicks;
        int glideSlowdownGraceTicks;
        double lastGlideHorizontal;
        boolean wasGliding;
        boolean lastServerOnGround;
        int vehicleGraceTicks;
        boolean wasInVehicle;
        long lastRubberBandAtMs;
    }
}
