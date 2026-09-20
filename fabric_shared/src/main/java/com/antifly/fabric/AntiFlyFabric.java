package com.antifly.fabric;

import com.antifly.common.AntiFlyConstants;
import com.antifly.common.AttemptTracker;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Items;
import net.minecraft.stats.Stats;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import java.util.EnumSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AntiFlyFabric implements ModInitializer {
    public static final String MOD_ID = "antifly";
    private static final long LOG_COOLDOWN_MS = 500;
    private static final double ELYTRA_HOVER_MAX_BPS = 2.0;
    private static final double SUPPORT_ENTITY_DEPTH = 0.5;
    private static final double SUPPORT_ENTITY_SLACK = 0.05;
    private static final Logger LOGGER = LoggerFactory.getLogger("AntiFly");
    private static final Pattern VERSION_NUMBER_PATTERN = Pattern.compile("\"version_number\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern DATE_PUBLISHED_PATTERN = Pattern.compile("\"date_published\"\\s*:\\s*\"([^\"]+)\"");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static boolean isOperator(net.minecraft.commands.CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return source.getEntity() == null;
        }
        return source.getServer().getPlayerList().isOp(new net.minecraft.server.players.NameAndId(player.getGameProfile()));
    }

    private static Component header(String text) {
        return Component.literal("◆ " ).withStyle(ChatFormatting.AQUA)
            .append(Component.literal(text).withStyle(style -> style.withColor(ChatFormatting.GOLD).withBold(true)))
            .append(Component.literal(" ◆").withStyle(ChatFormatting.AQUA));
    }

    private static Component statusLine(String label, String value, boolean positive) {
        return Component.literal("  " + label + ": ").withStyle(ChatFormatting.GRAY)
            .append(Component.literal(value).withStyle(positive ? ChatFormatting.GREEN : ChatFormatting.RED));
    }

    /**
     * Mirrors the colour scheme the Paper build uses for violation alerts, so
     * the two platforms read identically in game: red tag, yellow player, the
     * rule in white, and the tuning hints in aqua.
     */
    private static Component violationAlert(String playerName, String reason, String tune,
                                            double actual, double allowed) {
        return Component.literal("[AntiFly] ").withStyle(ChatFormatting.RED)
            .append(Component.literal(playerName).withStyle(ChatFormatting.YELLOW))
            .append(Component.literal(" blocked ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(reason).withStyle(ChatFormatting.WHITE))
            .append(Component.literal(" (").withStyle(ChatFormatting.DARK_GRAY))
            .append(Component.literal("tune=" + tune).withStyle(ChatFormatting.AQUA))
            .append(Component.literal(", ").withStyle(ChatFormatting.DARK_GRAY))
            .append(Component.literal(String.format("actual=%.3f allowed=%.3f", actual, allowed))
                .withStyle(ChatFormatting.AQUA))
            .append(Component.literal(")").withStyle(ChatFormatting.DARK_GRAY));
    }

    private static Component repeatOffenderAlert(String playerName, int failCount, long minutes) {
        return Component.literal("[AntiFly] ").withStyle(ChatFormatting.RED)
            .append(Component.literal(playerName).withStyle(ChatFormatting.YELLOW))
            .append(Component.literal(" has tripped ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(failCount + " flight checks").withStyle(ChatFormatting.WHITE))
            .append(Component.literal(" in the last ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(minutes + " minutes").withStyle(ChatFormatting.WHITE))
            .append(Component.literal(" - inspect rather than assume one lag spike")
                .withStyle(ChatFormatting.DARK_GRAY));
    }

    private static void sendHelp(net.minecraft.commands.CommandSourceStack source) {
        source.sendSuccess(() -> header("AntiFly Commands"), false);
        source.sendSuccess(() -> Component.literal("▸ Control").withStyle(style -> style.withColor(ChatFormatting.AQUA).withBold(true)), false);
        for (String command : new String[]{
            "/antifly enable", "/antifly disable", "/antifly status",
            "/antifly hungermode <on|off>", "/antifly voidaccess <on|off>", "/antifly alerts <off|game|console|both>",
            "/antifly debug <on|off>"}) {            source.sendSuccess(() -> Component.literal("  " + command).withStyle(ChatFormatting.YELLOW), false);
        }
        source.sendSuccess(() -> Component.literal("▸ Players").withStyle(style -> style.withColor(ChatFormatting.AQUA).withBold(true)), false);
        for (String command : new String[]{
            "/antifly exempt <player>", "/antifly unexempt <player>", "/antifly reset <player>"}) {
            source.sendSuccess(() -> Component.literal("  " + command).withStyle(ChatFormatting.YELLOW), false);
        }
        source.sendSuccess(() -> Component.literal("▸ Configuration").withStyle(style -> style.withColor(ChatFormatting.AQUA).withBold(true)), false);
        source.sendSuccess(() -> Component.literal("  /antifly reload").withStyle(ChatFormatting.YELLOW), false);
        source.sendSuccess(() -> Component.literal("  /antifly disabledworlds <worldName> <true|false>").withStyle(ChatFormatting.YELLOW), false);
        source.sendSuccess(() -> Component.literal("  /antifly set <key> <value>").withStyle(ChatFormatting.YELLOW), false);
    }
    private void sendStatus(net.minecraft.commands.CommandSourceStack source) {
        source.sendSuccess(() -> header("AntiFly Status"), false);
        source.sendSuccess(() -> statusLine("Protection", config.enabled ? "ENABLED" : "DISABLED", config.enabled), false);
        source.sendSuccess(() -> statusLine("Hunger mode", config.hungerModeEnabled ? "ON" : "OFF", !config.hungerModeEnabled), false);
        source.sendSuccess(() -> statusLine("Void access", config.voidAccess ? "ON" : "OFF", config.voidAccess), false);
        source.sendSuccess(() -> statusLine("Alerts", config.alertMode.toString(), true), false);
        source.sendSuccess(() -> statusLine("Disabled worlds", config.disabledWorlds.isEmpty() ? "none" : String.join(", ", config.disabledWorlds), true), false);
        source.sendSuccess(() -> Component.literal("Movement limits").withStyle(style -> style.withColor(ChatFormatting.AQUA).withBold(true)), false);
        source.sendSuccess(() -> statusLine("Ground", "walk=" + config.groundWalkMax + " | mounted=" + config.groundMountedMax, true), false);
        source.sendSuccess(() -> statusLine("Water", "horizontal=" + config.waterMax + " | vertical=" + config.waterVerticalMax, true), false);
        source.sendSuccess(() -> statusLine("Boat", "horizontal=" + config.boatMaxHorizontal, true), false);
        source.sendSuccess(() -> statusLine("Air", "horizontal=" + config.airMax + " | vertical=" + config.airVerticalMax, true), false);
        source.sendSuccess(() -> Component.literal("Elytra").withStyle(style -> style.withColor(ChatFormatting.AQUA).withBold(true)), false);
        source.sendSuccess(() -> statusLine("Enabled", config.elytraChecksEnabled ? "YES" : "NO", config.elytraChecksEnabled), false);
        source.sendSuccess(() -> statusLine("Boost grace", String.valueOf(config.elytraBoostGraceTicks), true), false);
        source.sendSuccess(() -> statusLine("Stall", "ticks=" + config.elytraStallTicks + " | buffer=" + config.elytraMovementBufferLimit, true), false);
        source.sendSuccess(() -> Component.literal("Settable values").withStyle(style -> style.withColor(ChatFormatting.AQUA).withBold(true)), false);
        for (String key : SET_KEYS) {
            String value = formatSettingValue(key);
            if (value != null) {
                source.sendSuccess(() -> Component.literal("  " + key + " = ").withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(value).withStyle(ChatFormatting.WHITE)), false);
            }
        }
    }
    private final AttemptTracker attemptTracker = new AttemptTracker();
    private final Map<UUID, PlayerState> states = new ConcurrentHashMap<>();
    private final Set<UUID> exempt = ConcurrentHashMap.newKeySet();
    private final Set<UUID> debug = ConcurrentHashMap.newKeySet();
    private volatile AntiFlyConfig config = AntiFlyConfig.load();

    @Override
    public void onInitialize() {
        exempt.clear();
        for (String entry : config.exempt) {
            try {
                exempt.add(UUID.fromString(entry));
            } catch (IllegalArgumentException ignored) {
                LOGGER.warn("Invalid exempt UUID: {}", entry);
            }
        }

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                handlePlayerTick(player);
            }
        });
        ServerPlayerEvents.COPY_FROM.register((oldPlayer, newPlayer, alive) -> {
            PlayerState state = states.computeIfAbsent(newPlayer.getUUID(), ignored -> new PlayerState());
            resetState(state, newPlayer.position());
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID uuid = handler.getPlayer().getUUID();
            states.remove(uuid);
            attemptTracker.reset(uuid);
            debug.remove(uuid);
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("antifly")
                .requires(source -> isOperator(source) && source.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
                .then(Commands.literal("help").executes(ctx -> {
                    sendHelp(ctx.getSource());
                    return 1;
                }))
                .then(Commands.literal("enable").executes(ctx -> {
                    config.enabled = true;
                    saveConfig();
                    ctx.getSource().sendSuccess(() -> Component.literal("AntiFly enabled."), false);
                    return 1;
                }))
                .then(Commands.literal("disable").executes(ctx -> {
                    config.enabled = false;
                    saveConfig();
                    ctx.getSource().sendSuccess(() -> Component.literal("AntiFly disabled."), false);
                    return 1;
                }))
                .then(Commands.literal("hungermode")
                    .then(Commands.literal("on").executes(ctx -> setHungerMode(ctx.getSource(), true)))
                    .then(Commands.literal("off").executes(ctx -> setHungerMode(ctx.getSource(), false)))
                    .executes(ctx -> {
                        ctx.getSource().sendSuccess(() -> Component.literal("Hunger Mode is " + (config.hungerModeEnabled ? "on" : "off")), false);
                        return 1;
                    }))
                .then(Commands.literal("voidaccess")
                    .then(Commands.literal("on").executes(ctx -> setVoidAccess(ctx.getSource(), true)))
                    .then(Commands.literal("off").executes(ctx -> setVoidAccess(ctx.getSource(), false)))
                    .executes(ctx -> {
                        ctx.getSource().sendSuccess(() -> Component.literal("Void access is " + (config.voidAccess ? "on" : "off")), false);
                        return 1;
                    }))
                .then(Commands.literal("reload").executes(ctx -> {
                    reloadConfig(ctx.getSource());
                    return 1;
                }))
                .then(Commands.literal("status").executes(ctx -> {
                    sendStatus(ctx.getSource());
                    return 1;
                }))
                .then(Commands.literal("alerts")
                    .then(Commands.literal("off").executes(ctx -> setAlertMode(ctx.getSource(), "off")))
                    .then(Commands.literal("game").executes(ctx -> setAlertMode(ctx.getSource(), "game")))
                    .then(Commands.literal("console").executes(ctx -> setAlertMode(ctx.getSource(), "console")))
                    .then(Commands.literal("both").executes(ctx -> setAlertMode(ctx.getSource(), "both")))
                    .executes(ctx -> {
                        ctx.getSource().sendSuccess(() -> Component.literal("Usage: /antifly alerts <off|game|console|both> (current=" + config.alertMode + ")"), false);
                        return 1;
                    }))
                .then(Commands.literal("debug")
                    .then(Commands.literal("on").executes(ctx -> setDebugMode(ctx.getSource(), true)))
                    .then(Commands.literal("off").executes(ctx -> setDebugMode(ctx.getSource(), false)))
                    .executes(ctx -> {
                        ServerPlayer player = ctx.getSource().getPlayer();
                        if (player == null) {
                            ctx.getSource().sendFailure(Component.literal("Usage: /antifly debug <on|off>"));
                            return 0;
                        }
                        ctx.getSource().sendSuccess(() -> Component.literal("Debug is " + (isDebug(player) ? "on" : "off")), false);
                        ctx.getSource().sendSuccess(() -> Component.literal("Usage: /antifly debug <on|off>"), false);
                        return 1;
                    }))
                .then(Commands.literal("exempt")
                    .then(Commands.argument("player", EntityArgument.player())
                        .executes(ctx -> {
                            ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
                            exempt.add(player.getUUID());
                            saveConfig();
                            ctx.getSource().sendSuccess(() -> Component.literal("Exempted " + player.getName().getString()), false);
                            return 1;
                        })))
                .then(Commands.literal("unexempt")
                    .then(Commands.argument("player", EntityArgument.player())
                        .executes(ctx -> {
                            ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
                            exempt.remove(player.getUUID());
                            saveConfig();
                            ctx.getSource().sendSuccess(() -> Component.literal("Unexempted " + player.getName().getString()), false);
                            return 1;
                        })))
                .then(Commands.literal("disabledworlds")
                    .then(Commands.argument("worldName", StringArgumentType.word())
                        .then(Commands.literal("true").executes(ctx -> setDisabledWorld(ctx.getSource(),
                            StringArgumentType.getString(ctx, "worldName"), true)))
                        .then(Commands.literal("false").executes(ctx -> setDisabledWorld(ctx.getSource(),
                            StringArgumentType.getString(ctx, "worldName"), false))))
                    .executes(ctx -> {
                        ctx.getSource().sendFailure(Component.literal("Usage: /antifly disabledworlds <worldName> <true|false>"));
                        return 0;
                    }))
                .then(Commands.literal("reset")
                    .then(Commands.argument("player", EntityArgument.player())
                        .executes(ctx -> {
                            ServerPlayer player = EntityArgument.getPlayer(ctx, "player");
                            states.remove(player.getUUID());
                            attemptTracker.reset(player.getUUID());
                            ctx.getSource().sendSuccess(() -> Component.literal("Reset " + player.getName().getString()), false);
                            return 1;
                        })))
                .then(Commands.literal("set").executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal("AntiFly settings:"), false);
                    for (String key : SET_KEYS) {
                        String value = formatSettingValue(key);
                        if (value != null) {
                            ctx.getSource().sendSuccess(() -> Component.literal(key + "=" + value), false);
                        }
                    }
                    return 1;
                })
                    .then(Commands.argument("key", StringArgumentType.word())
                        .suggests((ctx, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(SET_KEYS, builder))
                        .executes(ctx -> getValue(ctx.getSource(), StringArgumentType.getString(ctx, "key")))
                        .then(Commands.literal("off").executes(ctx -> setVerifierMode(ctx.getSource(), StringArgumentType.getString(ctx, "key"), "off")))
                        .then(Commands.literal("observe").executes(ctx -> setVerifierMode(ctx.getSource(), StringArgumentType.getString(ctx, "key"), "observe")))
                        .then(Commands.literal("enforce").executes(ctx -> setVerifierMode(ctx.getSource(), StringArgumentType.getString(ctx, "key"), "enforce")))
                        .then(Commands.argument("value", DoubleArgumentType.doubleArg(0))
                            .executes(ctx -> setValue(ctx.getSource(), StringArgumentType.getString(ctx, "key"), DoubleArgumentType.getDouble(ctx, "value"))))))
            );
        });

        ServerTickEvents.START_SERVER_TICK.register(server -> {
            if (!modrinthCheckedOnStartup) {
                modrinthCheckedOnStartup = true;
                checkModrinthVersionAndAlertOps(server);
            }
        });
    }

    private volatile boolean modrinthCheckedOnStartup = false;

    private void reloadConfig(net.minecraft.commands.CommandSourceStack source) {
        // Re-reads config/antifly.json so operators can edit it by hand without
        // restarting. Per-player state is untouched; only the tunables, world
        // list and exempt list are refreshed.
        config = AntiFlyConfig.load();
        exempt.clear();
        for (String entry : config.exempt) {
            try {
                exempt.add(UUID.fromString(entry));
            } catch (IllegalArgumentException ignored) {
            }
        }
        source.sendSuccess(() -> Component.literal("AntiFly config reloaded."), false);
    }

    private void applyHungerMode(ServerPlayer player, PlayerState state, Vec3 pos) {
        int fireworkUses = player.getStats().getValue(Stats.ITEM_USED.get(Items.FIREWORK_ROCKET));
        if (state.lastFireworkUses >= 0 && fireworkUses > state.lastFireworkUses && player.isFallFlying()) {
            state.rocketGraceTicks = config.hungerModeRocketGraceTicks;
        }
        state.lastFireworkUses = fireworkUses;

        double horizontal = state.lastPos == null ? 0.0 : horizontalDistance(state.lastPos, pos);
        double rawSpeedBps = horizontal * 20.0;
        // A vehicle only excuses the hover penalty when it is itself supported
        // (boat on water/ground, mount standing) - flying in a boat is
        // penalized like any other unsupported flight.
        boolean vehicleSupported = false;
        if (player.isPassenger()) {
            Entity vehicle = player.getVehicle();
            if (vehicle instanceof Boat boat) {
                vehicleSupported = isBoatInFluid(boat) || hasBoatGroundSupport(boat);
            } else {
                vehicleSupported = vehicle.onGround();
            }
        }
        boolean unsupported = !hasGroundSupport(player) && !isInFluid(player) && !vehicleSupported;

        boolean gliding = player.isFallFlying();
        boolean recentRocket = gliding && state.rocketGraceTicks > 0;

        // Elytra exploit detection: abnormal speed (> threshold), hover-hack
        // (no horizontal movement), gliding for a long time without any rocket,
        // or movement the glide physics verifier cannot explain. Vanilla
        // gliding with rockets is never an exploit.
        boolean glidingExploit = false;
        if (gliding) {
            if (recentRocket) {
                state.glideNoRocketTicks = 0;
            } else {
                state.glideNoRocketTicks++;
            }
            boolean hovering = unsupported && rawSpeedBps < ELYTRA_HOVER_MAX_BPS;
            int noRocketLimit = (int) Math.round(config.hungerModeElytraNoRocketAfterSeconds * 20.0);
            state.hungerElytraInput.set(
                state.lastPos == null ? 0.0 : pos.x - state.lastPos.x,
                state.lastPos == null ? 0.0 : pos.y - state.lastPos.y,
                state.lastPos == null ? 0.0 : pos.z - state.lastPos.z,
                player.getXRot(), player.getYRot(), pos.y, player.level().getGameTime(),
                recentRocket, !unsupported || player.hurtTime > 0 || state.impulseGraceTicks > 0
                    || player.hasEffect(MobEffects.LEVITATION) || player.hasEffect(MobEffects.SLOW_FALLING));
            boolean verifierExploit = state.hungerElytraVerifier.observe(
                state.hungerElytraInput, config.elytraVerifierTuning).actionable(config.elytraVerifierTuning);
            glidingExploit = rawSpeedBps > config.hungerModeElytraSpeedThresholdBps
                || hovering
                || state.glideNoRocketTicks > noRocketLimit
                || verifierExploit;
        } else {
            state.glideNoRocketTicks = 0;
            state.hungerElytraVerifier.reset();
        }

        double hungerLoss;
        if (recentRocket) {
            hungerLoss = 0.0;
        } else if (gliding) {
            if (!config.hungerModeElytraFoodEnabled) {
                // Elytra fully exempt from food drain.
                hungerLoss = 0.0;
            } else if (unsupported && rawSpeedBps < ELYTRA_HOVER_MAX_BPS) {
                // Elytra hover-hack: hover penalty, same as hovering
                // without an elytra.
                double hoverNormalized = Math.min(1.0, config.hungerModeAirborneMinimumBlocksPerSecond
                    / config.hungerModeMaxBlocksPerSecond);
                hungerLoss = config.hungerModeHungerPerSecondAtMaxSpeed
                    * hoverNormalized / 20.0;
            } else if (glidingExploit) {
                // Abnormal elytra speed or no-rocket exploit: speed-based
                // drain (with elytra multiplier).
                double normalizedSpeed = Math.min(1.0, rawSpeedBps / config.hungerModeMaxBlocksPerSecond);
                hungerLoss = config.hungerModeHungerPerSecondAtMaxSpeed
                    * normalizedSpeed * normalizedSpeed / 20.0
                    * config.hungerModeElytraFoodMultiplier;
            } else {
                // Normal elytra gliding: no food drain.
                hungerLoss = 0.0;
            }
        } else {
            // Normal (non-elytra) movement: speed-based drain with the
            // airborne minimum floor for unsupported players.
            double speedBlocksPerSecond = rawSpeedBps;
            if (unsupported) {
                speedBlocksPerSecond = Math.max(speedBlocksPerSecond, config.hungerModeAirborneMinimumBlocksPerSecond);
            }
            double normalizedSpeed = Math.min(1.0, speedBlocksPerSecond / config.hungerModeMaxBlocksPerSecond);
            // The airborne minimum is a baseline cost, not a squared
            // 1%-of-max penalty. This keeps idle hovering visible.
            if (unsupported && rawSpeedBps < config.hungerModeAirborneMinimumBlocksPerSecond) {
                hungerLoss = config.hungerModeHungerPerSecondAtMaxSpeed
                    * normalizedSpeed / 20.0;
            } else {
                hungerLoss = config.hungerModeHungerPerSecondAtMaxSpeed
                    * normalizedSpeed * normalizedSpeed / 20.0;
            }
        }
        // Hunger Mode penalizes unsupported flight only. Once support returns,
        // immediately discard any airborne debt so it cannot drain on landing.
        if (!unsupported) {
            hungerLoss = 0.0;
            state.hungerDebt = 0.0;
        }
        if (state.rocketGraceTicks > 0) {
            state.rocketGraceTicks--;
        }

        // Sustained unsupported flight deals real health damage after the
        // configured delay, so carrying stacks of food cannot sustain an
        // endless flight - food restores hunger, not the health being lost.
        // Health damage only follows hunger accumulation: for elytra the
        // timer counts only while hunger is ACTIVELY draining, so the
        // hunger phase always comes first and physical damage only starts
        // if the exploit keeps going. Damage ticks are slow (every 2s,
        // every 4s while descending) so fast hacks don't make it hectic,
        // and landing is always survivable.
        if (config.hungerModeFlightDamageEnabled) {
            boolean damageCounted = unsupported
                && (!gliding || (config.hungerModeElytraDamageEnabled && glidingExploit));
            if (damageCounted) {
                if (gliding && recentRocket && config.hungerModeRocketResetsDamage) {
                    state.flightAirborneTicks = 0;
                    state.hungerDrainTicks = 0;
                    state.lastFlightDamageAtTicks = -1;
                } else {
                    if (gliding) {
                        // Elytra: only count time while hunger is actually
                        // draining (hungerLoss > 0), so health damage can
                        // never skip the hunger phase.
                        if (hungerLoss > 0.0) {
                            state.hungerDrainTicks++;
                        } else {
                            state.hungerDrainTicks = 0;
                        }
                        state.flightAirborneTicks = state.hungerDrainTicks;
                    } else {
                        state.flightAirborneTicks++;
                    }
                    int gateTicks = gliding
                        ? (int) Math.round(config.hungerModeFlightDamageAfterHungerSeconds * 20.0)
                        : (int) Math.round(config.hungerModeFlightDamageAfterSeconds * 20.0);
                    boolean starving = player.getFoodData().getFoodLevel() <= 0;
                    // Reaching zero food is an immediate escalation: the health
                    // penalty must not wait out the normal flight timer.
                    if (starving || state.flightAirborneTicks > gateTicks) {
                        // While descending, only tick damage at half frequency so
                        // landing attempts are survivable.
                        double descent = state.lastPos != null ? (pos.y - state.lastPos.y) : 0.0;
                        int interval = descent < -0.5 ? 80 : 40;
                        // The first starvation hit lands at once; later hits wait
                        // out the interval (-1 means no hit yet this flight).
                        int next = starving && state.lastFlightDamageAtTicks < 0
                            ? state.flightAirborneTicks
                            : state.lastFlightDamageAtTicks + interval;
                        if (state.flightAirborneTicks >= next) {
                            state.lastFlightDamageAtTicks = state.flightAirborneTicks;
                            float dmg = (float) config.hungerModeFlightDamagePerSecond;
                            if (starving) {
                                // Health units are half-hearts: double the configured
                                // penalty at starvation so 1.0 deals one full heart.
                                dmg *= 2.0f;
                            }
                            if (dmg > 0.0f) {
                                player.hurt(player.damageSources().generic(), dmg);
                            }
                        }
                    }
                }
            } else {
                // Recovering from a flight attempt takes time. The timer decays
                // instead of resetting, so a flyer who touches down for a single
                // tick cannot erase everything before it and restart at zero.
                int recovery = Math.max(1, (int) Math.round(AntiFlyConstants.DAMAGE_TIMER_RECOVERY_MULTIPLIER));
                state.flightAirborneTicks = Math.max(0, state.flightAirborneTicks - recovery);
                state.hungerDrainTicks = Math.max(0, state.hungerDrainTicks - recovery);
            }
        } else {
            state.flightAirborneTicks = 0;
            state.hungerDrainTicks = 0;
            state.lastFlightDamageAtTicks = -1;
        }

        state.hungerDebt += hungerLoss;
        // Apply at most one food point per tick. Any remaining debt carries
        // forward, producing continuous loss instead of a bulk update.
        if (state.hungerDebt >= 1.0 && player.getFoodData().getFoodLevel() > 0) {
            player.getFoodData().setFoodLevel(player.getFoodData().getFoodLevel() - 1);
            state.hungerDebt -= 1.0;
        }
    }

    private int setHungerMode(net.minecraft.commands.CommandSourceStack source, boolean enabled) {
        config.hungerModeEnabled = enabled;
        saveConfig();
        source.sendSuccess(() -> Component.literal("Hunger Mode " + (enabled ? "enabled: AntiFly checks are bypassed." : "disabled.")), false);
        return 1;
    }

    private void handlePlayerTick(ServerPlayer player) {
        PlayerState state = states.computeIfAbsent(player.getUUID(), ignored -> new PlayerState());
        Vec3 pos = player.position();

        if (!config.voidAccess && player.isAlive()) {
            if (isForbiddenVoid(player, pos)) {
                if (config.hungerModeEnabled) {
                    // Hunger Mode: the zone is allowed but costs health directly
                    // instead of the normal hunger-then-health chain.
                    if (!isExempt(player)) {
                        applyVoidDamage(player, state);
                    }
                    state.hungerDebt = 0.0;
                    resetTransientState(state);
                    state.lastPos = pos;
                    state.lastServerOnGround = hasGroundSupport(player);
                    state.wasGliding = player.isFallFlying();
                    return;
                }
                ServerLevel targetLevel = state.lastVoidSafeLevel != null ? state.lastVoidSafeLevel
                    : getServerLevel(player).getServer().overworld();
                Vec3 target = state.lastVoidSafe != null ? state.lastVoidSafe
                    : Vec3.atBottomCenterOf(targetLevel.getRespawnData().pos()).add(0, 1, 0);
                if (player.getVehicle() != null) {
                    Entity vehicle = player.getVehicle();
                    player.stopRiding();
                    vehicle.teleportTo(targetLevel, target.x, target.y, target.z,
                        java.util.EnumSet.noneOf(net.minecraft.world.entity.Relative.class),
                        vehicle.getYRot(), vehicle.getXRot(), false);
                    vehicle.setDeltaMovement(Vec3.ZERO);
                }
                player.teleportTo(targetLevel, target.x, target.y, target.z,
                    java.util.EnumSet.noneOf(net.minecraft.world.entity.Relative.class),
                    player.getYRot(), player.getXRot(), false);
                player.setDeltaMovement(Vec3.ZERO);
                rebaselineForTeleport(state, target, hasGroundSupport(player));
                return;
            }
            state.lastVoidSafe = pos;
            state.lastVoidSafeLevel = getServerLevel(player);
        }
        // Outside a denied void zone: restart the damage clock so re-entry costs
        // health immediately and time spent outside never counts toward it.
        state.voidDamageTicks = -1;

        if (!player.isAlive()) {
            resetState(state, null);
            return;
        }

        // A vanilla survival client cannot enable flight without the server
        // granting the ability, so anything else is a hacked client asking to
        // fly. Clearing it here stops the exploit before it moves at all.
        if (player.getAbilities().flying && !player.getAbilities().mayfly
            && !player.isCreative() && !player.isSpectator() && !exempt.contains(player.getUUID())) {
            player.getAbilities().flying = false;
            player.onUpdateAbilities();
        }

        // External teleports (commands, plugins, ender pearls, chorus fruit,
        // portals) look like instant flight to the checks below. A single jump
        // farther than any legitimate movement that the player's own velocity
        // cannot explain is re-baselined instead of rubber-banded. The
        // rebaseline NEVER moves the setback anchor to the destination unless
        // it is genuinely supported, so a flyer chaining huge jumps gets
        // corrected to real ground. A 20-tick cooldown (matches the vanilla
        // ender-pearl cooldown) means chained jumps are caught by the normal
        // checks instead of being forgiven forever.
        if (state.teleportGraceTicks > 0) {
            state.teleportGraceTicks--;
        }
        if (state.lastPos != null && state.teleportGraceTicks <= 0) {
            Vec3 delta = pos.subtract(state.lastPos);
            double distSq = delta.lengthSqr();
            if (distSq > 36.0) {
                Vec3 vel = player.getDeltaMovement();
                if (vel.lengthSqr() < distSq) {
                    state.teleportGraceTicks = 20;
                    rebaselineForTeleport(state, pos, hasGroundSupport(player) || isInFluid(player));
                    return;
                }
            }
        }

        if ((!config.enabled && !config.hungerModeEnabled) || isWorldDisabled(player.level())) {
            boolean inFluid = isInFluid(player);
            boolean serverOnGround = hasGroundSupport(player);
            resetTransientState(state);
            updateSupport(state, serverOnGround, inFluid, pos);
            state.lastPos = pos;
            state.lastServerOnGround = serverOnGround;
            state.wasGliding = false;
            state.hungerDebt = 0.0;
            state.rocketGraceTicks = 0;
            state.flightAirborneTicks = 0;
            state.lastFlightDamageAtTicks = -1;
            return;
        }

        if (player.hurtTime > 0) {
            state.impulseGraceTicks = Math.max(state.impulseGraceTicks, config.impulseGraceTicks);
            state.elytraVerifier.reset();
        }
        detectExternalImpulse(player, state);

        if (state.impulseGraceTicks > 0) {
            state.impulseGraceTicks--;
            // An external launch is legitimate propulsion, so it must not feed
            // the no-rocket altitude accumulator.
            state.elytraNetAltitude = 0.0;
            state.glideSustainedClimbTicks = 0;
            resetTransientState(state);
            state.lastPos = pos;
            state.lastServerOnGround = hasGroundSupport(player);
            state.wasGliding = player.isFallFlying();
            return;
        }

        if (config.hungerModeEnabled) {
            // Hunger Mode replaces the movement blocks, so anything the
            // movement checks would have excused has to be excused here too -
            // otherwise an explicitly exempt player is still drained.
            if (isExempt(player)) {
                state.hungerDebt = 0.0;
                state.rocketGraceTicks = 0;
                state.flightAirborneTicks = 0;
                state.lastFlightDamageAtTicks = -1;
                resetTransientState(state);
                state.lastPos = pos;
                state.lastServerOnGround = hasGroundSupport(player);
                state.wasGliding = player.isFallFlying();
                return;
            }
            applyHungerMode(player, state, pos);
            resetTransientState(state);
            state.lastPos = pos;
            state.lastServerOnGround = hasGroundSupport(player);
            state.wasGliding = player.isFallFlying();
            return;
        }

        // Hunger Mode is off: clear any accumulated hunger debt and rocket grace
        // so they cannot leak into a later Hunger Mode session. While Hunger Mode
        // is on, hungerDebt must persist across ticks so fractional food losses
        // accumulate into whole food points (otherwise hovering/slow flight
        // would never drain).
        state.hungerDebt = 0.0;
        state.rocketGraceTicks = 0;
        state.flightAirborneTicks = 0;
        state.lastFlightDamageAtTicks = -1;

        boolean inFluid = isInFluid(player);
        boolean inVehicle = player.isPassenger();
        boolean clientOnGround = player.onGround();
        boolean serverOnGround = hasGroundSupport(player);
        boolean trustedGround = serverOnGround
            || (clientOnGround && hasGroundSupportLoose(player) && player.getDeltaMovement().y <= 0.05);
        boolean isGliding = player.isFallFlying();
        if (!isGliding) {
            state.glideStallTicks = 0;
            state.glideSlowdownGraceTicks = 0;
            state.lastGlideHorizontal = 0.0;
            // A new flight must start from a clean physics baseline, otherwise
            // the previous trajectory is scored against this one.
            if (state.wasGliding) {
                state.elytraVerifier.reset();
            }
            state.elytraNetAltitude = 0.0;
            state.glideSustainedClimbTicks = 0;
            if (state.wasGliding) {
                state.glideGroundGraceTicks = config.elytraLandingGraceTicks;
            }
            if (state.glideGroundGraceTicks > 0) {
                state.glideGroundGraceTicks--;
            }
        }
        boolean inBoatWater = false;
        boolean boatOnSupport = false;
        if (inVehicle && player.getVehicle() instanceof Boat boat) {
            inBoatWater = boat.isInWater()
                || isBoatInFluid(boat)
                || boat.onGround()
                || !boat.level().getFluidState(boat.blockPosition()).isEmpty()
                || !boat.level().getFluidState(boat.blockPosition().below()).isEmpty();
            boatOnSupport = hasBoatGroundSupport(boat);
        }
        if (inBoatWater) {
            inFluid = true;
        }

        // Vehicle transitions (mount, switch, dismount) move the player with no
        // input of their own: the seat snap on entry and the release bump on
        // exit. Bukkit reports these as a teleport event; Fabric has to
        // rebaseline them here or they read as flight. The grace then covers the
        // ticks in which the client is still catching up to the new seat.
        Entity currentVehicle = inVehicle ? player.getVehicle() : null;
        if (currentVehicle != state.lastVehicle) {
            boolean leavingVehicle = state.lastVehicle != null;
            state.vehicleGraceTicks = (inVehicle || leavingVehicle)
                ? Math.max(1, config.vehicleAirGraceTicks) : 0;
            state.lastVehicle = currentVehicle;
            state.lastVehiclePos = currentVehicle != null ? currentVehicle.position() : null;
            resetTransientState(state);
            state.lastPos = pos;
            state.lastServerOnGround = serverOnGround;
            state.wasGliding = false;
        }
        state.wasInVehicle = inVehicle;

        if (inVehicle && state.vehicleGraceTicks > 0) {
            state.vehicleGraceTicks--;
            state.lastVehiclePos = currentVehicle.position();
            resetTransientState(state);
            state.lastPos = pos;
            state.lastServerOnGround = false;
            state.wasGliding = false;
            return;
        }

        // A vehicle governs the rider completely: the player's position is just
        // the seat, so scoring it against the player movement checks is what
        // turns a mount transition, a wave bob or a beached boat into a
        // violation. Every branch below therefore returns, exactly like the
        // Bukkit implementation does.
        if (inVehicle) {
            Entity vehicle = player.getVehicle();
            if (vehicle instanceof Boat boat && (inBoatWater || boatOnSupport)) {
                Vec3 vel = boat.getDeltaMovement();
                double horizontal = state.lastVehiclePos != null
                    ? horizontalDistance(state.lastVehiclePos, boat.position()) : 0.0;
                horizontal = Math.max(horizontal, Math.sqrt(vel.x * vel.x + vel.z * vel.z));
                state.lastVehiclePos = boat.position();
                double maxAllowed = config.boatMaxHorizontal;
                sendDebugActionBar(player, state, "BOAT", horizontal,
                    pos.y - (state.lastPos != null ? state.lastPos.y : pos.y), maxAllowed, 0.0);
                if (horizontal > maxAllowed) {
                    Vec3 target = state.lastSupportPos != null ? state.lastSupportPos : pos;
                    rubberBandVehicle(player, state, target, "boat_speed", horizontal, maxAllowed);
                } else {
                    // Afloat or resting on blocks: no air violation is possible,
                    // and the support anchor follows the boat so a setback can
                    // never throw the rider back to a long abandoned position.
                    resetTransientState(state);
                    updateSupport(state, false, true, pos);
                }
                state.lastPos = pos;
                state.lastServerOnGround = false;
                state.wasGliding = false;
                return;
            }

            if (serverOnGround || inFluid) {
                resetTransientState(state);
                state.lastVehiclePos = vehicle.position();
                state.lastPos = pos;
                state.lastServerOnGround = serverOnGround;
                state.wasGliding = false;
                return;
            }

            Vec3 vel = player.getDeltaMovement();
            double deltaY = state.lastPos != null ? (pos.y - state.lastPos.y) : vel.y;
            double horizontal = state.lastVehiclePos != null
                ? horizontalDistance(state.lastVehiclePos, vehicle.position())
                : state.lastPos != null ? horizontalDistance(state.lastPos, pos) : 0.0;
            horizontal = Math.max(horizontal, Math.sqrt(vel.x * vel.x + vel.z * vel.z));
            state.lastVehiclePos = vehicle.position();
            if (vehicle instanceof Boat boat) {
                if (horizontal > config.boatMaxHorizontal) {
                    Vec3 target = state.lastSupportPos != null ? state.lastSupportPos : pos;
                    rubberBandVehicle(player, state, target, "boat_speed", horizontal, config.boatMaxHorizontal);
                    state.lastPos = pos;
                    state.wasGliding = false;
                    return;
                }
                if (deltaY > 0.02 || vel.y > 0.05) {
                    Vec3 target = state.lastSupportPos != null ? state.lastSupportPos : pos;
                    rubberBandVehicle(player, state, target, "vehicle_flight", Math.max(deltaY, vel.y), 0.0);
                    state.lastPos = pos;
                    state.wasGliding = false;
                    return;
                }
                if (Math.abs(deltaY) <= 0.001 && Math.abs(vel.y) <= 0.05) {
                    boat.setDeltaMovement(0.0, -0.08, 0.0);
                }
                state.lastPos = pos;
                state.lastServerOnGround = false;
                state.wasGliding = false;
                return;
            }
            if (isVehicleNaturalFall(deltaY, vel.y, horizontal, vehicle)) {
                state.vehicleFallTicks++;
                state.vehicleFallHorizontalDistance += horizontal;
                state.vehicleAirTicks = 0;
                double horizontalBudget = config.vehicleFallMaxHorizontal * Math.max(1, config.vehicleFallTicksMax);
                if (state.vehicleFallTicks <= config.vehicleFallTicksMax
                    && state.vehicleFallHorizontalDistance <= horizontalBudget) {
                    state.lastPos = pos;
                    state.lastServerOnGround = false;
                    state.wasGliding = false;
                    return;
                }
            } else {
                state.vehicleFallTicks = 0;
                state.vehicleFallHorizontalDistance = 0.0;
            }

            state.vehicleAirTicks++;
            if (state.vehicleAirTicks > vehicleAirGraceTicks(vehicle)) {
                Vec3 target = state.lastSupportPos != null ? state.lastSupportPos : pos;
                rubberBandVehicle(player, state, target, "vehicle_flight", state.vehicleAirTicks, vehicleAirGraceTicks(vehicle));
            }
            state.lastPos = pos;
            state.lastServerOnGround = false;
            state.wasGliding = false;
            return;
        }

        if (isExempt(player)) {
            state.airNonFallTicks = 0;
            state.airSessionTicks = 0;
            state.airSessionDescent = 0.0;
            state.sustainedAirTicks = 0;
            updateSupport(state, trustedGround, inFluid, pos);
            state.lastPos = pos;
            state.lastServerOnGround = serverOnGround;
            state.wasGliding = isGliding;
            return;
        }

        // A glide violation must suppress gliding for a while, not just end it
        // once: the hack's auto-deploy re-sends START_FALL_FLYING every tick, so
        // a one-shot stopFallFlying() would be undone before the next check.
        if (state.glideSuppressTicks > 0) {
            state.glideSuppressTicks--;
            if (player.isFallFlying()) {
                player.stopFallFlying();
                resetTransientState(state);
                state.lastPos = pos;
                state.lastServerOnGround = serverOnGround;
                state.wasGliding = false;
                return;
            }
        }

        if (isGliding) {
            if (!handleGlide(player, state, pos, serverOnGround, inFluid, inVehicle)) {
                state.wasGliding = false;
                return;
            }
            state.airTicks++;
            state.airNonFallTicks = 0;
            state.airSessionTicks = 0;
            state.airSessionDescent = 0.0;
            state.sustainedAirTicks = 0;
            state.lastPos = pos;
            state.lastServerOnGround = serverOnGround;
            state.wasGliding = true;
            return;
        }

        if (trustedGround) {
            if (state.lastPos != null) {
                state.groundSpoofTicks = 0;
                Vec3 vel = player.getDeltaMovement();
                double deltaY = pos.y - state.lastPos.y;
                double horizontal = horizontalDistance(state.lastPos, pos);
                double maxAllowed = Math.max(maxGroundSpeed(player), Math.hypot(vel.x, vel.z) + 0.05);
                if (!state.lastServerOnGround && state.wasGliding) {
                    state.glideGroundGraceTicks = Math.max(state.glideGroundGraceTicks,
                        AntiFlyConstants.GLIDE_GROUND_GRACE_TICKS);
                }
                if (deltaY > 0.02 || vel.y > 0.05) {
                    maxAllowed *= 1.5;
                }
                sendDebugActionBar(player, state, "GROUND", horizontal, deltaY, maxAllowed, 0.0);
                // Reject fast movement that still reports airborne while collision
                // support makes the position look grounded. Landing out of a glide
                // keeps elytra momentum for a tick or two and the client often
                // still reports airborne across that boundary, so this respects
                // the same landing grace the ground-speed check below uses.
                if (state.glideGroundGraceTicks <= 0
                    && !clientOnGround && horizontal > maxAllowed) {
                    Vec3 target = state.lastGroundPos != null ? state.lastGroundPos : pos;
                    rubberBand(player, state, target, "ground_spoof_speed", horizontal, maxAllowed);
                    state.lastPos = pos;
                    state.wasGliding = false;
                    return;
                }
                if (state.glideGroundGraceTicks <= 0 && state.lastServerOnGround && horizontal > maxAllowed) {
                    Vec3 target = state.lastGroundPos != null ? state.lastGroundPos : pos;
                    rubberBand(player, state, target, "ground_speed", horizontal, maxAllowed);
                    state.lastPos = pos;
                    state.wasGliding = false;
                    return;
                }
            }
            updateSupport(state, true, false, pos);
        } else if (inFluid) {
            state.groundSpoofTicks = 0;
            Vec3 vel = player.getDeltaMovement();
            double horizontal = state.lastPos != null ? horizontalDistance(state.lastPos, pos) : 0.0;
            horizontal = Math.max(horizontal, Math.sqrt(vel.x * vel.x + vel.z * vel.z));
            double maxAllowed = Math.max(maxWaterSpeed(player), Math.hypot(vel.x, vel.z) + 0.05);
            double deltaY = state.lastPos != null ? (pos.y - state.lastPos.y) : 0.0;
            deltaY = Math.max(deltaY, vel.y);
            double maxUp = Math.max(maxWaterVertical(player), vel.y + 0.05);
            sendDebugActionBar(player, state, "FLUID", horizontal, deltaY, maxAllowed, maxUp);
            if (horizontal > maxAllowed) {
                Vec3 target = state.lastSupportPos != null ? state.lastSupportPos : pos;
                rubberBand(player, state, target, "water_speed", horizontal, maxAllowed);
                state.lastPos = pos;
                state.wasGliding = false;
                return;
            }
            if (deltaY > maxUp) {
                Vec3 target = state.lastSupportPos != null ? state.lastSupportPos : pos;
                rubberBand(player, state, target, "water_vertical", deltaY, maxUp);
                state.lastPos = pos;
                state.wasGliding = false;
                return;
            }
            updateSupport(state, false, true, pos);
        } else if (!inFluid) {
            state.airTicks++;
            boolean graceAir = state.airTicks <= config.airGraceTicks || state.glideGroundGraceTicks > 0;
            double horizontal = 0.0;
            double maxAllowed = maxAirSpeed(player);
            if (state.lastPos != null) {
                Vec3 vel = player.getDeltaMovement();
                horizontal = Math.max(horizontalDistance(state.lastPos, pos), Math.sqrt(vel.x * vel.x + vel.z * vel.z));
                maxAllowed = Math.max(maxAllowed, Math.hypot(vel.x, vel.z) + 0.05);
                if (!graceAir && horizontal > maxAllowed) {
                    Vec3 target = state.lastSupportPos != null ? state.lastSupportPos : pos;
                    rubberBand(player, state, target, "air_speed", horizontal, maxAllowed);
                    state.lastPos = pos;
                    state.wasGliding = false;
                    return;
                }
            }
            Vec3 vel = player.getDeltaMovement();
            double deltaY = state.lastPos != null ? Math.max(pos.y - state.lastPos.y, vel.y) : vel.y;
            double hoverHorizontal = state.lastPos != null
                ? horizontalDistance(state.lastPos, pos)
                : Math.sqrt(vel.x * vel.x + vel.z * vel.z);
            double maxUp = Math.max(maxAirVertical(player), vel.y + 0.05);
            sendDebugActionBar(player, state, "AIR", horizontal, deltaY, maxAllowed, maxUp);
            if (!graceAir && deltaY > maxUp) {
                Vec3 target = state.lastSupportPos != null ? state.lastSupportPos : pos;
                rubberBand(player, state, target, "air_vertical", deltaY, maxUp);
                state.lastPos = pos;
                state.wasGliding = false;
                return;
            }
            // The client claiming solid ground while the server finds none, and
            // then moving faster or climbing higher than that missing ground
            // would allow, is a no-fall style spoof rather than a desync.
            // A client that claims ground the server cannot find is only spoofing
            // if that claim lets it move or climb beyond what real ground would
            // allow. Counting every such tick flagged standing still on a boat or
            // a partial block, so this matches the threshold the Bukkit build
            // already used.
            if (clientOnGround && !serverOnGround && !hasGroundSupportLoose(player)) {
                double maxGroundSpoofSpeed = maxGroundSpeed(player) + 0.05;
                if (horizontal > maxGroundSpoofSpeed || deltaY > 0.08) {
                    state.groundSpoofTicks++;
                    if (state.groundSpoofTicks > config.groundSpoofTicksLimit) {
                        Vec3 target = state.lastSupportPos != null ? state.lastSupportPos : pos;
                        rubberBand(player, state, target, "ground_spoof", state.groundSpoofTicks, config.groundSpoofTicksLimit);
                        state.lastPos = pos;
                        state.wasGliding = false;
                        return;
                    }
                } else {
                    state.groundSpoofTicks = Math.max(0, state.groundSpoofTicks - 1);
                }
            } else {
                state.groundSpoofTicks = 0;
            }
            // Ignore mid-air jump detection; we only care about prolonged hovering.
            boolean hoveringStill = !serverOnGround
                && state.airTicks > config.hoverStartTicks
                && Math.abs(deltaY) <= config.hoverDeltaY;
            if (hoveringStill) {
                state.hoverTicks++;
                if (state.hoverTicks > config.hoverTicksLimit) {
                    Vec3 target = state.lastSupportPos != null ? state.lastSupportPos : pos;
                    rubberBand(player, state, target, "air_hover", deltaY, 0.0);
                    state.lastPos = pos;
                    state.wasGliding = false;
                    return;
                }
            } else {
                state.hoverTicks = 0;
            }
            if (config.noFallDetectionEnabled && !serverOnGround && deltaY >= AntiFlyConstants.AIR_DESCENT_EPSILON) {
                state.airNonFallTicks++;
                if (state.airNonFallTicks > config.airNonFallTicks) {
                    Vec3 target = state.lastSupportPos != null ? state.lastSupportPos : pos;
                    rubberBand(player, state, target, "air_time", state.airNonFallTicks, config.airNonFallTicks);
                    state.lastPos = pos;
                    state.wasGliding = false;
                    return;
                }
            } else {
                state.airNonFallTicks = 0;
            }
            state.airSessionTicks++;
            if (deltaY < 0.0) {
                state.airSessionDescent += -deltaY;
            }
            boolean sustainedAirPlane = config.noFallDetectionEnabled
                && state.airSessionTicks >= Math.max(1, (int) Math.round(config.antiKickWindowTicks * 0.3))
                && state.airSessionDescent < Math.max(0.15, config.antiKickMinDescent)
                && horizontal >= 0.45;
            if (sustainedAirPlane) {
                Vec3 target = state.lastSupportPos != null ? state.lastSupportPos : pos;
                rubberBand(player, state, target, "air_plane", state.airSessionDescent, config.antiKickMinDescent);
                state.lastPos = pos;
                state.wasGliding = false;
                return;
            }
            if (config.noFallDetectionEnabled && state.airSessionTicks >= config.antiKickWindowTicks) {
                if (state.airSessionDescent < config.antiKickMinDescent) {
                    Vec3 target = state.lastSupportPos != null ? state.lastSupportPos : pos;
                    rubberBand(player, state, target, "air_antikick", state.airSessionDescent, config.antiKickMinDescent);
                    state.lastPos = pos;
                    state.wasGliding = false;
                    return;
                }
                state.airSessionTicks = 0;
                state.airSessionDescent = 0.0;
            }
            if (!config.noFallDetectionEnabled) {
                state.airSessionTicks = 0;
                state.airSessionDescent = 0.0;
            }
            if (isVoidBelow(player) && !serverOnGround && !inFluid
                && deltaY >= AntiFlyConstants.AIR_DESCENT_EPSILON) {
                state.voidTicks++;
                if (state.voidTicks > config.voidFallTicks) {
                    Vec3 fallback = Vec3.atCenterOf(player.blockPosition());
                    Vec3 target = state.lastSupportPos != null ? state.lastSupportPos : fallback;
                    rubberBand(player, state, target, "void_fall", 0.0, 0.0);
                    state.lastPos = pos;
                    state.wasGliding = false;
                    return;
                }
            } else {
                state.voidTicks = 0;
            }
            if (state.sustainedAirTicks == 0) state.sustainedAirStartY = state.lastPos != null ? state.lastPos.y : pos.y;
            state.sustainedAirTicks++;
            // Sustained air time catches level "bobbing" flight that evades the
            // speed, vertical, hover and no-fall checks by cruising under the
            // caps and resetting the descent counter on each bob. Only landing
            // (or fluid/vehicle support) resets this counter.
            if (state.sustainedAirTicks > config.sustainedAirTicksLimit) {
                if (state.sustainedAirStartY - pos.y >= config.sustainedAirMinDescent) {
                    state.sustainedAirTicks = 0;
                    state.sustainedAirStartY = pos.y;
                } else {
                    Vec3 target = state.lastSupportPos != null ? state.lastSupportPos : pos;
                    rubberBand(player, state, target, "air_sustained", state.sustainedAirStartY - pos.y, config.sustainedAirMinDescent);
                    state.lastPos = pos;
                    state.wasGliding = false;
                    return;
                }
            }
            // No generic "flight" rubberband; rely on hover/elytra checks only.
        }

        state.lastPos = pos;
        state.lastServerOnGround = serverOnGround;
        state.wasGliding = false;
    }

    private boolean isExempt(ServerPlayer player) {
        if (exempt.contains(player.getUUID())) {
            return true;
        }
        if (player.isCreative() || player.isSpectator()) {
            return true;
        }
        if (player.getAbilities().mayfly) {
            return true;
        }
        if (player.onClimbable()) {
            return true;
        }
        return player.hasEffect(MobEffects.LEVITATION)
            || player.hasEffect(MobEffects.SLOW_FALLING);
    }

    /**
     * Flags a velocity change no amount of legitimate input can produce. A
     * vanilla jump is worth 0.42 blocks per tick and landing only ever removes
     * velocity, so a large upwards gain leaving the player moving fast can only
     * have come from an external push such as a wind charge, explosion or mob
     * knockback. This is deliberately derived from velocity rather than from
     * damage: damage-over-time would let a player farm permanent grace.
     */
    private void detectExternalImpulse(ServerPlayer player, PlayerState state) {
        Vec3 current = player.getDeltaMovement();
        Vec3 previous = state.lastVelocity;
        state.lastVelocity = current;
        if (previous == null || config.impulseGraceTicks <= 0) {
            return;
        }
        double gain = current.length() - previous.length();
        if (gain > AntiFlyConstants.IMPULSE_MIN_VELOCITY_GAIN
            && current.length() > AntiFlyConstants.IMPULSE_MIN_RESULTING_VELOCITY) {
            state.impulseGraceTicks = config.impulseGraceTicks;
        }
    }

    private double horizontalDistance(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private double maxGroundSpeed(ServerPlayer player) {
        double max = player.isPassenger() ? config.groundMountedMax : config.groundWalkMax;
        if (player.isSprinting()) {
            max *= 1.3;
        }
        MobEffectInstance speed = player.getEffect(MobEffects.SPEED);
        if (speed != null) {
            max *= 1.0 + (0.2 * (speed.getAmplifier() + 1));
        }
        return max;
    }

    private double maxWaterSpeed(ServerPlayer player) {
        double max = config.waterMax;
        MobEffectInstance dolphins = player.getEffect(MobEffects.DOLPHINS_GRACE);
        if (dolphins != null) {
            max *= 1.0 + (0.3 * (dolphins.getAmplifier() + 1));
        }
        var depthStriderHolder = player.level().registryAccess()
            .lookupOrThrow(Registries.ENCHANTMENT)
            .getOrThrow(Enchantments.DEPTH_STRIDER);
        int depthStrider = EnchantmentHelper.getItemEnchantmentLevel(
            depthStriderHolder, player.getItemBySlot(EquipmentSlot.FEET));
        if (depthStrider > 0) {
            max *= 1.0 + (0.15 * depthStrider);
        }
        return max;
    }

    private double maxWaterVertical(ServerPlayer player) {
        double max = config.waterVerticalMax;
        MobEffectInstance dolphins = player.getEffect(MobEffects.DOLPHINS_GRACE);
        if (dolphins != null) {
            max *= 1.0 + (0.15 * (dolphins.getAmplifier() + 1));
        }
        return max;
    }

    private double maxAirSpeed(ServerPlayer player) {
        double max = config.airMax;
        MobEffectInstance speed = player.getEffect(MobEffects.SPEED);
        if (speed != null) {
            max *= 1.0 + (0.2 * (speed.getAmplifier() + 1));
        }
        return max;
    }

    private double maxAirVertical(ServerPlayer player) {
        double max = config.airVerticalMax;
        MobEffectInstance jump = player.getEffect(MobEffects.JUMP_BOOST);
        if (jump != null) {
            max *= 1.0 + (0.1 * (jump.getAmplifier() + 1));
        }
        return max;
    }

    private boolean handleGlide(ServerPlayer player, PlayerState state, Vec3 pos,
                                boolean serverOnGround, boolean inFluid, boolean inVehicle) {        if (!config.elytraChecksEnabled) {
            state.glideStallTicks = 0;
            state.glideSlowdownGraceTicks = 0;
            state.lastGlideHorizontal = 0.0;
            return true;
        }
        if (inFluid || inVehicle || !hasElytraEquipped(player)) {
            return true;
        }
        if (!state.wasGliding) {
            state.glideSlowdownGraceTicks = config.elytraSlowdownGraceTicks;
            state.glideStallTicks = 0;
            state.glideHoverTicks = 0;
            state.glideControlTicks = 0;
            state.glideRocketGraceTicks = 0;
            state.elytraNetAltitude = 0.0;
            state.glideSustainedClimbTicks = 0;
            state.elytraVerifier.reset();
            state.lastGlideHorizontal = 0.0;
        }

        Vec3 vel = player.getDeltaMovement();
        double moveDeltaY = state.lastPos != null ? (pos.y - state.lastPos.y) : 0.0;
        double horizontal = state.lastPos != null ? horizontalDistance(state.lastPos, pos) : 0.0;
        double horizontalSpeed = Math.max(horizontal, Math.sqrt(vel.x * vel.x + vel.z * vel.z));

        // A firework rocket is the only legitimate source of powered elytra
        // flight. It is read from the item-use statistic because Fabric has no
        // interact event to hook, and it must live here rather than in the
        // hunger-mode path so the glide checks work with Hunger Mode off.
        int fireworkUses = player.getStats().getValue(Stats.ITEM_USED.get(Items.FIREWORK_ROCKET));
        if (state.lastFireworkUses >= 0 && fireworkUses > state.lastFireworkUses) {
            state.glideRocketGraceTicks = config.elytraBoostGraceTicks;
        }
        state.lastFireworkUses = fireworkUses;
        boolean glideRecentRocket = state.glideRocketGraceTicks > 0;
        if (glideRecentRocket) {
            state.glideRocketGraceTicks--;
        }
        // Riptide is legitimate propulsion too, so it resets the accumulator the
        // same way a rocket does instead of tripping the climb check.
        boolean glideRiptide = player.isAutoSpinAttack();

        // While gliding without a rocket, drag and gravity guarantee a NET loss
        // of altitude, so a net GAIN is physically impossible unless velocity is
        // being written directly - which is exactly what an elytra-flight hack
        // does. Cumulative rather than per-window, so weaving between a climb
        // and a dive cannot hide the gain on the way up.
        if (glideRecentRocket || glideRiptide) {
            state.elytraNetAltitude = 0.0;
        } else {
            // Net altitude is only a contributor now, never a standalone reason
            // to punish: measured legit and hacked distributions overlap, so no
            // threshold on it can separate them. The shadow verifier below is
            // what decides.
            state.elytraNetAltitude += moveDeltaY;
        }

        // ---- Shadow physics verifier -------------------------------------------
        // Score a tick-sampled trajectory against the unpowered vanilla glide
        // step. Known propulsion and special movement reset the baseline.
        com.antifly.common.ElytraPhysics.Mode verifierMode =
            com.antifly.common.ElytraPhysics.Mode.parse(config.elytraVerifierMode);
        if (verifierMode != com.antifly.common.ElytraPhysics.Mode.OFF) {
            state.elytraInput.set(
                state.lastPos == null ? vel.x : pos.x - state.lastPos.x,
                state.lastPos == null ? vel.y : pos.y - state.lastPos.y,
                state.lastPos == null ? vel.z : pos.z - state.lastPos.z,
                player.getXRot(), player.getYRot(), pos.y,
                player.level().getGameTime(), glideRecentRocket || glideRiptide,
                serverOnGround || inFluid || inVehicle || state.impulseGraceTicks > 0
                    || player.hasEffect(MobEffects.LEVITATION)
                    || player.hasEffect(MobEffects.SLOW_FALLING));
            com.antifly.common.ElytraPhysics.Verdict verdict =
                state.elytraVerifier.observe(state.elytraInput, config.elytraVerifierTuning);
            if (verdict.actionable(config.elytraVerifierTuning)) {
                if (verifierMode == com.antifly.common.ElytraPhysics.Mode.ENFORCE) {
                    return failGlide(player, state, pos,
                        "elytra_physics_" + verdict.reason, verdict.evidence, verdict.confidence);
                }
                sendDebugActionBar(player, state, "VERIFY#" + verdict.reason,
                    verdict.evidence, verdict.confidence, 0.0, 0.0);
            }
        }

        // A net-altitude threshold alone cannot separate a hack from a genuine
        // momentum pull-up, because both can gain a similar amount. The shape
        // differs though: vanilla converts momentum, so its climb rate decays
        // within a few ticks, while a hack holds a constant rate indefinitely.
        boolean sustainedClimb = !glideRecentRocket && !glideRiptide
            && moveDeltaY > AntiFlyConstants.ELYTRA_SUSTAINED_CLIMB_MIN_DELTA_Y;
        if (sustainedClimb) {
            state.glideSustainedClimbTicks++;
        } else {
            state.glideSustainedClimbTicks = 0;
        }


        if (horizontal <= config.elytraStallHorizontalMax
            && Math.abs(moveDeltaY) <= config.elytraStallVerticalMax) {
            state.glideStallTicks++;
        } else {
            state.glideStallTicks = 0;
        }

        // Vanilla drag guarantees that a gliding player always loses altitude
        // without a rocket, and cannot hold a perfectly flat line. Holding
        // altitude, or climbing, requires writing velocity directly - which is
        // what an elytra-flight hack does. Both tests are restricted to the
        // no-rocket case so rocket-assisted flight stays completely untouched.
        if (!glideRecentRocket && !glideRiptide && !serverOnGround) {
            boolean flat = Math.abs(moveDeltaY) <= ELYTRA_FLAT_DELTA_Y_MAX;
            if (flat && horizontal >= ELYTRA_FLAT_MIN_HORIZONTAL) {
                state.glideHoverTicks++;
            } else {
                state.glideHoverTicks = Math.max(0, state.glideHoverTicks - 1);
            }

            // An unpowered elytra cannot hold a steady climb for long either.
            if (moveDeltaY > ELYTRA_UP_CONTROL_MIN_DELTA_Y && horizontal > 0.02) {
                state.glideControlTicks++;
            } else {
                state.glideControlTicks = Math.max(0, state.glideControlTicks - 1);
            }
        } else {
            state.glideHoverTicks = 0;
            state.glideControlTicks = 0;
        }

        state.lastGlideHorizontal = horizontalSpeed;
        return true;
    }

    private static final double ELYTRA_FLAT_DELTA_Y_MAX = 0.03;
    private static final double ELYTRA_FLAT_MIN_HORIZONTAL = 0.45;
    private static final int ELYTRA_FLAT_TICKS_LIMIT = 20;
    private static final double ELYTRA_UP_CONTROL_MIN_DELTA_Y = 1.0;
    private static final int ELYTRA_UP_CONTROL_TICKS_LIMIT = 6;

    /**
     * Single exit point for every glide violation. Centralised so a new check
     * cannot forget to end the glide or to start the suppression window, which
     * is what makes the penalty survive a client that immediately re-deploys.
     */
    private boolean failGlide(ServerPlayer player, PlayerState state, Vec3 pos, String reason,
                              double actual, double allowed) {
        Vec3 target = state.lastSupportPos != null ? state.lastSupportPos : pos;
        rubberBand(player, state, target, reason, actual, allowed);
        player.stopFallFlying();
        state.glideSuppressTicks = config.elytraGlideSuppressionTicks;
        return false;
    }

    private boolean hasElytraEquipped(ServerPlayer player) {
        return player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
    }

    private boolean isVoidBelow(ServerPlayer player) {
        ServerLevel level = getServerLevel(player);
        double voidY = minBuildHeight(level) - AntiFlyConstants.VOID_Y_OFFSET;
        return player.position().y < voidY;
    }

    private boolean hasGroundSupport(ServerPlayer player) {
        ServerLevel level = getServerLevel(player);
        AABB box = player.getBoundingBox();
        double minX = box.minX + AntiFlyConstants.SUPPORT_EPSILON;
        double maxX = box.maxX - AntiFlyConstants.SUPPORT_EPSILON;
        double minZ = box.minZ + AntiFlyConstants.SUPPORT_EPSILON;
        double maxZ = box.maxZ - AntiFlyConstants.SUPPORT_EPSILON;
        double y = box.minY - AntiFlyConstants.SUPPORT_EPSILON;
        int blockY = Mth.floor(y);
        if (blockY < minBuildHeight(level)) {
            return false;
        }
        if (hasSolidSupportAtY(level, minX, maxX, minZ, maxZ, blockY)) {
            return true;
        }
        int deepBlockY = Mth.floor(box.minY - AntiFlyConstants.SUPPORT_TALL_BLOCK_DEPTH);
        return (deepBlockY != blockY && deepBlockY >= minBuildHeight(level)
            && hasSolidSupportAtY(level, minX, maxX, minZ, maxZ, deepBlockY))
            || hasEntitySupport(player);
    }

    private boolean hasBoatGroundSupport(Boat boat) {
        ServerLevel level = (ServerLevel) boat.level();
        AABB box = boat.getBoundingBox();
        double minX = box.minX + AntiFlyConstants.SUPPORT_EPSILON;
        double maxX = box.maxX - AntiFlyConstants.SUPPORT_EPSILON;
        double minZ = box.minZ + AntiFlyConstants.SUPPORT_EPSILON;
        double maxZ = box.maxZ - AntiFlyConstants.SUPPORT_EPSILON;
        int blockY = Mth.floor(box.minY - AntiFlyConstants.SUPPORT_EPSILON);
        return blockY >= minBuildHeight(level)
            && hasSolidSupportAtY(level, minX, maxX, minZ, maxZ, blockY);
    }

    /**
     * True when the player is standing on a vehicle or mob (a boat, minecart or
     * similar) rather than on blocks. The client treats that collision box as a
     * floor, but the block probes cannot see it, so without this every step onto
     * a boat looks like a client claiming ground the server cannot find - which
     * lands the player in the airborne checks and sets them back off the boat.
     */
    private boolean hasEntitySupport(ServerPlayer player) {
        AABB box = player.getBoundingBox();
        double feet = box.minY;
        AABB probe = new AABB(box.minX, feet - SUPPORT_ENTITY_DEPTH, box.minZ, box.maxX, feet, box.maxZ);
        for (Entity entity : player.level().getEntities(player, probe)) {
            if (entity instanceof net.minecraft.world.entity.player.Player) {
                continue;
            }
            double otherTop = entity.getBoundingBox().maxY;
            if (otherTop >= feet - SUPPORT_ENTITY_DEPTH && otherTop <= feet + SUPPORT_ENTITY_SLACK) {
                return true;
            }
        }
        return false;
    }

    private boolean hasGroundSupportLoose(ServerPlayer player) {
        ServerLevel level = getServerLevel(player);
        AABB box = player.getBoundingBox();
        double minX = box.minX + AntiFlyConstants.SUPPORT_EPSILON;
        double maxX = box.maxX - AntiFlyConstants.SUPPORT_EPSILON;
        double minZ = box.minZ + AntiFlyConstants.SUPPORT_EPSILON;
        double maxZ = box.maxZ - AntiFlyConstants.SUPPORT_EPSILON;
        double y = box.minY - AntiFlyConstants.SUPPORT_LOOSE_EPSILON;
        int blockY = Mth.floor(y);
        if (blockY < minBuildHeight(level)) {
            return false;
        }
        if (hasSolidSupportAtY(level, minX, maxX, minZ, maxZ, blockY)) {
            return true;
        }
        int deepBlockY = Mth.floor(box.minY - AntiFlyConstants.SUPPORT_TALL_BLOCK_DEPTH);
        return (deepBlockY != blockY && deepBlockY >= minBuildHeight(level)
            && hasSolidSupportAtY(level, minX, maxX, minZ, maxZ, deepBlockY))
            || hasEntitySupport(player);
    }

    private boolean hasSolidSupportAtY(ServerLevel level, double minX, double maxX, double minZ, double maxZ, int blockY) {
        if (hasSolidAt(level, minX, blockY, minZ)) {
            return true;
        }
        if (hasSolidAt(level, maxX, blockY, minZ)) {
            return true;
        }
        if (hasSolidAt(level, minX, blockY, maxZ)) {
            return true;
        }
        return hasSolidAt(level, maxX, blockY, maxZ);
    }

    private boolean hasSolidAt(ServerLevel level, double x, int y, double z) {
        BlockPos pos = new BlockPos(Mth.floor(x), y, Mth.floor(z));
        BlockState state = level.getBlockState(pos);
        return !state.getCollisionShape(level, pos).isEmpty();
    }

    private void rubberBand(ServerPlayer player, PlayerState state, Vec3 target, String reason,
                            double actual, double allowed) {
        long now = System.currentTimeMillis();
        // The running count is deliberately NOT cleared by a setback: a player
        // who is corrected and immediately carries on should look different
        // from someone who tripped a check once and stopped.
        if (now - state.lastFailMs > AntiFlyConstants.REPEAT_OFFENDER_WINDOW_MS) {
            state.failCount = 0;
        }
        state.lastFailMs = now;
        state.failCount++;
        if (state.failCount >= config.repeatOffenderAlertCount
            && now - state.lastRepeatAlertMs > AntiFlyConstants.REPEAT_OFFENDER_COOLDOWN_MS) {
            state.lastRepeatAlertMs = now;
            broadcastRepeatOffender(player, state);
        }
        if (now - state.lastRubberBandAtMs > LOG_COOLDOWN_MS) {
            int count = attemptTracker.record(player.getUUID());
            String line = String.format(
                "Blocked %s for %s (%s) count=%d tune=%s loc=%s,%s,%s actual=%s allowed=%s",
                reason,
                player.getName().getString(),
                player.getUUID(),
                count,
                settingKeyForReason(reason),
                String.format("%.2f", target.x), String.format("%.2f", target.y), String.format("%.2f", target.z),
                String.format("%.3f", actual),
                String.format("%.3f", allowed)
            );
            if (isAlertConsole()) {
                LOGGER.info(line);
            }
            if (isAlertGame()) {
                broadcastFabricAlert(player, reason, settingKeyForReason(reason), actual, allowed);
            }
            state.lastRubberBandAtMs = now;
        }

        player.teleportTo(
            getServerLevel(player),
            target.x, target.y, target.z,
            java.util.EnumSet.noneOf(net.minecraft.world.entity.Relative.class),
            player.getYRot(),
            player.getXRot(),
            false
        );
        player.setDeltaMovement(0.0, 0.0, 0.0);
        state.airTicks = 0;
        state.lastPos = target;
        state.lastServerOnGround = hasGroundSupport(player);
    }

    private void rubberBandVehicle(ServerPlayer player, PlayerState state, Vec3 target, String reason,
                                   double actual, double allowed) {
        if (player.getVehicle() != null) {
            player.getVehicle().setDeltaMovement(0.0, 0.0, 0.0);
            player.getVehicle().teleportTo(target.x, target.y, target.z);
        }
        rubberBand(player, state, target, reason, actual, allowed);
    }

    private ServerLevel getServerLevel(ServerPlayer player) {
        return (ServerLevel) player.level();
    }

    private boolean isInFluid(ServerPlayer player) {
        Level level = player.level();
        BlockPos pos = player.blockPosition();
        return isFluidAt(level, pos) || isFluidAt(level, pos.above());
    }

    private boolean isBoatInFluid(Boat boat) {
        Level level = boat.level();
        BlockPos pos = boat.blockPosition();
        return isFluidAt(level, pos) || isFluidAt(level, pos.below());
    }

    private int vehicleAirGraceTicks(Entity vehicle) {
        if (vehicle instanceof Boat) {
            return config.boatAirGraceTicks;
        }
        String typePath = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.getType()).getPath();
        if (typePath.equals("horse")
            || typePath.equals("donkey")
            || typePath.equals("mule")
            || typePath.equals("skeleton_horse")
            || typePath.equals("zombie_horse")
            || typePath.equals("camel")
            || typePath.equals("llama")
            || typePath.equals("trader_llama")) {
            return config.horseAirGraceTicks;
        }
        return config.vehicleAirGraceTicks;
    }

    private String settingKeyForReason(String reason) {
        return switch (reason) {
            case "ground_speed" -> "groundWalkMax";
            case "water_speed" -> "waterMax";
            case "water_vertical" -> "waterVerticalMax";
            case "ground_spoof" -> "groundSpoofTicksLimit";
            case "boat_speed" -> "boatMaxHorizontal";
            case "air_speed" -> "maxAirHorizontal";
            case "air_vertical" -> "maxAirVertical";
            case "air_hover" -> "hoverBufferLimit";
            case "air_time" -> "airNonFallTicksLimit";
            case "air_sustained" -> "sustainedAirTicksLimit";
            case "air_antikick" -> "antiKickWindowTicks/antiKickMinDescent";
            case "air_plane" -> "airNonFallTicksLimit/antiKickMinDescent";
            case "void_fall" -> "voidFallTicks";
            case "vehicle_flight" -> "vehicleAirGraceTicks/boatAirGraceTicks/horseAirGraceTicks";
            case "elytra_speed" -> "elytraMaxHorizontal/elytraNoRocketSustainableHorizontal";
            case "elytra_up" -> "elytraMaxUp/elytraMaxNoRocketUp";
            case "elytra_no_rocket_climb" -> "elytraNoRocketMaxAscent";
            case "elytra_no_rocket_climb_sustained" -> "elytraSustainedClimbTicksLimit";
            case "elytra_physics_residual" -> "elytraVerifierMode";
            case "elytra_physics_energy_creation" -> "elytraVerifierMode";
            case "elytra_physics_constant_velocity" -> "elytraVerifierMode";
            case "elytra_physics_pitch_decoupled" -> "elytraVerifierMode";
            case "elytra_physics_impossible_turn" -> "elytraVerifierMode";
            case "elytra_down" -> "elytraMaxDown";
            case "elytra_stall" -> "elytraStallTicks";
            case "elytra_slowdown" -> "elytraMovementBufferLimit";
            case "elytra_no_item" -> "elytraEnabled";
            default -> "-";
        };
    }

    private int setAlertMode(net.minecraft.commands.CommandSourceStack source, String mode) {
        config.alertMode = mode;
        saveConfig();
        source.sendSuccess(() -> Component.literal("Alert mode set to " + mode), false);
        return 1;
    }

    private int setDisabledWorld(net.minecraft.commands.CommandSourceStack source, String worldName, boolean disabled) {
        String normalized = worldName == null ? "" : worldName.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            source.sendFailure(Component.literal("World name cannot be empty."));
            return 0;
        }
        java.util.LinkedHashSet<String> worlds = new java.util.LinkedHashSet<>(config.disabledWorlds);
        if (disabled) {
            worlds.add(normalized);
        } else {
            worlds.remove(normalized);
        }
        config.disabledWorlds = worlds.stream().sorted().toList();
        saveConfig();
        source.sendSuccess(() -> Component.literal("World " + worldName + " is now "
            + (disabled ? "disabled" : "enabled") + " for AntiFly."), false);
        return 1;
    }

    private int setDebugMode(net.minecraft.commands.CommandSourceStack source, boolean enabled) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Usage: /antifly debug <on|off>"));
            return 0;
        }
        if (enabled) {
            debug.add(player.getUUID());
            source.sendSuccess(() -> Component.literal("AntiFly action-bar debug enabled."), false);
        } else {
            debug.remove(player.getUUID());
            source.sendSuccess(() -> Component.literal("AntiFly action-bar debug disabled."), false);
        }
        return 1;
    }

    private boolean isDebug(ServerPlayer player) {
        return debug.contains(player.getUUID());
    }

    private void sendDebugActionBar(ServerPlayer player, PlayerState state, String mode,
                                    double horizontal, double deltaY, double hLimit, double vLimit) {
        if (!isDebug(player)) {
            return;
        }
        String text = String.format(
            "%s | h=%.3f/%.3f dy=%.3f/%.3f | buf[h=%.2f v=%.2f hov=%.2f ak=%.2f e=%.2f] | noFall=%s antiKick=%d/%d",
            mode,
            horizontal, hLimit,
            deltaY, vLimit,
            state.airHorizontalBuffer,
            state.airVerticalBuffer,
            state.hoverBuffer,
            state.antiKickBuffer,
            state.elytraMovementBuffer,
            config.noFallDetectionEnabled,
            state.airSessionTicks,
            config.antiKickWindowTicks
        );
        player.sendSystemMessage(Component.literal(text));
    }

    private boolean isAlertConsole() {
        return "console".equalsIgnoreCase(config.alertMode) || "both".equalsIgnoreCase(config.alertMode);
    }

    private boolean isAlertGame() {
        return "game".equalsIgnoreCase(config.alertMode) || "both".equalsIgnoreCase(config.alertMode);
    }

    private void broadcastRepeatOffender(ServerPlayer violator, PlayerState state) {
        LOGGER.warn("{} tripped {} flight checks within the {}-minute repeat-offender window",
            violator.getName().getString(), state.failCount, AntiFlyConstants.REPEAT_OFFENDER_WINDOW_MS / 60000L);
        if (!isAlertGame()) {
            return;
        }
        net.minecraft.server.MinecraftServer server = violator.level().getServer();
        if (server == null) {
            return;
        }
        Component message = repeatOffenderAlert(violator.getName().getString(), state.failCount,
            AntiFlyConstants.REPEAT_OFFENDER_WINDOW_MS / 60000L);
        for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
            if (viewer == violator) {
                continue;
            }
            if (viewer.createCommandSourceStack().permissions().hasPermission(Permissions.COMMANDS_MODERATOR)) {
                viewer.sendSystemMessage(message);
            }
        }
    }

    private void broadcastFabricAlert(ServerPlayer violator, String reason, String tune, double actual, double allowed) {
        Component message = violationAlert(violator.getName().getString(), reason, tune, actual, allowed);
        net.minecraft.server.MinecraftServer server = violator.level().getServer();
        if (server == null) {
            return;
        }
        for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
            if (viewer.createCommandSourceStack().permissions().hasPermission(Permissions.COMMANDS_MODERATOR)) {
                viewer.sendSystemMessage(message);
            }
        }
    }

    private boolean isVehicleNaturalFall(double deltaY, double velocityY, double horizontal, Entity vehicle) {
        double horizontalLimit = vehicle instanceof Boat ? config.boatMaxHorizontal : config.vehicleFallMaxHorizontal;
        return deltaY <= config.vehicleFallMinDescent
            && horizontal <= horizontalLimit;
    }

    private boolean isFluidAt(Level level, BlockPos pos) {
        FluidState fluidState = level.getFluidState(pos);
        return fluidState.is(FluidTags.WATER) || fluidState.is(FluidTags.LAVA);
    }

    private int minBuildHeight(Level level) {
        return ((LevelHeightAccessor) level).getMinY();
    }

    private void resetState(PlayerState state, Vec3 pos) {
        state.airTicks = 0;
        state.airNonFallTicks = 0;
        state.airSessionTicks = 0;
        state.airSessionDescent = 0.0;
        state.hoverTicks = 0;
        state.voidTicks = 0;
        state.glideStallTicks = 0;
        state.groundSpoofTicks = 0;
        state.glideGroundGraceTicks = 0;
        state.glideSlowdownGraceTicks = 0;
        state.wasGliding = false;
        state.lastServerOnGround = false;
        state.lastGlideHorizontal = 0.0;
        state.vehicleGraceTicks = 0;
        state.vehicleAirTicks = 0;
        state.vehicleFallTicks = 0;
        state.vehicleFallHorizontalDistance = 0.0;
        state.wasInVehicle = false;
        state.sustainedAirTicks = 0;
        state.impulseGraceTicks = 0;
        state.lastVelocity = null;
        state.lastFlightDamageAtTicks = -1;
        if (pos != null) {
            state.lastGroundPos = pos;
            state.lastSupportPos = pos;
            state.lastPos = pos;
            state.lastServerOnGround = true;
        }
    }

    /**
     * Lightweight rebaseline for legitimate teleports. Resets the movement
     * counters and baseline so the displacement is not treated as flight, but
     * NEVER moves the setback anchors (lastGroundPos/lastSupportPos) to an
     * unsupported destination. Only genuinely supported destinations update
     * the anchors, so a flyer chaining teleport-sized jumps is always
     * corrected back to real ground.
     */
    private void rebaselineForTeleport(PlayerState state, Vec3 pos, boolean supported) {
        state.hungerElytraVerifier.reset();
        state.lastPos = pos;
        state.airTicks = 0;
        state.sustainedAirTicks = 0;
        state.airNonFallTicks = 0;
        state.airSessionTicks = 0;
        state.airSessionDescent = 0.0;
        state.hoverTicks = 0;
        state.voidTicks = 0;
        state.groundSpoofTicks = 0;
        state.glideGroundGraceTicks = 0;
        state.glideSlowdownGraceTicks = 0;
        state.lastGlideHorizontal = 0.0;
        state.wasGliding = false;
        state.vehicleFallTicks = 0;
        state.vehicleFallHorizontalDistance = 0.0;
        state.lastServerOnGround = supported;
        if (supported) {
            state.lastGroundPos = pos;
            state.lastSupportPos = pos;
        }
    }

    private void updateSupport(PlayerState state, boolean onGround, boolean inFluid, Vec3 pos) {
        if (onGround) {
            state.lastGroundPos = pos;
            state.lastSupportPos = pos;
            state.airTicks = 0;
            state.airNonFallTicks = 0;
            state.airSessionTicks = 0;
            state.airSessionDescent = 0.0;
            state.hoverTicks = 0;
            state.voidTicks = 0;
            state.groundSpoofTicks = 0;
            state.vehicleAirTicks = 0;
            state.vehicleFallTicks = 0;
            state.vehicleFallHorizontalDistance = 0.0;
            state.lastServerOnGround = true;
            state.sustainedAirTicks = 0;
        } else if (inFluid) {
            state.lastSupportPos = pos;
            state.sustainedAirTicks = 0;
            state.airTicks = 0;
            state.airNonFallTicks = 0;
            state.airSessionTicks = 0;
            state.airSessionDescent = 0.0;
            state.hoverTicks = 0;
            state.voidTicks = 0;
            state.groundSpoofTicks = 0;
            state.vehicleAirTicks = 0;
            state.vehicleFallTicks = 0;
            state.vehicleFallHorizontalDistance = 0.0;
            state.lastServerOnGround = false;
        }
    }

    private int setVerifierMode(net.minecraft.commands.CommandSourceStack source, String key, String mode) {
        if (!"elytraVerifierMode".equals(normalizeSettingKey(key))) {
            source.sendFailure(Component.literal("Unknown mode key."));
            return 0;
        }
        config.elytraVerifierMode = mode;
        saveConfig();
        source.sendSuccess(() -> Component.literal("Set elytraVerifierMode to " + mode), false);
        return 1;
    }

    private boolean isForbiddenVoid(ServerPlayer player, Vec3 pos) {
        if (player.level().dimension() == Level.NETHER) {
            return pos.y >= 128.0 || pos.y < minBuildHeight(player.level());
        }
        return player.level().dimension() == Level.OVERWORLD
            && pos.y < minBuildHeight(player.level());
    }

    private int setVoidAccess(net.minecraft.commands.CommandSourceStack source, boolean enabled) {
        config.voidAccess = enabled;
        saveConfig();
        source.sendSuccess(() -> Component.literal("Void access is " + (enabled ? "on" : "off")), false);
        return 1;
    }

    /**
     * Health penalty for a player who stays in a denied void zone while Hunger
     * Mode is on. The first tick in the zone deals damage at once, then the
     * configured per-second amount keeps applying while they remain. The rate
     * reuses hungerModeFlightDamagePerSecond so it stays tunable.
     */
    private void applyVoidDamage(ServerPlayer player, PlayerState state) {
        if (state.voidDamageTicks < 0) {
            state.voidDamageTicks = 0;
            damageVoid(player);
            return;
        }
        state.voidDamageTicks++;
        if (state.voidDamageTicks >= 20) {
            state.voidDamageTicks = 0;
            damageVoid(player);
        }
    }

    private void damageVoid(ServerPlayer player) {
        float dmg = (float) config.hungerModeFlightDamagePerSecond;
        if (dmg > 0.0f) {
            player.hurt(player.damageSources().generic(), dmg);
        }
    }

    private int setValue(net.minecraft.commands.CommandSourceStack source, String key, double value) {
        if (config.elytraVerifierTuning.update(normalizeSettingKey(key), value)) {
            saveConfig();
            source.sendSuccess(() -> Component.literal("Set " + key + " to " + value), false);
            return 1;
        }
        switch (normalizeSettingKey(key)) {
            case "groundSpeed", "groundSpeedWalking" -> config.groundWalkMax = value;
            case "groundSpeedMounted" -> config.groundMountedMax = value;
            case "boatSpeed", "boatMaxHorizontal" -> config.boatMaxHorizontal = value;
            case "vehicleFallMinDescent" -> config.vehicleFallMinDescent = value;
            case "vehicleFallMaxHorizontal" -> config.vehicleFallMaxHorizontal = value;
            case "vehicleFallTicksMax" -> config.vehicleFallTicksMax = (int) Math.round(value);
            case "vehicleAirGraceTicks" -> config.vehicleAirGraceTicks = (int) Math.round(value);
            case "boatAirGraceTicks" -> config.boatAirGraceTicks = (int) Math.round(value);
            case "horseAirGraceTicks" -> config.horseAirGraceTicks = (int) Math.round(value);
            case "airSpeed" -> config.airMax = value;
            case "airVertical" -> config.airVerticalMax = value;
            case "bufferDecay" -> config.bufferDecay = value;
            case "horizontalBufferLimit" -> config.horizontalBufferLimit = value;
            case "verticalBufferLimit" -> config.verticalBufferLimit = value;
            case "hoverBufferLimit" -> config.hoverBufferLimit = value;
            case "setbackCooldownMs" -> config.setbackCooldownMs = (long) Math.round(value);
            case "noFallDetectionEnabled" -> config.noFallDetectionEnabled = value > 0.5;
            case "airNonFallTicks" -> config.airNonFallTicks = (int) Math.round(value);
            case "antiKickWindowTicks" -> config.antiKickWindowTicks = (int) Math.round(value);
            case "antiKickMinDescent" -> config.antiKickMinDescent = value;
            case "voidFallTicks" -> config.voidFallTicks = (int) Math.round(value);
            case "waterSpeed" -> config.waterMax = value;
            case "waterVertical" -> config.waterVerticalMax = value;
            case "elytraEnabled" -> config.elytraChecksEnabled = value > 0.5;
            case "elytraMaxHorizontal" -> config.elytraMaxHorizontal = value;
            case "elytraMaxUp" -> config.elytraMaxUp = value;
            // Paper-style aliases map to Fabric's single caps.
            case "elytraNoRocketSustainableHorizontal" -> config.elytraMaxHorizontal = value;
            case "elytraMaxRocketHorizontal" -> config.elytraMaxHorizontal = value;
            case "elytraMaxNoRocketUp" -> config.elytraMaxUp = value;
            case "elytraMaxRocketUp" -> config.elytraMaxUp = value;
            case "elytraStallHorizontalMax" -> config.elytraStallHorizontalMax = value;
            case "elytraStallVerticalMax" -> config.elytraStallVerticalMax = value;
            case "elytraStallTicks" -> config.elytraStallTicks = (int) Math.round(value);
            case "airGraceTicks" -> config.airGraceTicks = Math.max(0, (int) Math.round(value));
            case "hoverStartTicks" -> config.hoverStartTicks = Math.max(0, (int) Math.round(value));
            case "hoverTicksLimit" -> config.hoverTicksLimit = Math.max(0, (int) Math.round(value));
            case "hoverDeltaY" -> config.hoverDeltaY = Math.max(0.0, value);
            case "elytraLandingGraceTicks" -> config.elytraLandingGraceTicks = Math.max(0, (int) Math.round(value));
            case "elytraBoostGraceTicks" -> config.elytraBoostGraceTicks = (int) Math.round(value);
            case "elytraMovementBufferLimit" -> config.elytraMovementBufferLimit = value;
            case "elytraDurabilityCheckEnabled" -> config.elytraDurabilityCheckEnabled = value > 0.5;
            case "elytraNoRocketMaxAscent" -> config.elytraNoRocketMaxAscent = value;
            case "elytraSustainedClimbTicksLimit" -> config.elytraSustainedClimbTicksLimit = Math.max(1, (int) Math.round(value));
            case "elytraVerifierMode" -> config.elytraVerifierMode = value > 0.5 ? "enforce" : "off";
            case "elytraGlideSuppressionTicks" -> config.elytraGlideSuppressionTicks = Math.max(0, (int) Math.round(value));
            case "elytraRequiredDescentForPullup" -> config.elytraRequiredDescentForPullup = value;
            case "elytraSlowdownGraceTicks" -> config.elytraSlowdownGraceTicks = (int) Math.round(value);
            case "hungerModeMaxBlocksPerSecond" -> config.hungerModeMaxBlocksPerSecond = Math.max(1.0, value);
            case "hungerModeHungerPerSecondAtMaxSpeed" -> config.hungerModeHungerPerSecondAtMaxSpeed = Math.max(0.0, value);
            case "hungerModeRocketGraceTicks" -> config.hungerModeRocketGraceTicks = Math.max(0, (int) Math.round(value));
            case "hungerModeAirborneMinimumBlocksPerSecond" -> config.hungerModeAirborneMinimumBlocksPerSecond = Math.max(0.0, value);
            case "hungerModeFlightDamageEnabled" -> config.hungerModeFlightDamageEnabled = value > 0.5;
            case "hungerModeFlightDamageAfterSeconds" -> config.hungerModeFlightDamageAfterSeconds = Math.max(0.0, value);
            case "hungerModeFlightDamageAfterHungerSeconds" -> config.hungerModeFlightDamageAfterHungerSeconds = Math.max(0.0, value);
            case "hungerModeFlightDamagePerSecond" -> config.hungerModeFlightDamagePerSecond = Math.max(0.0, value);
            case "hungerModeElytraFoodEnabled" -> config.hungerModeElytraFoodEnabled = value > 0.5;
            case "hungerModeElytraFoodMultiplier" -> config.hungerModeElytraFoodMultiplier = Math.max(0.0, value);
            case "hungerModeElytraSpeedThresholdBps" -> config.hungerModeElytraSpeedThresholdBps = Math.max(1.0, value);
            case "hungerModeElytraNoRocketAfterSeconds" -> config.hungerModeElytraNoRocketAfterSeconds = Math.max(1.0, value);
            case "hungerModeElytraDamageEnabled" -> config.hungerModeElytraDamageEnabled = value > 0.5;
            case "hungerModeRocketResetsDamage" -> config.hungerModeRocketResetsDamage = value > 0.5;
            case "sustainedAirTicksLimit" -> config.sustainedAirTicksLimit = (int) Math.round(value);
            case "sustainedAirMinDescent" -> config.sustainedAirMinDescent = Math.max(0, value);
            case "impulseGraceTicks" -> config.impulseGraceTicks = Math.max(0, (int) Math.round(value));
            case "groundSpoofTicksLimit" -> config.groundSpoofTicksLimit = Math.max(1, (int) Math.round(value));
            case "repeatOffenderAlertCount" -> config.repeatOffenderAlertCount = Math.max(1, (int) Math.round(value));
            default -> {
                source.sendFailure(Component.literal("Unknown key."));
                return 0;
            }
        }
        saveConfig();
        source.sendSuccess(() -> Component.literal("Set " + key + " to " + value), false);
        return 1;
    }

    private int getValue(net.minecraft.commands.CommandSourceStack source, String key) {
        String value = formatSettingValue(normalizeSettingKey(key));
        if (value == null) {
            source.sendFailure(Component.literal("Unknown key."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(key + "=" + value), false);
        return 1;
    }

    private com.mojang.brigadier.builder.ArgumentBuilder<net.minecraft.commands.CommandSourceStack, ?> settingNode(String key) {
        return Commands.literal(key)
            .executes(ctx -> getValue(ctx.getSource(), key))
            .then(Commands.argument("value", DoubleArgumentType.doubleArg(0))
                .executes(ctx -> setValue(ctx.getSource(), key, DoubleArgumentType.getDouble(ctx, "value"))));
    }

    private String formatSettingValue(String key) {
        String tuningValue = config.elytraVerifierTuning.value(key);
        if (tuningValue != null) return tuningValue;
        return switch (key) {
            case "groundWalkMax" -> String.valueOf(config.groundWalkMax);
            case "groundMountedMax" -> String.valueOf(config.groundMountedMax);
            case "waterMax" -> String.valueOf(config.waterMax);
            case "waterVerticalMax" -> String.valueOf(config.waterVerticalMax);
            case "boatMaxHorizontal" -> String.valueOf(config.boatMaxHorizontal);
            case "maxAirHorizontal" -> String.valueOf(config.airMax);
            case "maxAirVertical" -> String.valueOf(config.airVerticalMax);
            case "bufferDecay" -> String.valueOf(config.bufferDecay);
            case "horizontalBufferLimit" -> String.valueOf(config.horizontalBufferLimit);
            case "verticalBufferLimit" -> String.valueOf(config.verticalBufferLimit);
            case "hoverBufferLimit" -> String.valueOf(config.hoverBufferLimit);
            case "noFallDetectionEnabled" -> String.valueOf(config.noFallDetectionEnabled);
            case "airNonFallTicksLimit" -> String.valueOf(config.airNonFallTicks);
            case "setbackCooldownMs" -> String.valueOf(config.setbackCooldownMs);
            case "elytraBoostGraceTicks" -> String.valueOf(config.elytraBoostGraceTicks);
            case "elytraMovementBufferLimit" -> String.valueOf(config.elytraMovementBufferLimit);
            case "elytraDurabilityCheckEnabled" -> String.valueOf(config.elytraDurabilityCheckEnabled);
            case "elytraNoRocketMaxAscent" -> String.valueOf(config.elytraNoRocketMaxAscent);
            case "elytraSustainedClimbTicksLimit" -> String.valueOf(config.elytraSustainedClimbTicksLimit);
            case "elytraVerifierMode" -> config.elytraVerifierMode;
            case "elytraGlideSuppressionTicks" -> String.valueOf(config.elytraGlideSuppressionTicks);
            case "elytraRequiredDescentForPullup" -> String.valueOf(config.elytraRequiredDescentForPullup);
            case "groundSpeed", "groundSpeedWalking" -> String.valueOf(config.groundWalkMax);
            case "groundSpeedMounted" -> String.valueOf(config.groundMountedMax);
            case "vehicleFallMinDescent" -> String.valueOf(config.vehicleFallMinDescent);
            case "vehicleFallMaxHorizontal" -> String.valueOf(config.vehicleFallMaxHorizontal);
            case "vehicleFallTicksMax" -> String.valueOf(config.vehicleFallTicksMax);
            case "airSpeed" -> String.valueOf(config.airMax);
            case "airVertical" -> String.valueOf(config.airVerticalMax);
            case "airNonFallTicks" -> String.valueOf(config.airNonFallTicks);
            case "antiKickWindowTicks" -> String.valueOf(config.antiKickWindowTicks);
            case "antiKickMinDescent" -> String.valueOf(config.antiKickMinDescent);
            case "voidFallTicks" -> String.valueOf(config.voidFallTicks);
            case "vehicleAirGraceTicks" -> String.valueOf(config.vehicleAirGraceTicks);
            case "boatAirGraceTicks" -> String.valueOf(config.boatAirGraceTicks);
            case "horseAirGraceTicks" -> String.valueOf(config.horseAirGraceTicks);
            case "waterSpeed" -> String.valueOf(config.waterMax);
            case "waterVertical" -> String.valueOf(config.waterVerticalMax);
            case "elytraEnabled" -> String.valueOf(config.elytraChecksEnabled);
            case "elytraMaxHorizontal" -> String.valueOf(config.elytraMaxHorizontal);
            case "elytraMaxUp" -> String.valueOf(config.elytraMaxUp);
            case "elytraNoRocketSustainableHorizontal", "elytraMaxRocketHorizontal" -> String.valueOf(config.elytraMaxHorizontal);
            case "elytraMaxNoRocketUp", "elytraMaxRocketUp" -> String.valueOf(config.elytraMaxUp);
            case "elytraStallHorizontalMax" -> String.valueOf(config.elytraStallHorizontalMax);
            case "elytraStallVerticalMax" -> String.valueOf(config.elytraStallVerticalMax);
            case "elytraStallTicks" -> String.valueOf(config.elytraStallTicks);
            case "airGraceTicks" -> String.valueOf(config.airGraceTicks);
            case "hoverStartTicks" -> String.valueOf(config.hoverStartTicks);
            case "hoverTicksLimit" -> String.valueOf(config.hoverTicksLimit);
            case "hoverDeltaY" -> String.valueOf(config.hoverDeltaY);
            case "elytraLandingGraceTicks" -> String.valueOf(config.elytraLandingGraceTicks);
            case "elytraSlowdownGraceTicks" -> String.valueOf(config.elytraSlowdownGraceTicks);
            case "hungerModeMaxBlocksPerSecond" -> String.valueOf(config.hungerModeMaxBlocksPerSecond);
            case "hungerModeHungerPerSecondAtMaxSpeed" -> String.valueOf(config.hungerModeHungerPerSecondAtMaxSpeed);
            case "hungerModeRocketGraceTicks" -> String.valueOf(config.hungerModeRocketGraceTicks);
            case "hungerModeAirborneMinimumBlocksPerSecond" -> String.valueOf(config.hungerModeAirborneMinimumBlocksPerSecond);
            case "hungerModeFlightDamageEnabled" -> String.valueOf(config.hungerModeFlightDamageEnabled);
            case "hungerModeFlightDamageAfterSeconds" -> String.valueOf(config.hungerModeFlightDamageAfterSeconds);
            case "hungerModeFlightDamageAfterHungerSeconds" -> String.valueOf(config.hungerModeFlightDamageAfterHungerSeconds);
            case "hungerModeFlightDamagePerSecond" -> String.valueOf(config.hungerModeFlightDamagePerSecond);
            case "hungerModeElytraFoodEnabled" -> String.valueOf(config.hungerModeElytraFoodEnabled);
            case "hungerModeElytraFoodMultiplier" -> String.valueOf(config.hungerModeElytraFoodMultiplier);
            case "hungerModeElytraSpeedThresholdBps" -> String.valueOf(config.hungerModeElytraSpeedThresholdBps);
            case "hungerModeElytraNoRocketAfterSeconds" -> String.valueOf(config.hungerModeElytraNoRocketAfterSeconds);
            case "hungerModeElytraDamageEnabled" -> String.valueOf(config.hungerModeElytraDamageEnabled);
            case "hungerModeRocketResetsDamage" -> String.valueOf(config.hungerModeRocketResetsDamage);
            case "sustainedAirTicksLimit" -> String.valueOf(config.sustainedAirTicksLimit);
            case "sustainedAirMinDescent" -> String.valueOf(config.sustainedAirMinDescent);
            case "impulseGraceTicks" -> String.valueOf(config.impulseGraceTicks);
            case "groundSpoofTicksLimit" -> String.valueOf(config.groundSpoofTicksLimit);
            case "repeatOffenderAlertCount" -> String.valueOf(config.repeatOffenderAlertCount);
            default -> null;
        };
    }

    private static final java.util.List<String> SET_KEYS = java.util.List.of(
        "groundWalkMax",
        "groundMountedMax",
        "waterMax",
        "waterVerticalMax",
        "boatMaxHorizontal",
        "maxAirHorizontal",
        "maxAirVertical",
        "bufferDecay",
        "horizontalBufferLimit",
        "verticalBufferLimit",
        "hoverBufferLimit",
        "noFallDetectionEnabled",
        "airNonFallTicksLimit",
        "setbackCooldownMs",
        "elytraBoostGraceTicks",
        "elytraMovementBufferLimit",
        "elytraDurabilityCheckEnabled",
        "elytraNoRocketMaxAscent",
        "elytraRequiredDescentForPullup",
        "elytraGlideSuppressionTicks",
        "elytraSustainedClimbTicksLimit",
        "elytraVerifierMode",
        "elytraVerifierGravity", "elytraVerifierResidualAllowance", "elytraVerifierResidualPerSpeed",
        "elytraVerifierEnergyAllowance", "elytraVerifierSteadySpeedDeviation", "elytraVerifierMinimumSteadySpeed",
        "elytraVerifierSteadyWindowTicks", "elytraVerifierEvidenceWindowTicks", "elytraVerifierMinimumEvidenceTicks",
        "elytraVerifierMinimumChannels", "elytraVerifierConfidenceThreshold", "elytraVerifierSharpTurnDegrees",
        "antiKickWindowTicks",
        "antiKickMinDescent",
        "voidFallTicks",
        "vehicleAirGraceTicks",
        "boatAirGraceTicks",
        "horseAirGraceTicks",
        "vehicleFallMinDescent",
        "vehicleFallMaxHorizontal",
        "vehicleFallTicksMax",
        "elytraEnabled",
        "elytraMaxHorizontal",
        "elytraMaxUp",
        "elytraNoRocketSustainableHorizontal",
        "elytraMaxRocketHorizontal",
        "elytraMaxNoRocketUp",
        "elytraMaxRocketUp",
        "elytraStallHorizontalMax",
        "elytraStallVerticalMax",
        "elytraStallTicks",
        "airGraceTicks",
        "hoverStartTicks",
        "hoverTicksLimit",
        "hoverDeltaY",
        "elytraLandingGraceTicks",
        "elytraSlowdownGraceTicks",
        "hungerModeMaxBlocksPerSecond",
        "hungerModeHungerPerSecondAtMaxSpeed",
        "hungerModeRocketGraceTicks",
        "hungerModeAirborneMinimumBlocksPerSecond",
        "hungerModeFlightDamageEnabled",
        "hungerModeFlightDamageAfterSeconds",
        "hungerModeFlightDamageAfterHungerSeconds",
        "hungerModeFlightDamagePerSecond",
        "hungerModeElytraFoodEnabled",
        "hungerModeElytraFoodMultiplier",
        "hungerModeElytraSpeedThresholdBps",
        "hungerModeElytraNoRocketAfterSeconds",
        "hungerModeElytraDamageEnabled",
        "hungerModeRocketResetsDamage",
        "sustainedAirTicksLimit",
        "sustainedAirMinDescent",
        "impulseGraceTicks",
        "groundSpoofTicksLimit",
        "repeatOffenderAlertCount"
    );
    private String normalizeSettingKey(String key) {
        if (key.regionMatches(true, 0, "elytra.verifier.", 0, 16)) {
            String child = key.substring(16);
            if (!child.isEmpty()) key = "elytraVerifier" + Character.toUpperCase(child.charAt(0)) + child.substring(1);
        }
        String normalizedKey = key.replace(".", "");
        String matchedKey = null;
        for (String settingKey : SET_KEYS) {
            if (settingKey.regionMatches(true, 0, "hungerMode", 0, "hungerMode".length())
                && settingKey.length() >= normalizedKey.length()
                && settingKey.regionMatches(true, settingKey.length() - normalizedKey.length(), normalizedKey, 0, normalizedKey.length())) {
                if (matchedKey != null) return key;
                matchedKey = settingKey;
            }
        }
        if (matchedKey != null) return matchedKey;
        return switch (key) {
            case "groundWalkMax" -> "groundSpeedWalking";
            case "groundMountedMax" -> "groundSpeedMounted";
            case "waterMax" -> "waterSpeed";
            case "waterVerticalMax" -> "waterVertical";
            case "maxAirHorizontal" -> "airSpeed";
            case "maxAirVertical" -> "airVertical";
            case "airNonFallTicksLimit" -> "airNonFallTicks";
            case "elytraMovementLimit" -> "elytraMovementBufferLimit";
            case "elytraNoRocketMaxAscent" -> "elytraNoRocketMaxAscent";
            case "elytraRequiredDescentForPullup" -> "elytraRequiredDescentForPullup";
            case "elytra_no_rocket_max_ascent" -> "elytraNoRocketMaxAscent";
            case "elytra_glide_suppression_ticks" -> "elytraGlideSuppressionTicks";
            case "elytra_sustained_climb_ticks_limit" -> "elytraSustainedClimbTicksLimit";
            case "elytra_verifier_mode" -> "elytraVerifierMode";
            case "elytra_required_descent_for_pullup" -> "elytraRequiredDescentForPullup";
            case "vehicleAirGraceTicks" -> "vehicleAirGraceTicks";
            case "boatAirGraceTicks" -> "boatAirGraceTicks";
            case "horseAirGraceTicks" -> "horseAirGraceTicks";
            case "boatSpeed", "boatMaxHorizontal" -> "boatMaxHorizontal";
            case "vehicle_air_grace_ticks" -> "vehicleAirGraceTicks";
            case "boat_air_grace_ticks" -> "boatAirGraceTicks";
            case "horse_air_grace_ticks" -> "horseAirGraceTicks";
            case "boat_max_horizontal" -> "boatMaxHorizontal";
            case "void_fall_ticks" -> "voidFallTicks";
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
            case "sustained_air_ticks_limit" -> "sustainedAirTicksLimit";
            case "sustained_air_min_descent" -> "sustainedAirMinDescent";
            case "air_grace_ticks" -> "airGraceTicks";
            case "hover_start_ticks" -> "hoverStartTicks";
            case "hover_ticks_limit" -> "hoverTicksLimit";
            case "hover_delta_y" -> "hoverDeltaY";
            case "elytra_landing_grace_ticks" -> "elytraLandingGraceTicks";
            case "impulse_grace_ticks" -> "impulseGraceTicks";
            case "ground_spoof_ticks_limit" -> "groundSpoofTicksLimit";
            case "repeat_offender_alert_count" -> "repeatOffenderAlertCount";
            default -> key;
        };
    }

    private boolean isWorldDisabled(ServerLevel level) {
        return level != null && isWorldDisabled(level.dimension().identifier().getPath());
    }

    private boolean isWorldDisabled(String worldName) {
        if (worldName == null) {
            return false;
        }
        return config.disabledWorlds.contains(worldName.trim().toLowerCase(Locale.ROOT));
    }

    private void resetTransientState(PlayerState state) {
        state.airHorizontalBuffer = 0.0;
        state.airVerticalBuffer = 0.0;
        state.hoverBuffer = 0.0;
        state.antiKickBuffer = 0.0;
        state.elytraMovementBuffer = 0.0;
        state.airTicks = 0;
        state.sustainedAirTicks = 0;
        state.airNonFallTicks = 0;
        state.airSessionTicks = 0;
        state.airSessionDescent = 0.0;
        state.hoverTicks = 0;
        state.voidTicks = 0;
        state.glideStallTicks = 0;
        state.glideHoverTicks = 0;
        state.glideControlTicks = 0;
        state.groundSpoofTicks = 0;
        state.glideGroundGraceTicks = 0;
        state.glideSlowdownGraceTicks = 0;
        state.lastGlideHorizontal = 0.0;
        state.vehicleAirTicks = 0;
        state.vehicleFallTicks = 0;
        state.vehicleFallHorizontalDistance = 0.0;
    }

    private void saveConfig() {
        config.exempt = exempt.stream().map(UUID::toString).sorted().toList();
        config.disabledWorlds = config.disabledWorlds.stream()
            .map(name -> name == null ? "" : name.trim().toLowerCase(Locale.ROOT))
            .filter(name -> !name.isEmpty())
            .distinct()
            .sorted()
            .toList();
        config.save();
    }

    private void checkModrinthVersion(net.minecraft.commands.CommandSourceStack source) {
        String currentVersion = FabricLoader.getInstance()
            .getModContainer(MOD_ID)
            .map(mc -> mc.getMetadata().getVersion().getFriendlyString())
            .orElse("unknown");
        source.sendSuccess(() -> Component.literal("Checking Modrinth for " + config.modrinthProjectSlug + " ..."), false);
        fetchLatestModrinthVersion(config.modrinthProjectSlug).thenAccept(result -> {
            Runnable send = () -> {
                if (!result.ok) {
                    source.sendFailure(Component.literal("Modrinth check failed: " + result.error));
                    return;
                }
                int cmp = compareVersions(currentVersion, result.latestVersion);
                if (cmp < 0) {
                    source.sendSuccess(() -> Component.literal("Outdated: running " + currentVersion + ", Modrinth has " + result.latestVersion), false);
                } else if (cmp > 0) {
                    source.sendSuccess(() -> Component.literal("Ahead of Modrinth: running " + currentVersion + ", latest hosted is " + result.latestVersion), false);
                } else {
                    source.sendSuccess(() -> Component.literal("Up to date with Modrinth: " + currentVersion), false);
                }
            };
            if (source.getServer() != null) {
                source.getServer().execute(send);
            } else {
                send.run();
            }
        });
    }

    private void checkModrinthVersionAndAlertOps(net.minecraft.server.MinecraftServer server) {
        String currentVersion = FabricLoader.getInstance()
            .getModContainer(MOD_ID)
            .map(mc -> mc.getMetadata().getVersion().getFriendlyString())
            .orElse("unknown");
        fetchLatestModrinthVersion(config.modrinthProjectSlug).thenAccept(result -> server.execute(() -> {
            if (!result.ok) {
                LOGGER.warn("Modrinth version check failed: {}", result.error);
                return;
            }
            int cmp = compareVersions(currentVersion, result.latestVersion);
            if (cmp >= 0) {
                return;
            }
            String msg = "AntiFly is outdated: running " + currentVersion + ", Modrinth has " + result.latestVersion;
            LOGGER.warn(msg);
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player.createCommandSourceStack().permissions().hasPermission(Permissions.COMMANDS_MODERATOR)) {
                    player.sendSystemMessage(Component.literal(msg));
                }
            }
        }));
    }

    private CompletableFuture<VersionResult> fetchLatestModrinthVersion(String projectSlug) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String encodedSlug = URLEncoder.encode(projectSlug, StandardCharsets.UTF_8);
                URI uri = URI.create("https://api.modrinth.com/v2/project/" + encodedSlug + "/version?featured=true&include_changelog=false");
                HttpRequest request = HttpRequest.newBuilder(uri)
                    .GET()
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "AntiFly/" + MOD_ID + " (version-check)")
                    .build();
                HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() != 200) {
                    return VersionResult.error("HTTP " + response.statusCode());
                }
                return parseLatestVersion(response.body());
            } catch (IOException | InterruptedException ex) {
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                String error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                return VersionResult.error(error);
            } catch (RuntimeException ex) {
                String error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                return VersionResult.error(error);
            }
        });
    }

    private VersionResult parseLatestVersion(String body) {
        Matcher versionMatcher = VERSION_NUMBER_PATTERN.matcher(body);
        Matcher dateMatcher = DATE_PUBLISHED_PATTERN.matcher(body);
        String latestVersion = null;
        String latestDate = "";
        while (versionMatcher.find()) {
            String version = versionMatcher.group(1);
            String date = dateMatcher.find() ? dateMatcher.group(1) : "";
            if (latestVersion == null || date.compareTo(latestDate) > 0) {
                latestVersion = version;
                latestDate = date;
            }
        }
        if (latestVersion == null) {
            return VersionResult.error("No versions found on Modrinth");
        }
        return VersionResult.ok(latestVersion);
    }

    private int compareVersions(String local, String remote) {
        String[] localParts = local.split("[^A-Za-z0-9]+");
        String[] remoteParts = remote.split("[^A-Za-z0-9]+");
        int len = Math.max(localParts.length, remoteParts.length);
        for (int i = 0; i < len; i++) {
            String a = i < localParts.length ? localParts[i] : "0";
            String b = i < remoteParts.length ? remoteParts[i] : "0";
            int cmp;
            if (isDigits(a) && isDigits(b)) {
                cmp = Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
            } else {
                cmp = a.compareToIgnoreCase(b);
            }
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    private boolean isDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return !s.isEmpty();
    }

    private static final class VersionResult {
        final boolean ok;
        final String latestVersion;
        final String error;

        private VersionResult(boolean ok, String latestVersion, String error) {
            this.ok = ok;
            this.latestVersion = latestVersion;
            this.error = error;
        }

        static VersionResult ok(String latestVersion) {
            return new VersionResult(true, latestVersion, null);
        }

        static VersionResult error(String error) {
            return new VersionResult(false, null, error);
        }
    }

    private static final class AntiFlyConfig {
        boolean enabled = true;
        boolean hungerModeEnabled = false;
        boolean voidAccess = true;
        double hungerModeMaxBlocksPerSecond = 200.0;
        double hungerModeHungerPerSecondAtMaxSpeed = 10.0;
        int hungerModeRocketGraceTicks = 80;
        double hungerModeAirborneMinimumBlocksPerSecond = 20.0;
        boolean hungerModeFlightDamageEnabled = true;
        double hungerModeFlightDamageAfterSeconds = 20.0;
        double hungerModeFlightDamageAfterHungerSeconds = 30.0;
        double hungerModeFlightDamagePerSecond = 1.0;
        boolean hungerModeElytraFoodEnabled = true;
        double hungerModeElytraFoodMultiplier = 0.5;
        double hungerModeElytraSpeedThresholdBps = 50.0;
        double hungerModeElytraNoRocketAfterSeconds = 45.0;
        boolean hungerModeElytraDamageEnabled = true;
        boolean hungerModeRocketResetsDamage = true;
        double groundWalkMax = AntiFlyConstants.DEFAULT_GROUND_WALK_MAX;
        double groundMountedMax = AntiFlyConstants.DEFAULT_GROUND_MOUNT_MAX;
        double vehicleFallMinDescent = AntiFlyConstants.VEHICLE_FALL_MIN_DESCENT;
        double vehicleFallMaxHorizontal = AntiFlyConstants.VEHICLE_FALL_MAX_HORIZONTAL;
        int vehicleFallTicksMax = AntiFlyConstants.VEHICLE_FALL_TICKS_MAX;
        double boatMaxHorizontal = AntiFlyConstants.BOAT_MAX_HORIZONTAL;
        int vehicleAirGraceTicks = 10;
        int boatAirGraceTicks = 0;
        int horseAirGraceTicks = 24;
        String modrinthProjectSlug = "antiflight";
        double airMax = AntiFlyConstants.DEFAULT_AIR_MAX;
        double airVerticalMax = AntiFlyConstants.DEFAULT_AIR_VERTICAL_MAX;
        double bufferDecay = 0.25;
        double horizontalBufferLimit = 3.0;
        double verticalBufferLimit = 2.0;
        double hoverBufferLimit = 3.0;
        boolean noFallDetectionEnabled = true;
        String alertMode = "both";
        long setbackCooldownMs = 500L;
        int airNonFallTicks = AntiFlyConstants.AIR_NON_FALL_TICKS;
        int sustainedAirTicksLimit = 150;
        double sustainedAirMinDescent = 40.0;
        int impulseGraceTicks = AntiFlyConstants.IMPULSE_GRACE_TICKS;
        int groundSpoofTicksLimit = AntiFlyConstants.GROUND_SPOOF_TICKS;
        int repeatOffenderAlertCount = 10;
        int antiKickWindowTicks = AntiFlyConstants.ANTI_KICK_WINDOW_TICKS;
        double antiKickMinDescent = AntiFlyConstants.ANTI_KICK_MIN_DESCENT;
        int voidFallTicks = AntiFlyConstants.VOID_FALL_TICKS;
        double waterMax = AntiFlyConstants.DEFAULT_WATER_MAX;
        double waterVerticalMax = AntiFlyConstants.DEFAULT_WATER_VERTICAL_MAX;
        boolean elytraChecksEnabled = true;
        double elytraMaxHorizontal = AntiFlyConstants.ELYTRA_MAX_HORIZONTAL;
        double elytraMaxUp = AntiFlyConstants.ELYTRA_MAX_UP;
        double elytraStallHorizontalMax = AntiFlyConstants.ELYTRA_STALL_HORIZONTAL_MAX;
        double elytraStallVerticalMax = AntiFlyConstants.ELYTRA_STALL_VERTICAL_MAX;
        int elytraStallTicks = AntiFlyConstants.ELYTRA_STALL_TICKS;
        int airGraceTicks = 4;
        int hoverStartTicks = AntiFlyConstants.MAX_AIR_TICKS;
        int hoverTicksLimit = AntiFlyConstants.HOVER_TICKS;
        double hoverDeltaY = AntiFlyConstants.HOVER_DELTA_Y_EPSILON;
        int elytraLandingGraceTicks = AntiFlyConstants.GLIDE_GROUND_GRACE_TICKS;
        int elytraBoostGraceTicks = 80;
        double elytraMovementBufferLimit = 6.0;
        boolean elytraDurabilityCheckEnabled = true;
        double elytraNoRocketMaxAscent = 6.0;
        int elytraSustainedClimbTicksLimit = AntiFlyConstants.ELYTRA_SUSTAINED_CLIMB_TICKS;
        String elytraVerifierMode = "enforce";
        com.antifly.common.ElytraPhysics.Tuning elytraVerifierTuning = new com.antifly.common.ElytraPhysics.Tuning();
        int elytraGlideSuppressionTicks = AntiFlyConstants.ELYTRA_GLIDE_SUPPRESSION_TICKS;
        double elytraRequiredDescentForPullup = 0.75;
        int elytraSlowdownGraceTicks = AntiFlyConstants.ELYTRA_SLOWDOWN_GRACE_TICKS;
        java.util.List<String> disabledWorlds = java.util.List.of();
        java.util.List<String> exempt = java.util.List.of();
        int configVersion = 0;

        // Bumped whenever a shipped default changes in a way existing configs
        // must inherit. Changing a default alone only affects new installs,
        // because Gson overwrites the field default with whatever the file
        // already stored. migrate() closes that gap. A stored value that is
        // still exactly an old default was never tuned by the operator, so it
        // is brought forward; anything else is a deliberate choice and is left
        // alone.
        static final int CONFIG_VERSION = 4;

        private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

        static AntiFlyConfig load() {
            Path path = getPath();
            if (Files.exists(path)) {
                try {
                    String json = Files.readString(path);
                    AntiFlyConfig cfg = GSON.fromJson(json, AntiFlyConfig.class);
                    if (cfg != null) {
                        if (cfg.elytraVerifierTuning == null) {
                            cfg.elytraVerifierTuning = new com.antifly.common.ElytraPhysics.Tuning();
                        }
                        if (!json.contains("\"vehicleAirGraceTicks\"")) {
                            cfg.vehicleAirGraceTicks = 10;
                        }
                        if (!json.contains("\"boatAirGraceTicks\"")) {
                            cfg.boatAirGraceTicks = 0;
                        }
                        if (!json.contains("\"boatMaxHorizontal\"")) {
                            cfg.boatMaxHorizontal = AntiFlyConstants.BOAT_MAX_HORIZONTAL;
                        }
                        if (!json.contains("\"elytraNoRocketMaxAscent\"")) {
                            cfg.elytraNoRocketMaxAscent = 6.0;
                        }
                        if (!json.contains("\"elytraRequiredDescentForPullup\"")) {
                            cfg.elytraRequiredDescentForPullup = 0.75;
                        }
                        if (!json.contains("\"horseAirGraceTicks\"")) {
                            cfg.horseAirGraceTicks = 24;
                        }
                        if (!json.contains("\"voidFallTicks\"")) {
                            cfg.voidFallTicks = AntiFlyConstants.VOID_FALL_TICKS;
                        }
                        boolean migrated = cfg.migrate();
                        cfg.disabledWorlds = cfg.disabledWorlds == null ? java.util.List.of()
                            : cfg.disabledWorlds.stream()
                                .map(name -> name == null ? "" : name.trim().toLowerCase(Locale.ROOT))
                                .filter(name -> !name.isEmpty())
                                .distinct()
                                .sorted()
                                .toList();
                        if (migrated) {
                            cfg.save();
                        }
                        return cfg;
                    }
                } catch (IOException ignored) {
                }
            }
            AntiFlyConfig cfg = new AntiFlyConfig();
            cfg.configVersion = CONFIG_VERSION;
            cfg.save();
            return cfg;
        }

        /**
         * Brings an older config forward. Every entry below is a value that
         * shipped as a default in an earlier release and was later raised
         * because it flagged behaviour vanilla players actually do. Older
         * installs keep the old number otherwise, which is how a perfectly
         * working elytra ends up rubber-banded on every glide.
         */
        private boolean migrate() {
            if (configVersion >= CONFIG_VERSION) {
                return false;
            }
            boolean changed = false;
            if (groundWalkMax == 0.49) {
                groundWalkMax = AntiFlyConstants.DEFAULT_GROUND_WALK_MAX;
                changed = true;
            }
            if (airVerticalMax == 0.756) {
                airVerticalMax = AntiFlyConstants.DEFAULT_AIR_VERTICAL_MAX;
                changed = true;
            }
            if (elytraMaxHorizontal == 2.6) {
                elytraMaxHorizontal = AntiFlyConstants.ELYTRA_MAX_HORIZONTAL;
                changed = true;
            }
            if (elytraMaxUp == 0.55) {
                elytraMaxUp = AntiFlyConstants.ELYTRA_MAX_UP;
                changed = true;
            }
            if (hungerModeHungerPerSecondAtMaxSpeed == 20.0) {
                hungerModeHungerPerSecondAtMaxSpeed = 10.0;
                changed = true;
            }
            // Reinterpreted twice: first a per-40-tick ascent allowance that could
            // never fire, then a net-altitude cap of 3.0 which real elytra play was
            // measured to exceed. 3.0 was a false-positive threshold, so raise it.
            if (elytraNoRocketMaxAscent == 3.0) {
                elytraNoRocketMaxAscent = 6.0;
                changed = true;
            }
            if (elytraNoRocketMaxAscent == 0.80) {
                elytraNoRocketMaxAscent = 6.0;
                changed = true;
            }
            configVersion = CONFIG_VERSION;
            if (changed) {
                LOGGER.info("AntiFly migrated config to version {} (raised defaults that flagged vanilla behaviour)",
                    CONFIG_VERSION);
            }
            return true;
        }

        void save() {
            Path path = getPath();
            try {
                Files.createDirectories(path.getParent());
                Files.writeString(path, GSON.toJson(this));
            } catch (IOException ignored) {
            }
        }

        private static Path getPath() {
            return FabricLoader.getInstance().getConfigDir().resolve("antifly.json");
        }
    }

    private static final class PlayerState {
        Vec3 lastGroundPos;
        Vec3 lastSupportPos;
        Vec3 lastVoidSafe;
        ServerLevel lastVoidSafeLevel;
        int voidDamageTicks = -1;
        Vec3 lastPos;
        double airHorizontalBuffer;
        double airVerticalBuffer;
        double hoverBuffer;
        double antiKickBuffer;
        double elytraMovementBuffer;
        int airTicks;
        int airNonFallTicks;
        int airSessionTicks;
        double airSessionDescent;
        int hoverTicks;
        int voidTicks;
        int glideStallTicks;
        int groundSpoofTicks;
        Vec3 lastVelocity;
        int impulseGraceTicks;
        int failCount;
        long lastFailMs;
        long lastRepeatAlertMs;
        int glideGroundGraceTicks;
        int glideSlowdownGraceTicks;
        double lastGlideHorizontal;
        boolean wasGliding;
        boolean lastServerOnGround;
        int vehicleGraceTicks;
        int vehicleAirTicks;
        int vehicleFallTicks;
        double vehicleFallHorizontalDistance;
        boolean wasInVehicle;
        Entity lastVehicle;
        Vec3 lastVehiclePos;
        long lastRubberBandAtMs;
        int lastFireworkUses = -1;
        int rocketGraceTicks;
        double hungerDebt;
        int flightAirborneTicks;
        int lastFlightDamageAtTicks = -1;
        int teleportGraceTicks;
        int sustainedAirTicks;
        double sustainedAirStartY;
        int glideNoRocketTicks;
        int glideRocketGraceTicks;
        int glideHoverTicks;
        int glideControlTicks;
        int glideSuppressTicks;
        int glideSustainedClimbTicks;
        final com.antifly.common.ElytraPhysics elytraVerifier = new com.antifly.common.ElytraPhysics();
        final com.antifly.common.ElytraPhysics hungerElytraVerifier = new com.antifly.common.ElytraPhysics();
        final com.antifly.common.ElytraPhysics.Input hungerElytraInput = new com.antifly.common.ElytraPhysics.Input();
        final com.antifly.common.ElytraPhysics.Input elytraInput = new com.antifly.common.ElytraPhysics.Input();
        double elytraNetAltitude;
        int hungerDrainTicks;
    }
}
