package de.coinshop;

import de.coinshop.Database.Offer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Lost die 10 Angebote des Server-Shops aus:
 *  - normale Items (jedes Item, das es im Survival gibt - Sperrliste und Preise stehen in der config.yml)
 *  - verzauberte Rüstungsteile, Werkzeuge und Waffen
 *  - Verzauberungsbücher
 */
public final class ShopItemGenerator {

    // Verzauberungen, die als Buch im Shop auftauchen können (Namen wie im Spiel, ohne "minecraft:").
    // Namen, die es in deiner Version nicht gibt, werden automatisch übersprungen.
    private static final List<String> BOOK_ENCHANTS = List.of(
            "protection", "fire_protection", "feather_falling", "blast_protection", "projectile_protection",
            "respiration", "aqua_affinity", "thorns", "depth_strider", "frost_walker", "soul_speed",
            "swift_sneak", "sharpness", "smite", "bane_of_arthropods", "knockback", "fire_aspect",
            "looting", "sweeping_edge", "efficiency", "silk_touch", "unbreaking", "fortune", "power",
            "punch", "flame", "infinity", "luck_of_the_sea", "lure", "loyalty", "impaling", "riptide",
            "channeling", "multishot", "quick_charge", "piercing", "mending", "density", "breach",
            "wind_burst");

    private final CoinShopPlugin plugin;
    private final Random random = new Random();

    // Werden bei jedem Auslosen neu aus der config.yml gelesen
    private final Map<String, Long> priceRules = new LinkedHashMap<>();
    private List<String> excludeRules = new ArrayList<>();
    // Items, die immer nur einzeln angeboten werden (z. B. Karten)
    private List<String> singleRules = new ArrayList<>();
    // Wie stark Verzauberungen je nach Material zählen (Leder billig, Netherit voll)
    private final Map<String, Double> materialFactors = new LinkedHashMap<>();
    private long defaultPrice = 3;

    public ShopItemGenerator(CoinShopPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    //  Hauptmethode
    // ------------------------------------------------------------------

    public List<Offer> generate() {
        FileConfiguration cfg = plugin.getConfig();
        loadRules(cfg);

        int count = cfg.getInt("server-shop.offers", 10);
        double armorChance = cfg.getDouble("server-shop.enchanted-armor-chance", 0.12);
        double toolChance = cfg.getDouble("server-shop.enchanted-tool-chance", 0.12);
        double bookChance = cfg.getDouble("server-shop.enchanted-book-chance", 0.18);
        double rarityDivisor = Math.max(1.0, cfg.getDouble("server-shop.rarity-divisor", 200));

        List<Material> candidates = new ArrayList<>();
        List<Material> armorPieces = new ArrayList<>();
        List<Material> toolPieces = new ArrayList<>();   // Werkzeuge und Waffen
        for (Material material : Material.values()) {
            if (!isAllowed(material)) {
                continue;
            }
            candidates.add(material);
            String name = material.name();
            if (name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE")
                    || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS")) {
                armorPieces.add(material);
            } else if (isTool(name)) {
                toolPieces.add(material);
            }
        }

        // Gewichte: teure Items erscheinen seltener
        double[] cumulative = new double[candidates.size()];
        double sum = 0;
        for (int i = 0; i < candidates.size(); i++) {
            sum += 1.0 / (1.0 + unitPrice(candidates.get(i)) / rarityDivisor);
            cumulative[i] = sum;
        }

        Set<String> used = new HashSet<>();
        List<Offer> offers = new ArrayList<>();
        int attempts = 0;
        while (offers.size() < count && attempts++ < 500) {
            double roll = random.nextDouble();
            Offer offer;
            int slot = offers.size();
            if (roll < armorChance && !armorPieces.isEmpty()) {
                offer = enchantedGear(slot, armorPieces, used);
            } else if (roll < armorChance + toolChance && !toolPieces.isEmpty()) {
                offer = enchantedGear(slot, toolPieces, used);
            } else if (roll < armorChance + toolChance + bookChance) {
                offer = enchantedBook(slot, used);
            } else {
                offer = normalItem(slot, candidates, cumulative, sum, used);
            }
            if (offer != null) {
                offers.add(offer);
            }
        }
        return offers;
    }

    // ------------------------------------------------------------------
    //  Regeln aus der config.yml
    // ------------------------------------------------------------------

    private void loadRules(FileConfiguration cfg) {
        priceRules.clear();
        ConfigurationSection prices = cfg.getConfigurationSection("server-shop.prices");
        if (prices != null) {
            for (String key : prices.getKeys(false)) {
                priceRules.put(key.toUpperCase(Locale.ROOT), Math.max(1, prices.getLong(key)));
            }
        }
        excludeRules = new ArrayList<>();
        for (String rule : cfg.getStringList("server-shop.exclude")) {
            excludeRules.add(rule.toUpperCase(Locale.ROOT));
        }
        defaultPrice = Math.max(1, cfg.getLong("server-shop.default-price", 3));

        singleRules = new ArrayList<>();
        for (String rule : cfg.getStringList("server-shop.single-only")) {
            singleRules.add(rule.toUpperCase(Locale.ROOT));
        }
        materialFactors.clear();
        ConfigurationSection factors = cfg.getConfigurationSection("server-shop.enchant-material-factor");
        if (factors != null) {
            for (String key : factors.getKeys(false)) {
                materialFactors.put(key.toUpperCase(Locale.ROOT), Math.max(0.0, factors.getDouble(key)));
            }
        }
    }

    /** Wird dieses Item immer nur einzeln angeboten? */
    private boolean isSingleOnly(Material material) {
        for (String rule : singleRules) {
            if (matches(rule, material.name())) {
                return true;
            }
        }
        return false;
    }

    /** Faktor für Verzauberungs-Preise je nach Material (erster passender Eintrag, sonst 1.0). */
    private double materialFactor(Material material) {
        for (Map.Entry<String, Double> rule : materialFactors.entrySet()) {
            if (matches(rule.getKey(), material.name())) {
                return rule.getValue();
            }
        }
        return 1.0;
    }

    /** Passt der Name zum Muster? Mit * am Anfang und/oder Ende sind Gruppen möglich. */
    private static boolean matches(String pattern, String name) {
        boolean star = pattern.contains("*");
        if (!star) {
            return pattern.equals(name);
        }
        String core = pattern.replace("*", "");
        boolean start = pattern.startsWith("*");
        boolean end = pattern.endsWith("*");
        if (start && end) {
            return name.contains(core);
        }
        if (start) {
            return name.endsWith(core);
        }
        return name.startsWith(core);
    }

    private boolean isAllowed(Material material) {
        if (material.name().startsWith("LEGACY_") || !material.isItem() || material.isAir()) {
            return false;
        }
        for (String rule : excludeRules) {
            if (matches(rule, material.name())) {
                return false;
            }
        }
        return true;
    }

    /** Preis eines einzelnen Items (erster passender Eintrag in der config). */
    private long unitPrice(Material material) {
        for (Map.Entry<String, Long> rule : priceRules.entrySet()) {
            if (matches(rule.getKey(), material.name())) {
                return rule.getValue();
            }
        }
        return defaultPrice;
    }

    // ------------------------------------------------------------------
    //  Normale Items
    // ------------------------------------------------------------------

    private Offer normalItem(int slot, List<Material> candidates, double[] cumulative, double sum, Set<String> used) {
        if (candidates.isEmpty()) {
            return null;
        }
        for (int tries = 0; tries < 50; tries++) {
            double pick = random.nextDouble() * sum;
            int index = 0;
            while (index < cumulative.length - 1 && cumulative[index] < pick) {
                index++;
            }
            Material material = candidates.get(index);
            if (!used.add("item:" + material.name())) {
                continue;
            }
            long unit = unitPrice(material);
            int[] range = amountRange(unit);
            int amount = range[0] + random.nextInt(range[1] - range[0] + 1);
            amount = Math.max(1, Math.min(amount, material.getMaxStackSize()));
            if (isSingleOnly(material)) {
                amount = 1;   // z. B. Karten gibt es nur einzeln
            }
            return new Offer(slot, new ItemStack(material, amount), unit * amount);
        }
        return null;
    }

    /** Teure Items gibt es nur einzeln, billige Items in großen Mengen. */
    private static int[] amountRange(long unitPrice) {
        if (unitPrice >= 1000) {
            return new int[]{1, 1};
        }
        if (unitPrice >= 300) {
            return new int[]{1, 2};
        }
        if (unitPrice >= 100) {
            return new int[]{1, 4};
        }
        if (unitPrice >= 25) {
            return new int[]{2, 8};
        }
        if (unitPrice >= 8) {
            return new int[]{4, 16};
        }
        if (unitPrice >= 3) {
            return new int[]{8, 32};
        }
        return new int[]{16, 64};
    }

    // ------------------------------------------------------------------
    //  Verzauberungen
    // ------------------------------------------------------------------

    private Enchantment lookup(String key) {
        return Registry.ENCHANTMENT.get(NamespacedKey.minecraft(key));
    }

    /** Kosten PRO STUFE einer Verzauberung (aus der config). */
    private long costPerLevel(String key) {
        FileConfiguration cfg = plugin.getConfig();
        long fallback = cfg.getLong("server-shop.enchant-cost-per-level.default", 100);
        return Math.max(1, cfg.getLong("server-shop.enchant-cost-per-level." + key, fallback));
    }

    /**
     * Preis einer Verzauberung in einer bestimmten Stufe. Hohe Stufen werden überproportional teurer:
     * Preis = Kosten pro Stufe x Stufe hoch "level-exponent" (Standard 1.4; Stufe 1 = 1x, Stufe 3 = ca. 4,7x,
     * Stufe 5 = ca. 9,5x).
     */
    private long enchantCost(String key, int level) {
        double exponent = Math.max(1.0, plugin.getConfig().getDouble("server-shop.enchant-level-exponent", 1.4));
        return Math.round(costPerLevel(key) * Math.pow(level, exponent));
    }

    private boolean chance(double probability) {
        return random.nextDouble() < probability;
    }

    /** Fügt eine Verzauberung mit zufälliger Stufe zur Liste hinzu (falls es sie gibt). */
    private void addEnchant(Map<Enchantment, Integer> target, Map<String, Integer> levelsByKey, String key) {
        Enchantment enchantment = lookup(key);
        if (enchantment == null) {
            return;
        }
        int level = 1 + random.nextInt(Math.max(1, enchantment.getMaxLevel()));
        target.put(enchantment, level);
        levelsByKey.put(key, level);
    }

    /** Ist das Item ein Werkzeug oder eine Waffe, die verzaubert angeboten werden darf? */
    private static boolean isTool(String name) {
        return name.endsWith("_SWORD") || name.endsWith("_AXE") || name.endsWith("_PICKAXE")
                || name.endsWith("_SHOVEL") || name.endsWith("_HOE")
                || name.equals("BOW") || name.equals("CROSSBOW") || name.equals("TRIDENT")
                || name.equals("MACE") || name.equals("FISHING_ROD");
    }

    /** Wählt zufällig EINE der genannten Verzauberungen (sie schließen sich im Spiel gegenseitig aus). */
    private void addOneOf(Map<Enchantment, Integer> enchants, Map<String, Integer> levels, String... keys) {
        addEnchant(enchants, levels, keys[random.nextInt(keys.length)]);
    }

    /** Würfelt passende Verzauberungen für Rüstung. */
    private void rollArmorEnchants(String name, Map<Enchantment, Integer> enchants, Map<String, Integer> levels) {
        // Immer genau ein Schutz-Typ
        addOneOf(enchants, levels, "protection", "fire_protection", "blast_protection", "projectile_protection");
        if (chance(0.7)) {
            addEnchant(enchants, levels, "unbreaking");
        }
        if (chance(0.45)) {
            addEnchant(enchants, levels, "mending");
        }
        if (chance(0.3)) {
            addEnchant(enchants, levels, "thorns");
        }
        if (name.endsWith("_HELMET")) {
            if (chance(0.4)) {
                addEnchant(enchants, levels, "respiration");
            }
            if (chance(0.4)) {
                addEnchant(enchants, levels, "aqua_affinity");
            }
        } else if (name.endsWith("_LEGGINGS")) {
            if (chance(0.4)) {
                addEnchant(enchants, levels, "swift_sneak");
            }
        } else if (name.endsWith("_BOOTS")) {
            if (chance(0.6)) {
                addEnchant(enchants, levels, "feather_falling");
            }
            // Tiefseeläufer und Frostläufer schließen sich aus
            if (chance(0.3)) {
                addEnchant(enchants, levels, "depth_strider");
            } else if (chance(0.2)) {
                addEnchant(enchants, levels, "frost_walker");
            }
            if (chance(0.2)) {
                addEnchant(enchants, levels, "soul_speed");
            }
        }
    }

    /** Würfelt passende Verzauberungen für Werkzeuge und Waffen. */
    private void rollToolEnchants(String name, Map<Enchantment, Integer> enchants, Map<String, Integer> levels) {
        boolean mending = chance(0.4);
        if (name.endsWith("_SWORD")) {
            addOneOf(enchants, levels, "sharpness", "smite", "bane_of_arthropods");
            if (chance(0.4)) {
                addEnchant(enchants, levels, "knockback");
            }
            if (chance(0.4)) {
                addEnchant(enchants, levels, "fire_aspect");
            }
            if (chance(0.5)) {
                addEnchant(enchants, levels, "looting");
            }
            if (chance(0.4)) {
                addEnchant(enchants, levels, "sweeping_edge");
            }
        } else if (name.endsWith("_AXE") && !name.endsWith("_PICKAXE")) {
            addEnchant(enchants, levels, "efficiency");
            if (chance(0.5)) {
                addOneOf(enchants, levels, "sharpness", "smite", "bane_of_arthropods");
            }
            // Behutsamkeit und Glück schließen sich aus
            if (chance(0.3)) {
                addOneOf(enchants, levels, "silk_touch", "fortune");
            }
        } else if (name.endsWith("_PICKAXE") || name.endsWith("_SHOVEL") || name.endsWith("_HOE")) {
            addEnchant(enchants, levels, "efficiency");
            if (chance(0.5)) {
                addOneOf(enchants, levels, "silk_touch", "fortune");
            }
        } else if (name.equals("BOW")) {
            addEnchant(enchants, levels, "power");
            if (chance(0.4)) {
                addEnchant(enchants, levels, "punch");
            }
            if (chance(0.4)) {
                addEnchant(enchants, levels, "flame");
            }
            // Unendlichkeit und Reparatur schließen sich aus
            if (chance(0.4)) {
                addEnchant(enchants, levels, "infinity");
                mending = false;
            }
        } else if (name.equals("CROSSBOW")) {
            addOneOf(enchants, levels, "multishot", "piercing");
            if (chance(0.7)) {
                addEnchant(enchants, levels, "quick_charge");
            }
        } else if (name.equals("TRIDENT")) {
            addEnchant(enchants, levels, "impaling");
            // Treue und Sturmlauf (Riptide) schließen sich aus; Blitzschlag geht nicht mit Sturmlauf
            if (chance(0.4)) {
                addEnchant(enchants, levels, "riptide");
            } else {
                if (chance(0.6)) {
                    addEnchant(enchants, levels, "loyalty");
                }
                if (chance(0.4)) {
                    addEnchant(enchants, levels, "channeling");
                }
            }
        } else if (name.equals("MACE")) {
            addOneOf(enchants, levels, "density", "breach");
            if (chance(0.5)) {
                addEnchant(enchants, levels, "wind_burst");
            }
            if (chance(0.3)) {
                addEnchant(enchants, levels, "fire_aspect");
            }
        } else if (name.equals("FISHING_ROD")) {
            addEnchant(enchants, levels, "luck_of_the_sea");
            if (chance(0.6)) {
                addEnchant(enchants, levels, "lure");
            }
        }
        if (chance(0.7)) {
            addEnchant(enchants, levels, "unbreaking");
        }
        if (mending) {
            addEnchant(enchants, levels, "mending");
        }
    }

    /**
     * Ein verzaubertes Rüstungsteil, Werkzeug oder eine Waffe.
     * Preis = Preis des Items + (Summe der Verzauberungen x Material-Faktor aus der config).
     * Dadurch ist eine verzauberte Lederrüstung viel billiger als eine verzauberte Diamantrüstung.
     */
    private Offer enchantedGear(int slot, List<Material> pieces, Set<String> used) {
        for (int tries = 0; tries < 30; tries++) {
            Material piece = pieces.get(random.nextInt(pieces.size()));
            String name = piece.name();

            Map<Enchantment, Integer> enchants = new LinkedHashMap<>();
            Map<String, Integer> levels = new LinkedHashMap<>();
            boolean armor = name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE")
                    || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS");
            if (armor) {
                rollArmorEnchants(name, enchants, levels);
            } else {
                rollToolEnchants(name, enchants, levels);
            }
            if (enchants.isEmpty()) {
                continue;
            }

            String signature = "gear:" + name + levels;
            if (!used.add(signature)) {
                continue;
            }

            ItemStack item = new ItemStack(piece);
            item.editMeta(meta -> {
                for (Map.Entry<Enchantment, Integer> e : enchants.entrySet()) {
                    meta.addEnchant(e.getKey(), e.getValue(), true);
                }
            });

            long enchantTotal = 0;
            for (Map.Entry<String, Integer> e : levels.entrySet()) {
                enchantTotal += enchantCost(e.getKey(), e.getValue());
            }
            long price = unitPrice(piece) + Math.round(enchantTotal * materialFactor(piece));
            return new Offer(slot, item, Math.max(1, price));
        }
        return null;
    }

    private Offer enchantedBook(int slot, Set<String> used) {
        for (int tries = 0; tries < 30; tries++) {
            String key = BOOK_ENCHANTS.get(random.nextInt(BOOK_ENCHANTS.size()));
            Enchantment enchantment = lookup(key);
            if (enchantment == null) {
                continue;
            }
            int level = 1 + random.nextInt(Math.max(1, enchantment.getMaxLevel()));
            if (!used.add("book:" + key + ":" + level)) {
                continue;
            }

            ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
            book.editMeta(EnchantmentStorageMeta.class, meta -> meta.addStoredEnchant(enchantment, level, true));
            long price = Math.max(250, enchantCost(key, level));
            return new Offer(slot, book, price);
        }
        return null;
    }
}
