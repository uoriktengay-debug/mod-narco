package com.narcos;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Сохраняемые данные игроков: розыск, бизнес, тюрьма. */
public class NarcosState extends PersistentState {
    public static class PlayerData {
        public int heat, couriers, artists, color, prevMode, record, squatsNeeded, bailCost;
        public long jailUntil;
        public double rx, ry, rz;
        public String rdim = "minecraft:overworld";
    }

    private final Map<UUID, PlayerData> map = new HashMap<>();

    public PlayerData get(UUID id) {
        return map.computeIfAbsent(id, k -> new PlayerData());
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        for (Map.Entry<UUID, PlayerData> e : map.entrySet()) {
            PlayerData d = e.getValue();
            NbtCompound c = new NbtCompound();
            c.putInt("heat", d.heat);
            c.putInt("couriers", d.couriers);
            c.putInt("artists", d.artists);
            c.putInt("color", d.color);
            c.putInt("prevMode", d.prevMode);
            c.putInt("record", d.record);
            c.putInt("squatsNeeded", d.squatsNeeded);
            c.putInt("bailCost", d.bailCost);
            c.putLong("jailUntil", d.jailUntil);
            c.putDouble("rx", d.rx);
            c.putDouble("ry", d.ry);
            c.putDouble("rz", d.rz);
            c.putString("rdim", d.rdim);
            nbt.put(e.getKey().toString(), c);
        }
        return nbt;
    }

    public static NarcosState fromNbt(NbtCompound nbt) {
        NarcosState s = new NarcosState();
        for (String k : nbt.getKeys()) {
            NbtCompound c = nbt.getCompound(k);
            PlayerData d = new PlayerData();
            d.heat = c.getInt("heat");
            d.couriers = c.getInt("couriers");
            d.artists = c.getInt("artists");
            d.color = c.getInt("color");
            d.prevMode = c.getInt("prevMode");
            d.record = c.getInt("record");
            d.squatsNeeded = c.getInt("squatsNeeded");
            d.bailCost = c.getInt("bailCost");
            d.jailUntil = c.getLong("jailUntil");
            d.rx = c.getDouble("rx");
            d.ry = c.getDouble("ry");
            d.rz = c.getDouble("rz");
            d.rdim = c.getString("rdim");
            s.map.put(UUID.fromString(k), d);
        }
        return s;
    }

    public static NarcosState of(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager()
                .getOrCreate(NarcosState::fromNbt, NarcosState::new, "narcos");
    }
}
