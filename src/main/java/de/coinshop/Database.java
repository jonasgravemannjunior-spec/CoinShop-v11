package de.coinshop;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Die Datenbank des Plugins (eine einzelne Datei: plugins/CoinShop/coinshop.db).
 *
 * Hier wird ALLES gespeichert: Kontostände, Server-Shop-Angebote, Spieler-Angebote,
 * Abholung und Verkaufs-Benachrichtigungen. Dadurch gehen beim Neustart keine Daten verloren.
 *
 * Wichtig für die Sicherheit (keine Duplikate / kein Coin-Verlust):
 * Alles, was zusammengehört (z. B. "Coins abziehen UND Angebot löschen"), läuft in einer
 * Transaktion: begin() ... commit(). Geht etwas schief, macht rollback() alles rückgängig.
 */
public final class Database {

    // ---- Kleine Datenpakete ("records"), die wir aus der Datenbank bekommen ----
    public record Offer(int slot, ItemStack item, long price) { }
    public record Ownership(String itemId, UUID owner, String ownerName) { }
    public record StockHolding(long shares, long invested) { }
    public record Listing(long id, UUID seller, String sellerName, ItemStack item, long price, long expires) { }
    public record Claim(long id, UUID owner, ItemStack item, String reason) { }
    public record SaleNotice(Material material, int amount, long coins) { }
    public record TopEntry(String name, long coins) { }
    public record RankEntry(UUID uuid, String name, long value) { }
    public record Account(UUID uuid, String name, long coins) { }
    public record Home(String world, double x, double y, double z, float yaw, float pitch) { }

    /** Wird geworfen, wenn die Datenbank einen Fehler meldet. */
    public static final class DbException extends RuntimeException {
        public DbException(Throwable cause) {
            super(cause);
        }
    }

    @FunctionalInterface
    private interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private final Connection conn;
    private final Logger log;

    public Database(File file, Logger log) throws SQLException {
        this.log = log;
        try {
            Class.forName("org.sqlite.JDBC"); // SQLite-Treiber (bringt der Server mit)
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite-Treiber wurde nicht gefunden", e);
        }
        conn = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement st = conn.createStatement()) {
            // Kontostände
            st.execute("CREATE TABLE IF NOT EXISTS balances ("
                    + "uuid TEXT PRIMARY KEY, name TEXT NOT NULL, coins INTEGER NOT NULL DEFAULT 0)");
            // Allerlei Merkzettel (z. B. wann der Server-Shop zuletzt gewechselt hat)
            st.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            // Die 10 aktuellen Angebote des Server-Shops
            st.execute("CREATE TABLE IF NOT EXISTS server_offers ("
                    + "slot INTEGER PRIMARY KEY, material TEXT NOT NULL, amount INTEGER NOT NULL, price INTEGER NOT NULL)");
            // Wie oft hat welcher Spieler welches Server-Angebot gekauft?
            st.execute("CREATE TABLE IF NOT EXISTS server_purchases ("
                    + "uuid TEXT NOT NULL, slot INTEGER NOT NULL, count INTEGER NOT NULL, PRIMARY KEY (uuid, slot))");
            // Angebote im Spieler-Shop (das Item steckt als Bytes in der Spalte "item")
            st.execute("CREATE TABLE IF NOT EXISTS listings ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, seller TEXT NOT NULL, seller_name TEXT NOT NULL, "
                    + "item BLOB NOT NULL, price INTEGER NOT NULL, created INTEGER NOT NULL, expires INTEGER NOT NULL)");
            // Abholung: Items aus abgelaufenen/abgebrochenen Angeboten
            st.execute("CREATE TABLE IF NOT EXISTS claims ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, owner TEXT NOT NULL, item BLOB NOT NULL, "
                    + "reason TEXT NOT NULL, created INTEGER NOT NULL)");
            // Verkäufe, die passiert sind, während der Verkäufer offline war
            st.execute("CREATE TABLE IF NOT EXISTS sale_notices ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL, material TEXT NOT NULL, "
                    + "amount INTEGER NOT NULL, coins INTEGER NOT NULL)");
            // Server-Shop-Angebote (neue Version: das ganze Item steckt als Bytes drin, damit auch
            // verzauberte Rüstung und Bücher gespeichert werden können)
            st.execute("CREATE TABLE IF NOT EXISTS server_offers2 ("
                    + "slot INTEGER PRIMARY KEY, item BLOB NOT NULL, price INTEGER NOT NULL)");
            // Gekaufte Verkaufs-Slots im Spieler-Shop
            st.execute("CREATE TABLE IF NOT EXISTS player_slots ("
                    + "uuid TEXT PRIMARY KEY, slots INTEGER NOT NULL)");
            // Investitionen: wem gehört welches Item aus welchem Set? (item_id = "setname:nummer")
            st.execute("CREATE TABLE IF NOT EXISTS investments ("
                    + "item_id TEXT PRIMARY KEY, owner TEXT NOT NULL, bought INTEGER NOT NULL)");
            // Tägliches Einkommen, das ein Spieler noch nicht "gesehen" hat (z. B. weil er offline war)
            st.execute("CREATE TABLE IF NOT EXISTS income_pending ("
                    + "uuid TEXT PRIMARY KEY, amount INTEGER NOT NULL)");
            // Börse: Aktien pro Spieler (invested = wofür der Spieler sie insgesamt gekauft hat)
            st.execute("CREATE TABLE IF NOT EXISTS stock_holdings ("
                    + "uuid TEXT PRIMARY KEY, shares INTEGER NOT NULL, invested INTEGER NOT NULL)");
            // Börse: Kursverlauf (ein Eintrag pro Tag)
            st.execute("CREATE TABLE IF NOT EXISTS stock_history ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, price INTEGER NOT NULL, at INTEGER NOT NULL)");
            // Statistiken für die Ranglisten (stat = z. B. "diamonds", "mobs", "walked")
            st.execute("CREATE TABLE IF NOT EXISTS stats ("
                    + "uuid TEXT NOT NULL, stat TEXT NOT NULL, value INTEGER NOT NULL, PRIMARY KEY (uuid, stat))");
            // Kollektionen: welche Items hat ein Spieler schon einmal besessen, welche Kollektionen sind fertig?
            st.execute("CREATE TABLE IF NOT EXISTS collection_items ("
                    + "uuid TEXT NOT NULL, item TEXT NOT NULL, PRIMARY KEY (uuid, item))");
            st.execute("CREATE TABLE IF NOT EXISTS collection_done ("
                    + "uuid TEXT NOT NULL, id TEXT NOT NULL, at INTEGER NOT NULL, PRIMARY KEY (uuid, id))");
            // Zuhause pro Spieler (/sethome, /home)
            st.execute("CREATE TABLE IF NOT EXISTS homes ("
                    + "uuid TEXT PRIMARY KEY, world TEXT NOT NULL, x REAL NOT NULL, y REAL NOT NULL, "
                    + "z REAL NOT NULL, yaw REAL NOT NULL, pitch REAL NOT NULL)");
            // Geheime Erfolge (seen = 0: der Spieler hat die Meldung noch nicht gesehen)
            st.execute("CREATE TABLE IF NOT EXISTS secret_achievements ("
                    + "uuid TEXT NOT NULL, id TEXT NOT NULL, at INTEGER NOT NULL, seen INTEGER NOT NULL DEFAULT 0, "
                    + "PRIMARY KEY (uuid, id))");
            // Protokoll aller Coin-Bewegungen (für Admins: wer hat wann was bezahlt/bekommen)
            st.execute("CREATE TABLE IF NOT EXISTS tx_log ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, uuid TEXT NOT NULL, "
                    + "name TEXT NOT NULL, type TEXT NOT NULL, amount INTEGER NOT NULL, detail TEXT NOT NULL)");
            // Login-Serie: wie viele Tage hintereinander war der Spieler online?
            st.execute("CREATE TABLE IF NOT EXISTS login_streak ("
                    + "uuid TEXT PRIMARY KEY, last_day TEXT NOT NULL, streak INTEGER NOT NULL, best INTEGER NOT NULL)");
            // Tägliche Aufgaben: baseline = Statistik-Wert bei Beginn, done = 1 wenn geschafft
            st.execute("CREATE TABLE IF NOT EXISTS quest_progress ("
                    + "uuid TEXT NOT NULL, day TEXT NOT NULL, idx INTEGER NOT NULL, baseline INTEGER NOT NULL, "
                    + "done INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (uuid, day, idx))");
            // Titel: dauerhaft erspielte Titel, Titel für Platz 1 (nur solange man Erster ist) und die Auswahl
            st.execute("CREATE TABLE IF NOT EXISTS player_titles ("
                    + "uuid TEXT NOT NULL, id TEXT NOT NULL, at INTEGER NOT NULL, PRIMARY KEY (uuid, id))");
            st.execute("CREATE TABLE IF NOT EXISTS rank_titles ("
                    + "title_id TEXT NOT NULL, uuid TEXT NOT NULL, PRIMARY KEY (title_id, uuid))");
            st.execute("CREATE TABLE IF NOT EXISTS title_choice ("
                    + "uuid TEXT PRIMARY KEY, choice TEXT NOT NULL)");
        }
    }

    public void close() {
        try {
            conn.close();
        } catch (SQLException e) {
            log.warning("Datenbank konnte nicht sauber geschlossen werden: " + e.getMessage());
        }
    }

    // =====================================================================
    //  Transaktionen
    // =====================================================================

    public void begin() {
        try {
            conn.setAutoCommit(false);
        } catch (SQLException e) {
            throw new DbException(e);
        }
    }

    public void commit() {
        try {
            conn.commit();
            conn.setAutoCommit(true);
        } catch (SQLException e) {
            throw new DbException(e);
        }
    }

    public void rollback() {
        try {
            conn.rollback();
            conn.setAutoCommit(true);
        } catch (SQLException e) {
            log.warning("Rollback fehlgeschlagen: " + e.getMessage());
        }
    }

    // =====================================================================
    //  Kleine Hilfsmethoden, damit wir nicht überall dasselbe schreiben müssen
    // =====================================================================

    private static void bind(PreparedStatement ps, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }

    /** Führt ein INSERT/UPDATE/DELETE aus und gibt zurück, wie viele Zeilen betroffen waren. */
    private int update(String sql, Object... params) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new DbException(e);
        }
    }

    /** Führt eine Abfrage aus und wandelt jede Zeile mit dem "mapper" um. */
    private <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
        List<T> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    T value = mapper.map(rs);
                    if (value != null) {
                        result.add(value);
                    }
                }
            }
        } catch (SQLException e) {
            throw new DbException(e);
        }
        return result;
    }

    private ItemStack readItem(ResultSet rs, String column) throws SQLException {
        try {
            return ItemStack.deserializeBytes(rs.getBytes(column));
        } catch (Exception e) {
            log.warning("Ein gespeichertes Item konnte nicht gelesen werden und wird übersprungen: " + e.getMessage());
            return null;
        }
    }

    // =====================================================================
    //  Kontostände
    // =====================================================================

    /** Legt den Spieler an (falls neu) und merkt sich seinen aktuellen Namen. Startguthaben: 0. */
    public void ensurePlayer(UUID uuid, String name) {
        update("INSERT INTO balances (uuid, name, coins) VALUES (?, ?, 0) "
                + "ON CONFLICT(uuid) DO UPDATE SET name = excluded.name", uuid.toString(), name);
    }

    public long getCoins(UUID uuid) {
        List<Long> r = query("SELECT coins FROM balances WHERE uuid = ?", rs -> rs.getLong(1), uuid.toString());
        return r.isEmpty() ? 0L : r.get(0);
    }

    public void addCoins(UUID uuid, long amount) {
        update("UPDATE balances SET coins = coins + ? WHERE uuid = ?", amount, uuid.toString());
    }

    /** Zieht Coins ab, aber nur wenn genug da sind. Gibt true zurück, wenn es geklappt hat. */
    public boolean takeCoins(UUID uuid, long amount) {
        return update("UPDATE balances SET coins = coins - ? WHERE uuid = ? AND coins >= ?",
                amount, uuid.toString(), amount) == 1;
    }

    public void setCoins(UUID uuid, long amount) {
        update("UPDATE balances SET coins = ? WHERE uuid = ?", amount, uuid.toString());
    }

    /** Überweisung von A nach B. Alles oder nichts. */
    public boolean transfer(UUID from, UUID to, long amount) {
        begin();
        try {
            if (!takeCoins(from, amount)) {
                rollback();
                return false;
            }
            addCoins(to, amount);
            commit();
            return true;
        } catch (RuntimeException e) {
            rollback();
            throw e;
        }
    }

    public Optional<UUID> findUuidByName(String name) {
        List<String> r = query("SELECT uuid FROM balances WHERE name = ? COLLATE NOCASE",
                rs -> rs.getString(1), name);
        return r.isEmpty() ? Optional.empty() : Optional.of(UUID.fromString(r.get(0)));
    }

    public String getName(UUID uuid) {
        List<String> r = query("SELECT name FROM balances WHERE uuid = ?", rs -> rs.getString(1), uuid.toString());
        return r.isEmpty() ? uuid.toString() : r.get(0);
    }

    public List<TopEntry> top(int limit) {
        return query("SELECT name, coins FROM balances WHERE coins > 0 ORDER BY coins DESC, name ASC LIMIT ?",
                rs -> new TopEntry(rs.getString(1), rs.getLong(2)), limit);
    }

    // =====================================================================
    //  Merkzettel (meta)
    // =====================================================================

    public long getMetaLong(String key, long fallback) {
        List<String> r = query("SELECT value FROM meta WHERE key = ?", rs -> rs.getString(1), key);
        if (r.isEmpty()) {
            return fallback;
        }
        try {
            return Long.parseLong(r.get(0));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public void setMetaLong(String key, long value) {
        update("INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                key, Long.toString(value));
    }

    // =====================================================================
    //  Server-Shop
    // =====================================================================

    /** Ersetzt alle Angebote durch neue und setzt die Kauf-Zähler aller Spieler zurück. */
    public void replaceOffers(List<Offer> offers) {
        begin();
        try {
            update("DELETE FROM server_offers2");
            update("DELETE FROM server_purchases");
            for (Offer o : offers) {
                update("INSERT INTO server_offers2 (slot, item, price) VALUES (?, ?, ?)",
                        o.slot(), o.item().serializeAsBytes(), o.price());
            }
            commit();
        } catch (RuntimeException e) {
            rollback();
            throw e;
        }
    }

    public List<Offer> getOffers() {
        return query("SELECT slot, item, price FROM server_offers2 ORDER BY slot",
                rs -> {
                    ItemStack item = readItem(rs, "item");
                    return item == null ? null : new Offer(rs.getInt("slot"), item, rs.getLong("price"));
                });
    }

    public int getPurchases(UUID uuid, int slot) {
        List<Integer> r = query("SELECT count FROM server_purchases WHERE uuid = ? AND slot = ?",
                rs -> rs.getInt(1), uuid.toString(), slot);
        return r.isEmpty() ? 0 : r.get(0);
    }

    /** Wie oft wurde dieses Angebot von ALLEN Spielern zusammen gekauft? */
    public int getTotalPurchases(int slot) {
        List<Integer> r = query("SELECT COALESCE(SUM(count), 0) FROM server_purchases WHERE slot = ?",
                rs -> rs.getInt(1), slot);
        return r.isEmpty() ? 0 : r.get(0);
    }

    public void addPurchase(UUID uuid, int slot) {
        update("INSERT INTO server_purchases (uuid, slot, count) VALUES (?, ?, 1) "
                + "ON CONFLICT(uuid, slot) DO UPDATE SET count = count + 1", uuid.toString(), slot);
    }

    // =====================================================================
    //  Spieler-Shop: Angebote
    // =====================================================================

    public void insertListing(UUID seller, String sellerName, ItemStack item, long price, long expires) {
        update("INSERT INTO listings (seller, seller_name, item, price, created, expires) VALUES (?, ?, ?, ?, ?, ?)",
                seller.toString(), sellerName, item.serializeAsBytes(), price, System.currentTimeMillis(), expires);
    }

    private Listing mapListing(ResultSet rs) throws SQLException {
        ItemStack item = readItem(rs, "item");
        if (item == null) {
            return null;
        }
        return new Listing(rs.getLong("id"), UUID.fromString(rs.getString("seller")),
                rs.getString("seller_name"), item, rs.getLong("price"), rs.getLong("expires"));
    }

    /** Neueste Angebote zuerst; "offset" und "limit" dienen zum Blättern. */
    public List<Listing> getListings(int offset, int limit) {
        return query("SELECT * FROM listings ORDER BY id DESC LIMIT ? OFFSET ?", this::mapListing, limit, offset);
    }

    public int countListings() {
        List<Integer> r = query("SELECT COUNT(*) FROM listings", rs -> rs.getInt(1));
        return r.isEmpty() ? 0 : r.get(0);
    }

    public List<Listing> getListingsOf(UUID seller) {
        return query("SELECT * FROM listings WHERE seller = ? ORDER BY id DESC", this::mapListing, seller.toString());
    }

    public int countListingsOf(UUID seller) {
        List<Integer> r = query("SELECT COUNT(*) FROM listings WHERE seller = ?", rs -> rs.getInt(1), seller.toString());
        return r.isEmpty() ? 0 : r.get(0);
    }

    public Optional<Listing> getListing(long id) {
        List<Listing> r = query("SELECT * FROM listings WHERE id = ?", this::mapListing, id);
        return r.isEmpty() ? Optional.empty() : Optional.of(r.get(0));
    }

    /** Löscht ein Angebot. true = es existierte und wurde gelöscht (schützt vor doppeltem Kauf). */
    public boolean deleteListing(long id) {
        return update("DELETE FROM listings WHERE id = ?", id) == 1;
    }

    public List<Listing> getExpired(long now) {
        return query("SELECT * FROM listings WHERE expires <= ?", this::mapListing, now);
    }

    // =====================================================================
    //  Abholung (claims)
    // =====================================================================

    public void addClaim(UUID owner, ItemStack item, String reason) {
        update("INSERT INTO claims (owner, item, reason, created) VALUES (?, ?, ?, ?)",
                owner.toString(), item.serializeAsBytes(), reason, System.currentTimeMillis());
    }

    private Claim mapClaim(ResultSet rs) throws SQLException {
        ItemStack item = readItem(rs, "item");
        if (item == null) {
            return null;
        }
        return new Claim(rs.getLong("id"), UUID.fromString(rs.getString("owner")), item, rs.getString("reason"));
    }

    public List<Claim> getClaims(UUID owner) {
        return query("SELECT * FROM claims WHERE owner = ? ORDER BY id ASC", this::mapClaim, owner.toString());
    }

    public int countClaims(UUID owner) {
        List<Integer> r = query("SELECT COUNT(*) FROM claims WHERE owner = ?", rs -> rs.getInt(1), owner.toString());
        return r.isEmpty() ? 0 : r.get(0);
    }

    public Optional<Claim> getClaim(long id) {
        List<Claim> r = query("SELECT * FROM claims WHERE id = ?", this::mapClaim, id);
        return r.isEmpty() ? Optional.empty() : Optional.of(r.get(0));
    }

    public boolean deleteClaim(long id) {
        return update("DELETE FROM claims WHERE id = ?", id) == 1;
    }

    // =====================================================================
    //  Verkaufs-Benachrichtigungen für Offline-Verkäufer
    // =====================================================================

    public void addSaleNotice(UUID uuid, Material material, int amount, long coins) {
        update("INSERT INTO sale_notices (uuid, material, amount, coins) VALUES (?, ?, ?, ?)",
                uuid.toString(), material.name(), amount, coins);
    }

    /** Holt alle Benachrichtigungen eines Spielers und löscht sie gleichzeitig. */
    public List<SaleNotice> popSaleNotices(UUID uuid) {
        List<SaleNotice> list = query("SELECT material, amount, coins FROM sale_notices WHERE uuid = ? ORDER BY id",
                rs -> {
                    Material m = Material.matchMaterial(rs.getString(1));
                    return m == null ? null : new SaleNotice(m, rs.getInt(2), rs.getLong(3));
                }, uuid.toString());
        update("DELETE FROM sale_notices WHERE uuid = ?", uuid.toString());
        return list;
    }

    // =====================================================================
    //  Konto des Shops (alle Einnahmen des Shops landen hier)
    // =====================================================================

    private static final String META_SHOP_ACCOUNT = "shop_account";

    public long getShopAccount() {
        return getMetaLong(META_SHOP_ACCOUNT, 0L);
    }

    public void addShopAccount(long amount) {
        setMetaLong(META_SHOP_ACCOUNT, getShopAccount() + amount);
    }

    /** Zieht Coins vom Shop-Konto ab, aber nur wenn genug drauf sind. */
    public boolean takeShopAccount(long amount) {
        long current = getShopAccount();
        if (current < amount) {
            return false;
        }
        setMetaLong(META_SHOP_ACCOUNT, current - amount);
        return true;
    }

    // =====================================================================
    //  Verkaufs-Slots im Spieler-Shop
    // =====================================================================

    /** Wie viele Slots hat der Spieler schon GEKAUFT? */
    public int getSlots(UUID uuid) {
        List<Integer> r = query("SELECT slots FROM player_slots WHERE uuid = ?", rs -> rs.getInt(1), uuid.toString());
        return r.isEmpty() ? 0 : r.get(0);
    }

    public void addSlot(UUID uuid) {
        update("INSERT INTO player_slots (uuid, slots) VALUES (?, 1) "
                + "ON CONFLICT(uuid) DO UPDATE SET slots = slots + 1", uuid.toString());
    }

    // =====================================================================
    //  Investitionen (Sets)
    // =====================================================================

    private Ownership mapOwnership(ResultSet rs) throws SQLException {
        return new Ownership(rs.getString(1), UUID.fromString(rs.getString(2)), rs.getString(3));
    }

    private static final String OWNERSHIP_SELECT =
            "SELECT i.item_id, i.owner, COALESCE(b.name, i.owner) FROM investments i "
                    + "LEFT JOIN balances b ON b.uuid = i.owner";

    public List<Ownership> getOwnerships() {
        return query(OWNERSHIP_SELECT, this::mapOwnership);
    }

    public Optional<Ownership> getOwnership(String itemId) {
        List<Ownership> r = query(OWNERSHIP_SELECT + " WHERE i.item_id = ?", this::mapOwnership, itemId);
        return r.isEmpty() ? Optional.empty() : Optional.of(r.get(0));
    }

    /** Trägt den Besitzer ein. false = das Item gehört schon jemandem (schützt vor Doppelkauf). */
    public boolean insertOwnership(String itemId, UUID owner) {
        return update("INSERT OR IGNORE INTO investments (item_id, owner, bought) VALUES (?, ?, ?)",
                itemId, owner.toString(), System.currentTimeMillis()) == 1;
    }

    /** Überträgt ein Item, aber nur wenn "from" wirklich noch der Besitzer ist. */
    public boolean transferOwnership(String itemId, UUID from, UUID to) {
        return update("UPDATE investments SET owner = ? WHERE item_id = ? AND owner = ?",
                to.toString(), itemId, from.toString()) == 1;
    }

    // =====================================================================
    //  Tägliches Einkommen
    // =====================================================================

    /** Gibt ALLEN bekannten Spielern (auch Offline-Spielern) den Grundbetrag. */
    public void payToAll(long amount) {
        update("UPDATE balances SET coins = coins + ?", amount);
        update("INSERT INTO income_pending (uuid, amount) SELECT uuid, ? FROM balances WHERE true "
                + "ON CONFLICT(uuid) DO UPDATE SET amount = amount + excluded.amount", amount);
    }

    /** Gibt einem Spieler Extra-Einkommen (z. B. aus Sets). */
    public void payExtra(UUID uuid, long amount) {
        update("UPDATE balances SET coins = coins + ? WHERE uuid = ?", amount, uuid.toString());
        update("INSERT INTO income_pending (uuid, amount) VALUES (?, ?) "
                + "ON CONFLICT(uuid) DO UPDATE SET amount = amount + excluded.amount", uuid.toString(), amount);
    }

    /** Holt den noch nicht angezeigten Einkommens-Betrag eines Spielers und setzt ihn auf 0. */
    public long popPendingIncome(UUID uuid) {
        List<Long> r = query("SELECT amount FROM income_pending WHERE uuid = ?", rs -> rs.getLong(1), uuid.toString());
        update("DELETE FROM income_pending WHERE uuid = ?", uuid.toString());
        return r.isEmpty() ? 0L : r.get(0);
    }

    // =====================================================================
    //  Börse
    // =====================================================================

    public StockHolding getStockHolding(UUID uuid) {
        List<StockHolding> r = query("SELECT shares, invested FROM stock_holdings WHERE uuid = ?",
                rs -> new StockHolding(rs.getLong(1), rs.getLong(2)), uuid.toString());
        return r.isEmpty() ? new StockHolding(0, 0) : r.get(0);
    }

    public void setStockHolding(UUID uuid, long shares, long invested) {
        update("INSERT INTO stock_holdings (uuid, shares, invested) VALUES (?, ?, ?) "
                + "ON CONFLICT(uuid) DO UPDATE SET shares = excluded.shares, invested = excluded.invested",
                uuid.toString(), shares, invested);
    }

    public void deleteStockHolding(UUID uuid) {
        update("DELETE FROM stock_holdings WHERE uuid = ?", uuid.toString());
    }

    public void addStockHistory(long price) {
        update("INSERT INTO stock_history (price, at) VALUES (?, ?)", price, System.currentTimeMillis());
    }

    /** Die letzten Kurse, der neueste zuerst. */
    public List<Long> getStockHistory(int limit) {
        return query("SELECT price FROM stock_history ORDER BY id DESC LIMIT ?", rs -> rs.getLong(1), limit);
    }

    // =====================================================================
    //  Statistiken / Ranglisten
    // =====================================================================

    /** Speichert mehrere Statistiken eines Spielers auf einmal (überschreibt alte Werte). */
    public void setStats(UUID uuid, java.util.Map<String, Long> values) {
        begin();
        try {
            for (java.util.Map.Entry<String, Long> e : values.entrySet()) {
                update("INSERT INTO stats (uuid, stat, value) VALUES (?, ?, ?) "
                        + "ON CONFLICT(uuid, stat) DO UPDATE SET value = excluded.value",
                        uuid.toString(), e.getKey(), e.getValue());
            }
            commit();
        } catch (RuntimeException e) {
            rollback();
            throw e;
        }
    }

    /** Zählt eine Statistik hoch und gibt den neuen Wert zurück. */
    public long addStat(UUID uuid, String stat, long delta) {
        update("INSERT INTO stats (uuid, stat, value) VALUES (?, ?, ?) "
                + "ON CONFLICT(uuid, stat) DO UPDATE SET value = value + excluded.value",
                uuid.toString(), stat, delta);
        return getStat(uuid, stat);
    }

    public long getStat(UUID uuid, String stat) {
        List<Long> r = query("SELECT value FROM stats WHERE uuid = ? AND stat = ?",
                rs -> rs.getLong(1), uuid.toString(), stat);
        return r.isEmpty() ? 0L : r.get(0);
    }

    /** Die Besten einer Statistik (höchster Wert zuerst; Werte von 0 zählen nicht). */
    public List<RankEntry> topStat(String stat, int limit) {
        return query("SELECT s.uuid, b.name, s.value FROM stats s JOIN balances b ON b.uuid = s.uuid "
                        + "WHERE s.stat = ? AND s.value > 0 ORDER BY s.value DESC, b.name ASC LIMIT ?",
                rs -> new RankEntry(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3)), stat, limit);
    }

    /**
     * Die wenigsten Tode: nur Spieler mit mindestens "minPlaytimeMinutes" Spielzeit (sonst würde jeder
     * Neuling mit 0 Toden gewinnen). Bei gleich vielen Toden gewinnt, wer länger gespielt hat.
     */
    public List<RankEntry> fewestDeaths(long minPlaytimeMinutes, int limit) {
        return query("SELECT d.uuid, b.name, d.value FROM stats d "
                        + "JOIN stats p ON p.uuid = d.uuid AND p.stat = 'playtime' "
                        + "JOIN balances b ON b.uuid = d.uuid "
                        + "WHERE d.stat = 'deaths' AND p.value >= ? "
                        + "ORDER BY d.value ASC, p.value DESC, b.name ASC LIMIT ?",
                rs -> new RankEntry(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3)),
                minPlaytimeMinutes, limit);
    }

    // =====================================================================
    //  Geheime Erfolge
    // =====================================================================

    /** Trägt den Erfolg ein. Gibt true zurück, wenn er NEU war (false = hatte der Spieler schon). */
    public boolean unlockSecret(UUID uuid, String id) {
        return update("INSERT OR IGNORE INTO secret_achievements (uuid, id, at, seen) VALUES (?, ?, ?, 0)",
                uuid.toString(), id, System.currentTimeMillis()) == 1;
    }

    /** id -> Zeitpunkt der Freischaltung. */
    public java.util.Map<String, Long> getSecrets(UUID uuid) {
        java.util.Map<String, Long> map = new java.util.LinkedHashMap<>();
        query("SELECT id, at FROM secret_achievements WHERE uuid = ? ORDER BY at", rs -> {
            map.put(rs.getString(1), rs.getLong(2));
            return Boolean.TRUE;
        }, uuid.toString());
        return map;
    }

    public void markSecretSeen(UUID uuid, String id) {
        update("UPDATE secret_achievements SET seen = 1 WHERE uuid = ? AND id = ?", uuid.toString(), id);
    }

    /** IDs der Erfolge, die der Spieler noch nicht gemeldet bekommen hat (werden danach als gesehen markiert). */
    public List<String> popUnseenSecrets(UUID uuid) {
        List<String> ids = query("SELECT id FROM secret_achievements WHERE uuid = ? AND seen = 0 ORDER BY at",
                rs -> rs.getString(1), uuid.toString());
        update("UPDATE secret_achievements SET seen = 1 WHERE uuid = ?", uuid.toString());
        return ids;
    }

    // =====================================================================
    //  Zuhause
    // =====================================================================

    public void setHome(UUID uuid, Home h) {
        update("INSERT INTO homes (uuid, world, x, y, z, yaw, pitch) VALUES (?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT(uuid) DO UPDATE SET world = excluded.world, x = excluded.x, y = excluded.y, "
                + "z = excluded.z, yaw = excluded.yaw, pitch = excluded.pitch",
                uuid.toString(), h.world(), h.x(), h.y(), h.z(), (double) h.yaw(), (double) h.pitch());
    }

    public Optional<Home> getHome(UUID uuid) {
        List<Home> r = query("SELECT world, x, y, z, yaw, pitch FROM homes WHERE uuid = ?",
                rs -> new Home(rs.getString(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4),
                        (float) rs.getDouble(5), (float) rs.getDouble(6)), uuid.toString());
        return r.isEmpty() ? Optional.empty() : Optional.of(r.get(0));
    }

    /** Alle bekannten Spieler mit Kontostand. */
    public List<Account> allAccounts() {
        return query("SELECT uuid, name, coins FROM balances",
                rs -> new Account(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3)));
    }

    // =====================================================================
    //  Kollektionen
    // =====================================================================

    public java.util.Set<String> getCollected(UUID uuid) {
        return new java.util.HashSet<>(query("SELECT item FROM collection_items WHERE uuid = ?",
                rs -> rs.getString(1), uuid.toString()));
    }

    public void addCollected(UUID uuid, java.util.Collection<String> items) {
        begin();
        try {
            for (String item : items) {
                update("INSERT OR IGNORE INTO collection_items (uuid, item) VALUES (?, ?)", uuid.toString(), item);
            }
            commit();
        } catch (RuntimeException e) {
            rollback();
            throw e;
        }
    }

    /** Trägt die fertige Kollektion ein. true = war neu (dann darf die Belohnung ausgezahlt werden). */
    public boolean markCollectionDone(UUID uuid, String id) {
        return update("INSERT OR IGNORE INTO collection_done (uuid, id, at) VALUES (?, ?, ?)",
                uuid.toString(), id, System.currentTimeMillis()) == 1;
    }

    public java.util.Set<String> getDoneCollections(UUID uuid) {
        return new java.util.HashSet<>(query("SELECT id FROM collection_done WHERE uuid = ?",
                rs -> rs.getString(1), uuid.toString()));
    }

    // =====================================================================
    //  Protokoll (tx_log), Statistik und Backup für Admins
    // =====================================================================

    public record TxEntry(long at, String name, String type, long amount, String detail) { }
    public record TypeSum(String type, long count, long sum) { }

    /** Schreibt einen Eintrag ins Protokoll. Positive Beträge = Spieler bekommt Coins, negative = zahlt. */
    public void logTx(UUID uuid, String name, String type, long amount, String detail) {
        try {
            update("INSERT INTO tx_log (at, uuid, name, type, amount, detail) VALUES (?, ?, ?, ?, ?, ?)",
                    System.currentTimeMillis(), uuid.toString(), name, type, amount, detail);
        } catch (RuntimeException e) {
            // Das Protokoll darf nie einen Kauf verhindern
            log.warning("Protokoll-Eintrag fehlgeschlagen: " + e.getMessage());
        }
    }

    /** Die letzten Einträge eines Spielers (uuid == null: von allen), neueste zuerst. */
    public List<TxEntry> recentTx(UUID uuid, int limit) {
        RowMapper<TxEntry> mapper = rs -> new TxEntry(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getLong(4), rs.getString(5));
        if (uuid == null) {
            return query("SELECT at, name, type, amount, detail FROM tx_log ORDER BY id DESC LIMIT ?", mapper, limit);
        }
        return query("SELECT at, name, type, amount, detail FROM tx_log WHERE uuid = ? ORDER BY id DESC LIMIT ?",
                mapper, uuid.toString(), limit);
    }

    /** Pro Art (Server-Shop, Spieler-Shop ...): Anzahl und Summe seit einem Zeitpunkt. */
    public List<TypeSum> txSummarySince(long since) {
        return query("SELECT type, COUNT(*), SUM(ABS(amount)) FROM tx_log WHERE at >= ? GROUP BY type ORDER BY 3 DESC",
                rs -> new TypeSum(rs.getString(1), rs.getLong(2), rs.getLong(3)), since);
    }

    /** Löscht alte Protokoll-Einträge. */
    public void pruneTx(long olderThan) {
        update("DELETE FROM tx_log WHERE at < ?", olderThan);
    }

    public long sumCoins() {
        List<Long> r = query("SELECT COALESCE(SUM(coins), 0) FROM balances", rs -> rs.getLong(1));
        return r.isEmpty() ? 0 : r.get(0);
    }

    public long countAccounts() {
        List<Long> r = query("SELECT COUNT(*) FROM balances", rs -> rs.getLong(1));
        return r.isEmpty() ? 0 : r.get(0);
    }

    public long countOwnedInvestments() {
        List<Long> r = query("SELECT COUNT(*) FROM investments", rs -> rs.getLong(1));
        return r.isEmpty() ? 0 : r.get(0);
    }

    public boolean playerExists(UUID uuid) {
        return !query("SELECT 1 FROM balances WHERE uuid = ?", rs -> 1, uuid.toString()).isEmpty();
    }

    /** Kopiert die komplette Datenbank in eine neue Datei (VACUUM INTO = sauberer, konsistenter Abzug). */
    public void backupTo(File target) {
        try (Statement st = conn.createStatement()) {
            st.execute("VACUUM INTO '" + target.getAbsolutePath().replace("'", "''") + "'");
        } catch (SQLException e) {
            throw new DbException(e);
        }
    }

    // =====================================================================
    //  Login-Serie
    // =====================================================================

    public record Streak(String lastDay, int streak, int best) { }

    public Optional<Streak> getStreak(UUID uuid) {
        List<Streak> r = query("SELECT last_day, streak, best FROM login_streak WHERE uuid = ?",
                rs -> new Streak(rs.getString(1), rs.getInt(2), rs.getInt(3)), uuid.toString());
        return r.isEmpty() ? Optional.empty() : Optional.of(r.get(0));
    }

    public void setStreak(UUID uuid, String lastDay, int streak, int best) {
        update("INSERT INTO login_streak (uuid, last_day, streak, best) VALUES (?, ?, ?, ?) "
                + "ON CONFLICT(uuid) DO UPDATE SET last_day = excluded.last_day, streak = excluded.streak, "
                + "best = excluded.best", uuid.toString(), lastDay, streak, best);
    }

    // =====================================================================
    //  Tägliche Aufgaben
    // =====================================================================

    public record QuestRow(int idx, long baseline, boolean done) { }

    public List<QuestRow> getQuests(UUID uuid, String day) {
        return query("SELECT idx, baseline, done FROM quest_progress WHERE uuid = ? AND day = ? ORDER BY idx",
                rs -> new QuestRow(rs.getInt(1), rs.getLong(2), rs.getInt(3) == 1), uuid.toString(), day);
    }

    public void insertQuest(UUID uuid, String day, int idx, long baseline) {
        update("INSERT OR IGNORE INTO quest_progress (uuid, day, idx, baseline, done) VALUES (?, ?, ?, ?, 0)",
                uuid.toString(), day, idx, baseline);
    }

    /** true = die Aufgabe war noch nicht als erledigt eingetragen (dann darf die Belohnung ausgezahlt werden). */
    public boolean markQuestDone(UUID uuid, String day, int idx) {
        return update("UPDATE quest_progress SET done = 1 WHERE uuid = ? AND day = ? AND idx = ? AND done = 0",
                uuid.toString(), day, idx) == 1;
    }

    /** Alte Aufgaben-Einträge aufräumen (alles, was nicht von diesem Tag ist). */
    public void pruneQuests(String keepDay) {
        update("DELETE FROM quest_progress WHERE day <> ?", keepDay);
    }

    // =====================================================================
    //  Titel
    // =====================================================================

    /** Dauerhafter Titel. true = war neu. */
    public boolean addTitle(UUID uuid, String id) {
        return update("INSERT OR IGNORE INTO player_titles (uuid, id, at) VALUES (?, ?, ?)",
                uuid.toString(), id, System.currentTimeMillis()) == 1;
    }

    public java.util.Set<String> getTitles(UUID uuid) {
        return new java.util.HashSet<>(query("SELECT id FROM player_titles WHERE uuid = ?",
                rs -> rs.getString(1), uuid.toString()));
    }

    /** Alle aktuellen Halter von Platz-1-Titeln: Titel-Id -> Spieler. */
    public java.util.Map<String, java.util.Set<UUID>> getRankTitleHolders() {
        java.util.Map<String, java.util.Set<UUID>> result = new java.util.LinkedHashMap<>();
        query("SELECT title_id, uuid FROM rank_titles", rs -> {
            result.computeIfAbsent(rs.getString(1), k -> new java.util.HashSet<>())
                    .add(UUID.fromString(rs.getString(2)));
            return null;
        });
        return result;
    }

    public java.util.Set<String> getRankTitles(UUID uuid) {
        return new java.util.HashSet<>(query("SELECT title_id FROM rank_titles WHERE uuid = ?",
                rs -> rs.getString(1), uuid.toString()));
    }

    /** Ersetzt alle Platz-1-Titel auf einmal. */
    public void replaceRankTitles(java.util.Map<String, java.util.Set<UUID>> holders) {
        begin();
        try {
            update("DELETE FROM rank_titles");
            for (java.util.Map.Entry<String, java.util.Set<UUID>> e : holders.entrySet()) {
                for (UUID uuid : e.getValue()) {
                    update("INSERT OR IGNORE INTO rank_titles (title_id, uuid) VALUES (?, ?)",
                            e.getKey(), uuid.toString());
                }
            }
            commit();
        } catch (RuntimeException e) {
            rollback();
            throw e;
        }
    }

    /** "auto" (Standard), "none" oder eine Titel-Id. */
    public String getTitleChoice(UUID uuid) {
        List<String> r = query("SELECT choice FROM title_choice WHERE uuid = ?", rs -> rs.getString(1), uuid.toString());
        return r.isEmpty() ? "auto" : r.get(0);
    }

    public void setTitleChoice(UUID uuid, String choice) {
        update("INSERT INTO title_choice (uuid, choice) VALUES (?, ?) "
                + "ON CONFLICT(uuid) DO UPDATE SET choice = excluded.choice", uuid.toString(), choice);
    }
}
