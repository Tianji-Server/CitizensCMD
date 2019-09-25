/*
  CitizensCMD - Add-on for Citizens
  Copyright (C) 2018 Mateus Moreira
  <p>
  This program is free software: you can redistribute it and/or modify
  it under the terms of the GNU General Public License as published by
  the Free Software Foundation, either version 3 of the License, or
  (at your option) any later version.
  <p>
  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.
  <p>
  You should have received a copy of the GNU General Public License
  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package me.mattstudios.citizenscmd;

import me.mattstudios.citizenscmd.api.CitizensCMDAPI;
import me.mattstudios.citizenscmd.commands.AddCommand;
import me.mattstudios.citizenscmd.commands.CooldownCommand;
import me.mattstudios.citizenscmd.commands.EditCommand;
import me.mattstudios.citizenscmd.commands.HelpCommand;
import me.mattstudios.citizenscmd.commands.ListCommand;
import me.mattstudios.citizenscmd.commands.PermissionCommand;
import me.mattstudios.citizenscmd.commands.PriceCommand;
import me.mattstudios.citizenscmd.commands.ReloadCommand;
import me.mattstudios.citizenscmd.commands.RemoveCommand;
import me.mattstudios.citizenscmd.files.CooldownHandler;
import me.mattstudios.citizenscmd.files.DataHandler;
import me.mattstudios.citizenscmd.files.LangHandler;
import me.mattstudios.citizenscmd.listeners.NPCClickListener;
import me.mattstudios.citizenscmd.listeners.NPCListener;
import me.mattstudios.citizenscmd.listeners.UpdateEvent;
import me.mattstudios.citizenscmd.permissions.PermissionsManager;
import me.mattstudios.citizenscmd.schedulers.CooldownScheduler;
import me.mattstudios.citizenscmd.schedulers.UpdateScheduler;
import me.mattstudios.citizenscmd.updater.SpigotUpdater;
import me.mattstudios.citizenscmd.utility.DisplayFormat;
import me.mattstudios.citizenscmd.utility.paths.Path;
import me.mattstudios.mf.base.CommandManager;
import net.milkbowl.vault.economy.Economy;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Objects;
import java.util.stream.Stream;

import static me.mattstudios.citizenscmd.utility.Util.TAG;
import static me.mattstudios.citizenscmd.utility.Util.disablePlugin;
import static me.mattstudios.citizenscmd.utility.Util.upCheck;
import static me.mattstudios.utils.MessageUtils.color;
import static me.mattstudios.utils.MessageUtils.info;
import static me.mattstudios.utils.YamlUtils.copyDefaults;

public final class CitizensCMD extends JavaPlugin {

    private LangHandler lang;
    private DataHandler dataHandler;
    private CooldownHandler cooldownHandler;
    private PermissionsManager permissionsManager;

    private static CitizensCMDAPI api;
    private static Economy economy;

    private boolean papi = false;
    private CommandManager commandManager;

    private boolean updateStatus = false;
    private boolean shift = false;

    private String newVersion;
    private DisplayFormat displayFormat;

    private HashMap<String, Boolean> waitingList;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        copyDefaults(getClassLoader().getResourceAsStream("config.yml"), new File(getDataFolder().getPath(), "config.yml"));

        setLang(Objects.requireNonNull(getConfig().getString("lang")));

        if (!hasCitizens() && getConfig().getBoolean("citizens-check")) {
            disablePlugin(this);
            return;
        }

        commandManager = new CommandManager(this);

        Metrics metrics = new Metrics(this);
        metrics.addCustomChart(new Metrics.SimplePie("lang", () -> {
            switch (Objects.requireNonNull(getConfig().getString("lang", "en")).toLowerCase()) {
                case "en":
                    return "English";

                case "bg":
                    return "Bulgarian";

                case "fr":
                    return "French";

                case "no":
                    return "Norwegian";

                case "pt":
                    return "Portuguese";

                case "Ro":
                    return "Romanian";

                case "ch":
                    return "Chinese";

                default:
                    return "Other";
            }
        }));

        info(color(TAG + "&3Citizens&cCMD &8&o" + getDescription().getVersion() + " &8By &3Mateus Moreira &c@LichtHund"));

        permissionsManager = new PermissionsManager(this);

        dataHandler = new DataHandler(this);
        dataHandler.initialize();

        cooldownHandler = new CooldownHandler(this);
        Bukkit.getScheduler().scheduleSyncDelayedTask(this, () -> cooldownHandler.initialize(), 30L);

        registerCommands();
        registerEvents();

        if (hasPAPI()) {
            info(color(TAG + lang.getMessage(Path.PAPI_AVAILABLE)));
            papi = true;
        }

        if (setupEconomy()) {
            info(color(TAG + lang.getUncoloredMessage(Path.VAULT_AVAILABLE)));
        }

        waitingList = new HashMap<>();

        if (getConfig().contains("cooldown-time-display")) {
            switch (Objects.requireNonNull(getConfig().getString("cooldown-time-display")).toLowerCase()) {
                case "short":
                    displayFormat = DisplayFormat.SHORT;
                    break;
                case "full":
                    displayFormat = DisplayFormat.FULL;
                    break;
                default:
                    displayFormat = DisplayFormat.MEDIUM;
                    break;
            }
        } else {
            displayFormat = DisplayFormat.MEDIUM;
        }

        if (upCheck(this)) {
            SpigotUpdater updater = new SpigotUpdater(this, 30224);
            try {
                // If there's an update, tell the user that they can update
                if (updater.checkForUpdates()) {
                    updateStatus = true;
                    newVersion = updater.getLatestVersion();

                    info(color(TAG + "&b&o" + updater.getResourceURL()));
                }
            } catch (Exception e) {
                // If it can't check for an update, tell the user and throw an error.
                info("Could not check for updates! Stacktrace:");
                e.printStackTrace();
            }
        }

        api = new CitizensCMDAPI(dataHandler);

        new UpdateScheduler(this).runTaskTimerAsynchronously(this, 72000L, 72000L);
        new CooldownScheduler(this).runTaskTimerAsynchronously(this, 36000L, 36000L);
    }

    private boolean hasCitizens() {
        return Bukkit.getPluginManager().isPluginEnabled("Citizens");
    }

    /**
     * Checks if PAPI is installed or not on the server
     *
     * @return Returns true if PAPI is found and false if not
     */
    private boolean hasPAPI() {
        return Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
    }

    /**
     * Registers all the commands to be used
     */
    private void registerCommands() {
        commandManager.getCompletionHandler().register("#permissions", input -> Arrays.asList("console", "player", "permission", "server", "message", "sound"));
        commandManager.getCompletionHandler().register("#type", input -> Arrays.asList("cmd", "perm"));
        commandManager.getCompletionHandler().register("#click", input -> Arrays.asList("left", "right"));
        commandManager.getCompletionHandler().register("#set", input -> Arrays.asList("set", "remove"));

        Stream.of(
                new AddCommand(this),
                new HelpCommand(this),
                new EditCommand(this),
                new ListCommand(this),
                new CooldownCommand(this),
                new PermissionCommand(this),
                new PriceCommand(this),
                new ReloadCommand(this),
                new RemoveCommand(this)
        ).forEach(commandManager::register);
    }

    /**
     * Registers all the events to be used
     */
    private void registerEvents() {
        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new UpdateEvent(this), this);
        pm.registerEvents(new NPCClickListener(this), this);

        try {
            pm.registerEvents(new NPCListener(this), this);
        } catch (Exception ex) {
            info(color("&cCould not register clone event, please update your Citizens."));
        }
    }

    /**
     * Sets up the economy if Vault is present
     *
     * @return returns true or false depending on if Vault is or not present
     */
    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> registeredServiceProvider = getServer().getServicesManager().getRegistration(Economy.class);
        if (registeredServiceProvider == null) {
            return false;
        }
        economy = registeredServiceProvider.getProvider();
        shift = getConfig().getBoolean("shift-confirm");
        return economy != null;
    }

    /**
     * Sets the language that is supposed to be used
     */
    public void setLang(String language) {
        lang = new LangHandler(this, language);
    }

    /**
     * Checks is PAPI is present or not
     *
     * @return Returns true if PAPI is being used
     */
    public boolean papiEnabled() {
        return papi;
    }

    /**
     * Gets the language that is selected on the config
     *
     * @return returns the language
     */
    public LangHandler getLang() {
        return lang;
    }

    /**
     * Gets if or not should alert player of new update on join
     *
     * @return Returns update status
     */
    public boolean getUpdateStatus() {
        return updateStatus;
    }

    /**
     * Sets new update status from scheduler
     *
     * @param newUpdateStatus New boolean with the update status;
     */
    public void setUpdateStatus(boolean newUpdateStatus) {
        this.updateStatus = newUpdateStatus;
    }

    /**
     * Gets String with new version
     *
     * @return the new version
     */
    public String getNewVersion() {
        return newVersion;
    }

    /**
     * Sets the new version string
     *
     * @param newVersion the new version to be set
     */
    public void setNewVersion(String newVersion) {
        this.newVersion = newVersion;
    }

    /**
     * Gets the NPC data to be used in other classes without needing to open the file
     *
     * @return returns the DataHandler class
     */
    public DataHandler getDataHandler() {
        return dataHandler;
    }

    /**
     * Gets the cooldown handler to check for cooldown informations
     *
     * @return Returns the cooldown handler
     */
    public CooldownHandler getCooldownHandler() {
        return cooldownHandler;
    }

    /**
     * Gets the permission manager to set and unset permission
     *
     * @return the permission manager class
     */
    public PermissionsManager getPermissionsManager() {
        return permissionsManager;
    }

    /**
     * Gets the economy to be used
     *
     * @return Returns the economy
     */
    public static Economy getEconomy() {
        return economy;
    }

    /**
     * Gets the hashmap with the players waiting to confirm the NPC payment
     *
     * @return returns the list of players
     */
    public HashMap<String, Boolean> getWaitingList() {
        return waitingList;
    }

    /**
     * Checks if player needs to shift or not to confirm payment
     *
     * @return Returns the boolean of whether or not players should shift
     */
    public boolean shouldShift() {
        return shift;
    }

    /**
     * Sets the new shifting rule
     *
     * @param shift The new shifting rule
     */
    public void setShift(boolean shift) {
        this.shift = shift;
    }

    /**
     * Gets the display format to be used
     *
     * @return Returns either SHORT, MEDIUM OR FULL
     */
    public DisplayFormat getDisplayFormat() {
        return displayFormat;
    }

    /**
     * Sets the new display format when reloading
     *
     * @param displayFormat The new display format
     */
    public void setDisplayFormat(DisplayFormat displayFormat) {
        this.displayFormat = displayFormat;
    }

    public static CitizensCMDAPI getApi() {
        return api;
    }

    public boolean isShift() {
        return shift;
    }

    public boolean isUpdateStatus() {
        return updateStatus;
    }
}
