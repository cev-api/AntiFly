package com.antifly.fabric.mixin;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;

import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.NameAndId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class CommandSuggestionMixin {
    @Shadow public ServerPlayer player;



    @Inject(method = "handleCustomCommandSuggestions", at = @At("HEAD"), cancellable = true)
    private void antifly$filterSuggestions(ServerboundCommandSuggestionPacket packet, CallbackInfo ci) {
        if (player.level().getServer().getPlayerList().isOp(new NameAndId(player.getGameProfile()))) {
            return;
        }

        StringReader reader = new StringReader(packet.command());
        if (reader.canRead() && reader.peek() == '/') {
            reader.skip();
        }
        CommandSourceStack source = player.createCommandSourceStack();
        var dispatcher = player.level().getServer().getCommands().getDispatcher();
        var parse = dispatcher.parse(reader, source);
        dispatcher.getCompletionSuggestions(parse).thenAccept(suggestions -> {
            List<Suggestion> filtered = new ArrayList<>();
            for (Suggestion suggestion : suggestions.getList()) {
                if (!suggestion.getText().equals("antifly")) {
                    filtered.add(suggestion);
                }
            }
            player.connection.send(new ClientboundCommandSuggestionsPacket(packet.id(),
                new Suggestions(suggestions.getRange(), filtered)));
        });
        ci.cancel();
    }
}