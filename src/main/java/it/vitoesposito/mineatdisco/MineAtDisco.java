package it.vitoesposito.mineatdisco;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import it.vitoesposito.mineatdisco.mixin.FallingLeavesInvoker;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.libc.LibCStdlib;

/** Plays a song from the disco folder and moves the game to its beat. */
public final class MineAtDisco implements ClientModInitializer {
	private static final Path SONGS = FabricLoader.getInstance().getGameDir().resolve("disco");
	private static final Path OFFSET_FILE = SONGS.resolve("offset.txt");
	/** A sound this soon after a grid line, as a share of a grid step, plays at once. */
	private static final double ON_GRID = 0.2;
	/** How hard each beat of the bar hits. The first beat leads, the third answers. */
	private static final float[] ACCENT = {1, 0.4f, 0.7f, 0.4f};
	/** GUI pixels the hotbar and status bars rise on beat 1 at full loudness. */
	private static final float HUD_HOP = 3;
	/** Share of a beat the landing ring takes to open and fade. */
	private static final float RING = 0.35f;
	/** Beats the sparks answering an on-beat action take to fly out and fade. */
	private static final float FLASH_BEATS = 0.6f;
	private static final int GOLD = 0xFFD040;
	private static final int WHITE = 0xFFFFFF;
	/** Bracket color for a beat on which the player's next full-strength hit will be ready. */
	private static final int READY = 0xFF5A3C;
	private static final Set<SoundSource> WORLD_SOUNDS =
			EnumSet.of(SoundSource.BLOCKS, SoundSource.PLAYERS, SoundSource.HOSTILE, SoundSource.NEUTRAL);
	/** Seconds mining counts as going on after the game last reported a hit on the block. */
	private static final double MINING_GRACE = 0.3;
	/** Beats the first block of a mining run may wait past vanilla's break time to land on a beat. */
	private static final double FIRST_BLOCK_WAIT = 0.25;
	/** Chips per mining hit on the first beat of the bar at full loudness. */
	private static final int CHIPS = 10;
	/** Distance in beats from the nearest beat within which a player attack counts as on-beat. */
	private static final double BEAT_WINDOW = 0.22;
	/** Ticks after raising a shield during which blocking an attack counts as a perfect parry. */
	private static final int PARRY_TICKS = 7;

	private record Song(ShortBuffer pcm, int channels, int sampleRate, double[] beats, float[] energy) {
	}

	private record Deferred(Runnable action, double due) {
	}

	// OpenAL handles, 0 while nothing plays. The mod owns its source so it can read the exact playback position.
	private static int source;
	private static int buffer;
	private static int sampleRate;
	private static double[] beats = {};
	/** Loudness of each beat next to the rest of the song, 0 to 1. */
	private static float[] energy = {};
	/** Beats where the band comes back after a quiet stretch, see BeatTracker.drops. */
	private static boolean[] drops = {};
	private static boolean paused;
	/** Largest (audio position - system clock) seen since the last start or resume, see played(). */
	private static double anchor;
	/** Seconds to delay what you see, to make up for audio output latency. */
	private static double offset;
	/** Counts play and stop requests, so a song that finishes loading late knows it was replaced. */
	private static int request;
	/** Audio beat position for this frame, readable from the integrated server thread without touching OpenAL. */
	private static volatile double audioBeatNow = -1;
	/** Entity ids of mobs that already played their pre-beat warning cue on the current beat. */
	private static final Set<Integer> telegraphedMobs = ConcurrentHashMap.newKeySet();
	/** Beat position for this frame, -1 outside the tracked beats. Saves asking OpenAL once per entity. */
	private static double beatNow = -1;
	private static int lastBeat = -1;
	private static int lastEighth = -1;
	private static int lastVisualEighth = -1;
	/** Loudness of the song right now, eased: it rises fast on a drop and falls slowly into a break. */
	private static float energyNow;
	/** Visual beat without the ramp. */
	private static double beatRaw = -1;
	private static float particleSpeed = 1;
	private static float lagTicks;
	// The last action that landed on the beat, for the HUD sparks: when (visual beat), how strong, which color.
	private static double flashBeat = Double.NEGATIVE_INFINITY;
	private static float flashStrength;
	private static int flashColor = WHITE;
	/** System time in seconds of the player's last attack: the ready cue only shows during a fight. */
	private static double lastAttackAt = Double.NEGATIVE_INFINITY;
	/**
	 * Whether this game runs the world itself, and whether it runs it alone (not open to LAN). Timing changes need the
	 * first, changes to mobs the second. On a server the mod only moves visuals and sounds, which anticheats ignore.
	 */
	private static volatile boolean localWorld;
	private static volatile boolean soloWorld;
	// The block the player mines, when it started on the beat grid, and the beat it will break on.
	private static BlockPos miningPos;
	private static Direction miningSide;
	private static double miningSeen = Double.NEGATIVE_INFINITY;
	private static BlockPos crackPos;
	private static double crackStartBeat = Double.NaN;
	private static double targetBeat = Double.NaN;
	private static double lastBreakTime = Double.NEGATIVE_INFINITY;
	private static double lastBreakBeat = Double.NEGATIVE_INFINITY;
	/** How much the player moves or looks around, 0 to 1. The raw value steps per tick, the eased one per frame. */
	private static float motionNow;
	private static float motion;
	private static Vec3 lastPosition = Vec3.ZERO;
	private static float lastYaw;
	private static float lastPitch;
	private static long lastFrame;
	private static KeyMapping menuKey;
	private static KeyMapping tapKey;
	private static boolean openMenu;
	private static String currentSong = "";
	private static double tapSum;
	private static int tapCount;
	private static double lastTapAt = Double.NEGATIVE_INFINITY;
	private static final List<Deferred> deferred = new ArrayList<>();
	private static boolean releasing;

	@Override
	public void onInitializeClient() {
		try {
			Files.createDirectories(SONGS);
			offset = Integer.parseInt(Files.readString(OFFSET_FILE).strip()) / 1000.0;
		} catch (IOException | NumberFormatException e) {
			// No saved offset yet.
		}
		menuKey = KeyMappingHelper.registerKeyMapping(
				new KeyMapping("key.mineatdisco.menu", InputConstants.KEY_M, KeyMapping.Category.MISC));
		tapKey = KeyMappingHelper.registerKeyMapping(
				new KeyMapping("key.mineatdisco.tap", InputConstants.KEY_B, KeyMapping.Category.MISC));
		ClientTickEvents.END_CLIENT_TICK.register(MineAtDisco::tick);
		HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR,
				Identifier.fromNamespaceAndPath("mineatdisco", "beat"), MineAtDisco::drawBeat);
		// After each beat the hotbar and the status bars bounce in a wave from left to right, delayed in beats.
		Map.of(VanillaHudElements.HEALTH_BAR, 0.0, VanillaHudElements.ARMOR_BAR, 0.0,
				VanillaHudElements.HOTBAR, 0.05, VanillaHudElements.EXPERIENCE_LEVEL, 0.05,
				VanillaHudElements.INFO_BAR, 0.05, VanillaHudElements.INFO_BAR_FOREGROUND, 0.05,
				VanillaHudElements.FOOD_BAR, 0.1, VanillaHudElements.AIR_BAR, 0.1, VanillaHudElements.MOUNT_HEALTH, 0.1)
				.forEach((element, delay) -> HudElementRegistry.replaceElement(element, original -> (gui, delta) -> {
					gui.pose().pushMatrix();
					gui.pose().translate(0, -hop(delay));
					original.extractRenderState(gui, delta);
					gui.pose().popMatrix();
				}));
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(literal("disco")
				.executes(command -> {
					openMenu = true;
					return 1;
				})
				.then(literal("play").then(argument("song", StringArgumentType.greedyString())
						.suggests((command, builder) -> SharedSuggestionProvider.suggest(songs(), builder))
						.executes(command -> play(command.getSource(), StringArgumentType.getString(command, "song")))))
				.then(literal("stop").executes(command -> {
					stop();
					command.getSource().sendFeedback(Component.translatable("mineatdisco.stopped"));
					return 1;
				}))
				.then(literal("x2").executes(command -> {
					scaleTempo(true);
					return 1;
				}))
				.then(literal("half").executes(command -> {
					scaleTempo(false);
					return 1;
				}))
				.then(literal("tap").executes(command -> {
					tapOffset();
					return 1;
				}))
				.then(literal("offset").then(argument("ms", IntegerArgumentType.integer(-500, 500))
						.executes(command -> setOffset(command.getSource(), IntegerArgumentType.getInteger(command, "ms")))))));
	}

	/** Beat position for this frame: the whole part counts beats from the first of a phrase, -1 when none is tracked. */
	public static double beat() {
		return beatNow;
	}

	/** 0 while the player stands still with a steady view, 1 while walking or looking around. Eased over time. */
	public static float motion() {
		return motion;
	}

	/** Visual beat without the ramp. The camera and impact effects use it, since bending them reads as lag. */
	public static double beatSteady() {
		return beatRaw;
	}

	/** How fast particles move: 1 normally, below 1 after the beat, above 1 while catching up with the next one. */
	public static float particleSpeed() {
		return particleSpeed;
	}

	/** Ticks the visuals run behind real time during a ramp, 0 outside one. Entity animations subtract it. */
	public static float lagTicks() {
		return lagTicks;
	}

	/** How big every effect is: 0.25 in the quietest part of the song, 1.25 in the loudest. */
	public static float drive() {
		return 0.25f + energyNow;
	}

	/** HUD bounce: a smooth hump over the first half of each beat, sized by the bar accent and the loudness. */
	private static float hop(double delay) {
		double beat = beatNow - delay;
		if (beatNow < 0 || beat < 0) {
			return 0;
		}
		double u = beat % 1;
		return u >= 0.5 ? 0 : HUD_HOP * ACCENT[(int) beat % 4] * drive() * (float) Math.sin(2 * Math.PI * u);
	}

	/** This phrase's disco light: the hue walks around the wheel once per phrase, one step per beat. */
	private static int discoColor(long beat) {
		return Mth.hsvToRgb((float) Math.floorMod(beat, BeatTracker.PHRASE) / BeatTracker.PHRASE, 0.7f, 1);
	}

	/** Answers an action that landed on the beat with colored sparks around the crosshair. */
	private static void flashHud(float strength) {
		if (beatNow < 0) {
			return;
		}
		flashBeat = beatNow;
		flashStrength = Math.min(1, strength);
		flashColor = discoColor(Math.round(beatRaw));
	}

	/** How strong the beat nearest to now is, 0 when the steady visual beat is more than {@code window} beats away. */
	private static float beatStrength(double window) {
		long beat = Math.round(beatRaw);
		return beatRaw < 0 || Math.abs(beatRaw - beat) > window ? 0 : ACCENT[(int) (beat % 4)];
	}

	private static boolean active() {
		return !releasing && source != 0 && !paused;
	}

	/** Audio time of the next of {@code lines} grid lines per beat, or NaN when the event can happen now. */
	private static double nextLine(int lines) {
		// Sounds share the song's output latency, so the grid follows the audio position without the offset.
		double grid = BeatTracker.beatAt(beats, played()) * lines;
		if (grid < 0 || grid % 1 < ON_GRID) {
			return Double.NaN;
		}
		return BeatTracker.timeAt(beats, Math.ceil(grid) / lines);
	}

	/**
	 * Holds a world sound until the next grid line of the song, so the game plays in time with it.
	 * Returns false when the sound should play now.
	 * ponytail: this catches every world sound, not only the ones the player causes.
	 */
	public static boolean defer(SoundInstance sound, Consumer<SoundInstance> play) {
		if (!active() || !WORLD_SOUNDS.contains(sound.getSource()) || sound.isLooping()
				|| sound instanceof TickableSoundInstance) {
			return false;
		}
		// Footsteps, combat, breaking blocks, throws and shots fall on eighth notes like the mining hits. Other sounds
		// take the next sixteenth.
		String name = sound.getIdentifier().getPath();
		boolean launch = name.endsWith(".throw") || name.endsWith(".shoot") || name.endsWith(".launch");
		boolean eighth = launch || name.endsWith(".hit") || name.endsWith(".step") || name.endsWith(".break")
				|| name.endsWith(".hurt") || name.endsWith(".death") || name.endsWith(".burp")
				|| name.startsWith("entity.player.attack.") || name.equals("item.shield.block");
		// A throw or shot the player let go close to the beat gets an answer on the HUD.
		LocalPlayer player = Minecraft.getInstance().player;
		float thrownOnBeat = launch && player != null && player.distanceToSqr(sound.getX(), sound.getY(), sound.getZ()) < 4
				? beatStrength(0.15) : 0;
		double due = nextLine(eighth ? 2 : 4);
		if (Double.isNaN(due)) {
			if (thrownOnBeat > 0) {
				flashHud(thrownOnBeat * 0.7f);
			}
			return false;
		}
		deferred.add(new Deferred(() -> {
			play.accept(sound);
			if (name.endsWith(".place")) {
				puff(sound.getX(), sound.getY(), sound.getZ());
			}
			if (thrownOnBeat > 0) {
				flashHud(thrownOnBeat * 0.7f);
			}
		}, due));
		return true;
	}

	/**
	 * Takes over the hit effects while the player mines. The game reports hits at its own pace, so instead the mod
	 * plays a hit and a burst of chips on every eighth note. Returns true to skip the vanilla effect.
	 */
	public static boolean holdMining(BlockPos pos, Direction side) {
		if (!active()) {
			return false;
		}
		miningPos = pos.immutable();
		miningSide = side;
		miningSeen = played();
		return true;
	}

	/** True while a song plays in a world this game runs, so mining and shields may snap to the beat. */
	public static boolean syncMining() {
		return audioBeatNow >= 0 && localWorld;
	}

	/** True while a song plays in a world this game runs alone, so mobs may wait for the beat. */
	private static boolean syncMobs() {
		return audioBeatNow >= 0 && soloWorld;
	}

	/** True when mobs need not wait, or the audio clock is inside the beat window where they strike and detonate. */
	public static boolean onBeatWindow() {
		double beat = audioBeatNow;
		if (!syncMobs()) {
			return true;
		}
		double phase = beat % 1;
		return phase < 0.25 || phase >= 0.92;
	}

	/**
	 * Holds a mob's melee attack until the beat, playing a warning cue on the eighth note before it so the player can
	 * raise a shield in time.
	 */
	public static boolean canMobAttack(Mob mob) {
		double beat = audioBeatNow;
		if (!syncMobs()) {
			return true;
		}
		double phase = beat % 1;
		if (phase < 0.25 || phase >= 0.92) {
			return true;
		}
		if (phase >= 0.45 && phase < 0.85 && telegraphedMobs.add(mob.getId())) {
			int id = mob.getId();
			Minecraft.getInstance().execute(() -> telegraph(id));
		}
		return false;
	}

	private static void telegraph(int entityId) {
		Minecraft client = Minecraft.getInstance();
		if (client.level == null) {
			return;
		}
		Entity entity = client.level.getEntity(entityId);
		if (entity == null) {
			return;
		}
		playImmediate(SoundEvents.NOTE_BLOCK_HAT.value(), SoundSource.HOSTILE, 0.85f * drive(), 1.75f,
				entity.getX(), entity.getY(), entity.getZ());
		burst(entity, ParticleTypes.CRIT, amount(6));
	}

	/**
	 * Bursts sparks from an attacked target on the next eighth note, bigger and with a sweep flash when the player
	 * clicked in time with the beat.
	 */
	public static void onAttack(Entity target) {
		if (!active() || target == null) {
			return;
		}
		double beat = audioBeatNow;
		boolean onBeat = beat >= 0 && Math.abs(beat - Math.round(beat)) <= BEAT_WINDOW;
		lastAttackAt = System.nanoTime() / 1e9;
		int id = target.getId();
		double due = nextLine(2);
		if (onBeat) {
			flashHud(1);
		}
		Runnable effect = () -> {
			ClientLevel level = Minecraft.getInstance().level;
			Entity hit = level == null ? null : level.getEntity(id);
			if (hit != null) {
				burst(hit, ParticleTypes.CRIT, amount((onBeat ? 18 : 8) * drive()));
				if (onBeat) {
					level.addParticle(ParticleTypes.SWEEP_ATTACK, hit.getX(), hit.getY(0.5), hit.getZ(), 0, 0, 0);
				}
			}
		};
		if (Double.isNaN(due)) {
			effect.run();
		} else {
			deferred.add(new Deferred(effect, due));
		}
	}

	/** Holds a mob's hurt flash, hurt sound and death animation until the next eighth note so hits land on the grid. */
	public static boolean holdCombat(LivingEntity entity, Runnable action) {
		if (!active() || entity instanceof Player) {
			return false;
		}
		double due = nextLine(2);
		if (Double.isNaN(due)) {
			return false;
		}
		deferred.add(new Deferred(() -> {
			if (entity.level() == Minecraft.getInstance().level) {
				action.run();
			}
		}, due));
		return true;
	}

	/**
	 * Holds an impact until the next eighth note, a thrown snowball or egg bursting or a splash potion breaking, and adds
	 * sparks there, more on the beat. Returns true to skip the vanilla effect for now.
	 */
	public static boolean holdImpact(double x, double y, double z, Runnable action) {
		if (!active()) {
			return false;
		}
		double due = nextLine(2);
		deferred.add(new Deferred(() -> {
			action.run();
			sparks(new Vec3(x, y, z), amount((4 + 8 * beatStrength(0.25)) * drive()));
		}, Double.isNaN(due) ? 0 : due));
		return true;
	}

	/** True when the mod plays this entity's chewing and drinking on the beat instead of vanilla's 4-tick timer. */
	public static boolean ownsChewing(LivingEntity entity) {
		return active() && entity == Minecraft.getInstance().player;
	}

	/**
	 * Holds a mob's death until the next beat, a little after its hurt flash on the eighth note, and lets it pop there
	 * with sparks and an answer on the HUD, so kills land on the beat. Only the death event pays off, not the poof.
	 */
	public static boolean holdDeath(LivingEntity entity, Runnable action, boolean payoff) {
		if (!active() || entity instanceof Player) {
			return false;
		}
		double due = nextLine(1);
		deferred.add(new Deferred(() -> {
			if (entity.level() == Minecraft.getInstance().level) {
				action.run();
				if (payoff) {
					float strength = Math.max(0.4f, beatStrength(0.25));
					flashHud(strength);
					sparks(entity.position().add(0, entity.getBbHeight() / 2, 0), amount(16 * strength * drive()));
				}
			}
		}, Double.isNaN(due) ? 0 : due));
		return true;
	}

	/**
	 * Checks whether {@code blocker} raised their shield right before the attack (within {@link #PARRY_TICKS} ticks).
	 * On a perfect parry, knocks the attacker back hard and skips axe shield-disable.
	 */
	public static boolean tryParry(LivingEntity blocker, LivingEntity attacker, DamageSource source, float amount) {
		if (!syncMobs() || blocker.getTicksUsingItem() > PARRY_TICKS) {
			return false;
		}
		attacker.knockback(1.4, blocker.getX() - attacker.getX(), blocker.getZ() - attacker.getZ(), source, amount);
		int id = attacker.getId();
		Minecraft.getInstance().execute(() -> parryBurst(id));
		return true;
	}

	/** Triggers the parry clang and spark burst when a newly raised shield blocks a projectile. */
	public static void tryParryProjectile(LivingEntity blocker, Entity projectile) {
		if (syncMobs() && blocker.getTicksUsingItem() <= PARRY_TICKS) {
			int id = projectile != null ? projectile.getId() : blocker.getId();
			Minecraft.getInstance().execute(() -> parryBurst(id));
		}
	}

	private static void parryBurst(int targetId) {
		Minecraft client = Minecraft.getInstance();
		if (client.level == null || client.player == null) {
			return;
		}
		Entity target = client.level.getEntity(targetId);
		Entity center = target != null ? target : client.player;
		playImmediate(SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 0.55f * drive(), 1.8f,
				center.getX(), center.getY(), center.getZ());
		playImmediate(SoundEvents.BELL_BLOCK, SoundSource.PLAYERS, 0.9f * drive(), 1.5f,
				center.getX(), center.getY(), center.getZ());
		client.level.addParticle(ParticleTypes.SWEEP_ATTACK, center.getX(), center.getY(0.5), center.getZ(), 0, 0, 0);
		burst(center, ParticleTypes.CRIT, amount(20 * drive()));
		burst(center, ParticleTypes.ENCHANTED_HIT, amount(14 * drive()));
	}

	private static void playImmediate(SoundEvent sound, SoundSource category, float volume, float pitch,
			double x, double y, double z) {
		releasing = true;
		try {
			Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(
					sound, category, volume, pitch, SoundInstance.createUnseededRandom(), x, y, z));
		} finally {
			releasing = false;
		}
	}

	private static void burst(Entity entity, ParticleOptions particle, int count) {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) {
			return;
		}
		RandomSource random = level.getRandom();
		for (int i = count; i > 0; i--) {
			double dx = random.nextDouble() - 0.5;
			double dy = random.nextDouble() - 0.5;
			double dz = random.nextDouble() - 0.5;
			level.addParticle(particle, entity.getX() + dx * entity.getBbWidth(), entity.getY(0.5) + dy * 0.5,
					entity.getZ() + dz * entity.getBbWidth(), dx * 0.5, dy * 0.4 + 0.15, dz * 0.5);
		}
	}

	/**
	 * Picks the beat on which the block at {@code pos} will break. The first block of a run answers the click like
	 * vanilla. When the player holds click across blocks, vanilla's 5-tick cooldown between blocks is folded into the
	 * block's duration, so fast mining (such as Efficiency V on stone) breaks one block per beat without drifting or
	 * skipping beats. Slower blocks drift toward strong beats, see BeatTracker.landing, so a chain of bar-long blocks
	 * settles onto beat 1. A NaN target leaves the block to vanilla.
	 */
	public static void startMining(BlockPos pos, Direction side) {
		if (!syncMining()) {
			crackPos = null;
			return;
		}
		Minecraft client = Minecraft.getInstance();
		if (client.level == null || client.player == null) {
			return;
		}
		float step = client.level.getBlockState(pos).getDestroyProgress(client.player, client.level, pos);
		if (step <= 0 || step >= 1) {
			crackPos = null;
			return;
		}
		double now = played();
		double audioBeat = BeatTracker.beatAt(beats, now);
		if (audioBeat < 0) {
			crackPos = null;
			return;
		}
		miningPos = pos.immutable();
		miningSide = side;
		miningSeen = now;
		crackPos = miningPos;
		boolean chained = now - lastBreakTime < 0.25;
		crackStartBeat = chained ? lastBreakBeat : (beatRaw >= 0 ? beatRaw : audioBeat);
		// Never break before vanilla would. The server takes a break once its own count reaches 70%, but client and
		// server ticks drift apart by a tick or two, and a break that comes too early gets the block put back.
		double earliest = BeatTracker.beatAt(beats, now + breakTicks(step) / 20.0);
		double idealBeat = BeatTracker.beatAt(beats, now + (breakTicks(step) + 5) / 20.0);
		if (earliest < 0 || idealBeat < 0) {
			crackPos = null;
			targetBeat = Double.NaN;
			return;
		}
		if (!chained) {
			// Holding a block past vanilla's time feels like the pickaxe stopped working, and the player moves on before
			// it breaks. So the first block only waits for a beat a quarter beat away; its burst still lands on the grid.
			double next = Math.ceil(earliest);
			targetBeat = next - earliest <= FIRST_BLOCK_WAIT ? next : Double.NaN;
			return;
		}
		double minBeat = Math.max(Math.ceil(earliest), Math.floor(lastBreakBeat) + 1);
		targetBeat = Math.max(minBeat, BeatTracker.landing(idealBeat, idealBeat - audioBeat));
	}

	/**
	 * Paces block breaking so the crack stages follow the velocity ramp and the block breaks on {@code targetBeat}.
	 * Never breaks before vanilla would, so the server always accepts the break.
	 */
	public static float paceMining(BlockPos pos, Direction side, float vanillaProgress, float ticks) {
		if (!syncMining()) {
			crackPos = null;
			return vanillaProgress;
		}
		if (!pos.equals(crackPos)) {
			startMining(pos, side);
		}
		if (crackPos == null || Double.isNaN(targetBeat) || targetBeat < 0) {
			if (crackPos != null && vanillaProgress >= 1) {
				// A block left to vanilla breaks this tick. Note it, so the next one in the run locks onto the beat.
				lastBreakTime = played();
				lastBreakBeat = BeatTracker.beatAt(beats, lastBreakTime);
				crackPos = null;
			}
			return vanillaProgress;
		}
		Minecraft client = Minecraft.getInstance();
		if (client.level == null || client.player == null) {
			return vanillaProgress;
		}
		float step = client.level.getBlockState(pos).getDestroyProgress(client.player, client.level, pos);
		if (step <= 0 || step >= 1) {
			crackPos = null;
			return vanillaProgress;
		}
		double now = played();
		miningPos = crackPos;
		miningSide = side;
		miningSeen = now;
		double due = BeatTracker.timeAt(beats, targetBeat);
		boolean serverReady = ticks >= breakTicks(step);
		if (now + 0.025 >= due && !serverReady) {
			double left = (breakTicks(step) - ticks) / 20.0;
			double nextBeat = Math.ceil(BeatTracker.beatAt(beats, now + Math.max(0.05, left)));
			if (nextBeat < 0) {
				crackPos = null;
				return vanillaProgress;
			}
			targetBeat = nextBeat;
			due = BeatTracker.timeAt(beats, targetBeat);
		}
		if (now + 0.025 >= due && serverReady) {
			lastBreakTime = now;
			lastBreakBeat = targetBeat;
			crackPos = null;
			return 1.0f;
		}
		return crackProgress();
	}

	/** Ticks vanilla takes to break a block that gains {@code step} progress per tick. */
	private static int breakTicks(float step) {
		return Math.max(1, (int) Math.ceil(1.0 / step - 1e-6));
	}

	public static void stopMining() {
		crackPos = null;
	}

	private static float crackProgress() {
		double span = Math.max(0.1, targetBeat - crackStartBeat);
		return Mth.clamp((float) ((beatNow - crackStartBeat) / span), 0.001f, 0.99f);
	}

	/** Moves the burst of a broken block to the next eighth note and makes it bigger. Returns true to skip vanilla. */
	public static boolean holdDestroy(BlockPos pos, BlockState state) {
		if (!active()) {
			return false;
		}
		BlockPos at = pos.immutable();
		double due = nextLine(2);
		deferred.add(new Deferred(() -> {
			ClientLevel level = Minecraft.getInstance().level;
			if (level != null) {
				level.addDestroyBlockEffect(at, state);
				pop(at, state);
			}
		}, Double.isNaN(due) ? 0 : due));
		return true;
	}

	/**
	 * Like a velocity edit, every beat slows the animations down and then speeds them up so they slam into the next
	 * beat. The music keeps its pace: only the visual beat, cracks, HUD and entity animations bend. Speed follows
	 * 1 - depth * sin, so it starts and ends each beat without a jump and lands right on the real beat.
	 * Particle motion bends too. The camera stays out of it: bent every beat, it read as the game losing speed.
	 */
	private static void ramp() {
		if (beatRaw < 0) {
			beatNow = -1;
			particleSpeed = 1;
			lagTicks = 0;
			return;
		}
		double u = beatRaw % 1;
		float depth = 0.4f + 0.45f * energyNow;
		beatNow = beatRaw + depth * (Math.cos(2 * Math.PI * u) - 1) / (2 * Math.PI);
		particleSpeed = 1 - depth * (float) Math.sin(2 * Math.PI * u);
		lagTicks = (float) (20 * (BeatTracker.timeAt(beats, beatRaw) - BeatTracker.timeAt(beats, beatNow)));
	}

	/** Runs once per frame: refreshes the beat and the loudness, then runs the effects that are due. */
	public static void frame() {
		double now = source == 0 ? Double.MAX_VALUE : played();
		double audioBeat = source == 0 || paused ? -1 : BeatTracker.beatAt(beats, now);
		audioBeatNow = audioBeat;
		beatRaw = source == 0 ? -1 : BeatTracker.beatAt(beats, now - offset);
		ramp();
		long nanos = System.nanoTime();
		float elapsed = Math.min(0.1f, (nanos - lastFrame) / 1e9f);
		lastFrame = nanos;
		motion += (motionNow - motion) * (1 - (float) Math.exp(-elapsed / 0.3f));
		float loudness = beatNow < 0 || energy.length == 0 ? 0 : energy[Mth.clamp((int) beatNow, 0, energy.length - 1)];
		energyNow += (loudness - energyNow) * (1 - (float) Math.exp(-elapsed / (loudness > energyNow ? 0.08f : 0.5f)));
		int beat = (int) Math.floor(beatNow);
		if (beat != lastBeat) {
			lastBeat = beat;
			telegraphedMobs.clear();
			if (beat >= 0 && beat < drops.length && drops[beat]) {
				drop();
			} else if (beat >= 0 && beat % 4 == 0) {
				shedLeaves(beat % BeatTracker.PHRASE == 0 ? 2 : 1);
			}
		}
		// The hit sound follows the music and the chips land with it, on the steady visual beat. Cracks follow the ramp.
		boolean mining = now - miningSeen < MINING_GRACE;
		int eighth = (int) Math.floor(audioBeat * 2);
		if (eighth != lastEighth) {
			lastEighth = eighth;
			if (eighth >= 0 && mining) {
				knock(eighth);
			}
			if (eighth >= 0) {
				chew(eighth);
			}
		}
		int visualEighth = (int) Math.floor(beatRaw * 2);
		if (visualEighth != lastVisualEighth) {
			lastVisualEighth = visualEighth;
			if (visualEighth >= 0 && mining) {
				chip(visualEighth);
			}
		}
		if (crackPos != null && beatNow >= 0 && !Double.isNaN(targetBeat)) {
			Minecraft client = Minecraft.getInstance();
			if (client.level != null && client.player != null && client.gameMode != null
					&& client.gameMode.isDestroying() && !client.level.getBlockState(crackPos).isAir()) {
				client.level.destroyBlockProgress(client.player.getId(), crackPos, (int) (crackProgress() * 10));
			} else {
				crackPos = null;
			}
		}
		if (deferred.isEmpty()) {
			return;
		}
		releasing = true;
		try {
			deferred.removeIf(held -> {
				if (held.due() > now) {
					return false;
				}
				held.action().run();
				return true;
			});
		} finally {
			releasing = false;
		}
	}

	/** How hard a mining hit on this eighth note lands: the bar's accent on the beat, light off it. */
	private static float accent(int eighth) {
		return eighth % 2 == 0 ? ACCENT[eighth / 2 % 4] : 0.25f;
	}

	/** The block being mined, or null when there is none. */
	private static BlockState mined(ClientLevel level) {
		if (level == null || miningPos == null || level.getBlockState(miningPos).isAir()) {
			return null;
		}
		return level.getBlockState(miningPos);
	}

	/** The mining hit sound on an eighth note of the music, louder on the beat. */
	private static void knock(int eighth) {
		Minecraft client = Minecraft.getInstance();
		BlockState state = mined(client.level);
		if (state == null) {
			return;
		}
		boolean onBeat = eighth % 2 == 0;
		SoundType sound = state.getSoundType();
		releasing = true;
		try {
			// Off the beat the hit sounds a little higher, so mining ticks and tocks with the song.
			client.getSoundManager().play(new SimpleSoundInstance(sound.getHitSound(), SoundSource.BLOCKS,
					(sound.getVolume() + 1) / 8 * (0.6f + accent(eighth)) * drive(), sound.getPitch() * (onBeat ? 0.5f : 0.65f),
					SoundInstance.createUnseededRandom(), miningPos));
		} finally {
			releasing = false;
		}
	}

	/** Scales a count of extra particles by the player's Particles setting: none on Minimal. */
	private static int amount(float count) {
		return switch (Minecraft.getInstance().options.particles().get()) {
			case ALL -> Math.round(count);
			case DECREASED -> Math.round(count / 2);
			case MINIMAL -> 0;
		};
	}

	/** Adds a particle with an exact velocity. Vanilla's own spawn randomizes the speed, which leaves chips limp. */
	private static void spawn(ParticleOptions type, double x, double y, double z, double vx, double vy, double vz) {
		Particle particle = Minecraft.getInstance().particleEngine.createParticle(type, x, y, z, 0, 0, 0);
		if (particle != null) {
			particle.setParticleSpeed(vx, vy, vz);
		}
	}

	/** A bite or a sip on an eighth note while the player eats or drinks: the item's own sound and crumbs, more on the beat. */
	private static void chew(int eighth) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null || !player.isUsingItem()) {
			return;
		}
		ItemStack stack = player.getUseItem();
		Consumable food = stack.get(DataComponents.CONSUMABLE);
		if (food == null) {
			return;
		}
		// Like vanilla, no bite during the first 22% of the time it takes to eat.
		int total = Math.round(food.consumeSeconds() * 20);
		if (total - player.getUseItemRemainingTicks() <= total * 0.21875f) {
			return;
		}
		releasing = true;
		try {
			food.emitParticlesAndSounds(player.getRandom(), player, stack, eighth % 2 == 0 ? 8 : 3);
		} finally {
			releasing = false;
		}
	}

	/** A spray of chips off the mined face on an eighth note, flying back toward the player and bigger on the beat. */
	private static void chip(int eighth) {
		Minecraft client = Minecraft.getInstance();
		BlockState state = mined(client.level);
		if (state == null || miningSide == null) {
			return;
		}
		double nx = miningSide.getStepX();
		double ny = miningSide.getStepY();
		double nz = miningSide.getStepZ();
		Vec3 hit = client.hitResult instanceof BlockHitResult block && block.getBlockPos().equals(miningPos)
				? block.getLocation()
				: Vec3.atCenterOf(miningPos).add(nx / 2, ny / 2, nz / 2);
		BlockParticleOption chips = new BlockParticleOption(ParticleTypes.BLOCK, state);
		RandomSource random = client.level.getRandom();
		float accent = accent(eighth);
		for (int i = amount(CHIPS * accent * drive()); i > 0; i--) {
			double speed = 0.08 + 0.14 * accent * random.nextDouble();
			// Start just off the face: a chip spawned on the surface collides with the block and never moves.
			spawn(chips, hit.x + nx * 0.05, hit.y + ny * 0.05, hit.z + nz * 0.05,
					nx * speed + (random.nextDouble() - 0.5) * 0.12,
					ny * speed + random.nextDouble() * 0.12,
					nz * speed + (random.nextDouble() - 0.5) * 0.12);
		}
	}

	/**
	 * Blows a broken block apart: a ring of chips flying out, bigger on strong beats. On beats 1 and 3 colored sparks
	 * join in and the HUD answers, so landing a break on the beat pays off.
	 */
	private static void pop(BlockPos pos, BlockState state) {
		ClientLevel level = Minecraft.getInstance().level;
		RandomSource random = level.getRandom();
		Vec3 center = Vec3.atCenterOf(pos);
		BlockParticleOption chips = new BlockParticleOption(ParticleTypes.BLOCK, state);
		float strength = beatStrength(0.25);
		if (strength > 0) {
			flashHud(strength);
		}
		for (int i = amount((10 + 10 * strength) * drive()); i > 0; i--) {
			double angle = random.nextDouble() * 2 * Math.PI;
			double speed = 0.15 + 0.15 * random.nextDouble();
			spawn(chips, center.x, center.y, center.z, Math.cos(angle) * speed, 0.1 + 0.2 * random.nextDouble(),
					Math.sin(angle) * speed);
		}
		if (strength >= 0.7f) {
			sparks(center, amount(12 * strength * drive()));
		}
	}

	/** Dust sparks in this beat's disco color, flying out flat from {@code center}. */
	private static void sparks(Vec3 center, int count) {
		RandomSource random = Minecraft.getInstance().level.getRandom();
		DustParticleOptions spark = new DustParticleOptions(discoColor(Math.round(beatRaw)), 1.3f);
		for (int i = count; i > 0; i--) {
			double angle = random.nextDouble() * 2 * Math.PI;
			// Dust barely slows down in the air, so a slow start keeps the sparks within about three blocks.
			double speed = 0.18 + 0.12 * random.nextDouble();
			spawn(spark, center.x, center.y, center.z, Math.cos(angle) * speed, 0.05 + 0.15 * random.nextDouble(),
					Math.sin(angle) * speed);
		}
	}

	/** The band is back: a rainbow ring rolls out from the player, fireworks go up around them and the trees let go. */
	private static void drop() {
		Minecraft client = Minecraft.getInstance();
		if (client.level == null || client.player == null) {
			return;
		}
		flashHud(1);
		shedLeaves(4);
		Vec3 feet = client.player.position();
		int ring = amount(48 * drive());
		for (int i = 0; i < ring; i++) {
			double angle = 2 * Math.PI * i / ring;
			spawn(new DustParticleOptions(Mth.hsvToRgb((float) i / ring, 0.7f, 1), 1.5f), feet.x, feet.y + 0.2, feet.z,
					Math.cos(angle) * 0.4, 0.02, Math.sin(angle) * 0.4);
		}
		// Three blocks out, so the rockets frame the view instead of flying through the camera.
		RandomSource random = client.level.getRandom();
		for (int i = amount(12 * drive()); i > 0; i--) {
			double angle = random.nextDouble() * 2 * Math.PI;
			spawn(ParticleTypes.FIREWORK, feet.x + Math.cos(angle) * 3, feet.y + 0.5, feet.z + Math.sin(angle) * 3,
					(random.nextDouble() - 0.5) * 0.1, 0.4 + 0.2 * random.nextDouble(), (random.nextDouble() - 0.5) * 0.1);
		}
	}

	/** Dust kicked out along the ground from a block the player just placed, more when the song is loud. */
	private static void puff(double x, double y, double z) {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) {
			return;
		}
		BlockState state = level.getBlockState(BlockPos.containing(x, y, z));
		if (state.isAir()) {
			return;
		}
		BlockParticleOption chips = new BlockParticleOption(ParticleTypes.BLOCK, state);
		RandomSource random = level.getRandom();
		float strength = beatStrength(0.12);
		if (strength > 0) {
			flashHud(strength / 2);
		}
		for (int i = amount(CHIPS * (1 + strength) * drive()); i > 0; i--) {
			// A point just outside one of the block's bottom edges. Inside the block the chip would be stuck.
			double along = random.nextDouble() - 0.5;
			double out = random.nextBoolean() ? 0.52 : -0.52;
			boolean onX = random.nextBoolean();
			double dx = onX ? out : along;
			double dz = onX ? along : out;
			spawn(chips, x + dx, y - 0.45, z + dz, dx * 0.25, 0.04 + 0.06 * random.nextDouble(), dz * 0.25);
		}
	}

	/** Trees around the player drop leaves on the first beat of each bar, more when a phrase opens. */
	private static void shedLeaves(int strength) {
		Minecraft client = Minecraft.getInstance();
		if (client.level == null || client.player == null) {
			return;
		}
		RandomSource random = client.level.getRandom();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int i = 0; i < 120 * strength * drive(); i++) {
			pos.setWithOffset(client.player.blockPosition(),
					random.nextInt(25) - 12, random.nextInt(17) - 4, random.nextInt(25) - 12);
			if (client.level.getBlockState(pos).getBlock() instanceof FallingLeavesInvoker leaves
					&& client.level.getBlockState(pos.below()).isAir()) {
				leaves.mineatdisco$shed(client.level, pos, random);
			}
		}
	}

	/** Song names are the .ogg files in the disco folder, without the extension. */
	private static List<String> songs() {
		try (Stream<Path> files = Files.list(SONGS)) {
			return files.map(file -> file.getFileName().toString())
					.filter(name -> name.endsWith(".ogg"))
					.map(name -> name.substring(0, name.length() - 4))
					.sorted()
					.toList();
		} catch (IOException e) {
			return List.of();
		}
	}

	private static int play(FabricClientCommandSource command, String name) {
		// Matching against the listing also keeps the name from pointing outside the folder.
		if (!songs().contains(name)) {
			command.sendError(Component.translatable("mineatdisco.not_found", name, SONGS.toString()));
			return 0;
		}
		playSong(name, command.getClient());
		return 1;
	}

	private static void playSong(String name, Minecraft client) {
		stop();
		int mine = request;
		if (client.player != null) {
			client.player.sendOverlayMessage(Component.translatable("mineatdisco.loading", name));
		}
		// Decoding and beat tracking take about a second, so they run off the client thread.
		CompletableFuture.supplyAsync(() -> decode(SONGS.resolve(name + ".ogg"))).whenCompleteAsync((song, error) -> {
			if (song == null) {
				if (client.player != null) {
					client.player.sendSystemMessage(Component.translatable("mineatdisco.failed", name));
				}
			} else if (mine != request) {
				LibCStdlib.free(song.pcm());
			} else {
				float volume = client.options.getFinalSoundSourceVolume(SoundSource.MUSIC);
				start(song, volume);
				currentSong = name;
				if (client.player != null) {
					client.player.sendOverlayMessage(
							Component.translatable("mineatdisco.playing", name, Math.round(BeatTracker.bpm(beats))));
					if (volume == 0) {
						client.player.sendSystemMessage(Component.translatable("mineatdisco.muted"));
					}
				}
			}
		}, client);
	}

	/** Doubles or halves the tracked beat grid for songs whose tempo reads half or double. */
	private static void scaleTempo(boolean doubleTempo) {
		if (beats.length < 4) {
			return;
		}
		double bpm = BeatTracker.bpm(beats);
		if (doubleTempo && bpm * 2 <= 300) {
			double[] nextBeats = new double[beats.length * 2 - 1];
			float[] nextEnergy = new float[energy.length * 2 - 1];
			for (int i = 0; i < beats.length; i++) {
				nextBeats[2 * i] = beats[i];
				nextEnergy[2 * i] = energy[i];
				if (i + 1 < beats.length) {
					nextBeats[2 * i + 1] = 0.5 * (beats[i] + beats[i + 1]);
					nextEnergy[2 * i + 1] = 0.5f * (energy[i] + energy[i + 1]);
				}
			}
			beats = nextBeats;
			energy = nextEnergy;
		} else if (!doubleTempo && bpm / 2 >= 45) {
			int count = (beats.length + 1) / 2;
			double[] nextBeats = new double[count];
			float[] nextEnergy = new float[count];
			for (int i = 0; i < count; i++) {
				nextBeats[i] = beats[2 * i];
				nextEnergy[i] = energy[2 * i];
			}
			beats = nextBeats;
			energy = nextEnergy;
		} else {
			return;
		}
		drops = BeatTracker.drops(energy);
		lastBeat = -1;
		lastEighth = -1;
		lastVisualEighth = -1;
		crackPos = null;
		LocalPlayer player = Minecraft.getInstance().player;
		if (player != null) {
			player.sendOverlayMessage(
					Component.translatable("mineatdisco.playing", currentSong, Math.round(BeatTracker.bpm(beats))));
		}
	}

	/** Calibrates the visual offset from the player tapping in time with the beat they hear. */
	private static void tapOffset() {
		if (source == 0 || paused || beats.length < 2) {
			return;
		}
		double now = played();
		double raw = BeatTracker.beatAt(beats, now);
		int index = Mth.clamp((int) Math.round(raw < 0 ? 0 : raw), 0, beats.length - 1);
		double period = index + 1 < beats.length ? beats[index + 1] - beats[index] : beats[index] - beats[index - 1];
		if (period <= 0) {
			return;
		}
		double diff = now - beats[index];
		diff -= period * Math.rint(diff / period);
		if (now - lastTapAt > 2.5) {
			tapCount = 1;
			tapSum = diff;
		} else {
			double avg = tapSum / tapCount;
			double delta = diff - avg;
			delta -= period * Math.rint(delta / period);
			diff = avg + delta;
			if (tapCount < 8) {
				tapCount++;
				tapSum += diff;
			} else {
				tapSum = tapSum * 7 / 8 + diff;
			}
		}
		lastTapAt = now;
		int ms = Mth.clamp((int) Math.round(tapSum / tapCount * 1000), -500, 500);
		saveOffset(ms);
		Minecraft client = Minecraft.getInstance();
		client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.2f));
		if (client.player != null) {
			client.player.sendOverlayMessage(Component.translatable("mineatdisco.offset", ms));
		}
	}

	/** Returns null when the file is not Ogg Vorbis in mono or stereo. */
	private static Song decode(Path file) {
		ByteBuffer ogg = null;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			byte[] bytes = Files.readAllBytes(file);
			ogg = MemoryUtil.memAlloc(bytes.length).put(bytes).flip();
			IntBuffer channels = stack.mallocInt(1);
			IntBuffer rate = stack.mallocInt(1);
			ShortBuffer pcm = STBVorbis.stb_vorbis_decode_memory(ogg, channels, rate);
			if (pcm == null) {
				return null;
			}
			if (channels.get(0) > 2) {
				LibCStdlib.free(pcm);
				return null;
			}
			double[] beats = BeatTracker.track(pcm, channels.get(0), rate.get(0));
			return new Song(pcm, channels.get(0), rate.get(0), beats, BeatTracker.energy(pcm, channels.get(0), rate.get(0), beats));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} finally {
			MemoryUtil.memFree(ogg);
		}
	}

	private static void start(Song song, float volume) {
		buffer = AL10.alGenBuffers();
		AL10.alBufferData(buffer, song.channels() == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16,
				song.pcm(), song.sampleRate());
		LibCStdlib.free(song.pcm()); // OpenAL keeps its own copy.
		source = AL10.alGenSources();
		AL10.alSourcei(source, AL10.AL_BUFFER, buffer);
		AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE); // Play at the listener, not somewhere in the world.
		AL10.alSourcef(source, AL10.AL_GAIN, volume);
		AL10.alSourcePlay(source);
		sampleRate = song.sampleRate();
		beats = song.beats();
		energy = song.energy();
		drops = BeatTracker.drops(energy);
		paused = false;
		anchor = Double.NEGATIVE_INFINITY;
	}

	/**
	 * False once the game has restarted its sound engine, which wipes every source and buffer.
	 * ponytail: a restart can hand our old ids to a vanilla sound, though both ids matching is unlikely. Hook
	 * SoundEngine.destroy with a mixin if it ever happens.
	 */
	private static boolean alive() {
		return source != 0 && AL10.alIsSource(source) && AL10.alGetSourcei(source, AL10.AL_BUFFER) == buffer;
	}

	private static void stop() {
		request++;
		if (alive()) {
			AL10.alSourceStop(source);
			AL10.alDeleteSources(source);
			AL10.alDeleteBuffers(buffer);
		}
		source = 0;
		currentSong = "";
		beats = new double[0];
		drops = new boolean[0];
		audioBeatNow = -1;
		telegraphedMobs.clear();
		crackPos = null;
	}

	private static void tick(Minecraft client) {
		var server = client.getSingleplayerServer();
		localWorld = server != null;
		soloWorld = server != null && !server.isPublished();
		while (menuKey != null && menuKey.consumeClick()) {
			openMenu = true;
		}
		while (tapKey != null && tapKey.consumeClick()) {
			tapOffset();
		}
		if (openMenu) {
			openMenu = false;
			client.gui.setScreen(new DiscoScreen());
		}
		if (source == 0) {
			return;
		}
		if (client.level == null || !alive() || AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) == AL10.AL_STOPPED) {
			stop();
			return;
		}
		client.getMusicManager().stopPlaying();
		AL10.alSourcef(source, AL10.AL_GAIN, client.options.getFinalSoundSourceVolume(SoundSource.MUSIC));
		LocalPlayer player = client.player;
		if (player != null) {
			// Walking pace or a turn of 15 degrees per tick counts as full motion.
			double moved = player.position().distanceTo(lastPosition) / 0.2;
			double turned = (Math.abs(Mth.wrapDegrees(player.getYRot() - lastYaw)) + Math.abs(player.getXRot() - lastPitch)) / 15;
			motionNow = (float) Math.min(1, Math.max(moved, turned));
			lastPosition = player.position();
			lastYaw = player.getYRot();
			lastPitch = player.getXRot();
		}
		if (client.isPaused() != paused) {
			paused = !paused;
			if (paused) {
				AL10.alSourcePause(source);
			} else {
				AL10.alSourcePlay(source);
			}
			anchor = Double.NEGATIVE_INFINITY;
		}
	}

	/** Audio position in seconds. */
	private static double played() {
		double played = AL10.alGetSourcei(source, AL11.AL_SAMPLE_OFFSET) / (double) sampleRate;
		if (!paused) {
			// OpenAL moves the offset once per mixer period, about 20 ms, so every reading is slightly stale.
			// The freshest reading so far gives the true position, and the system clock fills in between readings.
			// ponytail: the two clocks drift apart by a few ms over a song. Re-anchor now and then if timing gets tight.
			double now = System.nanoTime() / 1e9;
			anchor = Math.max(anchor, played - now);
			played = now + anchor;
		}
		return played;
	}

	/**
	 * The beat cue around the crosshair. Four brackets fly in and land on the beat, gold when the next beat opens the
	 * bar. A ring opens where they land, the pips below count the bar, and an action on the beat answers with sparks in
	 * the phrase's disco color. It all follows the velocity ramp and fades in quiet parts.
	 */
	private static void drawBeat(GuiGraphicsExtractor gui, DeltaTracker delta) {
		if (beatNow < 0) {
			return;
		}
		float cx = gui.guiWidth() / 2f;
		float cy = gui.guiHeight() / 2f;
		float phase = (float) (beatNow % 1);
		int bar = (int) beatNow % 4;
		float presence = Math.min(1, 0.4f + 0.6f * drive());
		float flash = (float) (beatNow - flashBeat) / FLASH_BEATS;
		boolean flashing = flash >= 0 && flash < 1;

		// Squaring the phase makes the brackets start slow and rush into the beat.
		float radius = 5 + 14 * (1 - phase * phase);
		int bracketColor = argb(presence * (0.25f + 0.75f * phase),
				flashing ? flashColor : readyOnNextBeat() ? READY : bar == 3 ? GOLD : WHITE);
		for (int corner = 0; corner < 4; corner++) {
			int sx = corner % 2 == 0 ? -1 : 1;
			int sy = corner < 2 ? -1 : 1;
			bracket(gui, cx + sx * radius, cy + sy * radius, sx, sy, bracketColor);
		}

		if (phase < RING) {
			float k = phase / RING;
			int size = Math.round(5 + 10 * k * (bar == 0 ? 1.5f : 1));
			gui.pose().pushMatrix();
			gui.pose().translate(cx, cy);
			gui.outline(-size, -size, 2 * size, 2 * size, argb((1 - k) * presence * (bar == 0 ? 1 : 0.5f), bar == 0 ? GOLD : WHITE));
			gui.pose().popMatrix();
		}

		for (int pip = 0; pip < 4; pip++) {
			float pop = pip == bar ? 1 + 1.5f * (1 - phase) * (1 - phase) : 0.6f;
			gui.pose().pushMatrix();
			gui.pose().translate(cx + (pip * 2 - 3) * 6, cy + 24);
			gui.pose().scale(pop);
			gui.fill(-1, -1, 1, 1, argb(presence * (pip == bar ? 1 : 0.4f), pip == 0 ? GOLD : WHITE));
			gui.pose().popMatrix();
		}

		for (int spark = 0; flashing && spark < 4; spark++) {
			gui.pose().pushMatrix();
			gui.pose().translate(cx, cy);
			gui.pose().rotate((float) (Math.PI / 4 + spark * Math.PI / 2));
			gui.pose().translate(9 + 12 * flash, 0);
			gui.fill(0, 0, Math.round(3 + 3 * flashStrength), 1, argb((1 - flash) * (1 - flash) * flashStrength, flashColor));
			gui.pose().popMatrix();
		}
	}

	/**
	 * True when the player's next full-strength hit will be ready by the next beat: a bow fully drawn, or, during a
	 * fight, a swing fully recharged. The brackets turn red for that beat, so full hits land in time.
	 */
	private static boolean readyOnNextBeat() {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null || beatRaw < 0) {
			return false;
		}
		double ticksToBeat = 20 * (BeatTracker.timeAt(beats, Math.floor(beatRaw) + 1) - BeatTracker.timeAt(beats, beatRaw));
		if (player.isUsingItem() && player.getUseItem().getItem() instanceof BowItem) {
			return player.getTicksUsingItem() + ticksToBeat >= BowItem.MAX_DRAW_DURATION;
		}
		if (System.nanoTime() / 1e9 - lastAttackAt > 2) {
			return false;
		}
		float delay = player.getCurrentItemAttackStrengthDelay();
		return player.getAttackStrengthScale(0) * delay + ticksToBeat >= delay;
	}

	/** An L-shaped bracket with its corner at (x, y) and its arms pointing back toward the crosshair. */
	private static void bracket(GuiGraphicsExtractor gui, float x, float y, int sx, int sy, int color) {
		int arm = 4;
		gui.pose().pushMatrix();
		gui.pose().translate(x, y);
		gui.fill(sx < 0 ? 0 : -arm, sy < 0 ? 0 : -1, sx < 0 ? arm : 0, sy < 0 ? 1 : 0, color);
		gui.fill(sx < 0 ? 0 : -1, sy < 0 ? 1 : -arm, sx < 0 ? 1 : 0, sy < 0 ? arm : -1, color);
		gui.pose().popMatrix();
	}

	private static int argb(float alpha, int rgb) {
		return Math.round(Mth.clamp(alpha, 0, 1) * 255) << 24 | rgb & 0xFFFFFF;
	}

	private static boolean saveOffset(int ms) {
		offset = ms / 1000.0;
		try {
			Files.writeString(OFFSET_FILE, Integer.toString(ms));
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	private static int setOffset(FabricClientCommandSource command, int ms) {
		if (saveOffset(ms)) {
			command.sendFeedback(Component.translatable("mineatdisco.offset", ms));
		} else {
			command.sendError(Component.translatable("mineatdisco.offset_not_saved", ms));
		}
		return 1;
	}

	/**
	 * In-world song picker and calibration screen. Keeps the game unpaused and leaves the crosshair clear so the player
	 * can watch the beat cue while tapping Space or adjusting tempo.
	 */
	private static final class DiscoScreen extends Screen {
		private static final int PAGE_SIZE = 4;
		private int page;
		private List<String> list = List.of();

		private DiscoScreen() {
			super(Component.translatable("mineatdisco.menu.title"));
		}

		@Override
		public boolean isPauseScreen() {
			return false;
		}

		@Override
		protected void init() {
			int cx = width / 2;
			int top = 36;
			addRenderableWidget(Button.builder(Component.translatable("mineatdisco.menu.half"),
					b -> scaleTempo(false)).bounds(cx - 156, top, 36, 20).build());
			addRenderableWidget(Button.builder(Component.translatable("mineatdisco.menu.double"),
					b -> scaleTempo(true)).bounds(cx - 116, top, 36, 20).build());
			addRenderableWidget(Button.builder(Component.translatable("mineatdisco.menu.earlier"),
					b -> saveOffset(Mth.clamp((int) Math.round(offset * 1000) - 10, -500, 500)))
					.bounds(cx - 76, top, 44, 20).build());
			addRenderableWidget(Button.builder(Component.translatable("mineatdisco.menu.tap"),
					b -> tapOffset()).bounds(cx - 28, top, 80, 20).build());
			addRenderableWidget(Button.builder(Component.translatable("mineatdisco.menu.later"),
					b -> saveOffset(Mth.clamp((int) Math.round(offset * 1000) + 10, -500, 500)))
					.bounds(cx + 56, top, 44, 20).build());
			addRenderableWidget(Button.builder(Component.translatable("mineatdisco.menu.stop"),
					b -> stop()).bounds(cx + 104, top, 52, 20).build());

			list = songs();
			int pages = Math.max(1, (list.size() + PAGE_SIZE - 1) / PAGE_SIZE);
			page = Mth.clamp(page, 0, pages - 1);
			int listTop = height / 2 + 38;
			for (int i = 0; i < PAGE_SIZE && page * PAGE_SIZE + i < list.size(); i++) {
				String song = list.get(page * PAGE_SIZE + i);
				addRenderableWidget(Button.builder(Component.literal(song),
						b -> playSong(song, minecraft)).bounds(cx - 100, listTop + i * 22, 200, 20).build());
			}
			if (pages > 1) {
				addRenderableWidget(Button.builder(Component.translatable("mineatdisco.menu.prev"), b -> {
					page = (page + pages - 1) % pages;
					rebuildWidgets();
				}).bounds(cx - 126, listTop, 22, 20).build());
				addRenderableWidget(Button.builder(Component.translatable("mineatdisco.menu.next"), b -> {
					page = (page + 1) % pages;
					rebuildWidgets();
				}).bounds(cx + 104, listTop, 22, 20).build());
			}
		}

		@Override
		public void extractBackground(GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
			// Keep the center around the crosshair clear so the beat cue and pips stay visible while calibrating.
			gui.fill(width / 2 - 164, 6, width / 2 + 164, 62, 0x90000000);
		}

		@Override
		public void extractRenderState(GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
			super.extractRenderState(gui, mouseX, mouseY, partialTick);
			int cx = width / 2;
			Component status = source != 0 && beats.length >= 2
					? Component.translatable("mineatdisco.playing", currentSong, Math.round(BeatTracker.bpm(beats)))
					: Component.translatable("mineatdisco.menu.idle");
			gui.centeredText(font, status, cx, 11, 0xFFFFFFFF);
			gui.centeredText(font, Component.translatable("mineatdisco.menu.offset", Math.round(offset * 1000)),
					cx, 23, 0xFFFFD040);
			if (list.isEmpty()) {
				gui.centeredText(font, Component.translatable("mineatdisco.menu.empty", SONGS.toString()),
						cx, height / 2 + 44, 0xFFCCCCCC);
			}
		}

		@Override
		public boolean keyPressed(KeyEvent event) {
			if (event.key() == InputConstants.KEY_SPACE) {
				tapOffset();
				return true;
			}
			return super.keyPressed(event);
		}
	}
}
