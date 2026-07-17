package com.antifly.paper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class AntiFlyCommand implements CommandExecutor, TabCompleter {
    private static final List<String> ROOT = List.of("help", "enable", "disable", "status", "alerts", "debug", "exempt", "unexempt", "disabledworlds", "set", "reset");
    private static final List<String> SET_KEYS = List.of(
        "groundWalkMax", "groundMountedMax", "waterMax", "waterVerticalMax", "boatMaxHorizontal",
        "maxAirHorizontal", "maxAirVertical", "bufferDecay", "horizontalBufferLimit", "verticalBufferLimit",
        "hoverBufferLimit", "noFallDetectionEnabled", "airNonFallTicksLimit", "antiKickWindowTicks",
        "antiKickMinDescent", "setbackCooldownMs", "vehicleAirGraceTicks", "boatAirGraceTicks", "horseAirGraceTicks",
        "elytraEnabled", "elytraBoostGraceTicks",
        "elytraStallTicks", "elytraMovementBufferLimit", "elytraDurabilityCheckEnabled",
        "elytraNoRocketMaxAscent", "elytraRequiredDescentForPullup",
        "elytraMaxRocketHorizontal", "elytraMaxRocketUp", "elytraNoRocketSustainableHorizontal", "elytraMaxNoRocketUp"
    );
    private final AntiFlyPlugin plugin;

    public AntiFlyCommand(AntiFlyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!plugin.canUseAdminCommands(sender)) {
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(ChatColor.GOLD + "AntiFly Commands");
            sender.sendMessage(ChatColor.YELLOW + "  /antifly help");
            sender.sendMessage(ChatColor.YELLOW + "  /antifly status");
            sender.sendMessage(ChatColor.YELLOW + "  /antifly set <key> <value>");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help" -> {
                sender.sendMessage(ChatColor.GOLD + "AntiFly Help");
                sender.sendMessage(ChatColor.AQUA + "Control");
                sender.sendMessage(ChatColor.YELLOW + "  /antifly enable");
                sender.sendMessage(ChatColor.YELLOW + "  /antifly disable");
                sender.sendMessage(ChatColor.YELLOW + "  /antifly status");
                sender.sendMessage(ChatColor.YELLOW + "  /antifly alerts <off|game|console|both>");
                sender.sendMessage(ChatColor.AQUA + "Player");
                sender.sendMessage(ChatColor.YELLOW + "  /antifly exempt <player>");
                sender.sendMessage(ChatColor.YELLOW + "  /antifly unexempt <player>");
                sender.sendMessage(ChatColor.YELLOW + "  /antifly reset <player>");
                sender.sendMessage(ChatColor.AQUA + "Worlds");
                sender.sendMessage(ChatColor.YELLOW + "  /antifly disabledworlds <worldName> <true|false>");
                sender.sendMessage(ChatColor.AQUA + "Tuning");
                sender.sendMessage(ChatColor.YELLOW + "  /antifly set");
                sender.sendMessage(ChatColor.YELLOW + "  /antifly set <key>");
                sender.sendMessage(ChatColor.YELLOW + "  /antifly set <key> <value>");
                sender.sendMessage(ChatColor.DARK_GRAY + "Tip: use /antifly set to list all keys.");
                return true;
            }
            case "enable" -> {
                plugin.setAntiFlyEnabled(true);
                sender.sendMessage(ChatColor.GREEN + "AntiFly enabled.");
                return true;
            }
            case "disable" -> {
                plugin.setAntiFlyEnabled(false);
                sender.sendMessage(ChatColor.RED + "AntiFly disabled.");
                return true;
            }
            case "status" -> {
                AntiFlyPlugin.Settings s = plugin.getSettings();
                sender.sendMessage(ChatColor.GOLD + "AntiFly Status");
                sender.sendMessage(ChatColor.GRAY + "State: "
                    + (plugin.isAntiFlyEnabled() ? ChatColor.GREEN + "ENABLED" : ChatColor.RED + "DISABLED"));
                sender.sendMessage(ChatColor.GRAY + "Disabled worlds: " + ChatColor.WHITE
                    + (s.disabledWorlds.isEmpty() ? "(none)" : String.join(", ", s.disabledWorlds)));

                sender.sendMessage(ChatColor.AQUA + "Ground / Fluid");
                sender.sendMessage(ChatColor.GRAY + "  groundWalkMax=" + ChatColor.WHITE + s.groundWalkMax
                    + ChatColor.GRAY + "  groundMountedMax=" + ChatColor.WHITE + s.groundMountedMax);
                sender.sendMessage(ChatColor.GRAY + "  waterMax=" + ChatColor.WHITE + s.waterMax
                    + ChatColor.GRAY + "  waterVerticalMax=" + ChatColor.WHITE + s.waterVerticalMax);
                sender.sendMessage(ChatColor.GRAY + "  boatMaxHorizontal=" + ChatColor.WHITE + s.boatMaxHorizontal);

                sender.sendMessage(ChatColor.AQUA + "Air");
                sender.sendMessage(ChatColor.GRAY + "  maxAirHorizontal=" + ChatColor.WHITE + s.maxAirHorizontal
                    + ChatColor.GRAY + "  maxAirVertical=" + ChatColor.WHITE + s.maxAirVertical);
                sender.sendMessage(ChatColor.GRAY + "  noFallDetectionEnabled=" + ChatColor.WHITE + s.noFallDetectionEnabled
                    + ChatColor.GRAY + "  airNonFallTicksLimit=" + ChatColor.WHITE + s.airNonFallTicksLimit);
                sender.sendMessage(ChatColor.GRAY + "  antiKickWindowTicks=" + ChatColor.WHITE + s.antiKickWindowTicks
                    + ChatColor.GRAY + "  antiKickMinDescent=" + ChatColor.WHITE + s.antiKickMinDescent);
                sender.sendMessage(ChatColor.GRAY + "  vehicleAirGraceTicks=" + ChatColor.WHITE + s.vehicleAirGraceTicks
                    + ChatColor.GRAY + "  boatAirGraceTicks=" + ChatColor.WHITE + s.boatAirGraceTicks
                    + ChatColor.GRAY + "  horseAirGraceTicks=" + ChatColor.WHITE + s.horseAirGraceTicks);

                sender.sendMessage(ChatColor.AQUA + "Buffers / Setback");
                sender.sendMessage(ChatColor.GRAY + "  bufferDecay=" + ChatColor.WHITE + s.bufferDecay
                    + ChatColor.GRAY + "  horizontalBufferLimit=" + ChatColor.WHITE + s.horizontalBufferLimit);
                sender.sendMessage(ChatColor.GRAY + "  verticalBufferLimit=" + ChatColor.WHITE + s.verticalBufferLimit
                    + ChatColor.GRAY + "  hoverBufferLimit=" + ChatColor.WHITE + s.hoverBufferLimit);
                sender.sendMessage(ChatColor.GRAY + "  setbackCooldownMs=" + ChatColor.WHITE + s.setbackCooldownMs
                    + ChatColor.GRAY + "  alertMode=" + ChatColor.WHITE + s.alertMode.name().toLowerCase(Locale.ROOT));

                sender.sendMessage(ChatColor.AQUA + "Elytra");
                sender.sendMessage(ChatColor.GRAY + "  enabled=" + ChatColor.WHITE + s.elytraEnabled
                    + ChatColor.GRAY + "  boostGraceTicks=" + ChatColor.WHITE + s.elytraBoostGraceTicks);
                sender.sendMessage(ChatColor.GRAY + "  stallTicks=" + ChatColor.WHITE + s.elytraStallTicks
                    + ChatColor.GRAY + "  movementBufferLimit=" + ChatColor.WHITE + s.elytraMovementBufferLimit);
                sender.sendMessage(ChatColor.GRAY + "  durabilityCheckEnabled=" + ChatColor.WHITE + s.elytraDurabilityCheckEnabled);
                sender.sendMessage(ChatColor.GRAY + "  maxRocketHorizontal=" + ChatColor.WHITE + s.elytraMaxRocketHorizontal
                    + ChatColor.GRAY + "  maxRocketUp=" + ChatColor.WHITE + s.elytraMaxRocketUp);
                sender.sendMessage(ChatColor.GRAY + "  noRocketSustainableHorizontal=" + ChatColor.WHITE + s.elytraNoRocketSustainableHorizontal
                    + ChatColor.GRAY + "  maxNoRocketUp=" + ChatColor.WHITE + s.elytraMaxNoRocketUp);
                sender.sendMessage(ChatColor.GRAY + "  noRocketMaxAscent=" + ChatColor.WHITE + s.elytraNoRocketMaxAscent
                    + ChatColor.GRAY + "  requiredDescentForPullup=" + ChatColor.WHITE + s.elytraRequiredDescentForPullup);
                plugin.checkModrinthVersion(sender);
                return true;
            }
            case "alerts" -> {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.YELLOW + "Usage: /antifly alerts <off|game|console|both>");
                    sender.sendMessage(ChatColor.GRAY + "Current: " + plugin.getSettings().alertMode.name().toLowerCase(Locale.ROOT));
                    return true;
                }
                AntiFlyPlugin.AlertMode mode = AntiFlyPlugin.AlertMode.fromString(args[1], null);
                if (mode == null) {
                    sender.sendMessage(ChatColor.RED + "Invalid mode. Use: off, game, console, both");
                    return true;
                }
                plugin.setAlertMode(mode);
                sender.sendMessage(ChatColor.GREEN + "Alert mode set to " + mode.name().toLowerCase(Locale.ROOT));
                return true;
            }
            case "debug" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(ChatColor.YELLOW + "Usage: /antifly debug <on|off>");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.YELLOW + "Debug is " + (plugin.isDebug(player) ? "on" : "off"));
                    sender.sendMessage(ChatColor.YELLOW + "Usage: /antifly debug <on|off>");
                    return true;
                }
                String mode = args[1].toLowerCase(Locale.ROOT);
                if (mode.equals("on")) {
                    plugin.setDebug(player, true);
                    sender.sendMessage(ChatColor.GREEN + "AntiFly action-bar debug enabled.");
                    return true;
                }
                if (mode.equals("off")) {
                    plugin.setDebug(player, false);
                    sender.sendMessage(ChatColor.RED + "AntiFly action-bar debug disabled.");
                    return true;
                }
                sender.sendMessage(ChatColor.YELLOW + "Usage: /antifly debug <on|off>");
                return true;
            }
            case "exempt" -> {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.YELLOW + "Usage: /antifly exempt <player>");
                    return true;
                }
                OfflinePlayer player = findPlayer(args[1]);
                if (player == null || player.getUniqueId() == null) {
                    sender.sendMessage(ChatColor.RED + "Player not found.");
                    return true;
                }
                plugin.addExempt(player.getUniqueId());
                sender.sendMessage(ChatColor.GREEN + "Exempted " + player.getName() + ".");
                return true;
            }
            case "unexempt" -> {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.YELLOW + "Usage: /antifly unexempt <player>");
                    return true;
                }
                OfflinePlayer player = findPlayer(args[1]);
                if (player == null || player.getUniqueId() == null) {
                    sender.sendMessage(ChatColor.RED + "Player not found.");
                    return true;
                }
                plugin.removeExempt(player.getUniqueId());
                sender.sendMessage(ChatColor.GREEN + "Unexempted " + player.getName() + ".");
                return true;
            }
            case "set" -> {
                if (args.length == 1) {
                    AntiFlyPlugin.Settings s = plugin.getSettings();
                    sender.sendMessage(ChatColor.GOLD + "AntiFly Settings Keys");
                    for (String key : SET_KEYS) {
                        sender.sendMessage(ChatColor.GRAY + "  " + key + ChatColor.DARK_GRAY + " = " + ChatColor.WHITE + formatSettingValue(s, key));
                    }
                    return true;
                }
                if (args.length == 2) {
                    String key = AntiFlyPlugin.normalizeSettingKey(args[1]);
                    String value = formatSettingValue(plugin.getSettings(), key);
                    if (value == null) {
                        sender.sendMessage(ChatColor.RED + "Unknown key.");
                        return true;
                    }
                    sender.sendMessage(ChatColor.YELLOW + key + "=" + value);
                    return true;
                }
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.YELLOW + "Usage: /antifly set <key> <value>");
                    return true;
                }
                String key = AntiFlyPlugin.normalizeSettingKey(args[1]);
                double value;
                try {
                    value = Double.parseDouble(args[2]);
                } catch (NumberFormatException ex) {
                    sender.sendMessage(ChatColor.RED + "Value must be a number.");
                    return true;
                }
                plugin.updateSetting(key, value);
                sender.sendMessage(ChatColor.GREEN + "Set " + key + " to " + value + ".");
                return true;
            }
            case "disabledworlds" -> {
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.YELLOW + "Usage: /antifly disabledworlds <worldName> <true|false>");
                    sender.sendMessage(ChatColor.GRAY + "Current: "
                        + (plugin.getDisabledWorlds().isEmpty() ? "(none)" : String.join(", ", plugin.getDisabledWorlds())));
                    return true;
                }
                String worldName = args[1];
                String value = args[2].toLowerCase(Locale.ROOT);
                if (!value.equals("true") && !value.equals("false")) {
                    sender.sendMessage(ChatColor.RED + "Value must be true or false.");
                    return true;
                }
                boolean disabled = Boolean.parseBoolean(value);
                plugin.setWorldDisabled(worldName, disabled);
                sender.sendMessage(ChatColor.GREEN + "World " + worldName + " is now "
                    + (disabled ? "disabled" : "enabled") + " for AntiFly.");
                return true;
            }
            case "reset" -> {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.YELLOW + "Usage: /antifly reset <player>");
                    return true;
                }
                OfflinePlayer player = findPlayer(args[1]);
                if (player == null || player.getUniqueId() == null) {
                    sender.sendMessage(ChatColor.RED + "Player not found.");
                    return true;
                }
                plugin.resetPlayer(player.getUniqueId());
                sender.sendMessage(ChatColor.GREEN + "Reset state for " + player.getName() + ".");
                return true;
            }
            default -> {
                sender.sendMessage(ChatColor.YELLOW + "Unknown subcommand.");
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!plugin.canUseAdminCommands(sender)) {
            return List.of();
        }
        if (args.length == 1) {
            return filter(ROOT, args[0]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("exempt") || args[0].equalsIgnoreCase("unexempt") || args[0].equalsIgnoreCase("reset"))) {
            if (args[0].equalsIgnoreCase("unexempt")) {
                return filter(exemptNames(), args[1]);
            }
            return null;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("debug")) {
            return filter(List.of("on", "off"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("alerts")) {
            return filter(List.of("off", "game", "console", "both"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("disabledworlds")) {
            return null;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("disabledworlds")) {
            return filter(List.of("true", "false"), args[2]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
            return filter(SET_KEYS, args[1]);
        }
        return List.of();
    }

    private List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                matches.add(option);
            }
        }
        return matches;
    }

    private OfflinePlayer findPlayer(String name) {
        OfflinePlayer online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        return Bukkit.getOfflinePlayer(name);
    }

    private List<String> exemptNames() {
        Set<String> names = new LinkedHashSet<>();
        for (UUID uuid : plugin.getExemptPlayers()) {
            Player online = Bukkit.getPlayer(uuid);
            if (online != null && online.getName() != null && !online.getName().isBlank()) {
                names.add(online.getName());
                continue;
            }
            OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
            if (offline.getName() != null && !offline.getName().isBlank()) {
                names.add(offline.getName());
            }
        }
        return List.copyOf(names);
    }

    private String formatSettingValue(AntiFlyPlugin.Settings s, String key) {
        return switch (key) {
            case "groundWalkMax" -> String.valueOf(s.groundWalkMax);
            case "groundMountedMax" -> String.valueOf(s.groundMountedMax);
            case "waterMax" -> String.valueOf(s.waterMax);
            case "waterVerticalMax" -> String.valueOf(s.waterVerticalMax);
            case "boatMaxHorizontal" -> String.valueOf(s.boatMaxHorizontal);
            case "maxAirHorizontal" -> String.valueOf(s.maxAirHorizontal);
            case "maxAirVertical" -> String.valueOf(s.maxAirVertical);
            case "bufferDecay" -> String.valueOf(s.bufferDecay);
            case "horizontalBufferLimit" -> String.valueOf(s.horizontalBufferLimit);
            case "verticalBufferLimit" -> String.valueOf(s.verticalBufferLimit);
            case "hoverBufferLimit" -> String.valueOf(s.hoverBufferLimit);
            case "noFallDetectionEnabled" -> String.valueOf(s.noFallDetectionEnabled);
            case "airNonFallTicksLimit" -> String.valueOf(s.airNonFallTicksLimit);
            case "antiKickWindowTicks" -> String.valueOf(s.antiKickWindowTicks);
            case "antiKickMinDescent" -> String.valueOf(s.antiKickMinDescent);
            case "setbackCooldownMs" -> String.valueOf(s.setbackCooldownMs);
            case "vehicleAirGraceTicks" -> String.valueOf(s.vehicleAirGraceTicks);
            case "boatAirGraceTicks" -> String.valueOf(s.boatAirGraceTicks);
            case "horseAirGraceTicks" -> String.valueOf(s.horseAirGraceTicks);
            case "elytraEnabled" -> String.valueOf(s.elytraEnabled);
            case "elytraBoostGraceTicks" -> String.valueOf(s.elytraBoostGraceTicks);
            case "elytraStallTicks" -> String.valueOf(s.elytraStallTicks);
            case "elytraMovementBufferLimit" -> String.valueOf(s.elytraMovementBufferLimit);
            case "elytraDurabilityCheckEnabled" -> String.valueOf(s.elytraDurabilityCheckEnabled);
            case "elytraNoRocketMaxAscent" -> String.valueOf(s.elytraNoRocketMaxAscent);
            case "elytraRequiredDescentForPullup" -> String.valueOf(s.elytraRequiredDescentForPullup);
            case "elytraMaxRocketHorizontal" -> String.valueOf(s.elytraMaxRocketHorizontal);
            case "elytraMaxRocketUp" -> String.valueOf(s.elytraMaxRocketUp);
            case "elytraNoRocketSustainableHorizontal" -> String.valueOf(s.elytraNoRocketSustainableHorizontal);
            case "elytraMaxNoRocketUp" -> String.valueOf(s.elytraMaxNoRocketUp);
            default -> null;
        };
    }
}
