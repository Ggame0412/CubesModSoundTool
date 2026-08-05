package soundtools;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Sound;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;

import ethanjones.cubes.block.Block;
import ethanjones.cubes.core.event.entity.living.player.PlayerBreakBlockEvent;
import ethanjones.cubes.core.event.entity.living.player.PlayerMovementEvent;
import ethanjones.cubes.core.event.entity.living.player.PlayerPlaceBlockEvent;
import ethanjones.cubes.core.logging.Log;
import ethanjones.cubes.core.mod.Mod;
import ethanjones.cubes.core.mod.ModEventHandler;
import ethanjones.cubes.core.mod.event.InitializationEvent; // ПРЕДПОЛОЖЕНИЕ: пакет по аналогии со StartingServerEvent.
                                                              // Если не скомпилируется - пришлите содержимое
                                                              // core/mod/event, поправлю импорт.
import ethanjones.cubes.core.platform.Compatibility;
import ethanjones.cubes.entity.living.player.Player;
import ethanjones.cubes.side.common.Side;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

@Mod
public class SoundTools {

  // ====================== Настройки ======================

  // ВАЖНО: имя .cm файла как он лежит в Cubes/mods/. Если ваш мод после сборки
  // называется иначе, поменяйте здесь - иначе звуки просто не найдутся (см. лог).
  private static final String MOD_FILE_NAME = "SoundTools.cm";

  // Материалы, которые ищем при старте. Просто добавьте сюда новое имя и
  // положите файлы в assets/sounds/<material>/ - код сам подхватит.
  private static final String[] MATERIALS = {"iron", "wood", "grass"};

  // Насколько случайно меняется питч/громкость при каждом проигрывании (аналог SoundType в других играх)
  private static final float PITCH_VARIANCE = 0.15f;   // ±15%
  private static final float VOLUME_MIN = 0.85f;
  private static final float VOLUME_MAX = 1.0f;

  // Через сколько блоков горизонтального перемещения играть звук шага
  private static final float STEP_DISTANCE = 1.0f;

  private static final Random random = new Random();

  // ====================== Маппинг блок -> материал ======================

  private static final Map<String, String> BLOCK_MATERIAL = new HashMap<String, String>();
  private static final String DEFAULT_MATERIAL = "grass";

  static {
    BLOCK_MATERIAL.put("core:stone", "iron");     // временно "iron" пока нет отдельного "stone" - поменяйте при добавлении звуков камня
    BLOCK_MATERIAL.put("core:bedrock", "iron");
    BLOCK_MATERIAL.put("core:dirt", "grass");
    BLOCK_MATERIAL.put("core:grass", "grass");
    BLOCK_MATERIAL.put("core:sapling", "grass");
    BLOCK_MATERIAL.put("core:leaves", "grass");
    BLOCK_MATERIAL.put("core:log", "wood");
    BLOCK_MATERIAL.put("core:chest", "wood");
    BLOCK_MATERIAL.put("core:planks", "wood");
    BLOCK_MATERIAL.put("core:glass", "iron");     // временная заглушка
  }

  // ====================== Загруженные звуки ======================

  private static class MaterialSounds {
    Sound[] breakSounds = new Sound[0];
    Sound[] stepSounds = new Sound[0]; // используется и для шагов, и для звука установки (один "шаг")
  }

  private static final Map<String, MaterialSounds> SOUNDS = new HashMap<String, MaterialSounds>();

  // Накопитель пройденного расстояния на игрока, для шагов
  private static final Map<Player, Float> stepAccumulator = new ConcurrentHashMap<Player, Float>();

  // ====================== Загрузка звуков ======================

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

  /**
   * ПРЕДПОЛОЖЕНИЕ: мод распаковывается в Cubes/mods/temp/<ModFile>/ (как видно
   * в логе для FileTools: ".../mods/temp/FileTools.cm/mod.jar"). Ассеты, если
   * есть, скорее всего лежат рядом в assets/. Проверяем оба варианта пути и
   * логируем, какой сработал - если ни один не найден, звуки просто не
   * загрузятся (без краша), а лог покажет, какие пути пробовались.
   */
  private FileHandle findAssetDir(String relative) {
    File base = Compatibility.get().getBaseFolder().file(); // абсолютный путь, см. фикс FileTools

    String[] candidates = new String[]{
        new File(base, "mods/temp/" + MOD_FILE_NAME + "/assets/" + relative).getPath(),
        new File(base, "mods/" + MOD_FILE_NAME + "/assets/" + relative).getPath()
    };

    for (String path : candidates) {
      FileHandle fh = Gdx.files.absolute(path);
      if (fh.exists() && fh.isDirectory()) {
        Log.info("[SoundTools] Found assets at: " + path);
        return fh;
      }
    }

    Log.warning("[SoundTools] No asset folder found for 'sounds/" + relative.substring(relative.lastIndexOf('/') + 1)
        + "'. Tried: " + Arrays.toString(candidates));
    return null;
  }

  // ====================== Логика проигрывания ======================

  private static String getMaterial(Block block) {
    if (block == null) return DEFAULT_MATERIAL;
    String material = BLOCK_MATERIAL.get(block.id);
    return material != null ? material : DEFAULT_MATERIAL;
  }

  /**
   * Проигрывает случайный звук из набора со случайным питчем/громкостью (аналог SoundType/SoLoud).
   * Оборачиваем в postRunnable: события ломания/установки блока приходят с серверного потока
   * (см. IntegratedServer.run() в трейсе краша ранее), а OpenAL-вызовы обычно должны идти с
   * рендер-потока. Если звук всё равно не проигрывается / крашится - это первое место для проверки.
   */
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

  // ====================== События ======================

  @ModEventHandler
  public void onBreak(PlayerBreakBlockEvent event) {
    String material = getMaterial(event.getBlock());
    MaterialSounds ms = SOUNDS.get(material);
    if (ms != null) playRandom(ms.breakSounds, 1.0f);
  }

  @ModEventHandler
  public void onPlace(PlayerPlaceBlockEvent event) {
    String material = getMaterial(event.getBlock());
    MaterialSounds ms = SOUNDS.get(material);
    // По просьбе: звук установки = один звук шага этого материала
    if (ms != null) playRandom(ms.stepSounds, 1.0f);
  }

  @ModEventHandler
  public void onMove(PlayerMovementEvent event) {
    if (!Side.isClient()) return; // звук шагов нужен только локально для игрока

    Player player = event.getPlayer();
    Vector3 oldPos = event.oldPosition;
    Vector3 newPos = event.newPosition;

    float dx = newPos.x - oldPos.x;
    float dz = newPos.z - oldPos.z;
    float horizontalDist = (float) Math.sqrt(dx * dx + dz * dz);
    if (horizontalDist < 0.001f) return;

    Float previous = stepAccumulator.get(player);
    float accumulated = (previous == null ? 0f : previous) + horizontalDist;

    if (accumulated >= STEP_DISTANCE) {
      accumulated -= STEP_DISTANCE;

      int blockX = MathUtils.floor(newPos.x);
      int blockY = MathUtils.floor(newPos.y) - 1; // блок под ногами
      int blockZ = MathUtils.floor(newPos.z);

      Block underfoot = Side.getCubes().world.getBlock(blockX, blockY, blockZ);
      if (underfoot != null) {
        String material = getMaterial(underfoot);
        MaterialSounds ms = SOUNDS.get(material);
        if (ms != null) playRandom(ms.stepSounds, 0.6f); // шаги тише, чем ломание
      }
    }

    stepAccumulator.put(player, accumulated);
  }
}
