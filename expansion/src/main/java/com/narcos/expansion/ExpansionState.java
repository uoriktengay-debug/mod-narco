package com.narcos.expansion;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class ExpansionState extends PersistentState {
    private final Map<Long, Integer> scientistsByBench = new HashMap<>();
    private final Set<String> builtRegions = new HashSet<>();

    public static ExpansionState of(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager()
                .getOrCreate(ExpansionState::fromNbt, ExpansionState::new, "narcos_expansion");
    }

    public int scientistsAt(long benchPos) {
        return scientistsByBench.getOrDefault(benchPos, 0);
    }

    public int addScientist(long benchPos) {
        int count = scientistsAt(benchPos) + 1;
        scientistsByBench.put(benchPos, count);
        markDirty();
        return count;
    }

    public boolean hasBuilt(String key) {
        return builtRegions.contains(key);
    }

    public void markBuilt(String key) {
        builtRegions.add(key);
        markDirty();
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        for (Map.Entry<Long, Integer> entry : scientistsByBench.entrySet()) {
            nbt.putInt("scientists_" + entry.getKey(), entry.getValue());
        }
        for (String key : builtRegions) {
            nbt.putBoolean("built_" + key, true);
        }
        return nbt;
    }

    public static ExpansionState fromNbt(NbtCompound nbt) {
        ExpansionState state = new ExpansionState();
        for (String key : nbt.getKeys()) {
            try {
                if (key.startsWith("scientists_")) {
                    long pos = Long.parseLong(key.substring("scientists_".length()));
                    state.scientistsByBench.put(pos, nbt.getInt(key));
                } else if (key.startsWith("built_") && nbt.getBoolean(key)) {
                    state.builtRegions.add(key.substring("built_".length()));
                }
            } catch (NumberFormatException ignored) {
                // Ignore malformed entries if the world data was edited manually.
            }
        }
        return state;
    }
}
