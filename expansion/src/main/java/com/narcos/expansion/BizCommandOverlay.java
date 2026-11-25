package com.narcos.expansion;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Заменяет только текст /biz по умолчанию, сохраняя дочерние команды основного мода.
 * Связь с NarcosState выполняется через reflection, поэтому JAR дополнения остаётся отдельным.
 */
final class BizCommandOverlay {
    private static final String[] COLORS = {"красный", "синий", "зелёный", "чёрный"};

    private BizCommandOverlay() {
    }

    static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        CommandNode<ServerCommandSource> original = dispatcher.getRoot().getChild("biz");

        LiteralCommandNode<ServerCommandSource> replacement = CommandManager.literal("biz")
                .executes(context -> showStatus(context.getSource().getPlayerOrThrow()))
                .build();

        // Не теряем /biz hire courier, /biz hire artist и /biz color из основного мода.
        if (original != null) {
            for (CommandNode<ServerCommandSource> child : original.getChildren()) {
                replacement.addChild(child);
            }
        }

        replacement.addChild(CommandManager.literal("location")
                .executes(context -> {
                    SettlementLayout.sendInfo(context.getSource().getPlayerOrThrow());
                    return 1;
                }).build());

        dispatcher.getRoot().removeCommand("biz");
        dispatcher.getRoot().addChild(replacement);
    }

    private static int showStatus(ServerPlayerEntity player) {
        SettlementLayout.sendInfo(player);
        try {
            MinecraftServer server = player.getServerWorld().getServer();
            Class<?> stateClass = Class.forName("com.narcos.NarcosState");
            Method of = stateClass.getMethod("of", MinecraftServer.class);
            Object state = of.invoke(null, server);
            Object data = stateClass.getMethod("get", UUID.class).invoke(state, player.getUuid());

            Class<?> dataClass = data.getClass();
            int heat = intField(dataClass, data, "heat");
            int record = intField(dataClass, data, "record");
            int couriers = intField(dataClass, data, "couriers");
            int artists = intField(dataClass, data, "artists");
            int color = intField(dataClass, data, "color");
            String colorName = COLORS[Math.max(0, Math.min(COLORS.length - 1, color))];

            player.sendMessage(Text.literal("=== Твой бизнес ===").formatted(Formatting.GOLD), false);
            player.sendMessage(Text.literal("Розыск: " + heat + "/100 | Судимостей: " + record), false);
            player.sendMessage(Text.literal("Курьеры: " + couriers + "/8 | Граффитчики: " + artists + "/10"), false);
            player.sendMessage(Text.literal("Цвет банды: " + colorName), false);
            player.sendMessage(Text.literal("Найм: /biz hire courier или /biz hire artist")
                    .formatted(Formatting.GRAY), false);
            player.sendMessage(Text.literal("Смена цвета: /biz color red|blue|green|black")
                    .formatted(Formatting.GRAY), false);
        } catch (Exception exception) {
            player.sendMessage(Text.literal("Не удалось прочитать данные бизнеса. Проверь, что основной Narcos: Dealer установлен.")
                    .formatted(Formatting.RED), false);
        }
        return 1;
    }

    private static int intField(Class<?> dataClass, Object data, String name) throws ReflectiveOperationException {
        Field field = dataClass.getField(name);
        return field.getInt(data);
    }
}
