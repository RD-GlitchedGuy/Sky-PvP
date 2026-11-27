package de.skypvp.bots;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.CitizensEnableEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.trait.trait.Equipment;
import net.citizensnpcs.trait.SkinTrait;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * PvP bots on top of Citizens.
 * Menus only need plain clicks (no right click, no custom cursor handling) so they work for
 * Bedrock players through Geyser as well. The fight logic is tuned per difficulty so bots play
 * fair: reaction time, reach, hit chance and short breaks after a few hits.
 */
public final class SkyBotsPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private static final int MAX_BOTS_PER_PLAYER = 5;
    private static final int[] LIST_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};

    // Loadout menu (54 slots):
    //  row 0: placeholders (leather armour, totem, sword) above the real fields, save button, help
    //  row 1: helmet, chestplate, leggings, boots | offhand, main hand
    //  rows 2–4: inventory (storage 9–35)
    //  row 5: hotbar label + hotbar slots 2–9 (storage 1–8); the main hand is hotbar slot 1 (storage 0)
    private static final int SLOT_HELMET = 9, SLOT_CHEST = 10, SLOT_LEGS = 11, SLOT_BOOTS = 12;
    private static final int SLOT_OFFHAND = 14, SLOT_MAINHAND = 15;
    private static final int SLOT_SAVE = 7;

    private final Map<Integer, BotData> bots = new HashMap<>();
    private final Map<Integer, BotState> states = new HashMap<>();
    private final Map<Inventory, MenuSession> sessions = new IdentityHashMap<>();

    private NPCRegistry npcRegistry;
    private BukkitTask aiTask;
    private long currentTick;

    // ------------------------------------------------------------------ difficulty

    enum Difficulty {
        // name, reach, hit chance, reaction ticks, extra delay, combo cap, back-off ticks, crit chance, wind cooldown
        LEICHT("§aLeicht", Material.LIME_WOOL, 2.6, 0.55, 10, 6, 2, 18, 0.0, -1),
        NORMAL("§eNormal", Material.YELLOW_WOOL, 2.85, 0.72, 6, 3, 3, 12, 0.15, 160),
        SCHWER("§cSchwer", Material.RED_WOOL, 3.0, 0.88, 3, 0, 4, 8, 0.3, 100);

        final String label;
        final Material icon;
        final double reach, hitChance, critChance;
        final int reaction, extraDelay, comboCap, backOff, windCooldown;

        Difficulty(String label, Material icon, double reach, double hitChance, int reaction, int extraDelay,
                   int comboCap, int backOff, double critChance, int windCooldown) {
            this.label = label;
            this.icon = icon;
            this.reach = reach;
            this.hitChance = hitChance;
            this.reaction = reaction;
            this.extraDelay = extraDelay;
            this.comboCap = comboCap;
            this.backOff = backOff;
            this.critChance = critChance;
            this.windCooldown = windCooldown;
        }

        Difficulty next() {
            return values()[(ordinal() + 1) % values().length];
        }

        static Difficulty parse(String s) {
            try {
                return valueOf(s.toUpperCase(Locale.ROOT));
            } catch (RuntimeException e) {
                return NORMAL;
            }
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        npcRegistry = CitizensAPI.getNPCRegistry();
        Bukkit.getPluginManager().registerEvents(this, this);
        // Citizens loads its NPCs after the plugins are enabled; load our bots once that is done.
        Bukkit.getScheduler().runTaskLater(this, this::loadBotsOnce, 40L);
        Objects.requireNonNull(getCommand("bot"), "Der Befehl 'bot' fehlt in plugin.yml").setExecutor(this);
        Objects.requireNonNull(getCommand("bot")).setTabCompleter(this);
        Objects.requireNonNull(getCommand("bots"), "Der Befehl 'bots' fehlt in plugin.yml").setExecutor(this);
        Objects.requireNonNull(getCommand("bots")).setTabCompleter(this);
        aiTask = Bukkit.getScheduler().runTaskTimer(this, this::tickBots, 1L, 1L);
    }

    @Override
    public void onDisable() {
        if (aiTask != null) aiTask.cancel();
        saveConfig();
        if (npcRegistry != null) npcRegistry.saveToStore();
        sessions.clear();
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("bots") || args.length == 0) {
            if (sender instanceof Player player) openListMenu(player);
            else sender.sendMessage("§cNur Spieler können ihr Bot-Menü öffnen.");
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("spawn")) {
            if (!(sender instanceof Player owner)) {
                sender.sendMessage("§cBots können nur von Spielern erstellt werden.");
                return true;
            }
            spawnBot(owner, args[1]);
            return true;
        }
        sender.sendMessage("§cVerwendung: /bot spawn <Name> oder /bots");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("bot") && args.length == 1) {
            return "spawn".startsWith(args[0].toLowerCase(Locale.ROOT)) ? List.of("spawn") : List.of();
        }
        return List.of();
    }

    private void spawnBot(Player owner, String name) {
        if (!name.matches("[A-Za-z0-9_-]{1,16}")) {
            owner.sendMessage("§cDer Name muss 1 bis 16 Zeichen lang sein und darf nur Buchstaben, Zahlen, _ oder - enthalten.");
            return;
        }
        List<BotData> owned = botsFor(owner.getUniqueId());
        if (owned.size() >= MAX_BOTS_PER_PLAYER) {
            owner.sendMessage("§cDu kannst höchstens fünf Bots besitzen.");
            return;
        }
        if (owned.stream().anyMatch(bot -> bot.name.equalsIgnoreCase(name))) {
            owner.sendMessage("§cDu hast bereits einen Bot mit diesem Namen.");
            return;
        }
        NPC npc = null;
        try {
            npc = npcRegistry.createNPC(EntityType.PLAYER, name);
            npc.setProtected(false);
            npc.setUseMinecraftAI(false);
            npc.getOrAddTrait(SkinTrait.class).setSkinPersistent(owner);
            if (!npc.spawn(owner.getLocation())) throw new IllegalStateException("Citizens konnte den Bot nicht spawnen");
            BotData data = new BotData(npc.getId(), owner.getUniqueId(), name);
            bots.put(data.id, data);
            applyLoadout(data);
            saveBot(data);
            npcRegistry.saveToStore();
            owner.sendMessage("§aBot §f" + name + " §aerstellt. Stelle ihn jetzt im Menü ein.");
            openSettingsMenu(owner, data);
        } catch (RuntimeException exception) {
            if (npc != null) npc.destroy();
            getLogger().warning("Bot-Erstellung fehlgeschlagen: " + exception.getMessage());
            owner.sendMessage("§cDer Bot konnte nicht erstellt werden. Prüfe, ob Citizens korrekt geladen ist.");
        }
    }

    // ------------------------------------------------------------------ menus (Bedrock friendly: one plain click per action)

    private boolean isSpawned(BotData data) {
        NPC npc = npcRegistry.getById(data.id);
        return npc != null && npc.isSpawned();
    }

    private void openListMenu(Player owner) {
        Inventory inventory = Bukkit.createInventory(null, 27, "§8Deine Bots");
        List<BotData> owned = botsFor(owner.getUniqueId());
        for (int index = 0; index < owned.size() && index < LIST_SLOTS.length; index++) {
            BotData data = owned.get(index);
            inventory.setItem(LIST_SLOTS[index], button(Material.PLAYER_HEAD, "§b" + data.name,
                    isSpawned(data) ? "§a● In der Welt" : "§7● Nicht in der Welt",
                    "§7Angriff: " + (data.attackEnabled ? "§aAN" : "§cAUS") + "  §7Stufe: " + data.difficulty.label,
                    "",
                    "§eAntippen / Klicken: Einstellungen öffnen"));
        }
        if (owned.isEmpty()) {
            inventory.setItem(13, button(Material.BARRIER, "§cNoch keine Bots",
                    "§7Erstelle einen Bot mit §f/bot spawn <Name>"));
        }
        inventory.setItem(26, button(Material.OAK_DOOR, "§7Schließen"));
        sessions.put(inventory, new MenuSession(owner.getUniqueId(), 0, MenuType.LIST));
        owner.openInventory(inventory);
    }

    private void openSettingsMenu(Player owner, BotData data) {
        Inventory inventory = Bukkit.createInventory(null, 27, "§8Bot: " + data.name);
        inventory.setItem(4, button(Material.PLAYER_HEAD, "§b" + data.name,
                isSpawned(data) ? "§a● In der Welt" : "§7● Nicht in der Welt (Teleport oder Herholen spawnt ihn)"));
        inventory.setItem(10, button(data.attackEnabled ? Material.IRON_SWORD : Material.WOODEN_SWORD,
                data.attackEnabled ? "§aAngreifen: AN" : "§cAngreifen: AUS",
                "§7Der Bot sucht nahe Spieler und kämpft fair.", "§eKlicken zum Umschalten"));
        inventory.setItem(11, button(data.difficulty.icon, "§fSchwierigkeit: " + data.difficulty.label,
                "§aLeicht§7: langsam, verfehlt oft", "§eNormal§7: wie ein normaler Spieler",
                "§cSchwer§7: schnell und genau, macht aber Pausen", "§eKlicken zum Wechseln"));
        inventory.setItem(12, button(data.crystalEnabled ? Material.END_CRYSTAL : Material.GLASS,
                data.crystalEnabled ? "§aCrystal-PvP: AN" : "§cCrystal-PvP: AUS",
                "§7Braucht End-Crystals (und am besten Obsidian)", "§7im Bot-Inventar.", "§eKlicken zum Umschalten"));
        inventory.setItem(13, button(data.maceEnabled ? Material.MACE : Material.STICK,
                data.maceEnabled ? "§aMace-PvP: AN" : "§cMace-PvP: AUS",
                "§7Mit Mace und Windkugeln im Inventar", "§7springt der Bot hoch und schlägt von oben zu.",
                "§7Nur auf Normal und Schwer.", "§eKlicken zum Umschalten"));
        inventory.setItem(14, button(Material.CHEST, "§eInventar und Ausrüstung",
                "§7Waffen, Rüstung, Zweithand, Essen festlegen"));
        inventory.setItem(15, button(Material.ENDER_PEARL, "§bZum Bot teleportieren"));
        inventory.setItem(16, button(Material.LEAD, "§bBot zu mir holen"));
        inventory.setItem(18, button(Material.ARROW, "§7Zurück zur Übersicht"));
        inventory.setItem(26, button(Material.BARRIER, "§cBot löschen"));
        sessions.put(inventory, new MenuSession(owner.getUniqueId(), data.id, MenuType.SETTINGS));
        owner.openInventory(inventory);
    }

    private void openDeleteMenu(Player owner, BotData data) {
        Inventory inventory = Bukkit.createInventory(null, 27, "§8Bot löschen: " + data.name);
        inventory.setItem(11, button(Material.RED_CONCRETE, "§cJa, " + data.name + " löschen"));
        inventory.setItem(15, button(Material.GREEN_CONCRETE, "§aNein, zurück"));
        sessions.put(inventory, new MenuSession(owner.getUniqueId(), data.id, MenuType.DELETE));
        owner.openInventory(inventory);
    }

    /** Menu slot -> index in the bot's 36-slot storage (0–8 hotbar, 9–35 inventory), or -1. */
    private static int storageIndex(int slot) {
        if (slot == SLOT_MAINHAND) return 0;
        if (slot >= 18 && slot <= 44) return slot - 9;
        if (slot >= 46 && slot <= 53) return slot - 45;
        return -1;
    }

    private static boolean isEditableLoadoutSlot(int slot) {
        return storageIndex(slot) >= 0 || (slot >= SLOT_HELMET && slot <= SLOT_BOOTS) || slot == SLOT_OFFHAND;
    }

    private void openLoadoutMenu(Player owner, BotData data) {
        Inventory inventory = Bukkit.createInventory(null, 54, "§8Ausrüstung: " + data.name);
        inventory.setItem(0, button(Material.LEATHER_HELMET, "§eHelm §7↓"));
        inventory.setItem(1, button(Material.LEATHER_CHESTPLATE, "§eBrustpanzer §7↓"));
        inventory.setItem(2, button(Material.LEATHER_LEGGINGS, "§eHose §7↓"));
        inventory.setItem(3, button(Material.LEATHER_BOOTS, "§eSchuhe §7↓"));
        inventory.setItem(4, button(Material.GRAY_STAINED_GLASS_PANE, "§8"));
        inventory.setItem(5, button(Material.TOTEM_OF_UNDYING, "§dOffhand §7↓", "§7z. B. Totem, Schild, Goldäpfel"));
        inventory.setItem(6, button(Material.IRON_SWORD, "§bMain-Hand §7↓", "§7Das Item, das der Bot in der Hand hält"));
        inventory.setItem(SLOT_SAVE, button(Material.LIME_DYE, "§aSpeichern und zurück"));
        inventory.setItem(8, button(Material.BOOK, "§fSo funktioniert es",
                "§7Reihe 2: Rüstung, Offhand, Main-Hand", "§7Reihen 3–5: Inventar",
                "§7Unten: Hotbar (Waffen, Crystals, Obsidian, Windkugeln)",
                "§7Der Bot nimmt sich Waffen auch aus dem Inventar."));
        inventory.setItem(SLOT_HELMET, cloneOrNull(data.helmet));
        inventory.setItem(SLOT_CHEST, cloneOrNull(data.chestplate));
        inventory.setItem(SLOT_LEGS, cloneOrNull(data.leggings));
        inventory.setItem(SLOT_BOOTS, cloneOrNull(data.boots));
        inventory.setItem(13, button(Material.GRAY_STAINED_GLASS_PANE, "§8"));
        inventory.setItem(SLOT_OFFHAND, cloneOrNull(data.offhand));
        inventory.setItem(16, button(Material.GRAY_STAINED_GLASS_PANE, "§8"));
        inventory.setItem(17, button(Material.CHEST, "§fInventar §7↓"));
        inventory.setItem(45, button(Material.NAME_TAG, "§eHotbar §7→", "§7Slot 1 der Hotbar ist die Main-Hand oben"));
        for (int slot = 0; slot < 54; slot++) {
            int index = storageIndex(slot);
            if (index >= 0) inventory.setItem(slot, cloneOrNull(data.contents[index]));
        }
        sessions.put(inventory, new MenuSession(owner.getUniqueId(), data.id, MenuType.LOADOUT));
        owner.openInventory(inventory);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory top = event.getView().getTopInventory();
        MenuSession session = sessions.get(top);
        if (session == null) return;
        if (!session.owner.equals(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        BotData data = bots.get(session.botId);

        if (session.type == MenuType.LOADOUT) {
            // Normal inventory behaviour in the editable slots; Geyser translates Bedrock taps into these.
            if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR) event.setCancelled(true);
            if (event.getClickedInventory() == top && !isEditableLoadoutSlot(event.getRawSlot())) {
                event.setCancelled(true);
                if (event.getRawSlot() == SLOT_SAVE && data != null) {
                    Bukkit.getScheduler().runTask(this, () -> openSettingsMenu(player, data));
                }
            }
            return;
        }

        event.setCancelled(true);
        if (event.getClickedInventory() != top) return;
        int slot = event.getRawSlot();
        if (session.type == MenuType.LIST) {
            if (slot == 26) {
                player.closeInventory();
                return;
            }
            List<BotData> owned = botsFor(player.getUniqueId());
            for (int index = 0; index < Math.min(owned.size(), LIST_SLOTS.length); index++) {
                if (LIST_SLOTS[index] == slot) {
                    BotData selected = owned.get(index);
                    Bukkit.getScheduler().runTask(this, () -> openSettingsMenu(player, selected));
                    return;
                }
            }
            return;
        }
        if (data == null) {
            player.closeInventory();
            return;
        }
        if (session.type == MenuType.SETTINGS) {
            switch (slot) {
                case 10 -> {
                    data.attackEnabled = !data.attackEnabled;
                    saveBot(data);
                    reopenSettings(player, data);
                }
                case 11 -> {
                    data.difficulty = data.difficulty.next();
                    saveBot(data);
                    reopenSettings(player, data);
                }
                case 12 -> {
                    data.crystalEnabled = !data.crystalEnabled;
                    saveBot(data);
                    reopenSettings(player, data);
                }
                case 13 -> {
                    data.maceEnabled = !data.maceEnabled;
                    saveBot(data);
                    reopenSettings(player, data);
                }
                case 14 -> Bukkit.getScheduler().runTask(this, () -> openLoadoutMenu(player, data));
                case 15 -> teleportToBot(player, data);
                case 16 -> bringBot(player, data);
                case 18 -> Bukkit.getScheduler().runTask(this, () -> openListMenu(player));
                case 26 -> Bukkit.getScheduler().runTask(this, () -> openDeleteMenu(player, data));
                default -> { }
            }
        } else if (session.type == MenuType.DELETE) {
            if (slot == 11) {
                deleteBot(data);
                player.sendMessage("§aBot §f" + data.name + " §awurde gelöscht.");
                Bukkit.getScheduler().runTask(this, () -> openListMenu(player));
            } else if (slot == 15) {
                reopenSettings(player, data);
            }
        }
    }

    private void reopenSettings(Player player, BotData data) {
        Bukkit.getScheduler().runTask(this, () -> openSettingsMenu(player, data));
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        MenuSession session = sessions.get(top);
        if (session == null) return;
        if (session.type != MenuType.LOADOUT || !session.owner.equals(event.getWhoClicked().getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < top.getSize() && !isEditableLoadoutSlot(rawSlot)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        Inventory inventory = event.getInventory();
        MenuSession session = sessions.remove(inventory);
        if (session == null || session.type != MenuType.LOADOUT) return;
        BotData data = bots.get(session.botId);
        if (data == null) return;
        for (int slot = 0; slot < 54; slot++) {
            int index = storageIndex(slot);
            if (index >= 0) data.contents[index] = cloneOrNull(inventory.getItem(slot));
        }
        data.helmet = cloneOrNull(inventory.getItem(SLOT_HELMET));
        data.chestplate = cloneOrNull(inventory.getItem(SLOT_CHEST));
        data.leggings = cloneOrNull(inventory.getItem(SLOT_LEGS));
        data.boots = cloneOrNull(inventory.getItem(SLOT_BOOTS));
        data.offhand = cloneOrNull(inventory.getItem(SLOT_OFFHAND));
        saveBot(data);
        applyLoadout(data);
        event.getPlayer().sendMessage("§aAusrüstung von §f" + data.name + " §agespeichert.");
    }

    // ------------------------------------------------------------------ bot management

    private NPC ensureSpawned(Player owner, BotData data) {
        NPC npc = npcRegistry.getById(data.id);
        if (npc == null) {
            owner.sendMessage("§cDieser Bot ist nicht mehr vorhanden.");
            return null;
        }
        if (!npc.isSpawned()) {
            Location stored = npc.getStoredLocation();
            if (stored == null || !npc.spawn(stored)) {
                if (!npc.spawn(owner.getLocation())) {
                    owner.sendMessage("§cDer Bot konnte nicht gespawnt werden.");
                    return null;
                }
            }
            applyLoadout(data);
        }
        return npc;
    }

    private void teleportToBot(Player owner, BotData data) {
        NPC npc = ensureSpawned(owner, data);
        if (npc == null || npc.getEntity() == null) return;
        owner.closeInventory();
        owner.teleport(npc.getEntity().getLocation().clone().add(1.0, 0.0, 0.0));
    }

    private void bringBot(Player owner, BotData data) {
        NPC npc = ensureSpawned(owner, data);
        if (npc == null) return;
        owner.closeInventory();
        npc.teleport(owner.getLocation().clone().add(owner.getLocation().getDirection().setY(0).normalize().multiply(2)),
                PlayerTeleportEvent.TeleportCause.PLUGIN);
        owner.sendMessage("§a" + data.name + " §7ist jetzt bei dir.");
    }

    private void deleteBot(BotData data) {
        NPC npc = npcRegistry.getById(data.id);
        if (npc != null) npc.destroy();
        bots.remove(data.id);
        states.remove(data.id);
        getConfig().set("bots." + data.id, null);
        saveConfig();
        npcRegistry.saveToStore();
    }

    private List<BotData> botsFor(UUID owner) {
        return bots.values().stream().filter(bot -> bot.owner.equals(owner))
                .sorted(Comparator.comparingInt(bot -> bot.id)).toList();
    }

    // ------------------------------------------------------------------ fight logic

    private static final class BotState {
        UUID target;
        long inRangeSince = -1;
        long nextAttack;
        int combo;
        long pauseUntil;
        long retreatUntil;
        long nextEat;
        long nextWind;
        long nextCrystal;
        UUID activeCrystal;
        long nextStrafe;
        boolean airborne;
        boolean airWithMace;
        long airUntil;
        long totemMissingSince = -1;
    }

    /** Like a player: after a totem pops, put the next one from the inventory into the empty offhand. */
    private void refillTotem(NPC npc, Player bot, BotState st) {
        PlayerInventory inv = bot.getInventory();
        ItemStack off = inv.getItemInOffHand();
        if (off != null && !off.getType().isAir()) {
            st.totemMissingSince = -1;
            return;
        }
        int slot = inv.first(Material.TOTEM_OF_UNDYING);
        if (slot < 0) return;
        if (st.totemMissingSince < 0) {
            st.totemMissingSince = currentTick;
            return;
        }
        if (currentTick - st.totemMissingSince < 6) return;
        ItemStack totem = inv.getItem(slot);
        inv.setItem(slot, null);
        inv.setItemInOffHand(totem);
        npc.getOrAddTrait(Equipment.class).set(Equipment.EquipmentSlot.OFF_HAND, inv.getItemInOffHand());
        st.totemMissingSince = -1;
    }

    /** A used totem must not come back: keep Citizens' equipment in sync with the emptied offhand. */
    @EventHandler(ignoreCancelled = true)
    public void onResurrect(EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Player p) || !npcRegistry.isNPC(p)) return;
        NPC npc = npcRegistry.getNPC(p);
        Bukkit.getScheduler().runTask(this, () ->
                npc.getOrAddTrait(Equipment.class).set(Equipment.EquipmentSlot.OFF_HAND, p.getInventory().getItemInOffHand()));
    }

    private void idle(NPC npc, BotState st) {
        if (npc.getNavigator().isNavigating()) npc.getNavigator().cancelNavigation();
        st.target = null;
        st.inRangeSince = -1;
        st.combo = 0;
        st.airborne = false;
    }

    private void tickBots() {
        currentTick++;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (BotData data : bots.values()) {
            NPC npc = npcRegistry.getById(data.id);
            if (npc == null || !npc.isSpawned() || !(npc.getEntity() instanceof Player bot)) continue;
            BotState st = states.computeIfAbsent(data.id, id -> new BotState());
            refillTotem(npc, bot, st);
            if (!data.attackEnabled) {
                idle(npc, st);
                continue;
            }
            Player target = findTarget(data, bot, st);
            if (target == null) {
                idle(npc, st);
                continue;
            }
            if (!target.getUniqueId().equals(st.target)) {
                st.target = target.getUniqueId();
                st.combo = 0;
                st.inRangeSince = -1;
            }
            Difficulty d = data.difficulty;
            npc.faceLocation(target.getEyeLocation());
            double dist = bot.getLocation().distance(target.getLocation());
            PlayerInventory inv = bot.getInventory();

            // Low on health: step back and eat (Normal/Schwer).
            if (d != Difficulty.LEICHT && bot.getHealth() <= 8 && currentTick >= st.nextEat && eat(bot)) {
                st.nextEat = currentTick + 200;
                st.retreatUntil = currentTick + 30;
            }
            if (currentTick < st.retreatUntil || currentTick < st.pauseUntil) {
                Vector away = bot.getLocation().toVector().subtract(target.getLocation().toVector()).setY(0);
                if (away.lengthSquared() < 0.01) away = new Vector(1, 0, 0);
                npc.getNavigator().setTarget(bot.getLocation().add(away.normalize().multiply(4)));
                continue;
            }

            // Chase until in reach, then stand and fight (with small side steps).
            if (dist > d.reach - 0.2) {
                if (!npc.getNavigator().isNavigating()) npc.getNavigator().setTarget(target, false);
            } else {
                if (npc.getNavigator().isNavigating()) npc.getNavigator().cancelNavigation();
                if (bot.isOnGround() && currentTick >= st.nextStrafe && d != Difficulty.LEICHT) {
                    Vector toTarget = target.getLocation().toVector().subtract(bot.getLocation().toVector()).setY(0);
                    if (toTarget.lengthSquared() > 0.01) {
                        Vector side = new Vector(-toTarget.getZ(), 0, toTarget.getX()).normalize().multiply(rnd.nextBoolean() ? 0.28 : -0.28);
                        bot.setVelocity(bot.getVelocity().add(side));
                    }
                    st.nextStrafe = currentTick + 20 + rnd.nextInt(25);
                }
            }

            if (dist <= d.reach) {
                if (st.inRangeSince < 0) st.inRangeSince = currentTick;
            } else {
                st.inRangeSince = -1;
            }

            // Mace PvP: launch with a wind charge, then smash on the way down.
            if (data.maceEnabled && d.windCooldown > 0 && !st.airborne && bot.isOnGround() && dist <= 4.5
                    && currentTick >= st.nextWind && inv.contains(Material.MACE)
                    && inv.contains(Material.WIND_CHARGE)) {
                inv.removeItem(new ItemStack(Material.WIND_CHARGE, 1));
                bot.getWorld().playSound(bot.getLocation(), Sound.ENTITY_WIND_CHARGE_WIND_BURST, 1f, 1f);
                bot.setVelocity(new Vector(0, 1.15, 0));
                st.airborne = true;
                st.airWithMace = true;
                st.airUntil = currentTick + 45;
                st.nextWind = currentTick + d.windCooldown;
                continue;
            }

            if (st.airborne) {
                if (currentTick > st.airUntil) {
                    st.airborne = false;
                } else if (bot.getVelocity().getY() < 0 && bot.getFallDistance() > (st.airWithMace ? 1.5f : 0.1f)
                        && dist <= d.reach + 0.4 && bot.hasLineOfSight(target)) {
                    if (st.airWithMace) select(npc, inv, m -> m == Material.MACE);
                    else selectBestWeapon(npc, inv);
                    strike(bot, target, d, rnd, st, inv);
                    st.airborne = false;
                }
                continue;
            }

            boolean ready = st.inRangeSince >= 0 && currentTick - st.inRangeSince >= d.reaction && currentTick >= st.nextAttack;
            if (ready && bot.hasLineOfSight(target)) {
                selectBestWeapon(npc, inv);
                if (bot.isOnGround() && rnd.nextDouble() < d.critChance) {
                    // jump for a critical hit, strike while falling
                    bot.setVelocity(bot.getVelocity().setY(0.42));
                    st.airborne = true;
                    st.airWithMace = false;
                    st.airUntil = currentTick + 15;
                } else {
                    strike(bot, target, d, rnd, st, inv);
                }
            }

            if (data.crystalEnabled && currentTick >= st.nextCrystal) {
                int result = tryCrystal(data, npc, bot, target, st);
                st.nextCrystal = currentTick + switch (result) {
                    case 1 -> 6L; // obsidian placed
                    case 2 -> 2L; // real crystal placed; hit it on a later tick
                    case 3 -> 40L + d.extraDelay * 12L; // crystal detonated
                    case 4 -> 2L; // waiting to reach the crystal or retrying a cancelled hit
                    default -> 10L;
                };
            }
        }
    }

    private void strike(Player bot, Player target, Difficulty d, ThreadLocalRandom rnd, BotState st, PlayerInventory inv) {
        bot.swingMainHand();
        if (rnd.nextDouble() < d.hitChance) bot.attack(target);
        st.nextAttack = currentTick + attackDelay(inv.getItemInMainHand()) + d.extraDelay + rnd.nextInt(4);
        if (++st.combo >= d.comboCap) {
            // short break after a few hits, so fights are not endless combos
            st.combo = 0;
            st.pauseUntil = currentTick + d.backOff + rnd.nextInt(6);
        }
    }

    private Player findTarget(BotData data, Player bot, BotState st) {
        Player current = st.target == null ? null : Bukkit.getPlayer(st.target);
        if (valid(data, bot, current, 576.0)) return current; // keep the same target up to 24 blocks
        return bot.getWorld().getPlayers().stream()
                .filter(p -> valid(data, bot, p, 400.0))
                .min(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(bot.getLocation())))
                .orElse(null);
    }

    private boolean valid(BotData data, Player bot, Player p, double maxDistSq) {
        return p != null && p.isOnline() && !p.isDead() && p.getHealth() > 0
                && p.getWorld() == bot.getWorld()
                && !p.getUniqueId().equals(data.owner)
                && !npcRegistry.isNPC(p)
                && p.getGameMode() != GameMode.SPECTATOR && p.getGameMode() != GameMode.CREATIVE
                && p.getLocation().distanceSquared(bot.getLocation()) <= maxDistSq;
    }

    private int attackDelay(ItemStack weapon) {
        if (weapon == null) return 10;
        String n = weapon.getType().name();
        if (n.endsWith("_SWORD")) return 13;
        if (n.endsWith("_AXE")) return 20;
        if (weapon.getType() == Material.MACE) return 16;
        return 10;
    }

    private static int weaponScore(Material m) {
        String n = m.name();
        int tier = n.startsWith("NETHERITE") ? 6 : n.startsWith("DIAMOND") ? 5 : n.startsWith("IRON") ? 4
                : n.startsWith("STONE") ? 3 : n.startsWith("GOLDEN") ? 2 : n.startsWith("WOODEN") ? 1 : 0;
        if (n.endsWith("_SWORD")) return 20 + tier;
        if (n.endsWith("_AXE")) return 10 + tier;
        if (m == Material.MACE) return 5;
        return -1;
    }

    /** Puts the first matching item into the bot's hand, swapping it in from the inventory if needed. */
    private boolean equip(NPC npc, PlayerInventory inv, Predicate<Material> test) {
        int held = inv.getHeldItemSlot();
        ItemStack inHand = inv.getItem(held);
        if (inHand != null && test.test(inHand.getType())) return true;
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || !test.test(it.getType())) continue;
            if (i < 9) inv.setHeldItemSlot(i);
            else {
                inv.setItem(i, inHand);
                inv.setItem(held, it);
            }
            npc.getOrAddTrait(Equipment.class).set(Equipment.EquipmentSlot.HAND, inv.getItemInMainHand());
            return true;
        }
        return false;
    }

    private void select(NPC npc, PlayerInventory inv, Predicate<Material> test) {
        equip(npc, inv, test);
    }

    private void selectBestWeapon(NPC npc, PlayerInventory inv) {
        Material best = null;
        int bestScore = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null) continue;
            int score = weaponScore(it.getType());
            if (score > bestScore) {
                bestScore = score;
                best = it.getType();
            }
        }
        if (best != null) {
            Material wanted = best;
            equip(npc, inv, m -> m == wanted);
        }
    }

    /** Eats a golden apple (or enchanted one) from the bot inventory. */
    private boolean eat(Player bot) {
        PlayerInventory inv = bot.getInventory();
        Material food = inv.contains(Material.ENCHANTED_GOLDEN_APPLE) ? Material.ENCHANTED_GOLDEN_APPLE
                : inv.contains(Material.GOLDEN_APPLE) ? Material.GOLDEN_APPLE : null;
        if (food == null) return false;
        inv.removeItem(new ItemStack(food, 1));
        AttributeInstance max = bot.getAttribute(Attribute.MAX_HEALTH);
        double cap = max == null ? 20.0 : max.getValue();
        bot.setHealth(Math.min(cap, bot.getHealth() + 4));
        boolean strong = food == Material.ENCHANTED_GOLDEN_APPLE;
        bot.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, strong ? 400 : 100, strong ? 1 : 1));
        bot.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 2400, strong ? 3 : 0));
        bot.getWorld().playSound(bot.getLocation(), Sound.ENTITY_PLAYER_BURP, 1f, 1f);
        return true;
    }

    /** 0 = nothing, 1 = obsidian placed, 2 = real crystal placed, 3 = crystal detonated, 4 = waiting to hit. */
    private int tryCrystal(BotData data, NPC npc, Player bot, Player target, BotState state) {
        PlayerInventory inventory = bot.getInventory();
        if (state.activeCrystal != null) {
            Entity entity = Bukkit.getEntity(state.activeCrystal);
            if (!(entity instanceof EnderCrystal crystal) || !crystal.isValid()) {
                state.activeCrystal = null;
                return 3;
            }
            if (crystal.getWorld() != bot.getWorld()) {
                state.activeCrystal = null;
                return 0;
            }
            if (bot.getEyeLocation().distanceSquared(crystal.getLocation()) > 9.0
                    || !bot.hasLineOfSight(crystal)) return 4;

            npc.faceLocation(crystal.getLocation());
            bot.swingMainHand();
            bot.attack(crystal);
            if (!crystal.isValid()) {
                state.activeCrystal = null;
                return 3;
            }
            return 4;
        }

        if (!inventory.contains(Material.END_CRYSTAL) || !bot.hasLineOfSight(target)) return 0;
        World world = bot.getWorld();
        Block targetBlock = target.getLocation().getBlock();
        Player ownerPlayer = Bukkit.getPlayer(data.owner);
        Location ownerLocation = ownerPlayer == null ? null : ownerPlayer.getLocation();
        Location best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = -2; dy <= 1; dy++) {
                    Block base = world.getBlockAt(targetBlock.getX() + dx, targetBlock.getY() + dy, targetBlock.getZ() + dz);
                    if (base.getType() != Material.OBSIDIAN && base.getType() != Material.BEDROCK) continue;
                    if (!base.getRelative(0, 1, 0).getType().isAir() || !base.getRelative(0, 2, 0).getType().isAir()) continue;
                    Location place = new Location(world, base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5);
                    if (!crystalSpotOk(bot, target, ownerLocation, place)) continue;
                    double targetDistance = target.getLocation().distance(place);
                    if (targetDistance < bestDistance) {
                        best = place;
                        bestDistance = targetDistance;
                    }
                }
            }
        }
        if (best == null) return placeObsidian(npc, bot, target, ownerLocation) ? 1 : 0;

        equip(npc, inventory, m -> m == Material.END_CRYSTAL);
        bot.swingMainHand();
        EnderCrystal crystal = world.spawn(best, EnderCrystal.class, entity -> entity.setShowingBottom(true));
        Map<Integer, ItemStack> removed = inventory.removeItem(new ItemStack(Material.END_CRYSTAL, 1));
        if (!removed.isEmpty()) {
            crystal.remove();
            return 0;
        }
        state.activeCrystal = crystal.getUniqueId();
        return 2;
    }

    private boolean crystalSpotOk(Player bot, Player target, Location ownerLocation, Location place) {
        double botDistance = bot.getEyeLocation().distance(place);
        double targetDistance = target.getLocation().distance(place);
        // Keep placement and the following crystal hit inside ordinary melee reach.
        if (botDistance > 3.0 || botDistance < 2.0 || targetDistance > 6.0) return false;
        if (ownerLocation != null && ownerLocation.getWorld() == place.getWorld() && ownerLocation.distance(place) <= 6.0) return false;
        return place.getWorld().getNearbyEntities(place, 0.9, 2.0, 0.9).isEmpty();
    }

    /** Puts obsidian on the ground next to the target, as a player would before crystalling. */
    private boolean placeObsidian(NPC npc, Player bot, Player target, Location ownerLocation) {
        PlayerInventory inventory = bot.getInventory();
        if (!inventory.contains(Material.OBSIDIAN)) return false;
        Block feet = target.getLocation().getBlock();
        int[][] around = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}, {2, 0}, {-2, 0}, {0, 2}, {0, -2}};
        for (int[] o : around) {
            Block spot = feet.getRelative(o[0], 0, o[1]);
            if (!spot.getType().isAir() || !spot.getRelative(0, -1, 0).getType().isSolid()) continue;
            if (!spot.getRelative(0, 1, 0).getType().isAir() || !spot.getRelative(0, 2, 0).getType().isAir()) continue;
            Location crystalAt = spot.getLocation().add(0.5, 1.0, 0.5);
            if (!crystalSpotOk(bot, target, ownerLocation, crystalAt)) continue;
            if (!spot.getWorld().getNearbyEntities(spot.getLocation().add(0.5, 0.5, 0.5), 0.6, 0.6, 0.6).isEmpty()) continue;
            equip(npc, inventory, m -> m == Material.OBSIDIAN);
            bot.swingMainHand();
            spot.setType(Material.OBSIDIAN);
            inventory.removeItem(new ItemStack(Material.OBSIDIAN, 1));
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ persistence

    private void applyLoadout(BotData data) {
        NPC npc = npcRegistry.getById(data.id);
        if (npc == null || !npc.isSpawned() || !(npc.getEntity() instanceof Player bot)) return;
        PlayerInventory inventory = bot.getInventory();
        ItemStack[] contents = new ItemStack[36];
        for (int slot = 0; slot < contents.length; slot++) contents[slot] = cloneOrNull(data.contents[slot]);
        inventory.setStorageContents(contents);
        inventory.setHelmet(cloneOrNull(data.helmet));
        inventory.setChestplate(cloneOrNull(data.chestplate));
        inventory.setLeggings(cloneOrNull(data.leggings));
        inventory.setBoots(cloneOrNull(data.boots));
        inventory.setItemInOffHand(cloneOrNull(data.offhand));
        Equipment equipment = npc.getOrAddTrait(Equipment.class);
        equipment.set(Equipment.EquipmentSlot.HAND, inventory.getItemInMainHand());
        equipment.set(Equipment.EquipmentSlot.HELMET, inventory.getHelmet());
        equipment.set(Equipment.EquipmentSlot.CHESTPLATE, inventory.getChestplate());
        equipment.set(Equipment.EquipmentSlot.LEGGINGS, inventory.getLeggings());
        equipment.set(Equipment.EquipmentSlot.BOOTS, inventory.getBoots());
        equipment.set(Equipment.EquipmentSlot.OFF_HAND, inventory.getItemInOffHand());
    }

    private boolean botsLoaded;

    @EventHandler
    public void onCitizensEnable(CitizensEnableEvent event) {
        loadBotsOnce();
    }

    private void loadBotsOnce() {
        if (botsLoaded) return;
        botsLoaded = true;
        loadBots();
    }

    private void loadBots() {
        var section = getConfig().getConfigurationSection("bots");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            int id;
            UUID owner;
            try {
                id = Integer.parseInt(key);
                owner = UUID.fromString(getConfig().getString("bots." + key + ".owner", ""));
            } catch (IllegalArgumentException exception) {
                getConfig().set("bots." + key, null);
                continue;
            }
            NPC npc = npcRegistry.getById(id);
            if (npc == null) {
                // never delete: the NPC may just not be loaded yet
                getLogger().warning("Bot " + key + " wurde in Citizens nicht gefunden und bleibt gespeichert.");
                continue;
            }
            String path = "bots." + key;
            BotData data = new BotData(id, owner, getConfig().getString(path + ".name", npc.getName()));
            data.attackEnabled = getConfig().getBoolean(path + ".attack", false);
            data.crystalEnabled = getConfig().getBoolean(path + ".crystal", false);
            data.maceEnabled = getConfig().getBoolean(path + ".mace", true);
            data.difficulty = Difficulty.parse(getConfig().getString(path + ".difficulty", "NORMAL"));
            for (int slot = 0; slot < 36; slot++) data.contents[slot] = getConfig().getItemStack(path + ".items." + slot);
            data.helmet = getConfig().getItemStack(path + ".armor.helmet");
            data.chestplate = getConfig().getItemStack(path + ".armor.chestplate");
            data.leggings = getConfig().getItemStack(path + ".armor.leggings");
            data.boots = getConfig().getItemStack(path + ".armor.boots");
            data.offhand = getConfig().getItemStack(path + ".offhand");
            bots.put(id, data);
            applyLoadout(data);
        }
        saveConfig();
    }

    private void saveBot(BotData data) {
        String path = "bots." + data.id;
        getConfig().set(path + ".owner", data.owner.toString());
        getConfig().set(path + ".name", data.name);
        getConfig().set(path + ".attack", data.attackEnabled);
        getConfig().set(path + ".crystal", data.crystalEnabled);
        getConfig().set(path + ".mace", data.maceEnabled);
        getConfig().set(path + ".difficulty", data.difficulty.name());
        for (int slot = 0; slot < data.contents.length; slot++) getConfig().set(path + ".items." + slot, cloneOrNull(data.contents[slot]));
        getConfig().set(path + ".armor.helmet", cloneOrNull(data.helmet));
        getConfig().set(path + ".armor.chestplate", cloneOrNull(data.chestplate));
        getConfig().set(path + ".armor.leggings", cloneOrNull(data.leggings));
        getConfig().set(path + ".armor.boots", cloneOrNull(data.boots));
        getConfig().set(path + ".offhand", cloneOrNull(data.offhand));
        saveConfig();
    }

    private ItemStack button(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        var meta = item.getItemMeta();
        meta.setDisplayName(name);
        if (lore.length > 0) meta.setLore(Arrays.asList(lore));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack cloneOrNull(ItemStack item) {
        return item == null || item.getType().isAir() ? null : item.clone();
    }

    private static final class BotData {
        private final int id;
        private final UUID owner;
        private final String name;
        private final ItemStack[] contents = new ItemStack[36];
        private ItemStack helmet;
        private ItemStack chestplate;
        private ItemStack leggings;
        private ItemStack boots;
        private ItemStack offhand;
        private boolean attackEnabled;
        private boolean crystalEnabled;
        private boolean maceEnabled = true;
        private Difficulty difficulty = Difficulty.NORMAL;

        private BotData(int id, UUID owner, String name) {
            this.id = id;
            this.owner = owner;
            this.name = name;
        }
    }

    private enum MenuType { LIST, SETTINGS, DELETE, LOADOUT }

    private record MenuSession(UUID owner, int botId, MenuType type) { }
}

