package com.narcos.expansion;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Persistent addon data: lab ownership, scientists and generated regions. */
public final class ExpansionState extends PersistentState {
    private final Map<String, Integer> scientistsByLab = new HashMap<>();
    private final Map<String, UUID> labOwners = new HashMap<>();
    private final Set<String> builtRegions = new HashSet<>();

    public static ExpansionState of(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager()
                .getOrCreate(ExpansionState::fromNbt, ExpansionState::new, "narcos_expansion");
    }

    public UUID claimOrGetLabOwner(String labKey, UUID requestedOwner) {
        UUID owner = labOwners.get(labKey);
        if (owner == null) {
            labOwners.put(labKey, requestedOwner);
            markDirty();
            return requestedOwner;
        }
        return owner;
    }

    public int scientistsAt(String labKey) {
        return scientistsByLab.getOrDefault(labKey, 0);
    }

    public int addScientist(String labKey) {
        int count = scientistsAt(labKey) + 1;
        scientistsByLab.put(labKey, count);
        markDirty();
        return count;
    }

    /** Compatibility helpers for worlds created by the first addon prototype. */
    public int scientistsAt(long oldBenchPos) {
        return scientistsAt(Long.toString(oldBenchPos));
    }

    public int addScientist(long oldBenchPos) {
        return addScientist(Long.toString(oldBenchPos));
    }

    public boolean hasBuilt(String key) {
        return builtRegions.contains(key);
    }

    public void markBuilt(String key) {
        if (builtRegions.add(key)) markDirty();
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        NbtList labs = new NbtList();
        for (Map.Entry<String, Integer> entry : scientistsByLab.entrySet()) {
            NbtCompound lab = new NbtCompound();
            lab.putString("key", entry.getKey());
            lab.putInt("scientists", entry.getValue());
            UUID owner = labOwners.get(entry.getKey());
            if (owner != null) lab.putString("owner", owner.toString());
            labs.add(lab);
        }
        for (Map.Entry<String, UUID> entry : labOwners.entrySet()) {
            if (scientistsByLab.containsKey(entry.getKey())) continue;
            NbtCompound lab = new NbtCompound();
            lab.putString("key", entry.getKey());
            lab.putInt("scientists", 0);
            lab.putString("owner", entry.getValue().toString());
            labs.add(lab);
        }
        nbt.put("labs", labs);

        NbtList regions = new NbtList();
        for (String region : builtRegions) regions.add(NbtString.of(region));
        nbt.put("built_regions", regions);
        return nbt;
    }

    public static ExpansionState fromNbt(NbtCompound nbt) {
        ExpansionState state = new ExpansionState();

        if (nbt.contains("labs", NbtElement.LIST_TYPE)) {
            NbtList labs = nbt.getList("labs", NbtElement.COMPOUND_TYPE);
            for (int i = 0; i < labs.size(); i++) {
                NbtCompound lab = labs.getCompound(i);
                String key = lab.getString("key");
                if (key.isEmpty()) continue;
                int scientists = Math.max(0, lab.getInt("scientists"));
                if (scientists > 0) state.scientistsByLab.put(key, scientists);
                String owner = lab.getString("owner");
                if (!owner.isEmpty()) {
                    try {
                        state.labOwners.put(key, UUID.fromString(owner));
                    } catch (IllegalArgumentException ignored) {
                        // Ignore malformed owner entries from manually edited world data.
                    }
                }
            }
        }

        if (nbt.contains("built_regions", NbtElement.LIST_TYPE)) {
            NbtList regions = nbt.getList("built_regions", NbtElement.STRING_TYPE);
            for (int i = 0; i < regions.size(); i++) {
                state.builtRegions.add(regions.getString(i));
            }
        }

        // Read data written by the first prototype so existing generated buildings survive upgrades.
        for (String key : nbt.getKeys()) {
            try {
                if (key.startsWith("scientists_")) {
                    String oldKey = key.substring("scientists_".length());
                    state.scientistsByLab.putIfAbsent(oldKey, Math.max(0, nbt.getInt(key)));
                } else if (key.startsWith("built_") && nbt.getBoolean(key)) {
                    state.builtRegions.add(key.substring("built_".length()));
                }
            } catch (NumberFormatException ignored) {
                // Ignore malformed legacy entries.
            }
        }
        return state;
    }
}
