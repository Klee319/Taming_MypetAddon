package com.mypetaddon;

import com.mypetaddon.bond.BondListener;
import com.mypetaddon.bond.BondManager;
import com.mypetaddon.command.PetCommandManager;
import com.mypetaddon.config.ConfigManager;
import com.mypetaddon.data.DatabaseManager;
import com.mypetaddon.data.MigrationManager;
import com.mypetaddon.data.PetDataRepository;
import com.mypetaddon.data.EncyclopediaRepository;
import com.mypetaddon.data.cache.PetDataCache;
import com.mypetaddon.integration.LevelledMobsIntegration;
import com.mypetaddon.integration.MythicMobsIntegration;
import com.mypetaddon.personality.PersonalityManager;
import com.mypetaddon.rarity.RarityManager;
import com.mypetaddon.stats.ModifierPipeline;
import com.mypetaddon.stats.StatsManager;
import com.mypetaddon.encyclopedia.EncyclopediaGUI;
import com.mypetaddon.encyclopedia.EncyclopediaManager;
import com.mypetaddon.equipment.EquipmentGUI;
import com.mypetaddon.equipment.EquipmentListener;
import com.mypetaddon.equipment.EquipmentManager;
import com.mypetaddon.evolution.EvolutionGUI;
import com.mypetaddon.evolution.EvolutionManager;
import com.mypetaddon.skilltree.PetInteractListener;
import com.mypetaddon.skilltree.SkilltreeAssigner;
import com.mypetaddon.taming.TamingListener;
import com.mypetaddon.taming.TamingManager;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class MyPetAddonPlugin extends JavaPlugin {

    private ConfigManager configManager;
    private DatabaseManager databaseManager;
    private PetDataRepository petDataRepository;
    private EncyclopediaRepository encyclopediaRepository;
    private PetDataCache petDataCache;
    private LevelledMobsIntegration levelledMobsIntegration;
    private MythicMobsIntegration mythicMobsIntegration;
    private RarityManager rarityManager;
    private PersonalityManager personalityManager;
    private BondManager bondManager;
    private ModifierPipeline modifierPipeline;
    private StatsManager statsManager;
    private TamingManager tamingManager;
    private EncyclopediaManager encyclopediaManager;
    private EncyclopediaGUI encyclopediaGUI;
    private EvolutionManager evolutionManager;
    private EvolutionGUI evolutionGUI;
    private EquipmentManager equipmentManager;
    private EquipmentGUI equipmentGUI;
    private SkilltreeAssigner skilltreeAssigner;

    @Override
    public void onEnable() {
        // 1. Compatibility check
        if (!checkDependencies()) {
            return;
        }

        // 2. Config
        configManager = new ConfigManager(this);
        if (!configManager.loadAndValidate()) {
            getLogger().severe("Config validation failed! Check config.yml for errors.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 3. Database (async)
        databaseManager = new DatabaseManager(this);
        if (!databaseManager.initialize()) {
            getLogger().severe("Database initialization failed!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 4. Repositories & Cache
        petDataRepository = new PetDataRepository(databaseManager);
        encyclopediaRepository = new EncyclopediaRepository(databaseManager);
        petDataCache = new PetDataCache(petDataRepository, this);

        // 5. Legacy migration
        MigrationManager migrationManager = new MigrationManager(this, petDataRepository);
        migrationManager.migrateIfNeeded();

        // 6. Integrations
        levelledMobsIntegration = new LevelledMobsIntegration(this, configManager);
        mythicMobsIntegration = new MythicMobsIntegration(this);

        // 7. Managers
        personalityManager = new PersonalityManager(configManager);
        rarityManager = new RarityManager(configManager, levelledMobsIntegration);
        modifierPipeline = new ModifierPipeline(configManager);
        bondManager = new BondManager(this, configManager, petDataCache);
        statsManager = new StatsManager(this, configManager, petDataCache, modifierPipeline);
        skilltreeAssigner = new SkilltreeAssigner(configManager, getLogger());
        tamingManager = new TamingManager(this, configManager, rarityManager,
                personalityManager, petDataCache, encyclopediaRepository,
                mythicMobsIntegration, statsManager, skilltreeAssigner);

        // 7b. Phase 2 Managers
        encyclopediaManager = new EncyclopediaManager(this, configManager, encyclopediaRepository);
        encyclopediaGUI = new EncyclopediaGUI(this, encyclopediaManager, configManager);
        evolutionManager = new EvolutionManager(this, configManager, petDataCache, statsManager);
        evolutionGUI = new EvolutionGUI(this, evolutionManager, petDataCache);
        equipmentManager = new EquipmentManager(this, configManager, databaseManager);
        modifierPipeline.setEquipmentManager(equipmentManager);
        equipmentGUI = new EquipmentGUI(this, equipmentManager, petDataCache);
        // 8. Event Listeners
        getServer().getPluginManager().registerEvents(statsManager, this);
        getServer().getPluginManager().registerEvents(equipmentGUI, this);
        getServer().getPluginManager().registerEvents(
                new TamingListener(this, tamingManager), this);
        getServer().getPluginManager().registerEvents(
                new BondListener(bondManager, petDataCache, mythicMobsIntegration, configManager, this), this);
        getServer().getPluginManager().registerEvents(encyclopediaGUI, this);
        getServer().getPluginManager().registerEvents(evolutionGUI, this);
        getServer().getPluginManager().registerEvents(
                new EquipmentListener(this, petDataCache, statsManager), this);
        getServer().getPluginManager().registerEvents(
                new PetInteractListener(this, configManager, petDataCache,
                        statsManager, skilltreeAssigner), this);

        // 9. Commands
        PetCommandManager commandManager = new PetCommandManager(this, tamingManager,
                petDataCache, statsManager, bondManager, configManager);
        commandManager.registerCommands();

        // 10. Stats application for already-active pets
        statsManager.applyAllActivePets();

        // 11. Cache flush scheduler (every 2 seconds)
        Bukkit.getScheduler().runTaskTimerAsynchronously(this,
                () -> petDataCache.flushDirty(), 40L, 40L);

        getLogger().info("MyPetAddon enabled successfully!");
    }

    @Override
    public void onDisable() {
        // Flush all cached data synchronously
        if (petDataCache != null) {
            petDataCache.flushAll();
        }

        // Unload all pet modifiers
        if (statsManager != null) {
            statsManager.unloadAll();
        }

        // Close database connections
        if (databaseManager != null) {
            databaseManager.close();
        }

        getLogger().info("MyPetAddon disabled.");
    }

    private boolean checkDependencies() {
        Plugin myPet = Bukkit.getPluginManager().getPlugin("MyPet");
        if (myPet == null || !myPet.isEnabled()) {
            getLogger().severe("MyPet is required but not found!");
            getServer().getPluginManager().disablePlugin(this);
            return false;
        }

        String myPetVersion = myPet.getDescription().getVersion();
        if (!isVersionCompatible(myPetVersion, "3.14")) {
            getLogger().severe("MyPet 3.14+ is required! Found: " + myPetVersion);
            getServer().getPluginManager().disablePlugin(this);
            return false;
        }

        Plugin levelledMobs = Bukkit.getPluginManager().getPlugin("LevelledMobs");
        if (levelledMobs != null && !isVersionCompatible(
                levelledMobs.getDescription().getVersion(), "4.0")) {
            getLogger().warning("LevelledMobs 4.0+ recommended. Found: "
                    + levelledMobs.getDescription().getVersion());
        }

        return true;
    }

    private boolean isVersionCompatible(String current, String minimum) {
        try {
            String[] currentParts = current.split("[.-]");
            String[] minParts = minimum.split("[.-]");
            for (int i = 0; i < minParts.length && i < currentParts.length; i++) {
                int c = Integer.parseInt(currentParts[i]);
                int m = Integer.parseInt(minParts[i]);
                if (c > m) return true;
                if (c < m) return false;
            }
            return true;
        } catch (NumberFormatException e) {
            return true; // assume compatible on parse failure
        }
    }

    public ConfigManager getConfigManager() { return configManager; }
    public DatabaseManager getDatabaseManager() { return databaseManager; }
    public PetDataCache getPetDataCache() { return petDataCache; }
    public RarityManager getRarityManager() { return rarityManager; }
    public PersonalityManager getPersonalityManager() { return personalityManager; }
    public BondManager getBondManager() { return bondManager; }
    public StatsManager getStatsManager() { return statsManager; }
    public TamingManager getTamingManager() { return tamingManager; }
    public LevelledMobsIntegration getLevelledMobsIntegration() { return levelledMobsIntegration; }
    public MythicMobsIntegration getMythicMobsIntegration() { return mythicMobsIntegration; }
    public EncyclopediaManager getEncyclopediaManager() { return encyclopediaManager; }
    public EncyclopediaGUI getEncyclopediaGUI() { return encyclopediaGUI; }
    public EvolutionManager getEvolutionManager() { return evolutionManager; }
    public EvolutionGUI getEvolutionGUI() { return evolutionGUI; }
    public EquipmentManager getEquipmentManager() { return equipmentManager; }
    public EquipmentGUI getEquipmentGUI() { return equipmentGUI; }
    public SkilltreeAssigner getSkilltreeAssigner() { return skilltreeAssigner; }
}
