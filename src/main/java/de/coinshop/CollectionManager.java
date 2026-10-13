package de.coinshop;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Kollektionen: Wer ALLE Items einer Kollektion (z. B. alle Schallplatten) schon einmal besessen hat,
 * bekommt eine Belohnung - einmalig.
 *
 * "Besessen" heißt: das Item war in deinem Inventar oder deiner Endertruhe, oder Minecraft hat gezählt,
 * dass du es aufgesammelt oder hergestellt hast (Statistik). Du musst die Items also nicht behalten.
 *
 * Die Kollektionen stehen in der config.yml unter "collections".
 */
public final class CollectionManager {

    /** Ein Eintrag einer Kollektion: ein Item (key = Materialname) oder eine Kreatur (key = "ENTITY:NAME"). */
    public record Entry(String key, Material icon, EntityType entityType, String label) {
        /** Kurzform ohne eigenen Namen. */
        public Entry(String key, Material icon, EntityType entityType) {
            this(key, icon, entityType, null);
        }
    }

    public record Collection(String id, String name, Material icon, long reward, List<Entry> entries) { }

    private final CoinShopPlugin plugin;
    private Map<String, Collection> collections = new LinkedHashMap<>();
    private Set<Material> tracked = new HashSet<>();

    public CollectionManager(CoinShopPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public java.util.Collection<Collection> all() {
        return collections.values();
    }

    public Collection get(String id) {
        return collections.get(id);
    }

    /**
     * Liest die Kollektionen aus der config.yml. Muster mit * (z. B. "MUSIC_DISC_*") werden aufgelöst;
     * Einträge mit "entity:" davor (z. B. "entity:ZOMBIE") sind Kreaturen.
     */
    public void reload() {
        Map<String, Collection> loaded = new LinkedHashMap<>();
        Set<Material> all = new HashSet<>();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("collections");
        if (section != null) {
            for (String id : section.getKeys(false)) {
                ConfigurationSection c = section.getConfigurationSection(id);
                if (c == null) {
                    continue;
                }
                Map<String, Entry> entries = new LinkedHashMap<>();
                for (String pattern : c.getStringList("items")) {
                    expand(pattern.trim(), entries);
                }
                if (entries.isEmpty()) {
                    plugin.getLogger().warning("Kollektion '" + id + "' hat keine gültigen Einträge und wird ignoriert.");
                    continue;
                }
                Material icon = Material.matchMaterial(c.getString("icon", ""));
                if (icon == null) {
                    icon = entries.values().iterator().next().icon();
                }
                loaded.put(id, new Collection(id, c.getString("name", id), icon,
                        Math.max(0, c.getLong("reward", 0)), new ArrayList<>(entries.values())));
                for (Entry e : entries.values()) {
                    if (e.entityType() == null) {
                        all.add(e.icon());
                    }
                }
            }
        }
        collections = loaded;
        tracked = all;
    }

    private static void expand(String pattern, Map<String, Entry> out) {
        if (pattern.isEmpty()) {
            return;
        }
        if (pattern.toLowerCase().startsWith("entity:")) {
            try {
                EntityType type = EntityType.valueOf(pattern.substring(7).trim().toUpperCase());
                Material egg = Material.matchMaterial(type.name() + "_SPAWN_EGG");
                out.put(entityKey(type), new Entry(entityKey(type), egg == null ? Material.PAPER : egg, type));
            } catch (IllegalArgumentException ignored) {
                // diese Kreatur gibt es in dieser Version nicht
            }
            return;
        }
        if (pattern.equalsIgnoreCase("axolotl:*")) {
            // Alle Axolotl-Farben (zählt, wenn man einen Axolotl-Eimer der Farbe hatte oder so einen Axolotl gesehen/getötet hat)
            for (org.bukkit.entity.Axolotl.Variant v : org.bukkit.entity.Axolotl.Variant.values()) {
                String name = switch (v) {
                    case LUCY -> "Rosa Axolotl (Lucy)";
                    case WILD -> "Brauner Axolotl";
                    case GOLD -> "Goldener Axolotl";
                    case CYAN -> "Cyan-Axolotl";
                    case BLUE -> "Blauer Axolotl (selten)";
                };
                out.put(axolotlKey(v), new Entry(axolotlKey(v), Material.AXOLOTL_BUCKET, null, name));
            }
            return;
        }
        if (!pattern.contains("*")) {
            Material m = Material.matchMaterial(pattern);
            if (m != null && m.isItem() && !m.isLegacy()) {
                out.put(m.name(), new Entry(m.name(), m, null));
            }
            return;
        }
        String regex = java.util.regex.Pattern.quote(pattern).replace("*", "\\E.*\\Q");
        for (Material m : Material.values()) {
            if (!m.isLegacy() && m.isItem() && m.name().matches(regex)) {
                out.put(m.name(), new Entry(m.name(), m, null));
            }
        }
    }

    public static String axolotlKey(org.bukkit.entity.Axolotl.Variant variant) {
        return "AXOLOTL:" + variant.name();
    }

    public static String entityKey(EntityType type) {
        return "ENTITY:" + type.name();
    }

    // ------------------------------------------------------------------
    //  Einsammeln prüfen
    // ------------------------------------------------------------------

    /** Prüft, welche Items der Spieler hat/hatte, speichert neue und zahlt fertige Kollektionen aus. */
    public void scan(Player player) {
        if (collections.isEmpty()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        Database db = plugin.database();
        try {
            Set<String> known = db.getCollected(uuid);
            Set<String> fresh = new HashSet<>();

            // 1. Was liegt gerade im Inventar / in der Endertruhe?
            for (ItemStack stack : player.getInventory().getContents()) {
                note(stack, known, fresh);
            }
            for (ItemStack stack : player.getEnderChest().getContents()) {
                note(stack, known, fresh);
            }
            note(player.getItemOnCursor(), known, fresh);

            // 2. Was hat Minecraft als aufgesammelt oder hergestellt gezählt?
            for (Material m : tracked) {
                if (known.contains(m.name()) || fresh.contains(m.name())) {
                    continue;
                }
                if (stat(player, Statistic.PICKUP, m) > 0 || stat(player, Statistic.CRAFT_ITEM, m) > 0) {
                    fresh.add(m.name());
                }
            }
            if (!fresh.isEmpty()) {
                db.addCollected(uuid, fresh);
                known.addAll(fresh);
            }

            // 3. Ist eine Kollektion fertig?
            checkCompletion(player, known);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Kollektionen von " + player.getName() + " konnten nicht geprüft werden: " + e);
        }
    }

    /** Zahlt jede Kollektion aus, deren Einträge alle gesammelt sind (und die noch nicht belohnt wurde). */
    private void checkCompletion(Player player, Set<String> known) {
        Set<String> done = plugin.database().getDoneCollections(player.getUniqueId());
        for (Collection c : collections.values()) {
            if (done.contains(c.id())) {
                continue;
            }
            boolean complete = true;
            for (Entry e : c.entries()) {
                if (!known.contains(e.key())) {
                    complete = false;
                    break;
                }
            }
            if (complete) {
                complete(player, c);
            }
        }
    }

    // ------------------------------------------------------------------
    //  Kreaturen: getötet oder durch das Fernglas angesehen
    // ------------------------------------------------------------------

    /** Merkt sich pro Spieler, welche Kreaturen er schon eingetragen hat (spart Datenbank-Abfragen). */
    private final Map<UUID, Set<String>> seenCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** Der Spieler hat diese Kreatur getötet oder durch ein Fernglas angesehen. */
    public void noteEntity(Player player, EntityType type) {
        if (collections.isEmpty() || type == EntityType.PLAYER) {
            return;
        }
        String key = entityKey(type);
        UUID uuid = player.getUniqueId();
        try {
            Set<String> seen = seenCache.computeIfAbsent(uuid, u -> {
                Set<String> set = new HashSet<>();
                for (String k : plugin.database().getCollected(u)) {
                    if (k.startsWith("ENTITY:")) {
                        set.add(k);
                    }
                }
                return set;
            });
            if (!seen.add(key)) {
                return; // schon eingetragen
            }
            plugin.database().addCollected(uuid, List.of(key));
            checkCompletion(player, plugin.database().getCollected(uuid));
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Kreatur konnte nicht eingetragen werden: " + e);
        }
    }

    /** Wie noteEntity(Typ), trägt bei Axolotl zusätzlich die Farbe ein. */
    public void noteEntity(Player player, org.bukkit.entity.Entity entity) {
        noteEntity(player, entity.getType());
        if (entity instanceof org.bukkit.entity.Axolotl axolotl && !collections.isEmpty()) {
            try {
                String key = axolotlKey(axolotl.getVariant());
                Set<String> known = plugin.database().getCollected(player.getUniqueId());
                if (!known.contains(key)) {
                    plugin.database().addCollected(player.getUniqueId(), List.of(key));
                    known.add(key);
                    checkCompletion(player, known);
                }
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Axolotl-Farbe konnte nicht eingetragen werden: " + e);
            }
        }
    }

    public void forget(UUID uuid) {
        seenCache.remove(uuid);
    }

    private void note(ItemStack stack, Set<String> known, Set<String> fresh) {
        if (stack == null || stack.getType().isAir() || !tracked.contains(stack.getType())) {
            return;
        }
        // Axolotl-Eimer: die Farbe steckt im Item
        if (stack.getType() == Material.AXOLOTL_BUCKET
                && stack.getItemMeta() instanceof org.bukkit.inventory.meta.AxolotlBucketMeta bucket
                && bucket.hasVariant()) {
            String key = axolotlKey(bucket.getVariant());
            if (!known.contains(key)) {
                fresh.add(key);
            }
        }
        String name = stack.getType().name();
        if (!known.contains(name)) {
            fresh.add(name);
        }
    }

    private static int stat(Player player, Statistic statistic, Material material) {
        try {
            return player.getStatistic(statistic, material);
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }

    private void complete(Player player, Collection c) {
        Database db = plugin.database();
        db.begin();
        try {
            if (!db.markCollectionDone(player.getUniqueId(), c.id())) {
                db.rollback();
                return;
            }
            if (c.reward() > 0) {
                db.addCoins(player.getUniqueId(), c.reward());
            }
            db.commit();
        } catch (RuntimeException e) {
            db.rollback();
            plugin.getLogger().warning("Kollektions-Belohnung fehlgeschlagen: " + e);
            return;
        }
        db.logTx(player.getUniqueId(), player.getName(), "kollektion", c.reward(), c.id());
        Messages msg = plugin.messages();
        msg.send(player, "collection-complete", "name", c.name(), "coins", c.reward());
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.equals(player)) {
                msg.send(online, "collection-broadcast", "player", player.getName(), "name", c.name());
            }
        }
        player.playSound(player.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
    }

    public void scanAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            scan(player);
        }
    }

    /** Wie viele Items der Kollektion hat der Spieler schon? */
    public int progress(Collection c, Set<String> known) {
        int n = 0;
        for (Entry e : c.entries()) {
            if (known.contains(e.key())) {
                n++;
            }
        }
        return n;
    }
}
