package com.narcos;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.narcos.expansion.SettlementLayout;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Расширяет команду основного мода, не меняя его исходники.
 * Базовые команды найма и настройки банды сохраняют прежнюю механику.
 */
public final class BizCommandExtension {
    private BizCommandExtension() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.getRoot().removeCommand("biz");

        LiteralArgumentBuilder<ServerCommandSource> biz = CommandManager.literal("biz")
                .executes(context -> showStatus(context.getSource().getPlayerOrThrow()));

        biz.then(CommandManager.literal("hire")
                .then(CommandManager.literal("courier")
                        .executes(context -> Game.hire(context.getSource().getPlayerOrThrow(), true)))
                .then(CommandManager.literal("artist")
                        .executes(context -> Game.hire(context.getSource().getPlayerOrThrow(), false))));

        LiteralArgumentBuilder<ServerCommandSource> color = CommandManager.literal("color");
        String[] ids = {"red", "blue", "green", "black"};
        for (int i = 0; i < ids.length; i++) {
            final int index = i;
            color.then(CommandManager.literal(ids[i])
                    .executes(context -> Game.setColor(context.getSource().getPlayerOrThrow(), index)));
        }
        biz.then(color);

        biz.then(CommandManager.literal("location")
                .executes(context -> {
                    SettlementLayout.sendInfo(context.getSource().getPlayerOrThrow());
                    return 1;
                }));

        dispatcher.register(biz);
    }

    private static int showStatus(ServerPlayerEntity player) {
        NarcosState.PlayerData data = NarcosState.of(player.getServer()).get(player.getUuid());

        SettlementLayout.sendInfo(player);
        player.sendMessage(Text.literal("=== Твой бизнес ===").formatted(Formatting.GOLD), false);
        player.sendMessage(Text.literal("Розыск: " + data.heat + "/100 | Судимостей: " + data.record), false);
        player.sendMessage(Text.literal("Доставщики: " + data.couriers + "/" + Game.COURIER_MAX
                + " | Граффитчики: " + data.artists + "/" + Game.ARTIST_MAX), false);
        player.sendMessage(Text.literal("Цвет банды: " + Game.COLOR_NAMES[data.color]
                + " — " + Game.COLOR_DESC[data.color]), false);
        player.sendMessage(Text.literal("Найм: /biz hire courier (" + Game.COURIER_COST
                + " изумрудов), /biz hire artist (" + Game.ARTIST_COST + " изумрудов)").formatted(Formatting.GRAY), false);
        player.sendMessage(Text.literal("Смена цвета: /biz color red|blue|green|black ("
                + Game.COLOR_COST + " изумрудов)").formatted(Formatting.GRAY), false);
        return 1;
    }
}
