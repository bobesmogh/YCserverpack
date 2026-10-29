package yardcraft.netherblight;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NavigableMap;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

public class NetherBlight extends JavaPlugin implements Listener {

    private record ChunkRef(World world, int x, int z) {}

    private static final Set<Material> PLANTS = EnumSet.of(
            Material.SHORT_GRASS, Material.TALL_GRASS, Material.FERN, Material.LARGE_FERN);

    private final Random rnd = new Random();
    private final ArrayDeque<ChunkRef> queue = new ArrayDeque<>();
    private final NavigableMap<Integer, Material> palette = new TreeMap<>();
    private int totalWeight;
    private NamespacedKey visitedKey;
    private BukkitTask nextCycle;
    private BukkitTask worker;
    private final Set<ChunkRef> lightningChunks = new HashSet<>();

    // ---------- настройки ----------
    private double minMinutes, maxMinutes, chance;
    private int radiusBlocks, maxChunkRadius, chunksPerTick;
    private boolean soundEnabled;
    private String soundKey;
    private float soundVolume, soundPitch;
    private boolean lightningEnabled, lightningReal;
    private int lightningPerPlayer, lightningMaxDelay;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        visitedKey = new NamespacedKey(this, "visited_nether");
        Bukkit.getPluginManager().registerEvents(this, this);
        loadSettings();
        scheduleNext();
    }

    @Override
    public void onDisable() {
        if (nextCycle != null) nextCycle.cancel();
        if (worker != null) worker.cancel();
        queue.clear();
        lightningChunks.clear();
    }

    private void loadSettings() {
        reloadConfig();
        minMinutes = getConfig().getDouble("interval-minutes.min", 15);
        maxMinutes = Math.max(minMinutes, getConfig().getDouble("interval-minutes.max", 20));
        chance = getConfig().getDouble("infection-percent", 20) / 100.0;
        radiusBlocks = getConfig().getInt("radius-blocks", 300);
        maxChunkRadius = getConfig().getInt("max-chunk-radius", 8);
        chunksPerTick = Math.max(1, getConfig().getInt("chunks-per-tick", 2));
        soundEnabled = getConfig().getBoolean("sound.enabled", true);
        soundKey = getConfig().getString("sound.key", "minecraft:entity.warden.heartbeat");
        soundVolume = (float) getConfig().getDouble("sound.volume", 1.0);
        soundPitch = (float) getConfig().getDouble("sound.pitch", 0.7);

        lightningEnabled = getConfig().getBoolean("lightning.enabled", true);
        lightningReal = getConfig().getBoolean("lightning.real", false);
        lightningPerPlayer = Math.max(0, getConfig().getInt("lightning.per-player-per-cycle", 3));
        lightningMaxDelay = Math.max(0, getConfig().getInt("lightning.max-delay-ticks", 100));

        palette.clear();
        totalWeight = 0;
        ConfigurationSection sec = getConfig().getConfigurationSection("blocks");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                Material m = Material.matchMaterial(key);
                int w = sec.getInt(key);
                if (m == null || !m.isBlock() || w <= 0) {
                    getLogger().warning("Пропущен блок в config.yml: " + key);
                    continue;
                }
                totalWeight += w;
                palette.put(totalWeight, m);
            }
        }
        if (palette.isEmpty()) {
            totalWeight = 1;
            palette.put(1, Material.NETHERRACK);
        }
    }

    // ---------- метка «был в Незере» ----------
    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        markIfInNether(e.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        markIfInNether(e.getPlayer());
    }

    private void markIfInNether(Player p) {
        if (p.getWorld().getEnvironment() == World.Environment.NETHER) {
            p.getPersistentDataContainer().set(visitedKey, PersistentDataType.BYTE, (byte) 1);
        }
    }

    private boolean visited(Player p) {
        return p.getPersistentDataContainer().has(visitedKey, PersistentDataType.BYTE);
    }

    // ---------- цикл заражения ----------
    private void scheduleNext() {
        double minutes = minMinutes + rnd.nextDouble() * (maxMinutes - minMinutes);
        long ticks = Math.max(20L, (long) (minutes * 60 * 20));
        nextCycle = Bukkit.getScheduler().runTaskLater(this, () -> {
            runCycle();
            scheduleNext();
        }, ticks);
    }

    /** Возвращает число игроков, вокруг которых запущено заражение. */
    private int runCycle() {
        Set<ChunkRef> chunks = new LinkedHashSet<>();
        int players = 0;
        int rc = Math.min((radiusBlocks + 15) >> 4, maxChunkRadius);

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!visited(p)) continue;
            World w = p.getWorld();
            if (w.getEnvironment() != World.Environment.NORMAL) continue;
            players++;

            if (soundEnabled) {
                p.playSound(p.getLocation(), soundKey, SoundCategory.AMBIENT, soundVolume, soundPitch);
            }

            List<ChunkRef> mine = new ArrayList<>();
            int pcx = p.getLocation().getBlockX() >> 4;
            int pcz = p.getLocation().getBlockZ() >> 4;
            for (int dx = -rc; dx <= rc; dx++) {
                for (int dz = -rc; dz <= rc; dz++) {
                    if (dx * dx + dz * dz > rc * rc) continue; // круг, а не квадрат
                    ChunkRef ref = new ChunkRef(w, pcx + dx, pcz + dz);
                    chunks.add(ref);
                    mine.add(ref);
                }
            }

            // выбираем чанки, где ударит молния (по одной точке на чанк)
            if (lightningEnabled && !mine.isEmpty()) {
                for (int i = 0; i < lightningPerPlayer; i++) {
                    lightningChunks.add(mine.get(rnd.nextInt(mine.size())));
                }
            }
        }

        queue.addAll(chunks);
        if (!queue.isEmpty() && (worker == null || worker.isCancelled())) {
            worker = Bukkit.getScheduler().runTaskTimer(this, this::work, 1L, 1L);
        }
        return players;
    }

    private void work() {
        for (int i = 0; i < chunksPerTick; i++) {
            ChunkRef ref = queue.poll();
            if (ref == null) {
                worker.cancel();
                return;
            }
            processChunk(ref);
        }
    }

    private void processChunk(ChunkRef ref) {
        World w = ref.world();
        boolean wantsStrike = lightningChunks.remove(ref);
        if (!w.isChunkLoaded(ref.x(), ref.z())) return; // только загруженные
        Block strike = null;
        int baseX = ref.x() << 4;
        int baseZ = ref.z() << 4;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                if (rnd.nextDouble() >= chance) continue;
                int bx = baseX + x;
                int bz = baseZ + z;
                int y = w.getHighestBlockYAt(bx, bz, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES);
                Block b = w.getBlockAt(bx, y, bz);
                if (b.getType() == Material.GRASS_BLOCK) {
                    infect(b);
                    if (strike == null) strike = b;
                }
            }
        }
        if (wantsStrike && strike != null) strikeLater(w, strike);
    }

    private void strikeLater(World w, Block b) {
        long delay = lightningMaxDelay > 0 ? rnd.nextInt(lightningMaxDelay) : 0;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Location loc = b.getLocation().add(0.5, 1, 0.5);
            if (lightningReal) {
                w.strikeLightning(loc);
            } else {
                w.strikeLightningEffect(loc); // только вспышка и гром, без урона и огня
            }
        }, delay);
    }

    private void infect(Block b) {
        Material m = palette.higherEntry(rnd.nextInt(totalWeight)).getValue();
        b.setType(m, false);

        Block up = b.getRelative(BlockFace.UP);
        Material u = up.getType();
        if (PLANTS.contains(u)) {
            if (u == Material.TALL_GRASS || u == Material.LARGE_FERN) {
                Block top = up.getRelative(BlockFace.UP);
                if (top.getType() == u) top.setType(Material.AIR, false);
            }
            up.setType(Material.AIR, false);
            u = Material.AIR;
        }
        // нарост поверх нилиума
        if (u == Material.AIR && rnd.nextDouble() < 0.25) {
            if (m == Material.CRIMSON_NYLIUM) {
                up.setType(Material.CRIMSON_ROOTS, false);
            } else if (m == Material.WARPED_NYLIUM) {
                up.setType(rnd.nextBoolean() ? Material.WARPED_ROOTS : Material.NETHER_SPROUTS, false);
            }
        }
    }

    // ---------- команды ----------
    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length == 0) {
            s.sendMessage("/netherblight run | mark <игрок> | unmark <игрок> | reload");
            return true;
        }
        switch (a[0].toLowerCase()) {
            case "run" -> s.sendMessage("Цикл запущен, игроков: " + runCycle());
            case "reload" -> {
                loadSettings();
                s.sendMessage("Конфиг перезагружен.");
            }
            case "mark", "unmark" -> {
                if (a.length < 2) {
                    s.sendMessage("Укажите игрока.");
                    return true;
                }
                Player p = Bukkit.getPlayerExact(a[1]);
                if (p == null) {
                    s.sendMessage("Игрок не в сети.");
                    return true;
                }
                if (a[0].equalsIgnoreCase("mark")) {
                    p.getPersistentDataContainer().set(visitedKey, PersistentDataType.BYTE, (byte) 1);
                } else {
                    p.getPersistentDataContainer().remove(visitedKey);
                }
                s.sendMessage("Готово: " + p.getName());
            }
            default -> s.sendMessage("Неизвестная подкоманда.");
        }
        return true;
    }
}
