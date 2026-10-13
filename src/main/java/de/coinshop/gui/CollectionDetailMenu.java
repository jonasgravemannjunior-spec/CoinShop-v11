package de.coinshop.gui;

import de.coinshop.CollectionManager;
import de.coinshop.CollectionManager.Collection;
import de.coinshop.CoinShopPlugin;
import de.coinshop.ItemUtil;
import de.coinshop.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Set;

/** Die Items einer Kollektion: gesammelt oder noch offen. */
public final class CollectionDetailMenu extends Menu {

    public CollectionDetailMenu(CoinShopPlugin plugin, Player viewer, Collection c) {
        super(plugin, 6, plugin.messages().get("gui.collection.detail-title", "name", c.name()));
        Messages msg = plugin.messages();
        Set<String> known = plugin.database().getCollected(viewer.getUniqueId());

        List<CollectionManager.Entry> entries = c.entries();
        for (int i = 0; i < entries.size() && i < 45; i++) {
            CollectionManager.Entry entry = entries.get(i);
            boolean have = known.contains(entry.key());
            ItemStack icon = new ItemStack(entry.icon());
            icon.editMeta(meta -> {
                if (entry.entityType() != null) {
                    // Kreatur: Name der Kreatur statt "Spawn-Ei"
                    meta.displayName(Component.translatable(entry.entityType().translationKey())
                            .decoration(TextDecoration.ITALIC, false));
                }
                if (entry.label() != null) {
                    meta.displayName(Component.text(entry.label())
                            .decoration(TextDecoration.ITALIC, false));
                }
                meta.lore(List.of(have ? msg.get("gui.collection.item-have")
                        : msg.get(entry.entityType() != null
                                ? "gui.collection.entity-missing" : "gui.collection.item-missing")));
                if (have) {
                    meta.setEnchantmentGlintOverride(true);
                }
            });
            set(i, icon, null);
        }

        int have = plugin.collections().progress(c, known);
        set(48, ItemUtil.icon(c.icon(), msg.get("gui.collection.name", "name", c.name()),
                msg.getList("gui.collection.lore", "have", have, "total", entries.size(), "coins", c.reward())), null);
        set(49, backButton(), (player, click) -> new CollectionsMenu(plugin, player).open(player));
        set(50, balanceIcon(viewer), incomeClick());
        fillEmpty();
    }
}
