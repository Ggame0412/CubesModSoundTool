package soundtools;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Sound;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.math.MathUtils;
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
    private static final float STEP_DISTANCE = 0.5f;
    private static final Random random = new Random();

    private static final Map<String, String> BLOCK_MATERIAL = new HashMap<String, String>();
    private static final String DEFAULT_MATERIAL = "grass";

    static {
        BLOCK_MATERIAL.put("core:stone", "iron");
        BLOCK_MATERIAL.put("core:bedrock", "iron");
        BLOCK_MATERIAL.put("core:dirt", "iron");
        BLOCK_MATERIAL.put("core:grass", "iron");
        BLOCK_MATERIAL.put("core:leaves", "iron");
        BLOCK_MATERIAL.put("core:log", "iron");
        BLOCK_MATERIAL.put("core:chest", "iron");
        BLOCK_MATERIAL.put("core:planks", "iron");
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

        Log.info("[SoundTools] move dist=" + horizontalDist + " old=" + oldPos + " new=" + newPos);
        if (horizontalDist < 0.001f) return;

        Float previous = stepAccumulator.get(player);
        float accumulated = (previous == null ? 0f : previous) + horizontalDist;

        if (accumulated >= STEP_DISTANCE) {
            accumulated -= STEP_DISTANCE;

            int blockX = MathUtils.floor(newPos.x);
            int blockY = MathUtils.floor(newPos.y) - 1;
            int blockZ = MathUtils.floor(newPos.z);

            Block underfoot = Side.getCubes().world.getBlock(blockX, blockY, blockZ);
            if (underfoot != null) {
                String material = getMaterial(underfoot);
                MaterialSounds ms = SOUNDS.get(material);
                if (ms != null) playRandom(ms.stepSounds, 0.6f);
            }
        }

        stepAccumulator.put(player, accumulated);
    }
}
