package com.antifly.paper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

public final class AntiFlyCommand implements CommandExecutor, TabCompleter {
    private static final List<String> ROOT = List.of(
        "help", "enable", "disable", "status", "exempt", "unexempt", "set", "reset"
    );
    private static final List<String> SET_KEYS = List.of(
        "airSpeed",
        "airVertical",
        "waterSpeed",
        "waterVertical",
        "groundSpeed",
        "elytraEnabled",
        "elytraMaxHorizontal",
        "elytraMaxUp",
        "elytraMaxDown",
        "elytraStallHorizontalMax",
        "elytraStallVerticalMax",
        "elytraStallTicks",
        "elytraSlowdownMinSpeed",
        "elytraSlowdownMinScale",
        "elytraSlowdownGraceTicks"
    );

    private final AntiFlyPlugin plugin;

    public AntiFlyCommand(AntiFlyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /antifly <enable|disable|status|exempt|unexempt|set|reset>");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help" -> {
                sender.sendMessage(ChatColor.YELLOW + "/antifly enable");
                sender.sendMessage(ChatColor.YELLOW + "/antifly disable");
                sender.sendMessage(ChatColor.YELLOW + "/antifly status");
                sender.sendMessage(ChatColor.YELLOW + "/antifly exempt <player>");
                sender.sendMessage(ChatColor.YELLOW + "/antifly unexempt <player>");
                sender.sendMessage(ChatColor.YELLOW + "/antifly set airSpeed <value>");
                sender.sendMessage(ChatColor.YELLOW + "/antifly set airVertical <value>");
                sender.sendMessage(ChatColor.YELLOW + "/antifly set waterSpeed <value>");
                sender.sendMessage(ChatColor.YELLOW + "/antifly set waterVertical <value>");
                sender.sendMessage(ChatColor.YELLOW + "/antifly set groundSpeed <value>");
                sender.sendMessage(ChatColor.YELLOW + "/antifly set elytraEnabled <value>");
                sender.sendMessage(ChatColor.YELLOW + "/antifly set elytraMaxHorizontal <value>");
                sender.sendMessage(ChatColor.YELLOW + "/antifly set elytraMaxUp <value>");
                sender.sendMessage(ChatColor.YELLOW + "/antifly set elytraMaxDown <value>");
                sender.sendMessage(ChatColor.YELLOW + "/antifly set elytraStallHorizontalMax <value>");
                sender.sendMessage(ChatColor.YELLOW + "/antifly set elytraStallVerticalMax <value>");
                sender.sendMessage(ChatColor.YELLOW + "/antifly set elytraStallTicks <value>");
                sender.sendMessage(ChatColor.YELLOW + "/antifly set elytraSlowdownMinSpeed <value>");
                sender.sendMessage(ChatColor.YELLOW + "/antifly set elytraSlowdownMinScale <value>");
                sender.sendMessage(ChatColor.YELLOW + "/antifly set elytraSlowdownGraceTicks <value>");
                sender.sendMessage(ChatColor.YELLOW + "/antifly reset <player>");
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
                sender.sendMessage(ChatColor.AQUA + "AntiFly: " + (plugin.isAntiFlyEnabled() ? "enabled" : "disabled"));
                sender.sendMessage(ChatColor.GRAY + "Limits: ground=" + s.groundMax
                    + " air=" + s.airMax
                    + " airVertical=" + s.airVerticalMax
                    + " water=" + s.waterMax
                    + " waterVertical=" + s.waterVerticalMax);
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
                    sender.sendMessage(ChatColor.YELLOW + "AntiFly settings:");
                    for (String key : SET_KEYS) {
                        String value = formatSettingValue(s, key);
                        if (value != null) {
                            sender.sendMessage(ChatColor.GRAY + key + "=" + value);
                        }
                    }
                    return true;
                }
                if (args.length == 2) {
                    String key = args[1];
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
                String key = args[1];
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
        if (args.length == 1) {
            return filter(ROOT, args[0]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("exempt") || args[0].equalsIgnoreCase("unexempt") || args[0].equalsIgnoreCase("reset"))) {
            return null;
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

    private String formatSettingValue(AntiFlyPlugin.Settings s, String key) {
        return switch (key) {
            case "groundSpeed" -> String.valueOf(s.groundMax);
            case "airSpeed" -> String.valueOf(s.airMax);
            case "airVertical" -> String.valueOf(s.airVerticalMax);
            case "waterSpeed" -> String.valueOf(s.waterMax);
            case "waterVertical" -> String.valueOf(s.waterVerticalMax);
            case "elytraEnabled" -> String.valueOf(s.elytraChecksEnabled);
            case "elytraMaxHorizontal" -> String.valueOf(s.elytraMaxHorizontal);
            case "elytraMaxUp" -> String.valueOf(s.elytraMaxUp);
            case "elytraMaxDown" -> String.valueOf(s.elytraMaxDown);
            case "elytraStallHorizontalMax" -> String.valueOf(s.elytraStallHorizontalMax);
            case "elytraStallVerticalMax" -> String.valueOf(s.elytraStallVerticalMax);
            case "elytraStallTicks" -> String.valueOf(s.elytraStallTicks);
            case "elytraSlowdownMinSpeed" -> String.valueOf(s.elytraSlowdownMinSpeed);
            case "elytraSlowdownMinScale" -> String.valueOf(s.elytraSlowdownMinScale);
            case "elytraSlowdownGraceTicks" -> String.valueOf(s.elytraSlowdownGraceTicks);
            default -> null;
        };
    }
}
