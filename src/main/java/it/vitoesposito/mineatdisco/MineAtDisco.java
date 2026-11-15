package it.vitoesposito.mineatdisco;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
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
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.ARGB;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.libc.LibCStdlib;

/** Plays a song from the disco folder and pulses the crosshair on its beat. */
public final class MineAtDisco implements ClientModInitializer {
	private static final Path SONGS = FabricLoader.getInstance().getGameDir().resolve("disco");
	private static final Path OFFSET_FILE = SONGS.resolve("offset.txt");

	private record Song(ShortBuffer pcm, int channels, int sampleRate, double[] beats) {
	}

	// OpenAL handles, 0 while nothing plays. The mod owns its source so it can read the exact playback position.
	private int source;
	private int buffer;
	private int sampleRate;
	private double[] beats = {};
	private boolean paused;
	/** Largest (audio position - system clock) seen since the last start or resume, see position(). */
	private double anchor;
	/** Seconds to delay the pulse, to make up for audio output latency. */
	private double offset;
	/** Counts play and stop requests, so a song that finishes loading late knows it was replaced. */
	private int request;

	@Override
	public void onInitializeClient() {
		try {
			Files.createDirectories(SONGS);
			offset = Integer.parseInt(Files.readString(OFFSET_FILE).strip()) / 1000.0;
		} catch (IOException | NumberFormatException e) {
			// No saved offset yet.
		}
		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
		HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR,
				Identifier.fromNamespaceAndPath("mineatdisco", "beat"), this::drawBeat);
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

	private int play(FabricClientCommandSource command, String name) {
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
				start(song, command.getClient());
				command.sendFeedback(Component.translatable("mineatdisco.playing", name, Math.round(BeatTracker.bpm(beats))));
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

	private void start(Song song, Minecraft client) {
		buffer = AL10.alGenBuffers();
		AL10.alBufferData(buffer, song.channels() == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16,
				song.pcm(), song.sampleRate());
		LibCStdlib.free(song.pcm()); // OpenAL keeps its own copy.
		source = AL10.alGenSources();
		AL10.alSourcei(source, AL10.AL_BUFFER, buffer);
		AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE); // Play at the listener, not somewhere in the world.
		AL10.alSourcef(source, AL10.AL_GAIN, client.options.getFinalSoundSourceVolume(SoundSource.MUSIC));
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
	private boolean alive() {
		return source != 0 && AL10.alIsSource(source) && AL10.alGetSourcei(source, AL10.AL_BUFFER) == buffer;
	}

	private void stop() {
		request++;
		if (alive()) {
			AL10.alSourceStop(source);
			AL10.alDeleteSources(source);
			AL10.alDeleteBuffers(buffer);
		}
		source = 0;
		beats = new double[0];
	}

	private void tick(Minecraft client) {
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

	/** Audio position in seconds, shifted by the calibration offset. */
	private double position() {
		double played = AL10.alGetSourcei(source, AL11.AL_SAMPLE_OFFSET) / (double) sampleRate;
		if (!paused) {
			// OpenAL moves the offset once per mixer period, about 20 ms, so every reading is slightly stale.
			// The freshest reading so far gives the true position, and the system clock fills in between readings.
			// ponytail: the two clocks drift apart by a few ms over a song. Re-anchor now and then if timing gets tight.
			double now = System.nanoTime() / 1e9;
			anchor = Math.max(anchor, played - now);
			played = now + anchor;
		}
		return played - offset;
	}

	/** A square that closes on the crosshair and lands on the beat. */
	private void drawBeat(GuiGraphicsExtractor gui, DeltaTracker delta) {
		if (source == 0) {
			return;
		}
		double beat = BeatTracker.beatAt(beats, position());
		if (beat < 0) {
			return;
		}
		float phase = (float) (beat % 1);
		int size = 9 + 2 * Math.round(12 * (1 - phase));
		gui.outline((gui.guiWidth() - size) / 2, (gui.guiHeight() - size) / 2, size, size, ARGB.white(0.2f + 0.8f * phase));
	}

	private int setOffset(FabricClientCommandSource command, int ms) {
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
