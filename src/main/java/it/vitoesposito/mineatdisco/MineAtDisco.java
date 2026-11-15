package it.vitoesposito.mineatdisco;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.ARGB;
import net.minecraft.util.RandomSource;
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
	private static final float HUD_HOP = 4;
	private static final int GOLD = 0xFFD040;
	private static final Set<SoundSource> WORLD_SOUNDS =
			EnumSet.of(SoundSource.BLOCKS, SoundSource.PLAYERS, SoundSource.HOSTILE, SoundSource.NEUTRAL);

	private record Song(ShortBuffer pcm, int channels, int sampleRate, double[] beats) {
	}

	private record Deferred(SoundInstance sound, double due) {
	}

	// OpenAL handles, 0 while nothing plays. The mod owns its source so it can read the exact playback position.
	private static int source;
	private static int buffer;
	private static int sampleRate;
	private static double[] beats = {};
	private static boolean paused;
	/** Largest (audio position - system clock) seen since the last start or resume, see played(). */
	private static double anchor;
	/** Seconds to delay what you see, to make up for audio output latency. */
	private static double offset;
	/** Counts play and stop requests, so a song that finishes loading late knows it was replaced. */
	private static int request;
	/** Beat position for this frame, -1 outside the tracked beats. Saves asking OpenAL once per entity. */
	private static double beatNow = -1;
	private static int lastBeat = -1;
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
		ClientTickEvents.END_CLIENT_TICK.register(MineAtDisco::tick);
		HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR,
				Identifier.fromNamespaceAndPath("mineatdisco", "beat"), MineAtDisco::drawBeat);
		// The hotbar and the status bars hop with the pulse.
		for (Identifier element : List.of(VanillaHudElements.HOTBAR, VanillaHudElements.HEALTH_BAR,
				VanillaHudElements.ARMOR_BAR, VanillaHudElements.FOOD_BAR, VanillaHudElements.AIR_BAR,
				VanillaHudElements.MOUNT_HEALTH, VanillaHudElements.INFO_BAR, VanillaHudElements.EXPERIENCE_LEVEL,
				VanillaHudElements.INFO_BAR_FOREGROUND)) {
			HudElementRegistry.replaceElement(element, original -> (gui, delta) -> {
				gui.pose().pushMatrix();
				gui.pose().translate(0, -HUD_HOP * pulse());
				original.extractRenderState(gui, delta);
				gui.pose().popMatrix();
			});
		}
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(literal("disco")
				.then(literal("play").then(argument("song", StringArgumentType.greedyString())
						.suggests((command, builder) -> SharedSuggestionProvider.suggest(songs(), builder))
						.executes(command -> play(command.getSource(), StringArgumentType.getString(command, "song")))))
				.then(literal("stop").executes(command -> {
					stop();
					command.getSource().sendFeedback(Component.translatable("mineatdisco.stopped"));
					return 1;
				}))
				.then(literal("offset").then(argument("ms", IntegerArgumentType.integer(-500, 500))
						.executes(command -> setOffset(command.getSource(), IntegerArgumentType.getInteger(command, "ms")))))));
	}

	/** Peaks on each beat and eases to 0 before the next. The first beat of the bar peaks at 1, the others lower. */
	public static float pulse() {
		if (beatNow < 0) {
			return 0;
		}
		double left = 1 - beatNow % 1;
		return ACCENT[(int) beatNow % 4] * (float) (left * left);
	}

	/**
	 * Holds a world sound until the next grid line of the song, so the game plays in time with it.
	 * Returns false when the sound should play now.
	 * ponytail: this catches every world sound, not only the ones the player causes.
	 */
	public static boolean defer(SoundInstance sound) {
		if (releasing || source == 0 || paused || !WORLD_SOUNDS.contains(sound.getSource())
				|| sound.isLooping() || sound instanceof TickableSoundInstance) {
			return false;
		}
		// Mining and footsteps repeat, so they keep steady eighth notes. One-shots take the next sixteenth.
		String name = sound.getIdentifier().getPath();
		int lines = name.endsWith(".hit") || name.endsWith(".step") ? 2 : 4;
		// Sounds share the song's output latency, so they follow the audio position without the offset.
		double grid = BeatTracker.beatAt(beats, played()) * lines;
		if (grid < 0 || grid % 1 < ON_GRID) {
			return false;
		}
		deferred.add(new Deferred(sound, BeatTracker.timeAt(beats, Math.ceil(grid) / lines)));
		return true;
	}

	/** Runs once per frame: refreshes the beat and plays the deferred sounds that are due. */
	public static void frame(Consumer<SoundInstance> play) {
		double now = source == 0 ? Double.MAX_VALUE : played();
		beatNow = source == 0 ? -1 : BeatTracker.beatAt(beats, now - offset);
		int beat = (int) Math.floor(beatNow);
		if (beat != lastBeat) {
			lastBeat = beat;
			if (beat >= 0 && beat % 4 == 0) {
				shedLeaves(beat % BeatTracker.PHRASE == 0 ? 2 : 1);
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
				play.accept(held.sound());
				return true;
			});
		} finally {
			releasing = false;
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
		for (int i = 0; i < 300 * strength; i++) {
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
		stop();
		int mine = request;
		command.sendFeedback(Component.translatable("mineatdisco.loading", name));
		// Decoding and beat tracking take about a second, so they run off the client thread.
		CompletableFuture.supplyAsync(() -> decode(SONGS.resolve(name + ".ogg"))).whenCompleteAsync((song, error) -> {
			if (song == null) {
				command.sendError(Component.translatable("mineatdisco.failed", name));
			} else if (mine != request) {
				LibCStdlib.free(song.pcm());
			} else {
				float volume = command.getClient().options.getFinalSoundSourceVolume(SoundSource.MUSIC);
				start(song, volume);
				command.sendFeedback(Component.translatable("mineatdisco.playing", name, Math.round(BeatTracker.bpm(beats))));
				if (volume == 0) {
					command.sendError(Component.translatable("mineatdisco.muted"));
				}
			}
		}, command.getClient());
		return 1;
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
			return new Song(pcm, channels.get(0), rate.get(0), BeatTracker.track(pcm, channels.get(0), rate.get(0)));
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
		beats = new double[0];
	}

	private static void tick(Minecraft client) {
		if (source == 0) {
			return;
		}
		if (client.level == null || !alive() || AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) == AL10.AL_STOPPED) {
			stop();
			return;
		}
		client.getMusicManager().stopPlaying();
		AL10.alSourcef(source, AL10.AL_GAIN, client.options.getFinalSoundSourceVolume(SoundSource.MUSIC));
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

	/** A square that closes on the crosshair and lands on the beat, over four pips that count the bar. */
	private static void drawBeat(GuiGraphicsExtractor gui, DeltaTracker delta) {
		if (beatNow < 0) {
			return;
		}
		float phase = (float) (beatNow % 1);
		int size = 9 + 2 * Math.round(12 * (1 - phase));
		gui.outline((gui.guiWidth() - size) / 2, (gui.guiHeight() - size) / 2, size, size, ARGB.white(0.2f + 0.8f * phase));

		int now = (int) beatNow % 4;
		int y = gui.guiHeight() / 2 + 24;
		for (int pip = 0; pip < 4; pip++) {
			int x = gui.guiWidth() / 2 + (pip * 2 - 3) * 6;
			int half = pip == now ? 3 : 1;
			int color = (pip == now ? 0xFF000000 : 0x80000000) | (pip == 0 ? GOLD : 0xFFFFFF);
			gui.fill(x - half, y - half, x + half, y + half, color);
		}
	}

	private static int setOffset(FabricClientCommandSource command, int ms) {
		offset = ms / 1000.0;
		try {
			Files.writeString(OFFSET_FILE, Integer.toString(ms));
			command.sendFeedback(Component.translatable("mineatdisco.offset", ms));
		} catch (IOException e) {
			command.sendError(Component.translatable("mineatdisco.offset_not_saved", ms));
		}
		return 1;
	}
}
