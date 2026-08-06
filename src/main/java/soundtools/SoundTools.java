package soundtools;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Sound;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.math.Vector3;

import ethanjones.cubes.block.Block;
import ethanjones.cubes.core.event.EventHandler;
import ethanjones.cubes.core.event.entity.living.player.PlayerBreakBlockEvent;
import ethanjones.cubes.core.event.entity.living.player.PlayerMovementEvent;
import ethanjones.cubes.core.event.entity.living.player.PlayerPlaceBlockEvent;
import ethanjones.cubes.core.logging.Log;
import ethanjones.cubes.core.mod.Mod;
import ethanjones.cubes.core.mod.ModEventHandler;
import ethanjones.cubes.core.mod.event.InitializationEvent;
import ethanjones.cubes.entity.living.player.Player;
import ethanjones.cubes.graphics.assets.Assets;
import ethanjones.cubes.side.common.Side;
import ethanjones.cubes.world.CoordinateConverter;
import ethanjones.cubes.world.World;
import ethanjones.cubes.world.gravity.WorldGravity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

@Mod
public class SoundTools {

    private static final String MOD_FILE_NAME = "SoundTools.cm";
    private static final String[] MATERIALS = {"iron", "wood", "grass"};
    private static final float PITCH_VARIANCE = 0.15f;
    private static final float VOLUME_MIN = 0.85f;
    private static final float VOLUME_MAX = 1.0f;
    private static final float STEP_DISTANCE = 1.0f;
    private static final Random random = new Random();

    private static final Map<String, String> BLOCK_MATERIAL = new HashMap<String, String>();
    private static final String DEFAULT_MATERIAL = "grass";

    static {
        BLOCK_MATERIAL.put("core:stone", "iron");
        BLOCK_MATERIAL.put("core:bedrock", "iron");
        BLOCK_MATERIAL.put("core:dirt", "grass");
        BLOCK_MATERIAL.put("core:grass", "grass");
        BLOCK_MATERIAL.put("core:sapling", "grass");
        BLOCK_MATERIAL.put("core:leaves", "grass");
        BLOCK_MATERIAL.put("core:log", "wood");
        BLOCK_MATERIAL.put("core:chest", "wood");
        BLOCK_MATERIAL.put("core:planks", "wood");
        BLOCK_MATERIAL.put("core:glass", "iron");
    }

    private static class MaterialSounds {
        Sound[] breakSounds = new Sound[0];
        Sound[] stepSounds = new Sound[0];
    }

    private static final Map<String, MaterialSounds> SOUNDS = new HashMap<String, MaterialSounds>();
    private static final Map<Player, Float> stepAccumulator = new ConcurrentHashMap<Player, Float>();

    @ModEventHandler
    public void init(InitializationEvent event) {
        for (String material : MATERIALS) {
            MaterialSounds ms = new MaterialSounds();
            ms.breakSounds = loadSoundSet(material, "break");
            ms.stepSounds = loadSoundSet(material, "step");
            SOUNDS.put(material, ms);
            Log.info("[SoundTools] " + material + ": " + ms.breakSounds.length + " break, " + ms.stepSounds.length + " step sound(s)");
        }
    }

    private Sound[] loadSoundSet(String material, String kind) {
        List<Sound> list = new ArrayList<Sound>();
        FileHandle dir = findAssetDir("sounds/" + material);
        if (dir == null) return new Sound[0];

        FileHandle[] children = dir.list();
        if (children == null) return new Sound[0];

        for (FileHandle f : children) {
            if (f.isDirectory()) continue;
            String name = f.name().toLowerCase();
            String ext = f.extension().toLowerCase();
            boolean audioExt = ext.equals("wav") || ext.equals("ogg") || ext.equals("mp3");
            if (name.startsWith(kind) && audioExt) {
                try {
                    list.add(Gdx.audio.newSound(f));
                } catch (Exception e) {
                    Log.warning("[SoundTools] Failed to load sound: " + f.path(), e);
                }
            }
        }
        return list.toArray(new Sound[0]);
    }

    private FileHandle findAssetDir(String relative) {
        FileHandle target = Assets.assetsFolder.child(MOD_FILE_NAME).child(relative);
        if (target.exists() && target.isDirectory()) {
            return target;
        }
        Log.warning("[SoundTools] Asset folder not found: " + target.path());
        return null;
    }

    private static String getMaterial(Block block) {
        if (block == null) return DEFAULT_MATERIAL;
        String material = BLOCK_MATERIAL.get(block.id);
        return material != null ? material : DEFAULT_MATERIAL;
    }

    private static void playRandom(final Sound[] sounds, final float baseVolume) {
        if (sounds == null || sounds.length == 0) return;
        final Sound sound = sounds[random.nextInt(sounds.length)];
        final float pitch = 1f + (random.nextFloat() * 2f - 1f) * PITCH_VARIANCE;
        final float volume = (VOLUME_MIN + random.nextFloat() * (VOLUME_MAX - VOLUME_MIN)) * baseVolume;

        Gdx.app.postRunnable(new Runnable() {
            @Override
            public void run() {
                sound.play(volume, pitch, 0f);
            }
        });
    }

    @EventHandler
    public void onBreak(PlayerBreakBlockEvent event) {
        String material = getMaterial(event.getBlock());
        MaterialSounds ms = SOUNDS.get(material);
        if (ms != null) playRandom(ms.breakSounds, 1.0f);
    }

    @EventHandler
    public void onPlace(PlayerPlaceBlockEvent event) {
        String material = getMaterial(event.getBlock());
        MaterialSounds ms = SOUNDS.get(material);
        if (ms != null) playRandom(ms.stepSounds, 1.0f);
    }

    @EventHandler
    public void onMove(PlayerMovementEvent event) {
        if (!Side.isClient()) return;

        Player player = event.getPlayer();
        Vector3 oldPos = event.oldPosition;
        Vector3 newPos = event.newPosition;

        float dx = newPos.x - oldPos.x;
        float dz = newPos.z - oldPos.z;
        float horizontalDist = (float) Math.sqrt(dx * dx + dz * dz);

        if (horizontalDist < 0.001f) return;

        World world = Side.getCubes().world;
        boolean grounded = WorldGravity.onBlock(world, newPos, Player.PLAYER_HEIGHT, Player.PLAYER_RADIUS);
        if (!grounded) {
            // В воздухе (прыжок/падение) - это не шаг. Сбрасываем накопитель, чтобы после
            // приземления не сыграл "запоздалый" звук от пройденного в воздухе расстояния.
            stepAccumulator.put(player, 0f);
            return;
        }

        Float previous = stepAccumulator.get(player);
        float accumulated = (previous == null ? 0f : previous) + horizontalDist;

        // ДИАГНОСТИКА 1: если playerId меняется каждый вызов - значит player не годится
        // как ключ карты (например, событие создаёт новый враппер), и накопление
        // расстояния всегда обнуляется, потому что previous всегда null.
        Log.info("[SoundTools] step-debug playerId=" + System.identityHashCode(player)
                + " prev=" + previous + " dist=" + horizontalDist + " acc=" + accumulated
                + " threshold=" + STEP_DISTANCE);

        if (accumulated >= STEP_DISTANCE) {
            accumulated -= STEP_DISTANCE;

            // Как в Player.updatePosition(): position.y - это верх игрока (голова),
            // а не ступни. Блок под ногами = position.y - PLAYER_HEIGHT, без доп. "-1".
            int blockX = CoordinateConverter.block(newPos.x);
            int blockY = CoordinateConverter.block(newPos.y - Player.PLAYER_HEIGHT);
            int blockZ = CoordinateConverter.block(newPos.z);

            Block underfoot = Side.getCubes().world.getBlock(blockX, blockY, blockZ);
            String material = getMaterial(underfoot);
            MaterialSounds ms = SOUNDS.get(material);

            Log.info("[SoundTools] step-trigger block=(" + blockX + "," + blockY + "," + blockZ + ")"
                    + " underfoot=" + (underfoot == null ? "null" : underfoot.id)
                    + " material=" + material
                    + " stepSounds=" + (ms == null ? "no MaterialSounds" : ms.stepSounds.length));

            if (ms != null) playRandom(ms.stepSounds, 0.6f);
        }

        stepAccumulator.put(player, accumulated);
    }
}