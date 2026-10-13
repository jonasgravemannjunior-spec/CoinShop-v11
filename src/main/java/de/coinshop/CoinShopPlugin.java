package de.coinshop;

import de.coinshop.gui.MenuListener;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;

/**
 * Das Hauptprogramm des Plugins. Der Server startet hier:
 * onEnable() läuft beim Einschalten, onDisable() beim Ausschalten.
 */
public final class CoinShopPlugin extends JavaPlugin {

    private Messages messages;
    private Database database;
    private ServerShopManager serverShop;
    private PlayerShopManager playerShop;
    private ChatPrompts prompts;
    private InvestmentManager investments;
    private WheelManager wheel;
    private WheelManager slots;
    private WheelManager scratch;
    private StockManager stock;
    private AchievementRewards rewards;
    private RankingManager rankings;
    private SecretAchievements secrets;
    private TeleportManager teleports;
    private SleepManager sleep;
    private FreecamManager freecam;
    private CollectionManager collections;
    private BackupManager backups;
    private QuestManager quests;
    private LoginRewards logins;
    private TitleManager titles;

    @Override
    public void onEnable() {
        // Legt config.yml an, falls es sie noch nicht gibt (inkl. Plugin-Ordner)
        saveDefaultConfig();

        messages = new Messages(this);
        messages.load();

        try {
            database = new Database(new java.io.File(getDataFolder(), "coinshop.db"), getLogger());
        } catch (SQLException e) {
            getLogger().severe("Datenbank konnte nicht geöffnet werden: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        prompts = new ChatPrompts();
        investments = new InvestmentManager(this);
        wheel = new WheelManager(this, "wheel", "wheel");
        slots = new WheelManager(this, "slots", "casino.slots");
        scratch = new WheelManager(this, "scratch", "casino.scratch");
        stock = new StockManager(this);
        rewards = new AchievementRewards(this);
        rankings = new RankingManager(this);
        secrets = new SecretAchievements(this);
        teleports = new TeleportManager(this);
        sleep = new SleepManager(this);
        freecam = new FreecamManager(this);
        collections = new CollectionManager(this);
        serverShop = new ServerShopManager(this);
        playerShop = new PlayerShopManager(this);
        backups = new BackupManager(this);
        quests = new QuestManager(this);
        logins = new LoginRewards(this);
        titles = new TitleManager(this);

        // Auf Ereignisse im Spiel reagieren (Beitreten, Erfolge, Menü-Klicks, Chat)
        getServer().getPluginManager().registerEvents(new MenuListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        getServer().getPluginManager().registerEvents(new SecretListener(this), this);
        getServer().getPluginManager().registerEvents(sleep, this);
        getServer().getPluginManager().registerEvents(freecam, this);
        getServer().getPluginManager().registerEvents(titles, this);

        // Befehle verbinden (die Namen stehen in plugin.yml)
        CoinCommands coinCommands = new CoinCommands(this);
        for (String name : new String[]{"coins", "pay", "baltop", "coinsadmin", "shopreset"}) {
            register(name, coinCommands, coinCommands);
        }
        ShopCommand shopCommand = new ShopCommand(this);
        register("shop", shopCommand, shopCommand);
        TeleportCommands teleportCommands = new TeleportCommands(this);
        for (String name : new String[]{"tp", "spawn", "sethome", "home", "tpa", "tpaccept", "tpdeny"}) {
            register(name, teleportCommands, teleportCommands);
        }
        ExtraCommands extraCommands = new ExtraCommands(this);
        for (String name : new String[]{"titel", "aufgaben", "profil"}) {
            PluginCommand extra = getCommand(name);
            if (extra != null) {
                extra.setExecutor(extraCommands);
                extra.setTabCompleter(extraCommands);
            } else {
                getLogger().severe("Befehl '" + name + "' fehlt in der plugin.yml!");
            }
        }
        PluginCommand freecamCommand = getCommand("freecam");
        if (freecamCommand != null) {
            freecamCommand.setExecutor(new FreecamCommand(this));
        }

        // Kollektionen: alle 10 Sekunden die Inventare der Online-Spieler prüfen
        getServer().getScheduler().runTaskTimer(this, collections::scanAll, 200L, 200L);

        sleep.start();
        freecam.start();

        // Server-Shop beim Start prüfen und danach alle 30 Sekunden (Wechsel um 12:00 Uhr)
        serverShop.checkReset();
        getServer().getScheduler().runTaskTimer(this, serverShop::checkReset, 20L * 30, 20L * 30);

        // Abgelaufene Spieler-Angebote jede Minute einsammeln
        getServer().getScheduler().runTaskTimer(this, playerShop::processExpired, 20L * 60, 20L * 60);

        // Statistiken für die Ranglisten regelmäßig einlesen
        long syncTicks = 20L * rankings.syncSeconds();
        getServer().getScheduler().runTaskTimer(this, rankings::syncOnline, syncTicks, syncTicks);

        // Fledermaus durch das Fernglas ansehen (geheimer Erfolg): alle halbe Sekunde prüfen
        getServer().getScheduler().runTaskTimer(this, secrets::checkBatSightings, 10L, 10L);

        // Sicherungen der Datenbank
        backups.start();
        try {
            database.pruneTx(System.currentTimeMillis() - 60L * 24 * 3_600_000L); // Protokoll: 60 Tage behalten
        } catch (RuntimeException e) {
            getLogger().warning("Altes Protokoll konnte nicht aufgeräumt werden: " + e);
        }

        // Tägliche Aufgaben: alle 20 Sekunden prüfen
        getServer().getScheduler().runTaskTimer(this, quests::tickAll, 20L * 20, 20L * 20);

        // Titel: Platz-1-Titel alle 5 Minuten, dauerhafte Titel jede Minute prüfen
        getServer().getScheduler().runTaskLater(this, titles::updateRankTitles, 20L * 10);
        getServer().getScheduler().runTaskTimer(this, titles::updateRankTitles, 20L * 60 * 5, 20L * 60 * 5);
        getServer().getScheduler().runTaskTimer(this, titles::checkPermanentAll, 20L * 60, 20L * 60);

        getLogger().info("CoinShop wurde gestartet.");
    }

    @Override
    public void onDisable() {
        if (freecam != null) {
            freecam.stopAll(); // alle Freecam-Spieler zurück an ihren Ort
        }
        if (database != null) {
            if (backups != null && backups.enabled()) {
                backups.run(); // letzte Sicherung beim Ausschalten
            }
            database.close();
        }
    }

    private void register(String name, org.bukkit.command.CommandExecutor executor,
                          org.bukkit.command.TabCompleter completer) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            getLogger().severe("Befehl '" + name + "' fehlt in der plugin.yml!");
            return;
        }
        command.setExecutor(executor);
        command.setTabCompleter(completer);
    }

    /** Lädt config.yml und messages.yml neu (/coinsadmin reload). */
    public void reloadAll() {
        reloadConfig();
        messages.load();
        investments.reload();
        collections.reload();
        titles.reload();
    }

    public Messages messages() {
        return messages;
    }

    public Database database() {
        return database;
    }

    public ServerShopManager serverShop() {
        return serverShop;
    }

    public PlayerShopManager playerShop() {
        return playerShop;
    }

    public ChatPrompts prompts() {
        return prompts;
    }

    public InvestmentManager investments() {
        return investments;
    }

    public WheelManager wheel() {
        return wheel;
    }

    /** Spielautomat (Casino). */
    public WheelManager slots() {
        return slots;
    }

    /** Rubbellos (Casino). */
    public WheelManager scratch() {
        return scratch;
    }

    public StockManager stock() {
        return stock;
    }

    public TeleportManager teleports() {
        return teleports;
    }

    public CollectionManager collections() {
        return collections;
    }

    public FreecamManager freecam() {
        return freecam;
    }

    public RankingManager rankings() {
        return rankings;
    }

    public SecretAchievements secrets() {
        return secrets;
    }

    public BackupManager backups() {
        return backups;
    }

    public QuestManager quests() {
        return quests;
    }

    public LoginRewards logins() {
        return logins;
    }

    public TitleManager titles() {
        return titles;
    }

    public AchievementRewards rewards() {
        return rewards;
    }
}
