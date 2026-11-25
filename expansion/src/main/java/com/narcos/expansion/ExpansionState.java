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

/** Persistent addon data: lab ownership, scientists, generated regions and sales competition. */
public final class ExpansionState extends PersistentState {
    private final Map<String, Integer> scientistsByLab = new HashMap<>();
    private final Map<String, UUID> labOwners = new HashMap<>();
    private final Set<String> builtRegions = new HashSet<>();

    private final Map<UUID, Integer> salesByPlayer = new HashMap<>();
    private final Map<UUID, Map<UUID, Integer>> salesByCustomer = new HashMap<>();
    private final Map<UUID, UUID> preferredSupplierByCustomer = new HashMap<>();

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

    public int salesForPlayer(UUID playerId) {
        return salesByPlayer.getOrDefault(playerId, 0);
    }

    /**
     * Records units successfully sold to a specific villager.
     * A customer starts preferring a supplier at 5 sold units. To take a customer
     * from an existing supplier, a competitor must beat that supplier by 5 units.
     *
     * @return true only when the customer's preferred supplier changed to seller.
     */
    public boolean recordSale(UUID seller, UUID customer, int units) {
        if (units <= 0) return false;

        salesByPlayer.merge(seller, units, Integer::sum);
        Map<UUID, Integer> customerScores =
                salesByCustomer.computeIfAbsent(customer, ignored -> new HashMap<>());
        int sellerScore = customerScores.merge(seller, units, Integer::sum);

        UUID incumbent = preferredSupplierByCustomer.get(customer);
        boolean converted = false;
        if (incumbent == null) {
            if (sellerScore >= 5) {
                preferredSupplierByCustomer.put(customer, seller);
                converted = true;
            }
        } else if (!incumbent.equals(seller)) {
            int incumbentScore = customerScores.getOrDefault(incumbent, 0);
            if (sellerScore >= incumbentScore + 5) {
                preferredSupplierByCustomer.put(customer, seller);
                converted = true;
            }
        }

        markDirty();
        return converted;
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

        NbtList playerSales = new NbtList();
        for (Map.Entry<UUID, Integer> entry : salesByPlayer.entrySet()) {
            NbtCompound sale = new NbtCompound();
            sale.putString("player", entry.getKey().toString());
            sale.putInt("units", entry.getValue());
            playerSales.add(sale);
        }
        nbt.put("player_sales", playerSales);

        NbtList customerSales = new NbtList();
        for (Map.Entry<UUID, Map<UUID, Integer>> customerEntry : salesByCustomer.entrySet()) {
            NbtCompound customer = new NbtCompound();
            customer.putString("customer", customerEntry.getKey().toString());

            UUID preferred = preferredSupplierByCustomer.get(customerEntry.getKey());
            if (preferred != null) customer.putString("preferred", preferred.toString());

            NbtList scores = new NbtList();
            for (Map.Entry<UUID, Integer> score : customerEntry.getValue().entrySet()) {
                NbtCompound item = new NbtCompound();
                item.putString("player", score.getKey().toString());
                item.putInt("units", score.getValue());
                scores.add(item);
            }
            customer.put("scores", scores);
            customerSales.add(customer);
        }
        nbt.put("customer_sales", customerSales);

        // Persist customers that have a preferred supplier but no score entry (defensive).
        NbtList preferredOnly = new NbtList();
        for (Map.Entry<UUID, UUID> entry : preferredSupplierByCustomer.entrySet()) {
            if (salesByCustomer.containsKey(entry.getKey())) continue;
            NbtCompound customer = new NbtCompound();
            customer.putString("customer", entry.getKey().toString());
            customer.putString("preferred", entry.getValue().toString());
            customer.put("scores", new NbtList());
            preferredOnly.add(customer);
        }
        if (!preferredOnly.isEmpty()) {
            for (int i = 0; i < preferredOnly.size(); i++) customerSales.add(preferredOnly.getCompound(i));
        }
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

        if (nbt.contains("player_sales", NbtElement.LIST_TYPE)) {
            NbtList playerSales = nbt.getList("player_sales", NbtElement.COMPOUND_TYPE);
            for (int i = 0; i < playerSales.size(); i++) {
                NbtCompound sale = playerSales.getCompound(i);
                try {
                    UUID player = UUID.fromString(sale.getString("player"));
                    state.salesByPlayer.put(player, Math.max(0, sale.getInt("units")));
                } catch (IllegalArgumentException ignored) {
                    // Ignore invalid player UUID.
                }
            }
        }

        if (nbt.contains("customer_sales", NbtElement.LIST_TYPE)) {
            NbtList customers = nbt.getList("customer_sales", NbtElement.COMPOUND_TYPE);
            for (int i = 0; i < customers.size(); i++) {
                NbtCompound customer = customers.getCompound(i);
                try {
                    UUID customerId = UUID.fromString(customer.getString("customer"));
                    String preferred = customer.getString("preferred");
                    if (!preferred.isEmpty()) {
                        state.preferredSupplierByCustomer.put(customerId, UUID.fromString(preferred));
                    }

                    Map<UUID, Integer> scores = new HashMap<>();
                    NbtList entries = customer.getList("scores", NbtElement.COMPOUND_TYPE);
                    for (int j = 0; j < entries.size(); j++) {
                        NbtCompound entry = entries.getCompound(j);
                        UUID player = UUID.fromString(entry.getString("player"));
                        scores.put(player, Math.max(0, entry.getInt("units")));
                    }
                    if (!scores.isEmpty()) state.salesByCustomer.put(customerId, scores);
                } catch (IllegalArgumentException ignored) {
                    // Ignore invalid customer or supplier UUID.
                }
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
