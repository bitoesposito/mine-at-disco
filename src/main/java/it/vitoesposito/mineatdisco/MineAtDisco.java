package it.vitoesposito.mineatdisco;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.mojang.blaze3d.Blaze3D;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Kernel32Util;
import com.sun.jna.platform.win32.WinReg;
import it.vitoesposito.mineatdisco.mixin.FallingLeavesInvoker;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.SOFTDeviceClock;
import org.lwjgl.openal.SOFTSourceLatency;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.libc.LibCStdlib;

/** Plays a song from the disco folder and moves the game to its beat. */
public final class MineAtDisco implements ClientModInitializer {
	private static final org.slf4j.Logger LOGGER = LogUtils.getLogger();
	/** Live mode captures system audio through WASAPI, which only Windows has. */
	private static final boolean WINDOWS = Util.getPlatform() == Util.OS.WINDOWS;
	private static final Path SONGS = FabricLoader.getInstance().getGameDir().resolve("disco");
	/**
	 * The player's extra audio delay in ms. It used to hold the whole output latency; now OpenAL reports that, so the
	 * file got a new name and old values, which would count the latency twice, are left behind.
	 */
	private static final Path OFFSET_FILE = SONGS.resolve("audio-offset.txt");
	/** Written once the player has been told which key opens the menu, so the hint shows once per install. */
	private static final Path HINT_FILE = SONGS.resolve(".hint-shown");
	/** Where a player can drop yt-dlp and FFmpeg instead of installing them. */
	private static final Path TOOLS = SONGS.resolve("tools");
	/** One text file per playlist, a song name per line. */
	private static final Path PLAYLISTS = SONGS.resolve("playlists");
	/** Key in a burned disc's custom data that names its song. */
	private static final String DISC_TAG = "mineatdisco_song";
	/** Key in the custom data of a blank disc, which the mod's recipe crafts from coal and copper. */
	private static final String BLANK_TAG = "mineatdisco_blank";
	/** Blocks a jukebox carries, like vanilla's: loud up close, silent this far away. */
	private static final double JUKEBOX_RANGE = 64;
	/** Seconds into a song after which the previous button restarts it instead of going back. */
	private static final double RESTART_AFTER = 3;
	static final int REPEAT_OFF = 0;
	static final int REPEAT_ALL = 1;
	static final int REPEAT_ONE = 2;
	private static final Identifier PANEL = Identifier.withDefaultNamespace("popup/background");
	private static final Identifier NOW_PLAYING = Identifier.withDefaultNamespace("toast/now_playing");
	private static final Identifier MUSIC_NOTES = Identifier.withDefaultNamespace("icon/music_notes");
	private static final int GRAY = 0xFFA0A0A0;
	/** Warm-up taps skipped before the offset follows, like lazer's tap-for-BPM button skips its first four. */
	private static final int TAPS_TO_IGNORE = 2;
	/** Most recent taps the offset is the median of, so one stray tap moves nothing. */
	private static final int TAPS_KEPT = 16;
	/** Deliberate clicks near the beat the suggested offset waits for, like lazer's suggestion from hit errors. */
	private static final int CLICKS_FOR_SUGGESTION = 16;
	/**
	 * A beat sound noticed this late, after a slow frame, is skipped instead of played off the beat, like the mistimed
	 * allowance of lazer's beat-synced containers. Two 60 fps frames.
	 */
	private static final double MISTIMED = 2.0 / 60;
	/** Longest frame time used to draw a frame ahead, see visualTime. */
	private static final double MAX_FRAME_LEAD = 0.025;
	/** A sound this soon after a grid line, as a share of a grid step, plays at once. */
	private static final double ON_GRID = 0.2;
	/** How hard each beat of the bar hits. The first beat leads, the third answers. */
	private static final float[] ACCENT = {1, 0.4f, 0.7f, 0.4f};
	/** GUI pixels the hotbar and status bars rise on the hardest kick of the song. */
	private static final float HUD_HOP = 4;
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
	/** Chips per mining hit on the first beat of the bar at full loudness. */
	private static final int CHIPS = 10;
	/** Distance in beats from the nearest beat within which a player attack counts as on-beat. */
	private static final double BEAT_WINDOW = 0.22;
	/** Ticks after raising a shield during which blocking an attack counts as a perfect parry. */
	private static final int PARRY_TICKS = 7;

	private record Song(ShortBuffer pcm, int channels, int sampleRate, double[] beats, float[] energy, float[] punch, byte[] roots) {
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
	/** How hard the low end hits over the song, see BeatTracker.punch. */
	private static float[] punch = {};
	/** Visual time in seconds with the ramp applied, so the HUD bounce lingers and whips with everything else. */
	private static double rampedSeconds;
	private static boolean paused;
	// Live mode follows what the system plays (Spotify, a browser, any player) instead of a local song.
	private static LoopbackCapture capture;
	private static LivePunch livePunch = new LivePunch();
	private static volatile LiveBeat.Snapshot liveSnapshot = LiveBeat.Snapshot.EMPTY;
	private static ScheduledExecutorService liveUpdates;
	/** nanoTime in seconds when live mode started. Live song time counts from here. */
	private static double liveEpoch;
	/** Smoothed mixer position, the system time it was read at, and the clock that smooths it. See played(). */
	private static final AudioClock clock = new AudioClock();
	private static double playedAt;
	private static long playedNanos;
	/** Seconds from the mixer to the speakers as OpenAL reports it, 0 when the driver doesn't say. */
	private static double latency;
	private static boolean latencyReported;
	private static final double[] offsetAndLatency = new double[2];
	/** Duration of the last frame, which is about how long a frame drawn now takes to reach the screen. */
	private static double frameLead;
	/** Seconds the player hears the song later than OpenAL reports, such as Bluetooth delay. Set by tapping. */
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
	/**
	 * Song time when the cracks started, when the block will break (NaN leaves it to vanilla) and when the last block
	 * broke. Seconds, not beat numbers: live mode drops old beats and /2 halves the numbering, and a stale beat number
	 * could push a break minutes away. stop() clears them, since every song starts its clock at zero.
	 */
	private static double crackStartTime = Double.NaN;
	private static double targetTime = Double.NaN;
	private static double lastBreakTime = Double.NEGATIVE_INFINITY;
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
	/** Whether the current world has had its first frame, when the mod checks the volume and shows the hint. */
	private static boolean inWorld;
	/** Set by {@code /disco live}, so the warning opens once the chat has closed. */
	private static boolean confirmLive;
	/** The YouTube title being saved and its file name, both null when nothing downloads. */
	private static volatile String downloadTitle;
	private static volatile String downloadName;
	/** From 0 to 1. */
	private static volatile double downloadProgress;
	private static volatile String downloadId;
	/** Downloads waiting behind the current one. Touched on the client thread only. */
	private static final List<YouTube.Result> downloadQueue = new ArrayList<>();
	/** Videos saved in this session, so the search screen can mark them. */
	private static final Set<String> downloaded = new HashSet<>();
	/** Songs waiting to play after the current one, in order. */
	private static final List<String> queue = new ArrayList<>();
	/** Songs played before the current one, the latest last, for the previous button. */
	private static final List<String> history = new ArrayList<>();
	private static int repeat = REPEAT_OFF;
	/** The playlist the queue came from, so repeat can start it over. Null for a queue built by hand. */
	private static String playlist;
	/** The player paused the song from the menu, as opposed to the game pausing. */
	private static boolean userPaused;
	/** Length of the song in seconds. */
	private static double songLength;
	/** Each beat's strongest pitch class, see BeatTracker.roots. */
	private static byte[] roots = new byte[0];
	/** The jukebox playing a disco disc, null for a song from the menu. */
	private static BlockPos jukebox;
	/** 1 for a song from the menu. For a jukebox, how well the player hears it: 0 out of range. */
	private static float proximity = 1;
	/** Jukeboxes holding a burned disc in the integrated server's worlds, by jukeboxKey, with the song's name. */
	private static final Map<String, String> discoJukeboxes = new ConcurrentHashMap<>();
	/** How long each of those songs lasts in ticks, once the client has decoded it, so the server stops on time. */
	private static final Map<String, Long> discoLengths = new ConcurrentHashMap<>();
	/**
	 * Where the last seek went, until OpenAL reports a position near it. The mixer applies an offset a moment later, and
	 * a stale reading would lock the clock, which never runs backwards, at the old position.
	 */
	private static double seekedTo = Double.NaN;
	private static long seekedNanos;
	/** The latest short message while the menu is open: the menu covers the line where the game shows them. */
	private static Component menuMessage;
	private static long menuMessageAt;
	private static String currentSong = "";
	/** The song being decoded, null otherwise, so the menu can say so instead of "no music". */
	private static String loadingSong;
	/** Whether live mode has announced that it found the beat. */
	private static boolean liveLocked;
	private static final List<Double> taps = new ArrayList<>();
	private static double lastTapAt = Double.NEGATIVE_INFINITY;
	/** How far deliberate clicks near the beat landed from it, in seconds, see suggestedOffset. */
	private static final List<Double> clickErrors = new ArrayList<>();
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
		// The hotbar and the status bars bounce on the song's kicks in a wave from left to right, delayed in seconds.
		Map.of(VanillaHudElements.HEALTH_BAR, 0.0, VanillaHudElements.ARMOR_BAR, 0.0,
				VanillaHudElements.HOTBAR, 0.03, VanillaHudElements.EXPERIENCE_LEVEL, 0.03,
				VanillaHudElements.INFO_BAR, 0.03, VanillaHudElements.INFO_BAR_FOREGROUND, 0.03,
				VanillaHudElements.FOOD_BAR, 0.06, VanillaHudElements.AIR_BAR, 0.06, VanillaHudElements.MOUNT_HEALTH, 0.06)
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
				.then(literal("live").executes(command -> {
					confirmLive = true;
					return 1;
				}))
				.then(literal("stop").executes(command -> {
					stopWithMessage();
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
		return (0.25f + energyNow) * proximity;
	}

	/**
	 * Minecraft's Distortion Effects slider, from 0 to 1. Players who turn it down for motion sickness get a calmer
	 * ramp, HUD bounce and dance; at the default of 1 nothing changes.
	 */
	public static float motionScale() {
		return Minecraft.getInstance().options.screenEffectScale().get().floatValue();
	}

	/** How big the mob and player dance is: the song's loudness times the Distortion Effects slider. */
	public static float danceDrive() {
		return drive() * motionScale();
	}

	/**
	 * HUD bounce in GUI pixels: the song's bass punch {@code delay} seconds back on the ramped visual time. It follows
	 * the kicks the song really plays, fills and breaks included, instead of a fixed accent per beat.
	 */
	private static float hop(double delay) {
		return beatNow < 0 ? 0 : HUD_HOP * motionScale() * punchAt(rampedSeconds - delay);
	}

	/**
	 * Bass punch of the beat that opens the current two-beat cycle, a little after the beat where the kick peaks. The
	 * camera kick scales by it, held for the whole cycle, so the view never jitters with the waveform.
	 */
	public static float cyclePunch() {
		return beatRaw < 0 ? 0 : punchAt(BeatTracker.timeAt(beats, 2 * Math.round(beatRaw / 2)) + 0.03);
	}

	/** Bass punch at {@code seconds} of song time, from the local song's curve or the live capture. */
	private static float punchAt(double seconds) {
		return live() ? livePunch.at(capture, seconds + liveEpoch) : BeatTracker.punchAt(punch, seconds);
	}

	/** This phrase's disco light: the hue walks around the wheel once per phrase, one step per beat. */
	private static int discoColor(long beat) {
		return Mth.hsvToRgb((float) Math.floorMod(beat, BeatTracker.PHRASE) / BeatTracker.PHRASE, 0.7f, 1);
	}

	/**
	 * Answers an action that landed on the beat with colored sparks around the crosshair. The hue walks from green to
	 * magenta only, so it never reads as the red of a ready hit or the gold of the one.
	 */
	private static void flashHud(float strength) {
		if (beatNow < 0) {
			return;
		}
		flashBeat = beatNow;
		flashStrength = Math.min(1, strength);
		flashColor = Mth.hsvToRgb(0.3f + 0.55f * Math.floorMod(Math.round(beatRaw), BeatTracker.PHRASE) / BeatTracker.PHRASE, 0.7f, 1);
	}

	/** How strong the beat nearest to now is, 0 when the steady visual beat is more than {@code window} beats away. */
	private static float beatStrength(double window) {
		long beat = Math.round(beatRaw);
		return beatRaw < 0 || Math.abs(beatRaw - beat) > window ? 0 : ACCENT[(int) (beat % 4)];
	}

	private static boolean live() {
		return capture != null;
	}

	/** True while a local song or live audio drives the game. */
	private static boolean playing() {
		return source != 0 || capture != null;
	}

	private static boolean active() {
		return !releasing && playing() && !paused && proximity > 0;
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
				|| name.startsWith("entity.player.attack.");
		if (name.equals("item.shield.block")) {
			// A blocked hit lands on the beat whenever it arrives, with a bell in the song's key.
			double x = sound.getX();
			double y = sound.getY();
			double z = sound.getZ();
			return onBeat(() -> chime(x, y, z, false), () -> play.accept(sound));
		}
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
		noteClick();
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
		Minecraft.getInstance().execute(() -> onBeat(() -> parryBurst(id), null));
		return true;
	}

	/** Triggers the parry clang and spark burst when a newly raised shield blocks a projectile. */
	public static void tryParryProjectile(LivingEntity blocker, Entity projectile) {
		if (syncMobs() && blocker.getTicksUsingItem() <= PARRY_TICKS) {
			int id = projectile != null ? projectile.getId() : blocker.getId();
			Minecraft.getInstance().execute(() -> onBeat(() -> parryBurst(id), null));
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
		chime(center.getX(), center.getY(), center.getZ(), true);
		flashHud(1);
		client.level.addParticle(ParticleTypes.SWEEP_ATTACK, center.getX(), center.getY(0.5), center.getZ(), 0, 0, 0);
		burst(center, ParticleTypes.CRIT, amount(20 * drive()));
		burst(center, ParticleTypes.ENCHANTED_HIT, amount(14 * drive()));
	}

	/**
	 * Runs {@code effect} on the next beat, or now when a beat has just passed, like a held sound. {@code sound}, if
	 * any, plays with it; the return value says whether the caller should leave it to the mod.
	 */
	private static boolean onBeat(Runnable effect, Runnable sound) {
		double due = active() ? nextLine(1) : Double.NaN;
		if (Double.isNaN(due)) {
			effect.run();
			return false;
		}
		deferred.add(new Deferred(() -> {
			if (sound != null) {
				sound.run();
			}
			effect.run();
		}, due));
		return sound != null;
	}

	/**
	 * Note block bells in the key the song plays right now: the root alone for a blocked hit; root, fifth and the
	 * root an octave down for a perfect parry. Note blocks are tuned so pitch 1 is F sharp, and pitch C to B stays
	 * between 0.7 and 1.34, inside the game's 0.5 to 2.
	 */
	private static void chime(double x, double y, double z, boolean perfect) {
		int beat = (int) Math.floor(audioBeatNow);
		int root = beat >= 0 && beat < roots.length ? roots[beat] : -1;
		float pitch = root < 0 ? 1 : (float) Math.pow(2, (root - 6) / 12.0);
		float volume = 0.8f * Math.max(0.5f, drive());
		playImmediate(SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.PLAYERS, volume, pitch, x, y, z);
		if (perfect) {
			float fifth = pitch * (float) Math.pow(2, 7 / 12.0);
			playImmediate(SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.PLAYERS, volume * 0.7f, fifth > 2 ? fifth / 2 : fifth, x, y, z);
			playImmediate(SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.PLAYERS, volume * 0.6f, pitch, x, y, z);
		}
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
		crackStartTime = Math.abs(now - lastBreakTime) < BeatTracker.RUN_GAP ? lastBreakTime : visualTime(now);
		// Holding a block past vanilla's time feels like the pickaxe stopped working, and the player moves on before it
		// breaks, so a first block mostly breaks on vanilla time, its burst still on the grid. Breaking before vanilla
		// gets the block put back: the server needs 70% of its own count, and client and server ticks drift apart.
		targetTime = BeatTracker.breakTime(beats, now, breakTicks(step), lastBreakTime);
	}

	/**
	 * Paces block breaking so the crack stages follow the velocity ramp and the block breaks at {@code targetTime}.
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
		if (crackPos == null || Double.isNaN(targetTime)) {
			if (crackPos != null && vanillaProgress >= 1) {
				// A block left to vanilla breaks this tick. Note it, so the next one in the run locks onto the beat.
				lastBreakTime = played();
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
		boolean serverReady = ticks >= breakTicks(step);
		if (now + 0.025 >= targetTime && !serverReady) {
			double left = (breakTicks(step) - ticks) / 20.0;
			double nextBeat = Math.ceil(BeatTracker.beatAt(beats, now + Math.max(0.05, left)));
			if (nextBeat < 0) {
				crackPos = null;
				return vanillaProgress;
			}
			targetTime = BeatTracker.timeAt(beats, nextBeat);
		}
		if (now + 0.025 >= targetTime && serverReady) {
			lastBreakTime = targetTime;
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

	/** Crack stage between 0 and 1 on the ramped visual time, so the cracks linger and whip with the beat. */
	private static float crackProgress() {
		double span = Math.max(0.03, targetTime - crackStartTime);
		return Mth.clamp((float) ((rampedSeconds - crackStartTime) / span), 0.001f, 0.99f);
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
		float depth = (0.4f + 0.45f * energyNow) * motionScale();
		beatNow = beatRaw + depth * (Math.cos(2 * Math.PI * u) - 1) / (2 * Math.PI);
		particleSpeed = 1 - depth * (float) Math.sin(2 * Math.PI * u);
		lagTicks = (float) (20 * (BeatTracker.timeAt(beats, beatRaw) - BeatTracker.timeAt(beats, beatNow)));
	}

	/** Runs once per frame: refreshes the clocks, the beat and the loudness, then runs the effects that are due. */
	public static void frame() {
		long nanos = System.nanoTime();
		double frameSeconds = (nanos - lastFrame) / 1e9;
		float elapsed = (float) Math.min(0.1, frameSeconds);
		lastFrame = nanos;
		frameLead = Math.min(MAX_FRAME_LEAD, frameSeconds);
		if (source != 0) {
			readClock(nanos, frameSeconds);
		} else if (live()) {
			readLive();
		}
		double now = playing() ? played() : Double.MAX_VALUE;
		double audioBeat = !playing() || paused ? -1 : BeatTracker.beatAt(beats, now);
		audioBeatNow = audioBeat;
		beatRaw = !playing() ? -1 : BeatTracker.beatAt(beats, visualTime(now));
		ramp();
		rampedSeconds = beatNow < 0 ? 0 : BeatTracker.timeAt(beats, beatNow);
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
			boolean onTime = eighth >= 0 && now - BeatTracker.timeAt(beats, eighth / 2.0) <= MISTIMED;
			if (onTime && mining) {
				knock(eighth);
			}
			if (onTime) {
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
		if (crackPos != null && beatNow >= 0 && !Double.isNaN(targetTime)) {
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
		if (client.options.hideLightningFlash().get()) {
			// Players who hide lightning flashes get the ring and the leaves without the bursts of light.
			return;
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
					// A song still being written by the YouTube download would fail to load.
					.filter(name -> !name.equals(downloadName) && !name.endsWith(".temp"))
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
		playNow(name, command.getClient());
		return 1;
	}

	/**
	 * Follows the system audio instead of a local song. The capture and its beat analysis run on their own threads;
	 * the timeline updates twice a second. Works on Windows only, where WASAPI can capture what the speakers play.
	 */
	private static void startLive(Minecraft client) {
		if (!WINDOWS) {
			if (client.player != null) {
				client.player.sendSystemMessage(Component.translatable("mineatdisco.live.windows").withStyle(ChatFormatting.RED));
			}
			return;
		}
		stop();
		liveLocked = false;
		liveEpoch = System.nanoTime() / 1e9;
		latency = deviceLatency();
		livePunch = new LivePunch();
		LiveBeat live = new LiveBeat();
		LoopbackCapture listening = LoopbackCapture.start();
		capture = listening;
		liveUpdates = Executors.newSingleThreadScheduledExecutor(task -> {
			Thread thread = new Thread(task, "Mine at Disco live beat");
			thread.setDaemon(true);
			return thread;
		});
		liveUpdates.scheduleAtFixedRate(() -> {
			try {
				int rate = listening.rate();
				// Four seconds give the tracker enough beats to lock on; it looks at up to twelve.
				int count = (int) Math.min(LiveBeat.WINDOW * rate, listening.written());
				if (rate == 0 || count < 4 * rate) {
					return;
				}
				short[] window = new short[count];
				long first = listening.copyLatest(window, count);
				liveSnapshot = live.update(window, rate, listening.timeOf(first) - liveEpoch, System.nanoTime() / 1e9 - liveEpoch);
			} catch (RuntimeException e) {
				liveSnapshot = LiveBeat.Snapshot.EMPTY;
			}
		}, 500, 500, TimeUnit.MILLISECONDS);
		if (client.player != null) {
			tell(Component.translatable("mineatdisco.live.start"));
		}
	}

	/** Takes the newest live timeline and works through the audio captured since the last frame. */
	private static void readLive() {
		if (capture.failure() != null) {
			// The player gets plain words; the HRESULT or library error goes to the log for bug reports.
			LOGGER.warn("Mine at Disco could not capture system audio: {}", capture.failure());
			LocalPlayer player = Minecraft.getInstance().player;
			if (player != null) {
				player.sendSystemMessage(Component.translatable("mineatdisco.live.failed").withStyle(ChatFormatting.RED));
			}
			stop();
			return;
		}
		LiveBeat.Snapshot snapshot = liveSnapshot;
		beats = snapshot.beats();
		energy = snapshot.energy();
		drops = snapshot.drops();
		roots = snapshot.roots();
		livePunch.advance(capture);
		// Say once when the beat is found, so the player knows the wait is over; say again after losing it.
		boolean locked = recentBpm() > 0;
		LocalPlayer player = Minecraft.getInstance().player;
		if (locked && !liveLocked && player != null) {
			tell(Component.translatable("mineatdisco.live.locked", recentBpm()));
		}
		liveLocked = locked;
	}

	/** Output latency of Minecraft's audio device in seconds, as OpenAL Soft reports it, or 0 when it doesn't. */
	private static double deviceLatency() {
		long device = ALC10.alcGetContextsDevice(ALC10.alcGetCurrentContext());
		if (device == 0 || !ALC.createCapabilities(device).ALC_SOFT_device_clock) {
			return 0;
		}
		long[] nanos = new long[1];
		SOFTDeviceClock.alcGetInteger64vSOFT(device, SOFTDeviceClock.ALC_DEVICE_LATENCY_SOFT, nanos);
		return nanos[0] / 1e9;
	}

	/** Beats per minute over the last phrase of the timeline, for the menu in live mode. */
	private static long recentBpm() {
		int n = beats.length;
		return n <= BeatTracker.PHRASE ? 0 : Math.round(60 * BeatTracker.PHRASE / (beats[n - 1] - beats[n - 1 - BeatTracker.PHRASE]));
	}

	/** Decodes and plays a song from the songs folder: at the listener, or in the world from the jukebox at {@code at}. */
	private static void playSong(String name, Minecraft client, BlockPos at) {
		stop();
		loadingSong = name;
		int mine = request;
		tell(Component.translatable("mineatdisco.loading", shortName(name)));
		// Decoding and beat tracking take about a second, so they run off the client thread.
		CompletableFuture.supplyAsync(() -> decode(SONGS.resolve(name + ".ogg"))).whenCompleteAsync((song, error) -> {
			if (mine == request) {
				loadingSong = null;
			}
			if (song == null) {
				if (client.player != null && mine == request) {
					client.player.sendSystemMessage(Component.translatable("mineatdisco.failed", name).withStyle(ChatFormatting.RED));
				}
			} else if (mine != request) {
				LibCStdlib.free(song.pcm());
			} else {
				float volume = client.options.getFinalSoundSourceVolume(at == null ? SoundSource.MUSIC : SoundSource.RECORDS);
				start(song, volume, at);
				currentSong = name;
				if (at != null && client.level != null) {
					discoLengths.put(jukeboxKey(client.level, at), (long) Math.ceil(songLength * 20));
				}
				tell(Component.translatable(at == null ? "mineatdisco.playing" : "mineatdisco.jukebox.playing",
						shortName(name), Math.round(BeatTracker.bpm(beats))));
				if (volume == 0 && client.player != null) {
					client.player.sendSystemMessage(Component.translatable(at == null ? "mineatdisco.muted" : "mineatdisco.muted.jukebox")
							.withStyle(ChatFormatting.YELLOW));
				}
			}
		}, client);
	}

	/** Doubles or halves the tracked beat grid for songs whose tempo reads half or double. */
	private static void scaleTempo(boolean doubleTempo) {
		LocalPlayer messages = Minecraft.getInstance().player;
		// Live mode rebuilds its timeline twice a second, so tempo fixes only apply to local songs.
		if (live()) {
			if (messages != null) {
				tell(Component.translatable("mineatdisco.tempo.live"));
			}
			return;
		}
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
			if (messages != null) {
				tell(Component.translatable("mineatdisco.tempo.limit", doubleTempo ? 300 : 45));
			}
			return;
		}
		drops = BeatTracker.drops(energy);
		lastBeat = -1;
		lastEighth = -1;
		lastVisualEighth = -1;
		crackPos = null;
		LocalPlayer player = Minecraft.getInstance().player;
		if (player != null) {
			tell(
					Component.translatable("mineatdisco.playing", currentSong, Math.round(BeatTracker.bpm(beats))));
		}
	}

	/**
	 * Calibrates the offset from the player tapping in time with the beat they hear: the first taps warm up, then the
	 * offset is the median of the latest ones. A pause of 2.5 s starts over.
	 * ponytail: taps are measured from the nearest beat, so an offset beyond half a beat needs /disco offset.
	 */
	private static void tapOffset() {
		Minecraft client = Minecraft.getInstance();
		if (!playing() || paused || beats.length < 2) {
			if (client.player != null && !paused) {
				tell(Component.translatable("mineatdisco.tap.no_song"));
			}
			return;
		}
		// The player taps to what they hear, which the speakers play a device latency after the mixer.
		double now = played() - latency;
		double error = beatError(now);
		if (Double.isNaN(error)) {
			return;
		}
		if (now - lastTapAt > 2.5) {
			taps.clear();
		}
		lastTapAt = now;
		taps.add(error);
		client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.2f));
		if (taps.size() <= TAPS_TO_IGNORE) {
			if (client.player != null) {
				tell(Component.translatable("mineatdisco.tap.more"));
			}
			return;
		}
		int ms = Mth.clamp((int) Math.round(median(taps.subList(Math.max(TAPS_TO_IGNORE, taps.size() - TAPS_KEPT), taps.size())) * 1000), -500, 500);
		saveOffset(ms);
		if (client.player != null) {
			tell(Component.translatable("mineatdisco.tap.offset", ms, taps.size() - TAPS_TO_IGNORE));
		}
	}

	/**
	 * Notes how far a deliberate click, a hit or a used block, landed from the beat. Only clicks within a quarter beat
	 * count, since most clicks in Minecraft aren't meant as music.
	 */
	public static void noteClick() {
		if (!active() || beats.length < 2) {
			return;
		}
		// Measured on the time the mod thinks the player hears: clicks late on average mean the offset is too small.
		double time = played() - latency - offset;
		double error = beatError(time);
		double beat = Math.round(BeatTracker.beatAt(beats, time));
		if (Double.isNaN(error) || Math.abs(error) > (BeatTracker.timeAt(beats, beat + 1) - BeatTracker.timeAt(beats, beat)) / 4) {
			return;
		}
		clickErrors.add(error);
		if (clickErrors.size() > 4 * CLICKS_FOR_SUGGESTION) {
			clickErrors.remove(0);
		}
	}

	/** The offset in ms that centers the player's deliberate clicks on the beat, or null without enough of them. */
	private static Integer suggestedOffset() {
		if (clickErrors.size() < CLICKS_FOR_SUGGESTION) {
			return null;
		}
		double shift = median(clickErrors);
		return Math.abs(shift) < 0.008 ? null : Mth.clamp((int) Math.round((offset + shift) * 1000), -500, 500);
	}

	/** Seconds from the nearest beat to {@code time} on the song timeline, NaN outside the tracked beats. */
	private static double beatError(double time) {
		double beat = BeatTracker.beatAt(beats, time);
		return beat < 0 ? Double.NaN : time - BeatTracker.timeAt(beats, Math.round(beat));
	}

	private static double median(List<Double> values) {
		double[] sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
		int middle = sorted.length / 2;
		return sorted.length % 2 == 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
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
			return new Song(pcm, channels.get(0), rate.get(0), beats, BeatTracker.energy(pcm, channels.get(0), rate.get(0), beats),
					BeatTracker.punch(pcm, channels.get(0), rate.get(0)), BeatTracker.roots(pcm, channels.get(0), rate.get(0), beats));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} finally {
			MemoryUtil.memFree(ogg);
		}
	}

	/** Starts the song at the listener, or in the world at {@code at} like a jukebox record. */
	private static void start(Song song, float volume, BlockPos at) {
		ShortBuffer pcm = song.pcm();
		songLength = (double) pcm.remaining() / song.channels() / song.sampleRate();
		ShortBuffer upload = pcm;
		if (at != null && song.channels() == 2) {
			// OpenAL only places mono sounds in the world.
			upload = MemoryUtil.memAllocShort(pcm.remaining() / 2);
			for (int i = 0; i < upload.capacity(); i++) {
				upload.put(i, (short) ((pcm.get(pcm.position() + 2 * i) + pcm.get(pcm.position() + 2 * i + 1)) / 2));
			}
		}
		buffer = AL10.alGenBuffers();
		AL10.alBufferData(buffer, at != null || song.channels() == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16,
				upload, song.sampleRate());
		if (upload != pcm) {
			MemoryUtil.memFree(upload);
		}
		LibCStdlib.free(pcm); // OpenAL keeps its own copy.
		source = AL10.alGenSources();
		AL10.alSourcei(source, AL10.AL_BUFFER, buffer);
		if (at == null) {
			AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE); // Play at the listener, not somewhere in the world.
		} else {
			// Like the game's own jukebox records: linear fade to silence at 64 blocks.
			AL10.alSource3f(source, AL10.AL_POSITION, at.getX() + 0.5f, at.getY() + 0.5f, at.getZ() + 0.5f);
			AL10.alSourcei(source, AL10.AL_DISTANCE_MODEL, AL11.AL_LINEAR_DISTANCE);
			AL10.alSourcef(source, AL10.AL_MAX_DISTANCE, (float) JUKEBOX_RANGE);
			AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 1);
			AL10.alSourcef(source, AL10.AL_REFERENCE_DISTANCE, 0);
		}
		jukebox = at;
		proximity = 1;
		roots = song.roots();
		AL10.alSourcef(source, AL10.AL_GAIN, volume);
		AL10.alSourcePlay(source);
		sampleRate = song.sampleRate();
		beats = song.beats();
		energy = song.energy();
		drops = BeatTracker.drops(energy);
		punch = song.punch();
		paused = false;
		clock.reset();
		playedAt = 0;
		playedNanos = System.nanoTime();
		latencyReported = AL.getCapabilities().AL_SOFT_source_latency;
		latency = 0;
	}

	/**
	 * False once the game has restarted its sound engine, which wipes every source and buffer.
	 * ponytail: a restart can hand our old ids to a vanilla sound, though both ids matching is unlikely. Hook
	 * SoundEngine.destroy with a mixin if it ever happens.
	 */
	private static boolean alive() {
		return source != 0 && AL10.alIsSource(source) && AL10.alGetSourcei(source, AL10.AL_BUFFER) == buffer;
	}

	/** Stops the music and says so, for the command and the menu button. */
	private static void stopWithMessage() {
		stop();
		LocalPlayer player = Minecraft.getInstance().player;
		if (player != null) {
			tell(Component.translatable("mineatdisco.stopped"));
		}
	}

	private static void stop() {
		request++;
		loadingSong = null;
		if (capture != null) {
			liveUpdates.shutdownNow();
			capture.close();
			capture = null;
			liveSnapshot = LiveBeat.Snapshot.EMPTY;
		}
		if (alive()) {
			AL10.alSourceStop(source);
			AL10.alDeleteSources(source);
			AL10.alDeleteBuffers(buffer);
		}
		source = 0;
		currentSong = "";
		beats = new double[0];
		drops = new boolean[0];
		punch = new float[0];
		roots = new byte[0];
		songLength = 0;
		jukebox = null;
		proximity = 1;
		userPaused = false;
		taps.clear();
		resetTiming();
	}

	/** Forgets times on the song's clock, which a new song or a jump in the song would turn into the future or the past. */
	private static void resetTiming() {
		audioBeatNow = -1;
		telegraphedMobs.clear();
		crackPos = null;
		targetTime = Double.NaN;
		lastBreakTime = Double.NEGATIVE_INFINITY;
		miningSeen = Double.NEGATIVE_INFINITY;
		lastTapAt = Double.NEGATIVE_INFINITY;
		lastBeat = -1;
		lastEighth = -1;
		lastVisualEighth = -1;
	}

	/** Plays everything held for the grid right away, for when the song's clock stops or jumps. */
	private static void flushDeferred() {
		releasing = true;
		try {
			List<Deferred> held = new ArrayList<>(deferred);
			deferred.clear();
			held.forEach(entry -> entry.action().run());
		} finally {
			releasing = false;
		}
	}

	/**
	 * Jumps to {@code seconds} into the song from the menu. Pausing first makes OpenAL take the new offset in samples
	 * whatever the source was doing; playback resumes from there unless the song is paused.
	 */
	private static void seek(double seconds) {
		if (!localSong()) {
			return;
		}
		double to = Mth.clamp(seconds, 0, Math.max(0, songLength - 0.1));
		AL10.alGetError();
		AL10.alSourcePause(source);
		AL10.alSourcei(source, AL11.AL_SAMPLE_OFFSET, (int) (to * sampleRate));
		int error = AL10.alGetError();
		if (!paused) {
			AL10.alSourcePlay(source);
		}
		LOGGER.info("Mine at Disco: seek to {} s{}", String.format(java.util.Locale.ROOT, "%.1f", to),
				error == AL10.AL_NO_ERROR ? "" : ", OpenAL error " + error);
		clock.reset();
		seekedTo = to;
		seekedNanos = System.nanoTime();
		playedAt = to;
		playedNanos = seekedNanos;
		flushDeferred();
		resetTiming();
	}

	/** Seconds into the song the player hears now. */
	private static double position() {
		return source == 0 ? 0 : Mth.clamp(played() - latency, 0, songLength);
	}

	private static boolean localSong() {
		return source != 0 && jukebox == null;
	}

	/** Plays {@code name} now from the menu, the queue or a download, keeping the song it replaces for previous. */
	private static void playNow(String name, Minecraft client) {
		if (localSong() && !currentSong.isEmpty()) {
			history.add(currentSong);
		}
		playSong(name, client, null);
	}

	/** Moves on: the next song in the queue, the playlist again when it repeats, or silence. */
	private static void next(Minecraft client) {
		if (queue.isEmpty() && repeat == REPEAT_ALL && playlist != null) {
			queue.addAll(readPlaylist(playlist));
		}
		if (queue.isEmpty()) {
			stop();
			return;
		}
		playNow(queue.removeFirst(), client);
	}

	/** Restarts the song, or goes back to the one before it when the song has only just begun. */
	private static void previous(Minecraft client) {
		if (!localSong()) {
			return;
		}
		if (position() > RESTART_AFTER || history.isEmpty()) {
			seek(0);
			return;
		}
		queue.addFirst(currentSong);
		playSong(history.removeLast(), client, null);
	}

	/** Pauses or resumes the song from the menu, or starts the queue when nothing plays. */
	private static void togglePause(Minecraft client) {
		if (localSong()) {
			userPaused = !userPaused;
		} else if (!playing() && loadingSong == null && !queue.isEmpty()) {
			next(client);
		}
	}

	/** Tells the player something short: in the open menu, which covers the line above the hotbar, or on that line. */
	private static void tell(Component message) {
		Minecraft client = Minecraft.getInstance();
		if (client.gui.screen() instanceof DiscoScreen) {
			menuMessage = message;
			menuMessageAt = System.nanoTime();
		} else if (client.player != null) {
			client.player.sendOverlayMessage(message);
		}
	}

	private static List<String> playlists() {
		try (Stream<Path> files = Files.list(PLAYLISTS)) {
			return files.map(file -> file.getFileName().toString()).filter(name -> name.endsWith(".txt"))
					.map(name -> name.substring(0, name.length() - 4)).sorted().toList();
		} catch (IOException e) {
			return List.of();
		}
	}

	/** The songs of a playlist that are still in the songs folder, in order. */
	private static List<String> readPlaylist(String name) {
		try {
			List<String> songs = songs();
			return Files.readAllLines(PLAYLISTS.resolve(name + ".txt")).stream().map(String::strip).filter(songs::contains).toList();
		} catch (IOException e) {
			return List.of();
		}
	}

	/** Saves the queue, with the song that plays first, as a playlist. Returns its file name, or null on failure. */
	private static String saveQueue(String name) {
		List<String> songs = new ArrayList<>();
		if (localSong() && !currentSong.isEmpty()) {
			songs.add(currentSong);
		}
		songs.addAll(queue);
		String file = YouTube.fileName(name);
		try {
			Files.createDirectories(PLAYLISTS);
			Files.write(PLAYLISTS.resolve(file + ".txt"), songs);
			return file;
		} catch (IOException e) {
			return null;
		}
	}

	private static void playPlaylist(String name, Minecraft client) {
		List<String> songs = readPlaylist(name);
		if (songs.isEmpty()) {
			tell(Component.translatable("mineatdisco.playlist.empty", name));
			return;
		}
		queue.clear();
		queue.addAll(songs);
		playlist = name;
		next(client);
	}

	/** Adds a song to the end of the queue, or plays it when nothing plays. */
	private static void enqueue(String song, Minecraft client) {
		if (!playing() && loadingSong == null) {
			playNow(song, client);
			return;
		}
		queue.add(song);
		tell(Component.translatable("mineatdisco.queue.added", shortName(song), queue.size()));
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
		if (confirmLive) {
			confirmLive = false;
			askLive(client, null);
		}
		if (client.level == null) {
			inWorld = false;
		} else if (!inWorld && client.player != null && client.gui.screen() == null) {
			// Once per world, when it first shows: offer the volume sliders if songs would be silent, and in the very
			// first world, say which key opens the menu.
			inWorld = true;
			if (client.options.getSoundSourceVolume(SoundSource.MASTER) == 0 || client.options.getSoundSourceVolume(SoundSource.MUSIC) == 0) {
				client.gui.setScreen(new VolumeScreen());
			}
			if (!Files.exists(HINT_FILE)) {
				tell(Component.translatable("mineatdisco.hint", menuKey.getTranslatedKeyMessage()));
				try {
					Files.writeString(HINT_FILE, "");
				} catch (IOException e) {
					// Then the hint shows again next time, which is harmless.
				}
			}
		}
		if (!playing()) {
			return;
		}
		boolean ended = source != 0 && alive() && AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) == AL10.AL_STOPPED;
		if (client.level == null || source != 0 && !alive() || ended) {
			if (ended && client.level != null && jukebox == null) {
				if (repeat == REPEAT_ONE) {
					seek(0);
					return;
				}
				if (!queue.isEmpty() || repeat == REPEAT_ALL && playlist != null) {
					next(client);
					return;
				}
				tell(Component.translatable("mineatdisco.ended", shortName(currentSong), menuKey.getTranslatedKeyMessage()));
			}
			stop();
			return;
		}
		client.getMusicManager().stopPlaying();
		if (source != 0) {
			AL10.alSourcef(source, AL10.AL_GAIN, client.options.getFinalSoundSourceVolume(jukebox == null ? SoundSource.MUSIC : SoundSource.RECORDS));
		}
		if (jukebox != null && client.player != null) {
			double distance = Math.sqrt(client.player.distanceToSqr(Vec3.atCenterOf(jukebox)));
			proximity = (float) Mth.clamp(1 - distance / JUKEBOX_RANGE, 0, 1);
		}
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
		// The game or the player can pause a local song, not the music another program plays. Whatever waits for the
		// grid plays at once, since the grid stops with the song.
		boolean pause = client.isPaused() || userPaused && jukebox == null;
		if (source != 0 && pause != paused) {
			paused = pause;
			if (paused) {
				AL10.alSourcePause(source);
				flushDeferred();
			} else {
				AL10.alSourcePlay(source);
			}
			clock.reset();
		}
	}

	/** Reads the mixer position and the output latency from OpenAL once per frame and smooths the position. */
	private static void readClock(long nanos, double frameSeconds) {
		double reading;
		if (latencyReported) {
			SOFTSourceLatency.alGetSourcedvSOFT(source, SOFTSourceLatency.AL_SEC_OFFSET_LATENCY_SOFT, offsetAndLatency);
			reading = offsetAndLatency[0];
			latency = offsetAndLatency[1];
		} else {
			reading = AL10.alGetSourcei(source, AL11.AL_SAMPLE_OFFSET) / (double) sampleRate;
		}
		if (!Double.isNaN(seekedTo)) {
			double expected = seekedTo + (paused ? 0 : (nanos - seekedNanos) / 1e9);
			if (Math.abs(reading - expected) > 0.1 && nanos - seekedNanos < 500_000_000L) {
				playedAt = expected;
				playedNanos = nanos;
				return;
			}
			seekedTo = Double.NaN;
			clock.reset();
		}
		playedAt = paused ? reading : clock.update(reading, frameSeconds);
		playedNanos = nanos;
	}

	/**
	 * Mixer position of the song in seconds, smoothed, see AudioClock. Sounds the mod plays share the song's latency,
	 * so they follow this time directly. Between frames it moves on with the system clock.
	 */
	private static double played() {
		if (live()) {
			// The capture's timestamps say when the speakers play each sample, so live song time is what the player
			// hears now. Sounds the mod plays still go through OpenAL, so they run one device latency ahead of it.
			return System.nanoTime() / 1e9 - liveEpoch + latency;
		}
		return paused ? playedAt : playedAt + Math.min(0.1, (System.nanoTime() - playedNanos) / 1e9);
	}

	/**
	 * Song time the screen should show now. The speakers play the mixer's output a device latency later, a frame drawn
	 * now reaches the screen about a frame later (lazer's beat-synced early activation does the same job), and the
	 * player's offset covers delay OpenAL can't see.
	 */
	private static double visualTime(double played) {
		return played - latency + frameLead - offset;
	}

	/**
	 * The beat cue around the crosshair. Four brackets fly in and land on the beat, framing the crosshair just clear of
	 * the attack indicator, gold when the next beat opens the bar and red with longer arms when a full hit will be
	 * ready. A ring opens where they land, the pips below count the bar, and an action on the beat answers with sparks.
	 * Everything has a dark edge so it reads over snow and sky. It follows the velocity ramp and fades in quiet parts.
	 */
	private static void drawBeat(GuiGraphicsExtractor gui, DeltaTracker delta) {
		// Out of a jukebox's range the song is silent, so the cue goes too.
		if (beatNow < 0 || proximity <= 0) {
			return;
		}
		Options options = Minecraft.getInstance().options;
		float cx = gui.guiWidth() / 2f;
		float cy = gui.guiHeight() / 2f;
		float phase = (float) (beatNow % 1);
		int bar = (int) beatNow % 4;
		float presence = Math.min(1, 0.4f + 0.6f * drive());
		float edge = options.highContrast().get() ? 0.9f : 0.5f;
		float flash = (float) (beatNow - flashBeat) / FLASH_BEATS;
		boolean flashing = flash >= 0 && flash < 1 && !options.hideLightningFlash().get();

		// Squaring the phase makes the brackets start slow and rush into the beat.
		float radius = 8 + 13 * (1 - phase * phase);
		boolean ready = readyOnNextBeat();
		float alpha = presence * (0.25f + 0.75f * phase);
		int color = ready ? READY : bar == 3 ? GOLD : WHITE;
		for (int corner = 0; corner < 4; corner++) {
			int sx = corner % 2 == 0 ? -1 : 1;
			int sy = corner < 2 ? -1 : 1;
			int arm = ready ? 6 : 4;
			bracket(gui, cx + sx * radius + 1, cy + sy * radius + 1, sx, sy, arm, argb(alpha * edge, 0));
			bracket(gui, cx + sx * radius, cy + sy * radius, sx, sy, arm, argb(alpha, color));
		}

		if (phase < RING) {
			float k = phase / RING;
			int size = Math.round(8 + 10 * k * (bar == 0 ? 1.5f : 1));
			// The ring answers the kick that lands with the beat: bright on a hard hit, faint where the drums rest.
			float hit = 0.35f + 0.65f * punchAt(rampedSeconds);
			float ringAlpha = (1 - k) * presence * hit * (bar == 0 ? 1 : 0.5f);
			gui.pose().pushMatrix();
			gui.pose().translate(cx, cy);
			gui.outline(1 - size, 1 - size, 2 * size, 2 * size, argb(ringAlpha * edge, 0));
			gui.outline(-size, -size, 2 * size, 2 * size, argb(ringAlpha, bar == 0 ? GOLD : WHITE));
			gui.pose().popMatrix();
		}

		for (int pip = 0; pip < 4; pip++) {
			float pop = pip == bar ? 1 + 0.75f * (1 - phase) * (1 - phase) : 1;
			float pipAlpha = presence * (pip == bar ? 1 : 0.55f);
			gui.pose().pushMatrix();
			gui.pose().translate(cx + (pip * 2 - 3) * 6, cy + 26);
			gui.pose().scale(pop);
			gui.fill(0, 0, 2, 2, argb(pipAlpha * edge, 0));
			gui.fill(-1, -1, 1, 1, argb(pipAlpha, pip == 0 ? GOLD : WHITE));
			gui.pose().popMatrix();
		}

		for (int spark = 0; flashing && spark < 4; spark++) {
			gui.pose().pushMatrix();
			gui.pose().translate(cx, cy);
			gui.pose().rotate((float) (Math.PI / 4 + spark * Math.PI / 2));
			gui.pose().translate(12 + 12 * flash, 0);
			int length = Math.round(3 + 3 * flashStrength);
			float sparkAlpha = (1 - flash) * (1 - flash) * flashStrength;
			gui.fill(0, 1, length, 2, argb(sparkAlpha * edge, 0));
			gui.fill(0, 0, length, 1, argb(sparkAlpha, flashColor));
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

	/** An L-shaped bracket with its corner at (x, y) and arms {@code arm} pixels long pointing back toward the crosshair. */
	private static void bracket(GuiGraphicsExtractor gui, float x, float y, int sx, int sy, int arm, int color) {
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
		// Click errors were measured against the old offset.
		clickErrors.clear();
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

	/** Asks before live mode starts: it is experimental, guesses the beat while it listens and can lose it. */
	private static void askLive(Minecraft client, Screen parent) {
		if (!WINDOWS) {
			// startLive says that it needs Windows.
			startLive(client);
			return;
		}
		client.gui.setScreen(new ConfirmScreen(yes -> {
			if (yes) {
				startLive(client);
			}
			client.gui.setScreen(parent);
		}, Component.translatable("mineatdisco.live.confirm.title").withStyle(ChatFormatting.YELLOW),
				Component.translatable("mineatdisco.live.confirm.message"),
				Component.translatable("mineatdisco.live.confirm.yes"), CommonComponents.GUI_CANCEL));
	}

	/** Queues a YouTube result for saving into the songs folder. Downloads run one at a time in the background. */
	private static void download(YouTube.Result result) {
		if (result.id().equals(downloadId) || downloaded.contains(result.id())
				|| downloadQueue.stream().anyMatch(waiting -> waiting.id().equals(result.id()))) {
			return;
		}
		downloadQueue.add(result);
		if (downloadId == null) {
			nextDownload();
		}
	}

	/** Starts the next queued download. Each saved song plays when nothing else does, otherwise it joins the queue. */
	private static void nextDownload() {
		if (downloadQueue.isEmpty()) {
			return;
		}
		YouTube.Result result = downloadQueue.removeFirst();
		downloadId = result.id();
		downloadTitle = result.title();
		downloadName = YouTube.fileName(result.title());
		downloadProgress = 0;
		Minecraft client = Minecraft.getInstance();
		CompletableFuture.supplyAsync(() -> {
			try {
				return YouTube.download(result, SONGS, toolDirs(), progress -> downloadProgress = progress);
			} catch (IOException | InterruptedException e) {
				throw new CompletionException(e);
			}
		}).whenCompleteAsync((file, error) -> {
			String name = downloadName;
			downloadId = null;
			downloadTitle = null;
			downloadName = null;
			if (error != null) {
				LOGGER.warn("Mine at Disco could not download {}: {}", result.id(), error.getCause() == null ? error : error.getCause().getMessage());
				if (client.player != null) {
					client.player.sendSystemMessage(Component.translatable("mineatdisco.youtube.failed", shortName(result.title())).withStyle(ChatFormatting.RED));
				}
			} else {
				downloaded.add(result.id());
				if (client.player != null) {
					client.player.sendSystemMessage(Component.translatable("mineatdisco.youtube.added", name));
				}
				if (client.level != null) {
					enqueue(name, client);
				}
			}
			nextDownload();
		}, client);
	}

	/** Key for a jukebox that the integrated server and the client share: its dimension and block position. */
	private static String jukeboxKey(Level level, BlockPos pos) {
		return level.dimension() + "@" + pos.asLong();
	}

	/** The song a burned disc holds, or null for any other item. */
	private static String discSong(ItemStack item) {
		CustomData data = item.get(DataComponents.CUSTOM_DATA);
		String song = data == null ? "" : data.copyTag().getStringOr(DISC_TAG, "");
		return song.isEmpty() ? null : song;
	}

	/** A blank disc from the mod's recipe: a record without a song yet, which no jukebox takes. */
	private static boolean isBlank(ItemStack item) {
		CustomData data = item.get(DataComponents.CUSTOM_DATA);
		return data != null && data.copyTag().getBooleanOr(BLANK_TAG, false);
	}

	/** Server side, in a world this game runs: a jukebox is about to hold {@code item}. Remembers a burned disc's song. */
	public static void jukeboxItem(Level level, BlockPos pos, ItemStack item) {
		if (level == null || level.isClientSide()) {
			return;
		}
		String key = jukeboxKey(level, pos);
		String song = discSong(item);
		if (song == null) {
			discoJukeboxes.remove(key);
			discoLengths.remove(key);
		} else {
			discoJukeboxes.put(key, song);
		}
	}

	/**
	 * Server side: whether a jukebox's song is over. Vanilla judges a burned disc by the record it was burned on, so for
	 * those the client's measured length counts instead, and the song plays on until the client has decoded it.
	 */
	public static boolean jukeboxFinished(LevelAccessor level, BlockPos pos, boolean vanilla, long ticks) {
		if (!(level instanceof Level world) || !discoJukeboxes.containsKey(jukeboxKey(world, pos))) {
			return vanilla;
		}
		Long length = discoLengths.get(jukeboxKey(world, pos));
		return length != null && ticks >= length;
	}

	/** Client side: a jukebox started a record. Returns true when it is a burned disc, which the mod plays instead. */
	public static boolean jukeboxStarted(BlockPos pos) {
		Minecraft client = Minecraft.getInstance();
		String song = client.level == null ? null : discoJukeboxes.get(jukeboxKey(client.level, pos));
		if (song == null || !songs().contains(song)) {
			return false;
		}
		playSong(song, client, pos.immutable());
		return true;
	}

	/** Client side: a jukebox stopped, because its record ended, was taken out or the jukebox broke. */
	public static void jukeboxStopped(BlockPos pos) {
		if (pos.equals(jukebox)) {
			stop();
		}
	}

	/**
	 * Burns a song onto the blank disc or music disc in the player's main hand, or onto a new disc in creative, so a
	 * jukebox plays it in the world. The disc then looks like the one the song wears in the menu. Only in a world this
	 * game runs: the item changes on the integrated server.
	 */
	private static void burnDisc(String song) {
		Minecraft client = Minecraft.getInstance();
		MinecraftServer server = client.getSingleplayerServer();
		if (server == null || client.player == null) {
			tell(Component.translatable("mineatdisco.disc.solo").withStyle(ChatFormatting.RED));
			return;
		}
		var id = client.player.getUUID();
		Item look = IconButton.disc(song);
		server.execute(() -> {
			ServerPlayer player = server.getPlayerList().getPlayer(id);
			if (player == null) {
				return;
			}
			ItemStack held = player.getMainHandItem();
			boolean onHeld = held.has(DataComponents.JUKEBOX_PLAYABLE) || isBlank(held);
			if (!onHeld && !player.getAbilities().instabuild) {
				client.execute(() -> tell(Component.translatable("mineatdisco.disc.hold").withStyle(ChatFormatting.RED)));
				return;
			}
			ItemStack disc = onHeld ? held : new ItemStack(look);
			if (!disc.has(DataComponents.JUKEBOX_PLAYABLE)) {
				// A blank disc gets a record's jukebox part now that it holds a song; the record's own line stays hidden.
				disc.set(DataComponents.JUKEBOX_PLAYABLE, new ItemStack(Items.MUSIC_DISC_13).get(DataComponents.JUKEBOX_PLAYABLE));
			}
			disc.set(DataComponents.ITEM_MODEL, BuiltInRegistries.ITEM.getKey(look));
			disc.set(DataComponents.CUSTOM_NAME, Component.literal(song).withStyle(style -> style.withItalic(false).withColor(ChatFormatting.LIGHT_PURPLE)));
			disc.set(DataComponents.LORE, new ItemLore(List.of(Component.translatable("mineatdisco.disc.lore")
					.withStyle(style -> style.withItalic(false).withColor(ChatFormatting.GRAY)))));
			disc.set(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay.DEFAULT.withHidden(DataComponents.JUKEBOX_PLAYABLE, true));
			CustomData.update(DataComponents.CUSTOM_DATA, disc, tag -> {
				tag.putString(DISC_TAG, song);
				tag.remove(BLANK_TAG);
			});
			if (!onHeld) {
				player.getInventory().add(disc);
			}
			client.execute(() -> tell(Component.translatable("mineatdisco.disc.burned", shortName(song))));
		});
	}

	/**
	 * Where to look for yt-dlp, FFmpeg and Deno: the mod's tools folder, where winget links what it installs, the PATH
	 * Windows has now and the PATH the game started with. Installers write the new PATH to the registry, and a running
	 * game never sees it. Without Developer Mode winget can't make links and adds each program's folder there instead.
	 */
	private static List<Path> toolDirs() {
		List<String> entries = new ArrayList<>();
		String local = System.getenv("LOCALAPPDATA");
		if (local != null) {
			entries.add(local + "\\Microsoft\\WinGet\\Links");
		}
		if (WINDOWS) {
			WinReg.HKEY[] roots = {WinReg.HKEY_CURRENT_USER, WinReg.HKEY_LOCAL_MACHINE};
			String[] keys = {"Environment", "SYSTEM\\CurrentControlSet\\Control\\Session Manager\\Environment"};
			for (int i = 0; i < roots.length; i++) {
				try {
					if (Advapi32Util.registryGetValue(roots[i], keys[i], "Path") instanceof String path) {
						entries.addAll(List.of(Kernel32Util.expandEnvironmentStrings(path).split(";")));
					}
				} catch (RuntimeException e) {
					// No such value: the PATH the game started with still counts.
				}
			}
		}
		entries.addAll(List.of(System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)));
		List<Path> dirs = new ArrayList<>(List.of(TOOLS));
		for (String entry : entries) {
			try {
				if (!entry.isBlank()) {
					dirs.add(Path.of(entry.strip()));
				}
			} catch (InvalidPathException e) {
				// A broken PATH entry can't hold the program.
			}
		}
		return dirs;
	}

	/** A song or video title cut to fit a status line. */
	private static String shortName(String name) {
		return name.length() <= 32 ? name : name.substring(0, 30).strip() + "...";
	}

	/** Pixel icons for buttons no item fits, one string per row and '#' for a lit pixel. */
	private static final String[] PLAY = {"#.....", "###...", "#####.", "######", "#####.", "###...", "#....."};
	private static final String[] PAUSE = {"##.##", "##.##", "##.##", "##.##", "##.##", "##.##", "##.##"};
	private static final String[] NEXT = {"#....#", "##...#", "###..#", "####.#", "###..#", "##...#", "#....#"};
	private static final String[] PREVIOUS = {"#....#", "#...##", "#..###", "#.####", "#..###", "#...##", "#....#"};
	private static final String[] SQUARE = {"######", "######", "######", "######", "######", "######"};
	private static final String[] LOOP = {"....#..", "######.", "#...#.#", "#.....#", "#.#...#", ".######", "..#...."};
	private static final String[] PLUS = {"..#..", "..#..", "#####", "..#..", "..#.."};
	private static final String[] CROSS = {"#...#", ".#.#.", "..#..", ".#.#.", "#...#"};
	private static final Identifier TAB = Identifier.withDefaultNamespace("widget/tab");
	private static final Identifier TAB_HIGHLIGHTED = Identifier.withDefaultNamespace("widget/tab_highlighted");
	private static final Identifier TAB_SELECTED = Identifier.withDefaultNamespace("widget/tab_selected");
	private static final Identifier TAB_SELECTED_HIGHLIGHTED = Identifier.withDefaultNamespace("widget/tab_selected_highlighted");
	/** The seek bar wears a boss bar: pink for a song the player can move through, white for a jukebox's. */
	private static final Identifier BAR_BACKGROUND = Identifier.withDefaultNamespace("boss_bar/pink_background");
	private static final Identifier BAR_PROGRESS = Identifier.withDefaultNamespace("boss_bar/pink_progress");
	private static final Identifier JUKEBOX_BAR_BACKGROUND = Identifier.withDefaultNamespace("boss_bar/white_background");
	private static final Identifier JUKEBOX_BAR_PROGRESS = Identifier.withDefaultNamespace("boss_bar/white_progress");
	private static final Identifier SEPARATOR = Identifier.withDefaultNamespace("textures/gui/footer_separator.png");

	/** Draws a pixel icon centered on (x, y), with a dark shadow like button text. */
	private static void drawGlyph(GuiGraphicsExtractor gui, String[] rows, int x, int y, int color) {
		int left = x - rows[0].length() / 2;
		int top = y - rows.length / 2;
		for (int pass = 0; pass < 2; pass++) {
			int shade = pass == 0 ? 1 : 0;
			for (int r = 0; r < rows.length; r++) {
				for (int c = 0; c < rows[r].length(); c++) {
					if (rows[r].charAt(c) == '#') {
						gui.fill(left + c + shade, top + r + shade, left + c + shade + 1, top + r + shade + 1, pass == 0 ? 0xFF3F3F3F : color);
					}
				}
			}
		}
	}

	/** Minutes and seconds, like 3:07. */
	private static String clockTime(double seconds) {
		int s = (int) Math.max(0, seconds);
		return s / 60 + ":" + String.format("%02d", s % 60);
	}

	/**
	 * A vanilla button with an item or pixel icon before its label, like the buttons in Minecraft's own menus. A long
	 * label is cut to fit, a label that fits only alone drops the icon, and an icon-only button keeps its label for
	 * the narrator. List rows reuse one button and change what it shows.
	 */
	private static final class IconButton extends Button {
		private static final Item[] DISCS = {Items.MUSIC_DISC_13, Items.MUSIC_DISC_CAT, Items.MUSIC_DISC_BLOCKS, Items.MUSIC_DISC_CHIRP,
				Items.MUSIC_DISC_FAR, Items.MUSIC_DISC_MALL, Items.MUSIC_DISC_MELLOHI, Items.MUSIC_DISC_STAL, Items.MUSIC_DISC_STRAD,
				Items.MUSIC_DISC_WARD, Items.MUSIC_DISC_WAIT, Items.MUSIC_DISC_PIGSTEP, Items.MUSIC_DISC_OTHERSIDE, Items.MUSIC_DISC_RELIC,
				Items.MUSIC_DISC_CREATOR, Items.MUSIC_DISC_PRECIPICE, Items.MUSIC_DISC_TEARS, Items.MUSIC_DISC_LAVA_CHICKEN,
				Items.MUSIC_DISC_BOUNCE};
		private ItemStack icon;
		private String[] glyph;
		/** Color of a lit glyph, for a button that shows a mode. */
		private int glyphColor = 0xFFFFFFFF;
		private Component trailing;
		private final boolean iconOnly;
		/** Set on the source that plays: its icon dances to the beat and music notes play beside it. */
		private boolean dancing;
		/** Scrolls the list this row belongs to by one row up (-1) or down (1). */
		private java.util.function.IntConsumer scroll;

		private IconButton(int x, int y, int width, Item icon, Component label, Component trailing, boolean iconOnly, OnPress onPress) {
			super(x, y, width, 20, label, onPress, DEFAULT_NARRATION);
			this.icon = icon == null ? null : new ItemStack(icon);
			this.trailing = trailing;
			this.iconOnly = iconOnly;
		}

		/** A square button showing a pixel icon, with {@code label} for the narrator and the tooltip. */
		private static IconButton glyph(int x, int y, String[] glyph, Component label, Component tooltip, OnPress onPress) {
			IconButton button = new IconButton(x, y, 20, null, label, null, true, onPress);
			button.glyph = glyph;
			button.setTooltip(Tooltip.create(tooltip));
			return button;
		}

		/** The music disc a song wears in the menus, the same one every time for the same name. */
		private static Item disc(String name) {
			return DISCS[Math.floorMod(name.hashCode(), DISCS.length)];
		}

		@Override
		public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
			if (scroll == null || scrollY == 0) {
				return false;
			}
			scroll.accept(scrollY > 0 ? -1 : 1);
			return true;
		}

		private void show(Item item, Component label, Component trailing) {
			icon = new ItemStack(item);
			setMessage(label);
			this.trailing = trailing;
		}

		@Override
		protected void extractContents(GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
			extractDefaultSprite(gui);
			Font font = Minecraft.getInstance().font;
			int x = getX();
			int y = getY();
			int width = getWidth();
			if (iconOnly) {
				if (glyph != null) {
					drawGlyph(gui, glyph, x + width / 2, y + 10, active ? glyphColor : GRAY);
				} else {
					gui.item(icon, x + (width - 16) / 2, y + 2);
				}
				return;
			}
			int bob = dancing ? Math.round(hop(0)) : 0;
			int color = active ? 0xFFFFFFFF : GRAY;
			int tail = dancing ? 20 : trailing == null ? 0 : font.width(trailing) + 6;
			if (dancing) {
				gui.blitSprite(RenderPipelines.GUI_TEXTURED, MUSIC_NOTES, x + width - 18, y + 2, 16, 16);
			} else if (trailing != null) {
				gui.text(font, trailing, x + width - 4 - font.width(trailing), y + 6, GRAY, true);
			}
			String label = getMessage().getString();
			if (font.width(label) + 22 + tail > width - 4 && font.width(label) + tail <= width - 8) {
				gui.text(font, label, x + (width - tail - font.width(label)) / 2, y + 6, color, true);
				return;
			}
			int room = width - 26 - tail;
			if (font.width(label) > room) {
				label = font.plainSubstrByWidth(label, room - font.width("...")).strip() + "...";
			}
			gui.item(icon, x + 2, y + 2 - bob);
			gui.text(font, label, x + 22, y + 6, color, true);
		}
	}

	/**
	 * The seek bar: a boss bar scaled to the column, pink for a song the player can move through and white for a
	 * jukebox's. A click jumps at once; a drag shows the time and jumps again on release.
	 */
	private static final class SeekBar extends AbstractWidget {
		/** Where a drag points, NaN when there is none. */
		private double dragging = Double.NaN;
		private double clicked = Double.NaN;

		private SeekBar(int x, int y, int width) {
			super(x, y, width, 9, Component.translatable("mineatdisco.player.seek"));
		}

		private double timeAt(double mouseX) {
			return Mth.clamp((mouseX - getX()) / getWidth(), 0, 1) * songLength;
		}

		/** The time to show: a drag, the mouse over the bar, or NaN for the song's own position. */
		private double preview(int mouseX) {
			return !Double.isNaN(dragging) ? dragging : active && isHovered() ? timeAt(mouseX) : Double.NaN;
		}

		@Override
		public void onClick(MouseButtonEvent event, boolean doubleClick) {
			dragging = timeAt(event.x());
			clicked = dragging;
			seek(dragging);
		}

		@Override
		protected void onDrag(MouseButtonEvent event, double dragX, double dragY) {
			dragging = timeAt(event.x());
		}

		@Override
		public void onRelease(MouseButtonEvent event) {
			finish();
		}

		/** Ends a drag, wherever the mouse was let go. */
		private void finish() {
			if (!Double.isNaN(dragging) && Math.abs(dragging - clicked) > 0.2) {
				seek(dragging);
			}
			dragging = Double.NaN;
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
			if (!Double.isNaN(dragging) && !Minecraft.getInstance().mouseHandler.isLeftPressed()) {
				// Let go off the bar: the game only tells the widget under the mouse about a release.
				finish();
			}
			double shown = preview(mouseX);
			double at = Double.isNaN(shown) ? position() : shown;
			// Boss bars are 182 pixels wide: scale one to the column and draw the played part over it.
			gui.pose().pushMatrix();
			gui.pose().translate(getX(), getY() + 2);
			gui.pose().scale(getWidth() / 182f, 1);
			gui.blitSprite(RenderPipelines.GUI_TEXTURED, jukebox != null ? JUKEBOX_BAR_BACKGROUND : BAR_BACKGROUND, 0, 0, 182, 5);
			gui.blitSprite(RenderPipelines.GUI_TEXTURED, jukebox != null ? JUKEBOX_BAR_PROGRESS : BAR_PROGRESS, 182, 5, 0, 0, 0, 0,
					(int) Math.round(182 * Mth.clamp(at / songLength, 0, 1)), 5);
			gui.pose().popMatrix();
			if (!Double.isNaN(shown)) {
				int x = getX() + (int) Math.round(getWidth() * shown / songLength);
				gui.fill(x, getY() + 1, x + 1, getY() + 8, 0xFFFFFFFF);
			}
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			defaultButtonNarrationText(output);
		}
	}

	/** A tab on Minecraft's own tab sprites, white when selected. */
	private static final class TabButton extends Button {
		private final boolean selected;

		private TabButton(int x, int y, int width, Component label, boolean selected, OnPress onPress) {
			super(x, y, width, DiscoScreen.TAB_HEIGHT, label, onPress, DEFAULT_NARRATION);
			this.selected = selected;
		}

		@Override
		protected void extractContents(GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
			boolean lit = isHoveredOrFocused();
			Identifier sprite = selected ? lit ? TAB_SELECTED_HIGHLIGHTED : TAB_SELECTED : lit ? TAB_HIGHLIGHTED : TAB;
			gui.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, getX(), getY(), getWidth(), getHeight());
			gui.centeredText(Minecraft.getInstance().font, getMessage(), getX() + getWidth() / 2, getY() + (getHeight() - 7) / 2,
					selected ? 0xFFFFFFFF : GRAY);
		}
	}

	/**
	 * In-world music menu. The game keeps running and the middle stays clear, so the player watches the beat cue while
	 * picking music or calibrating. The library sits on the left: songs and playlists, with search and actions above a
	 * line and the list below it. The player sits on the right: what plays, a seek bar and the controls, then the queue,
	 * the volumes and the sync settings. Everything sits on Minecraft's own popup panels and tabs.
	 */
	private static final class DiscoScreen extends Screen {
		/** GUI pixels kept clear on each side of the crosshair. The beat cue reaches 24 at most, sparks included. */
		private static final int CLEAR = 32;
		private static final int TOP = 8;
		private static final int ROW = 22;
		private static final int TAB_HEIGHT = 18;
		/** Margin inside the panel sprite, whose border is 6 pixels. */
		private static final int PAD = 6;
		private static final int SONGS_TAB = 0;
		private static final int PLAYLISTS_TAB = 1;
		private static final int QUEUE_TAB = 0;
		private static final int VOLUME_TAB = 1;
		private static final int SYNC_TAB = 2;
		private static final SoundSource[] VOLUMES = {SoundSource.MASTER, SoundSource.MUSIC, SoundSource.RECORDS, SoundSource.BLOCKS};
		/** Kept between openings, like a music app remembers its view. */
		private static int libraryTab = SONGS_TAB;
		private static int playerTab = QUEUE_TAB;
		private static String filter = "";
		private static int libraryScroll;
		private static int queueScroll;
		private int ticks;
		private int left;
		private int column;
		private int right;
		private int bottom;
		private int listTop;
		private int queueTop;
		private List<String> list = List.of();
		private List<String> playlistNames = List.of();
		private final List<IconButton> rows = new ArrayList<>();
		private final List<IconButton> addButtons = new ArrayList<>();
		private final List<IconButton> burnButtons = new ArrayList<>();
		private final List<IconButton> queueRows = new ArrayList<>();
		private final List<IconButton> removeButtons = new ArrayList<>();
		/** What each library row shows: a song, a playlist, or null for system audio. */
		private final List<String> rowEntries = new ArrayList<>();
		private EditBox textBox;
		private SeekBar seekBar;
		private int lastMouseX;
		private IconButton previousButton;
		private IconButton playButton;
		private IconButton nextButton;
		private IconButton repeatButton;
		private IconButton stopButton;
		private Button earlier;
		private Button later;
		private Button tap;
		private Button half;
		private Button twice;
		private Button apply;
		private Button saveButton;

		private DiscoScreen() {
			super(Component.translatable("mineatdisco.menu.title"));
		}

		@Override
		public boolean isPauseScreen() {
			return false;
		}

		@Override
		protected void init() {
			left = 10;
			column = Math.min(160, width / 2 - CLEAR - left);
			right = width - left - column;
			// Above the hearts and armor, which the left panel would cover otherwise.
			bottom = height - 56;
			rows.clear();
			addButtons.clear();
			burnButtons.clear();
			queueRows.clear();
			removeButtons.clear();
			tap = null;
			saveButton = null;
			list = songs();
			playlistNames = playlists();
			initLibrary();
			initPlayer();
			refresh();
		}

		private void initLibrary() {
			int tabWidth = (column - 2) / 2;
			addRenderableWidget(new TabButton(left, TOP, tabWidth, Component.translatable("mineatdisco.menu.songs"), libraryTab == SONGS_TAB,
					b -> switchLibrary(SONGS_TAB)));
			addRenderableWidget(new TabButton(left + column - tabWidth, TOP, tabWidth, Component.translatable("mineatdisco.menu.playlists"),
					libraryTab == PLAYLISTS_TAB, b -> switchLibrary(PLAYLISTS_TAB)));
			int actions = TOP + TAB_HEIGHT + 4;
			if (libraryTab == SONGS_TAB) {
				textBox = new EditBox(font, left + 1, actions + 1, column - 46, 18, Component.translatable("mineatdisco.menu.search"));
				textBox.setHint(Component.translatable("mineatdisco.menu.search").withStyle(EditBox.SEARCH_HINT_STYLE));
				textBox.setValue(filter);
				textBox.setResponder(text -> {
					filter = text;
					libraryScroll = 0;
					refresh();
				});
				addRenderableWidget(textBox);
				addRenderableWidget(new IconButton(left + column - 42, actions, 20, Items.SPYGLASS, Component.translatable("mineatdisco.menu.youtube"),
						null, true, b -> minecraft.gui.setScreen(new SearchScreen(this))))
						.setTooltip(Tooltip.create(Component.translatable("mineatdisco.menu.youtube.tooltip")));
				addRenderableWidget(new IconButton(left + column - 20, actions, 20, Items.CHEST, Component.translatable("mineatdisco.menu.folder"),
						null, true, b -> Blaze3D.openPath(SONGS)))
						.setTooltip(Tooltip.create(Component.translatable("mineatdisco.menu.folder.tooltip", SONGS.toAbsolutePath().toString())));
			} else {
				textBox = new EditBox(font, left + 1, actions + 1, column - 68, 18, Component.translatable("mineatdisco.playlist.name"));
				textBox.setHint(Component.translatable("mineatdisco.playlist.name").withStyle(EditBox.SEARCH_HINT_STYLE));
				textBox.setMaxLength(60);
				addRenderableWidget(textBox);
				saveButton = addRenderableWidget(Button.builder(Component.translatable("mineatdisco.playlist.save"), b -> savePlaylist())
						.bounds(left + column - 64, actions, 64, 20)
						.tooltip(Tooltip.create(Component.translatable("mineatdisco.playlist.save.tooltip"))).build());
			}
			// The list starts under a line, apart from the search and the actions.
			listTop = actions + 20 + 8;
			int count = Math.max(1, (bottom - listTop + 2) / ROW);
			boolean songsTab = libraryTab == SONGS_TAB;
			int side = songsTab ? 44 : 22;
			for (int i = 0; i < count; i++) {
				int index = i;
				int y = listTop + i * ROW;
				rows.add(addRenderableWidget(new IconButton(left, y, column - side, Items.MUSIC_DISC_CAT, Component.empty(), null, false,
						b -> pressRow(index))));
				rows.getLast().scroll = this::scrollLibrary;
				if (songsTab) {
					addButtons.add(addRenderableWidget(IconButton.glyph(left + column - 42, y, PLUS, Component.translatable("mineatdisco.queue.add"),
							Component.translatable("mineatdisco.queue.add"), b -> enqueue(rowEntries.get(index), minecraft))));
					IconButton burn = new IconButton(left + column - 20, y, 20, Items.FIRE_CHARGE, Component.translatable("mineatdisco.disc.burn"),
							null, true, b -> burnDisc(rowEntries.get(index)));
					burnButtons.add(addRenderableWidget(burn));
				} else {
					addButtons.add(addRenderableWidget(IconButton.glyph(left + column - 20, y, CROSS, Component.translatable("mineatdisco.playlist.delete"),
							Component.translatable("mineatdisco.playlist.delete"), b -> askDelete(rowEntries.get(index)))));
				}
			}
		}

		private void initPlayer() {
			seekBar = addRenderableWidget(new SeekBar(right, TOP + 19, column));
			int y = TOP + 30;
			previousButton = addRenderableWidget(IconButton.glyph(right, y, PREVIOUS, Component.translatable("mineatdisco.player.previous"),
					Component.translatable("mineatdisco.player.previous.tooltip"), b -> previous(minecraft)));
			playButton = addRenderableWidget(IconButton.glyph(right + 22, y, PAUSE, Component.translatable("mineatdisco.player.pause"),
					Component.translatable("mineatdisco.player.pause"), b -> togglePause(minecraft)));
			nextButton = addRenderableWidget(IconButton.glyph(right + 44, y, NEXT, Component.translatable("mineatdisco.player.next"),
					Component.translatable("mineatdisco.player.next.tooltip"), b -> next(minecraft)));
			repeatButton = addRenderableWidget(IconButton.glyph(right + 66, y, LOOP, Component.translatable("mineatdisco.player.repeat"),
					Component.translatable("mineatdisco.player.repeat.off"), b -> repeat = (repeat + 1) % 3));
			stopButton = addRenderableWidget(IconButton.glyph(right + column - 20, y, SQUARE, Component.translatable("mineatdisco.menu.stop"),
					Component.translatable("mineatdisco.menu.stop.tooltip"), b -> stopWithMessage()));

			int tabs = TOP + 54;
			int tabWidth = (column - 4) / 3;
			String[] names = {"mineatdisco.menu.queue", "mineatdisco.menu.volume", "mineatdisco.menu.sync"};
			for (int i = 0; i < 3; i++) {
				int tab = i;
				addRenderableWidget(new TabButton(right + i * (tabWidth + 2), tabs, i == 2 ? column - 2 * (tabWidth + 2) : tabWidth,
						Component.translatable(names[i]), playerTab == i, b -> {
							playerTab = tab;
							rebuildWidgets();
						}));
			}
			int row = tabs + TAB_HEIGHT + 4;
			int halfWidth = (column - 2) / 2;
			if (playerTab == QUEUE_TAB) {
				addRenderableWidget(Button.builder(Component.translatable("mineatdisco.queue.shuffle"), b -> {
					Collections.shuffle(queue);
					playlist = null;
					refresh();
				}).bounds(right, row, halfWidth, 20).tooltip(Tooltip.create(Component.translatable("mineatdisco.queue.shuffle.tooltip"))).build());
				addRenderableWidget(Button.builder(Component.translatable("mineatdisco.queue.clear"), b -> {
					queue.clear();
					playlist = null;
					refresh();
				}).bounds(right + column - halfWidth, row, halfWidth, 20).build());
				queueTop = row + ROW + (downloadId != null ? 12 : 0);
				int count = Math.max(1, (bottom - queueTop + 2) / ROW);
				for (int i = 0; i < count; i++) {
					int index = i;
					queueRows.add(addRenderableWidget(new IconButton(right, queueTop + i * ROW, column - 22, Items.MUSIC_DISC_CAT, Component.empty(),
							null, false, b -> playFromQueue(queueScroll + index))));
					queueRows.getLast().scroll = step -> {
						queueScroll += step;
						refresh();
					};
					removeButtons.add(addRenderableWidget(IconButton.glyph(right + column - 20, queueTop + i * ROW, CROSS,
							Component.translatable("mineatdisco.queue.remove"), Component.translatable("mineatdisco.queue.remove"), b -> {
								if (queueScroll + index < queue.size()) {
									queue.remove(queueScroll + index);
									refresh();
								}
							})));
				}
			} else if (playerTab == VOLUME_TAB) {
				for (SoundSource category : VOLUMES) {
					addRenderableWidget(minecraft.options.getSoundSourceOptionInstance(category).createButton(minecraft.options, right, row, column));
					row += ROW;
				}
			} else {
				row += ROW;
				tap = addRenderableWidget(new IconButton(right, row, column, Items.NOTE_BLOCK, Component.translatable("mineatdisco.menu.tap"),
						null, false, b -> tapOffset()));
				tap.setTooltip(Tooltip.create(Component.translatable("mineatdisco.menu.tap.tooltip", tapKey.getTranslatedKeyMessage())));
				row += ROW;
				int small = (column - 6) / 4;
				earlier = addRenderableWidget(Button.builder(Component.empty(), b -> nudge(-1))
						.bounds(right, row, small, 20).tooltip(Tooltip.create(Component.translatable("mineatdisco.menu.earlier.tooltip"))).build());
				later = addRenderableWidget(Button.builder(Component.empty(), b -> nudge(1))
						.bounds(right + small + 2, row, small, 20).tooltip(Tooltip.create(Component.translatable("mineatdisco.menu.later.tooltip"))).build());
				half = addRenderableWidget(Button.builder(Component.translatable("mineatdisco.menu.half"), b -> scaleTempo(false))
						.bounds(right + 2 * (small + 2), row, small, 20).tooltip(Tooltip.create(Component.translatable("mineatdisco.menu.half.tooltip"))).build());
				twice = addRenderableWidget(Button.builder(Component.translatable("mineatdisco.menu.double"), b -> scaleTempo(true))
						.bounds(right + column - small, row, small, 20).tooltip(Tooltip.create(Component.translatable("mineatdisco.menu.double.tooltip"))).build());
				row += ROW + 12;
				apply = addRenderableWidget(Button.builder(Component.empty(), b -> {
					Integer suggestion = suggestedOffset();
					if (suggestion != null) {
						saveOffset(suggestion);
					}
				}).bounds(right, row, column, 20).tooltip(Tooltip.create(Component.translatable("mineatdisco.menu.apply.tooltip"))).build());
			}
		}

		/**
		 * Moves the library list by one row. Minecraft 26.3 hands the mouse wheel to the widget under the cursor and not
		 * to the screen, so the rows call this.
		 */
		private void scrollLibrary(int step) {
			libraryScroll += step;
			refresh();
		}

		private void switchLibrary(int tab) {
			libraryTab = tab;
			libraryScroll = 0;
			rebuildWidgets();
		}

		/** The library rows' entries: songs matching the search, after system audio when nothing is typed; or playlists. */
		private List<String> libraryEntries() {
			if (libraryTab == PLAYLISTS_TAB) {
				return playlistNames;
			}
			String query = filter.strip().toLowerCase(java.util.Locale.ROOT);
			List<String> entries = new ArrayList<>();
			if (query.isEmpty()) {
				entries.add(null);
			}
			for (String song : list) {
				if (song.toLowerCase(java.util.Locale.ROOT).contains(query)) {
					entries.add(song);
				}
			}
			return entries;
		}

		/** Puts the current entries, scroll positions and states into the rows. Cheap, so it runs every tick. */
		private void refresh() {
			List<String> entries = libraryEntries();
			libraryScroll = Mth.clamp(libraryScroll, 0, Math.max(0, entries.size() - rows.size()));
			rowEntries.clear();
			boolean solo = minecraft.getSingleplayerServer() != null;
			boolean creative = minecraft.player != null && minecraft.player.getAbilities().instabuild;
			for (int i = 0; i < rows.size(); i++) {
				int index = libraryScroll + i;
				boolean shown = index < entries.size();
				String entry = shown ? entries.get(index) : null;
				rowEntries.add(entry);
				IconButton row = rows.get(i);
				row.visible = shown;
				addButtons.get(i).visible = shown && entry != null;
				if (!burnButtons.isEmpty()) {
					burnButtons.get(i).visible = shown && entry != null;
					burnButtons.get(i).active = solo;
					burnButtons.get(i).setTooltip(Tooltip.create(Component.translatable(!solo ? "mineatdisco.disc.solo"
							: creative ? "mineatdisco.disc.burn.creative" : "mineatdisco.disc.burn.tooltip")));
				}
				if (!shown) {
					continue;
				}
				if (libraryTab == PLAYLISTS_TAB) {
					row.show(Items.WRITABLE_BOOK, Component.literal(entry), null);
					row.setTooltip(Tooltip.create(Component.translatable("mineatdisco.playlist.play", entry)));
				} else if (entry == null) {
					row.show(Items.SCULK_SENSOR, Component.translatable("mineatdisco.menu.live"), null);
					row.setTooltip(Tooltip.create(Component.translatable(WINDOWS ? "mineatdisco.menu.live.tooltip" : "mineatdisco.live.windows")));
				} else {
					row.show(IconButton.disc(entry), Component.literal(entry), null);
					row.setTooltip(Tooltip.create(Component.translatable("mineatdisco.menu.song.tooltip", entry)));
				}
			}
			queueScroll = Mth.clamp(queueScroll, 0, Math.max(0, queue.size() - queueRows.size()));
			for (int i = 0; i < queueRows.size(); i++) {
				int index = queueScroll + i;
				boolean shown = index < queue.size();
				queueRows.get(i).visible = shown;
				removeButtons.get(i).visible = shown;
				if (shown) {
					String song = queue.get(index);
					queueRows.get(i).show(IconButton.disc(song), Component.literal(song), Component.literal(Integer.toString(index + 1)));
					queueRows.get(i).setTooltip(Tooltip.create(Component.translatable("mineatdisco.queue.jump", song)));
				}
			}
		}

		private void pressRow(int index) {
			if (index >= rowEntries.size()) {
				return;
			}
			String entry = rowEntries.get(index);
			if (libraryTab == PLAYLISTS_TAB) {
				playPlaylist(entry, minecraft);
			} else if (entry == null) {
				askLive(minecraft, this);
			} else {
				playNow(entry, minecraft);
			}
		}

		/** Plays a song from the queue now, dropping the ones queued before it. */
		private void playFromQueue(int index) {
			if (index >= queue.size()) {
				return;
			}
			queue.subList(0, index).clear();
			next(minecraft);
			refresh();
		}

		private void savePlaylist() {
			String name = textBox.getValue().strip();
			if (name.isEmpty() || !localSong() && queue.isEmpty()) {
				tell(Component.translatable("mineatdisco.playlist.nothing"));
				return;
			}
			String saved = saveQueue(name);
			tell(saved == null ? Component.translatable("mineatdisco.playlist.failed").withStyle(ChatFormatting.RED)
					: Component.translatable("mineatdisco.playlist.saved", saved));
			if (saved != null) {
				playlist = saved;
				rebuildWidgets();
			}
		}

		private void askDelete(String name) {
			minecraft.gui.setScreen(new ConfirmScreen(yes -> {
				if (yes) {
					try {
						Files.deleteIfExists(PLAYLISTS.resolve(name + ".txt"));
					} catch (IOException e) {
						LOGGER.warn("Mine at Disco could not delete playlist {}: {}", name, e.getMessage());
					}
					if (name.equals(playlist)) {
						playlist = null;
					}
				}
				minecraft.gui.setScreen(this);
			}, Component.translatable("mineatdisco.playlist.delete.title", name), Component.translatable("mineatdisco.playlist.delete.message"),
					Component.translatable("mineatdisco.playlist.delete"), CommonComponents.GUI_CANCEL));
		}

		/** Moves the offset by 10 ms, or 1 ms with Shift held, toward earlier ({@code -1}) or later ({@code 1}). */
		private void nudge(int direction) {
			int step = minecraft.hasShiftDown() ? 1 : 10;
			int ms = Mth.clamp((int) Math.round(offset * 1000) + direction * step, -500, 500);
			if (!saveOffset(ms)) {
				tell(Component.translatable("mineatdisco.offset_not_saved", ms).withStyle(ChatFormatting.RED));
			}
		}

		@Override
		public void tick() {
			// Songs and playlists added while the menu is open show up within a second.
			if (++ticks % 20 == 0 && (!songs().equals(list) || !playlists().equals(playlistNames))) {
				list = songs();
				playlistNames = playlists();
			}
			refresh();
		}

		/** The now-playing card: what plays, and a line with its tempo and time, a fresh message or a hint. */
		private Component[] card() {
			Component title;
			Component line;
			if (loadingSong != null) {
				title = Component.literal(loadingSong);
				line = Component.translatable("mineatdisco.card.loading");
			} else if (live()) {
				title = Component.translatable("mineatdisco.menu.live");
				line = recentBpm() > 0 ? Component.translatable("mineatdisco.card.bpm", recentBpm()) : Component.translatable("mineatdisco.card.listening");
			} else if (source != 0) {
				title = Component.literal(currentSong);
				double shown = seekBar.preview(lastMouseX);
				double at = Double.isNaN(shown) ? position() : shown;
				String key = jukebox != null ? "mineatdisco.card.jukebox" : paused ? "mineatdisco.card.paused" : "mineatdisco.card.playing";
				line = Component.translatable(key, Math.round(BeatTracker.bpm(beats)), clockTime(at), clockTime(songLength));
			} else {
				title = Component.translatable("mineatdisco.menu.idle");
				line = Component.translatable(queue.isEmpty() ? "mineatdisco.menu.idle.hint" : "mineatdisco.card.queued", queue.size());
			}
			if (menuMessage != null && System.nanoTime() - menuMessageAt < 4_000_000_000L) {
				line = menuMessage;
			}
			return new Component[] {title, line};
		}

		@Override
		public void extractBackground(GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
			// No dimming or blur: the world and the beat cue stay in full view.
			gui.blitSprite(RenderPipelines.GUI_TEXTURED, PANEL, left - PAD, TOP - PAD, column + 2 * PAD, bottom - TOP + 2 * PAD);
			gui.blitSprite(RenderPipelines.GUI_TEXTURED, PANEL, right - PAD, TOP - PAD, column + 2 * PAD, bottom - TOP + 2 * PAD);
			// Minecraft's own list separator between the library's actions and its list.
			gui.blit(RenderPipelines.GUI_TEXTURED, SEPARATOR, left, listTop - 6, 0, 0, column, 2, 32, 2);
		}

		@Override
		public void extractRenderState(GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
			boolean local = localSong();
			seekBar.visible = source != 0 && songLength > 0;
			seekBar.active = local;
			previousButton.active = local;
			playButton.active = local || !playing() && loadingSong == null && !queue.isEmpty();
			playButton.glyph = local && !paused ? PAUSE : PLAY;
			playButton.setMessage(Component.translatable(local && !paused ? "mineatdisco.player.pause" : "mineatdisco.player.play"));
			nextButton.active = local && (!queue.isEmpty() || repeat == REPEAT_ALL && playlist != null);
			repeatButton.glyphColor = repeat == REPEAT_OFF ? GRAY : repeat == REPEAT_ALL ? 0xFFFFFFFF : argb(1, GOLD);
			repeatButton.setTooltip(Tooltip.create(Component.translatable(repeat == REPEAT_OFF ? "mineatdisco.player.repeat.off"
					: repeat == REPEAT_ALL ? "mineatdisco.player.repeat.all" : "mineatdisco.player.repeat.one")));
			stopButton.active = playing() || loadingSong != null;
			for (int i = 0; i < rows.size(); i++) {
				String entry = i < rowEntries.size() ? rowEntries.get(i) : null;
				boolean current = libraryTab == SONGS_TAB && (entry == null ? live() && rows.get(i).visible
						: entry.equals(loadingSong) || source != 0 && entry.equals(currentSong));
				rows.get(i).active = !current && (entry != null || WINDOWS);
				rows.get(i).dancing = current;
			}
			if (saveButton != null) {
				saveButton.active = !textBox.getValue().isBlank() && (local || !queue.isEmpty());
			}
			Integer suggestion = suggestedOffset();
			if (tap != null) {
				int step = minecraft.hasShiftDown() ? 1 : 10;
				boolean localSongPlays = source != 0 && beats.length >= 4 && jukebox == null;
				tap.active = playing();
				half.active = localSongPlays;
				twice.active = localSongPlays;
				earlier.setMessage(Component.translatable("mineatdisco.menu.earlier", step));
				later.setMessage(Component.translatable("mineatdisco.menu.later", step));
				apply.visible = suggestion != null;
				if (suggestion != null) {
					apply.setMessage(Component.translatable("mineatdisco.menu.apply", suggestion));
				}
			}
			super.extractRenderState(gui, mouseX, mouseY, partialTick);

			drawCard(gui, mouseX, mouseY);
			drawLibraryNotes(gui);
			if (playerTab == QUEUE_TAB) {
				drawQueueNotes(gui);
			} else if (playerTab == SYNC_TAB) {
				int row = TOP + 54 + TAB_HEIGHT + 4;
				// The clock item turns with the time of day, a small Minecraft touch beside the timing numbers.
				gui.item(new ItemStack(Items.CLOCK), right, row + 2);
				gui.text(font, Component.translatable("mineatdisco.menu.offset", Math.round(offset * 1000)), right + 20, row + 1, 0xFFFFFFFF, true);
				if (latencyReported) {
					gui.text(font, Component.translatable("mineatdisco.menu.latency", Math.round(latency * 1000)), right + 20, row + 11, GRAY, true);
				}
				Component note = DiscoScreen.note(suggestion);
				if (note != null) {
					gui.textWithWordWrap(font, note, right, row + 3 * ROW + 1, column, GRAY);
				}
			}
		}

		private void drawCard(GuiGraphicsExtractor gui, int mouseX, int mouseY) {
			lastMouseX = mouseX;
			Component[] card = card();
			int bob = playing() ? Math.round(hop(0)) : 0;
			if (jukebox != null) {
				gui.item(new ItemStack(Items.JUKEBOX), right, TOP + 2 - bob);
			} else if (live()) {
				gui.item(new ItemStack(Items.SCULK_SENSOR), right, TOP + 2 - bob);
			} else if (source != 0) {
				gui.item(new ItemStack(IconButton.disc(currentSong)), right, TOP + 2 - bob);
			} else {
				gui.blitSprite(RenderPipelines.GUI_TEXTURED, MUSIC_NOTES, right, TOP + 2, 16, 16, 0.35f);
			}
			String title = card[0].getString();
			if (font.width(title) > column - 20) {
				title = font.plainSubstrByWidth(title, column - 20 - font.width("...")).strip() + "...";
			}
			gui.text(font, title, right + 20, TOP + 1, 0xFFFFFFFF, true);
			gui.text(font, font.substrByWidth(card[1], column - 20).getString(), right + 20, TOP + 11, GRAY, true);
		}

		private void drawLibraryNotes(GuiGraphicsExtractor gui) {
			List<String> entries = libraryEntries();
			int shownRows = rows.size();
			if (entries.size() > shownRows) {
				// A thin scrollbar in the panel's margin: the mouse wheel moves the list.
				int track = shownRows * ROW - 2;
				int thumb = Math.max(8, track * shownRows / entries.size());
				int at = (track - thumb) * libraryScroll / Math.max(1, entries.size() - shownRows);
				gui.fill(left + column + 2, listTop, left + column + 4, listTop + track, 0x40FFFFFF);
				gui.fill(left + column + 2, listTop + at, left + column + 4, listTop + at + thumb, 0xFFC0C0C0);
			}
			Component empty = null;
			if (libraryTab == PLAYLISTS_TAB && entries.isEmpty()) {
				empty = Component.translatable("mineatdisco.playlist.none");
			} else if (libraryTab == SONGS_TAB && list.isEmpty()) {
				empty = Component.translatable("mineatdisco.menu.empty");
			} else if (libraryTab == SONGS_TAB && entries.isEmpty()) {
				empty = Component.translatable("mineatdisco.menu.no_match", filter);
			}
			if (empty != null) {
				int y = listTop + (libraryTab == SONGS_TAB && filter.isBlank() ? ROW : 0) + 2;
				gui.textWithWordWrap(font, empty, left, y, column, GRAY);
			}
		}

		private void drawQueueNotes(GuiGraphicsExtractor gui) {
			String title = downloadTitle;
			if (title != null) {
				Component line = Component.translatable("mineatdisco.youtube.downloading", shortName(title), Math.round(downloadProgress * 100));
				if (!downloadQueue.isEmpty()) {
					line = Component.translatable("mineatdisco.youtube.downloading_more", line, downloadQueue.size());
				}
				gui.text(font, font.substrByWidth(line, column).getString(), right, queueTop - 11, GRAY, true);
			}
			if (queue.isEmpty()) {
				gui.textWithWordWrap(font, Component.translatable("mineatdisco.queue.empty"), right, queueTop + 2, column, GRAY);
			} else if (queue.size() > queueRows.size()) {
				int track = queueRows.size() * ROW - 2;
				int thumb = Math.max(8, track * queueRows.size() / queue.size());
				int at = (track - thumb) * queueScroll / Math.max(1, queue.size() - queueRows.size());
				gui.fill(right + column + 2, queueTop, right + column + 4, queueTop + track, 0x40FFFFFF);
				gui.fill(right + column + 2, queueTop + at, right + column + 4, queueTop + at + thumb, 0xFFC0C0C0);
			}
		}

		/** The line under the tempo buttons: the suggested offset, how far the player is from getting one, or null. */
		private static Component note(Integer suggestion) {
			int ms = (int) Math.round(offset * 1000);
			if (suggestion != null) {
				return Component.translatable("mineatdisco.menu.suggested", Math.abs(suggestion - ms),
						Component.translatable(suggestion > ms ? "mineatdisco.menu.later_word" : "mineatdisco.menu.earlier_word"));
			}
			if (!playing()) {
				return null;
			}
			return clickErrors.size() < CLICKS_FOR_SUGGESTION
					? Component.translatable("mineatdisco.menu.hits", clickErrors.size(), CLICKS_FOR_SUGGESTION)
					: Component.translatable("mineatdisco.menu.on_time");
		}

		@Override
		public boolean keyPressed(KeyEvent event) {
			if (textBox != null && textBox.isFocused()) {
				// Typing a name or a search: M, B and Space are letters here.
				if ((event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) && saveButton != null) {
					savePlaylist();
					return true;
				}
				return super.keyPressed(event);
			}
			if (event.key() == InputConstants.KEY_SPACE || tapKey.matches(event)) {
				tapOffset();
				return true;
			}
			if (menuKey.matches(event)) {
				onClose();
				return true;
			}
			return super.keyPressed(event);
		}

		@Override
		public void removed() {
			// The volume sliders change the game's options, which vanilla saves when its own screen closes.
			minecraft.options.save();
		}
	}

	/** Shown when a world opens with Master or Music at 0%: songs play on Music, so the sliders are right here. */
	private static final class VolumeScreen extends Screen {
		private VolumeScreen() {
			super(Component.translatable("mineatdisco.volume.title"));
		}

		@Override
		protected void init() {
			Options options = minecraft.options;
			int x = width / 2 - 100;
			int y = height / 2 - 6;
			addRenderableWidget(options.getSoundSourceOptionInstance(SoundSource.MASTER).createButton(options, x, y, 200));
			addRenderableWidget(options.getSoundSourceOptionInstance(SoundSource.MUSIC).createButton(options, x, y + 24, 200));
			addRenderableWidget(Button.builder(Component.translatable("mineatdisco.volume.play"), b -> onClose()).bounds(x, y + 56, 200, 20).build());
		}

		@Override
		public void onClose() {
			minecraft.options.save();
			super.onClose();
		}

		@Override
		public void extractRenderState(GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
			super.extractRenderState(gui, mouseX, mouseY, partialTick);
			gui.centeredText(font, title, width / 2, height / 2 - 56, 0xFFFFFFFF);
			var lines = font.split(Component.translatable("mineatdisco.volume.message"), 280);
			for (int i = 0; i < lines.size(); i++) {
				gui.centeredText(font, lines.get(i), width / 2, height / 2 - 40 + i * 10, GRAY);
			}
		}
	}

	/**
	 * Searches YouTube from inside the game, or opens a YouTube link, and saves picks into the songs folder through
	 * yt-dlp. Picks queue up and download one at a time; each plays next or joins the queue when it is saved.
	 */
	private static final class SearchScreen extends Screen {
		private static String lastQuery = "";
		private static List<YouTube.Result> results = List.of();
		private final Screen parent;
		private final List<IconButton> resultButtons = new ArrayList<>();
		private final List<YouTube.Result> shownResults = new ArrayList<>();
		private EditBox box;
		/** The line under the search box: searching, no results, an error. Null while results show. */
		private Component message;
		private boolean missingTools;
		private int request;

		private SearchScreen(Screen parent) {
			super(Component.translatable("mineatdisco.youtube.title"));
			this.parent = parent;
		}

		@Override
		protected void init() {
			int w = Math.min(300, width - 20);
			int x = width / 2 - w / 2;
			box = new EditBox(font, x, 28, w - 66, 20, Component.translatable("mineatdisco.youtube.hint"));
			box.setMaxLength(300);
			box.setValue(lastQuery);
			box.setHint(Component.translatable("mineatdisco.youtube.hint").withStyle(EditBox.SEARCH_HINT_STYLE));
			addRenderableWidget(box);
			setInitialFocus(box);
			addRenderableWidget(Button.builder(Component.translatable("mineatdisco.youtube.search"), b -> search())
					.bounds(x + w - 62, 28, 62, 20).build());
			if (missingTools) {
				addRenderableWidget(Button.builder(Component.translatable("mineatdisco.youtube.copy"), b -> {
					minecraft.keyboardHandler.setClipboard(YouTube.INSTALL);
					message = Component.translatable("mineatdisco.youtube.copied", YouTube.INSTALL);
				}).bounds(width / 2 - 100, 92, 200, 20).build());
			}
			resultButtons.clear();
			shownResults.clear();
			int top = 58;
			int fit = Math.max(1, (height - 50 - top) / 22);
			for (int i = 0; message == null && i < Math.min(fit, results.size()); i++) {
				YouTube.Result result = results.get(i);
				IconButton button = new IconButton(x, top + i * 22, w, IconButton.disc(YouTube.fileName(result.title())),
						Component.literal(result.title()), null, false, b -> download(result));
				button.setTooltip(Tooltip.create(result.playable()
						? Component.translatable("mineatdisco.youtube.result.tooltip", result.title())
						: Component.translatable("mineatdisco.youtube.too_long", YouTube.MAX_SECONDS / 60)));
				resultButtons.add(addRenderableWidget(button));
				shownResults.add(result);
			}
			int done = height - 28;
			if (results.size() > 1 && lastQuery.contains("/playlist")) {
				// A playlist link: one click queues every song that fits.
				addRenderableWidget(Button.builder(Component.translatable("mineatdisco.youtube.all", results.size()), b -> {
					results.stream().filter(YouTube.Result::playable).forEach(MineAtDisco::download);
				}).bounds(width / 2 - 100, done, 98, 20).build());
				addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(width / 2 + 2, done, 98, 20).build());
			} else {
				addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(width / 2 - 100, done, 200, 20).build());
			}
		}

		private void search() {
			String query = box.getValue().strip();
			if (query.isEmpty()) {
				return;
			}
			lastQuery = query;
			int mine = ++request;
			message = Component.translatable("mineatdisco.youtube.searching");
			missingTools = false;
			rebuildWidgets();
			CompletableFuture.supplyAsync(() -> {
				try {
					return YouTube.search(query, toolDirs());
				} catch (IOException | InterruptedException e) {
					throw new CompletionException(e);
				}
			}).whenCompleteAsync((found, error) -> {
				if (mine != request) {
					return;
				}
				if (error != null) {
					Throwable cause = error.getCause() == null ? error : error.getCause();
					missingTools = cause instanceof YouTube.MissingTools;
					if (!missingTools && !(cause instanceof YouTube.NotYouTube)) {
						LOGGER.warn("Mine at Disco could not search YouTube: {}", cause.getMessage());
					}
					message = Component.translatable(missingTools ? "mineatdisco.youtube.missing"
							: cause instanceof YouTube.NotYouTube ? "mineatdisco.youtube.not_youtube" : "mineatdisco.youtube.search_failed");
					results = List.of();
				} else {
					results = found;
					message = found.isEmpty() ? Component.translatable("mineatdisco.youtube.none", query) : null;
				}
				rebuildWidgets();
			}, minecraft);
		}

		@Override
		public boolean keyPressed(KeyEvent event) {
			if ((event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) && box.isFocused()) {
				search();
				return true;
			}
			return super.keyPressed(event);
		}

		@Override
		public void onClose() {
			minecraft.gui.setScreen(parent);
		}

		/** What a result row shows at its right end: its length, or where its download stands. */
		private static Component state(YouTube.Result result) {
			if (downloaded.contains(result.id())) {
				return Component.translatable("mineatdisco.youtube.state.added");
			}
			if (result.id().equals(downloadId)) {
				return Component.literal(Math.round(downloadProgress * 100) + "%");
			}
			if (downloadQueue.stream().anyMatch(waiting -> waiting.id().equals(result.id()))) {
				return Component.translatable("mineatdisco.youtube.state.queued");
			}
			return result.seconds() > 0 ? Component.literal(clockTime(result.seconds())) : Component.translatable("mineatdisco.youtube.live");
		}

		@Override
		public void extractRenderState(GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
			for (int i = 0; i < resultButtons.size(); i++) {
				YouTube.Result result = shownResults.get(i);
				resultButtons.get(i).trailing = state(result);
				resultButtons.get(i).active = result.playable() && !downloaded.contains(result.id()) && !result.id().equals(downloadId)
						&& downloadQueue.stream().noneMatch(waiting -> waiting.id().equals(result.id()));
			}
			super.extractRenderState(gui, mouseX, mouseY, partialTick);
			gui.centeredText(font, title, width / 2, 12, 0xFFFFFFFF);
			if (message != null) {
				var lines = font.split(message, Math.min(300, width - 20));
				for (int i = 0; i < lines.size(); i++) {
					gui.centeredText(font, lines.get(i), width / 2, 58 + i * 10, GRAY);
				}
			}
			String downloading = downloadTitle;
			if (downloading != null) {
				Component line = Component.translatable("mineatdisco.youtube.downloading", shortName(downloading), Math.round(downloadProgress * 100));
				if (!downloadQueue.isEmpty()) {
					line = Component.translatable("mineatdisco.youtube.downloading_more", line, downloadQueue.size());
				}
				gui.centeredText(font, line, width / 2, height - 42, 0xFFFFFFFF);
			}
		}
	}
}
