package it.vitoesposito.mineatdisco;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/**
 * Searches YouTube and saves a result as an Ogg Vorbis song through yt-dlp and FFmpeg, which the player installs. They
 * run as separate programs with their arguments passed as a list, so no shell ever sees the search text.
 */
final class YouTube {
	/** Longer videos are mixes or streams: slow to fetch, and an hour of decoded audio doesn't fit in memory. */
	static final int MAX_SECONDS = 12 * 60;
	/** One command that installs everything needed. YouTube needs Deno too: yt-dlp runs its player code with it. */
	static final String INSTALL = "winget install yt-dlp.yt-dlp Gyan.FFmpeg DenoLand.Deno";
	private static final int RESULTS = 8;
	private static final String PROGRESS = "MAD ";
	private static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");

	/** A search result. {@code seconds} is -1 when YouTube gives no length, as for live streams. */
	record Result(String id, int seconds, String title) {
		boolean playable() {
			return seconds > 0 && seconds <= MAX_SECONDS;
		}
	}

	/** yt-dlp or FFmpeg is missing. */
	static final class MissingTools extends IOException {
		MissingTools() {
			super("yt-dlp or FFmpeg is not installed");
		}
	}

	private YouTube() {
	}

	/** A link that isn't YouTube's. */
	static final class NotYouTube extends IllegalArgumentException {
		NotYouTube() {
			super("only YouTube links");
		}
	}

	/**
	 * What yt-dlp should open for the text in the search box: a YouTube link as it is, words as a search. A playlist
	 * link lists its songs. Throws {@link NotYouTube} for a link to any other site.
	 */
	static String target(String text) {
		String query = text.strip();
		if (query.matches("(?i)(https?://)?([a-z0-9-]+\\.)*(youtube\\.com|youtu\\.be)(/\\S*)?")) {
			return query.matches("(?i)https?://.*") ? query : "https://" + query;
		}
		if (query.matches("(?i)https?://.*")) {
			throw new NotYouTube();
		}
		return "ytsearch" + RESULTS + ":" + query;
	}

	/** Searches YouTube, or lists the video or playlist a link points to, with the yt-dlp found in {@code dirs}. */
	static List<Result> search(String query, List<Path> dirs) throws IOException, InterruptedException {
		String target = target(query);
		Path ytDlp = require("yt-dlp", dirs);
		List<Result> results = new ArrayList<>();
		List<String> command = new ArrayList<>(List.of(ytDlp.toString(), "--encoding", "utf-8", "--no-warnings", "--flat-playlist",
				"--print", "%(id)s|%(duration)s|%(title)s"));
		// A watch link inside a playlist means the one video; a playlist link means its first 50 songs.
		command.addAll(target.contains("/playlist") ? List.of("--playlist-end", "50") : List.of("--no-playlist"));
		command.add(target);
		run(command, dirs, line -> {
			Result result = parse(line);
			if (result != null) {
				results.add(result);
			}
		});
		return results;
	}

	/**
	 * Saves the result as {@code <title>.ogg} in {@code songs} and returns the file. {@code progress} goes from 0 to 1:
	 * the download fills most of it and the conversion the rest.
	 */
	static Path download(Result result, Path songs, List<Path> dirs, DoubleConsumer progress) throws IOException, InterruptedException {
		Path ytDlp = require("yt-dlp", dirs);
		Path ffmpeg = require("ffmpeg", dirs);
		String name = fileName(result.title());
		Path target = songs.resolve(name + ".ogg");
		if (Files.exists(target)) {
			return target;
		}
		run(List.of(ytDlp.toString(), "--encoding", "utf-8", "--no-warnings", "--no-playlist", "--newline",
				"--ffmpeg-location", ffmpeg.toString(), "-f", "bestaudio", "-x", "--audio-format", "vorbis", "--audio-quality", "5",
				"--progress-template", "download:" + PROGRESS + "%(progress._percent_str)s",
				"-o", songs.resolve(name).toString() + ".%(ext)s", "https://www.youtube.com/watch?v=" + result.id()), dirs, line -> {
			double percent = percent(line);
			if (!Double.isNaN(percent)) {
				progress.accept(0.9 * percent / 100);
			} else if (line.startsWith("[ExtractAudio]")) {
				progress.accept(0.95);
			}
		});
		if (!Files.exists(target)) {
			throw new IOException("yt-dlp finished without writing " + target);
		}
		return target;
	}

	/** One line of the search output, {@code id|duration|title}, or null when it is something else. */
	static Result parse(String line) {
		String[] parts = line.split("\\|", 3);
		if (parts.length < 3 || !parts[0].matches("[A-Za-z0-9_-]{11}") || parts[2].isBlank()) {
			return null;
		}
		int seconds;
		try {
			seconds = (int) Math.round(Double.parseDouble(parts[1]));
		} catch (NumberFormatException e) {
			seconds = -1;
		}
		return new Result(parts[0], seconds, parts[2].strip());
	}

	/** The download percent on a progress line, NaN on any other line. */
	static double percent(String line) {
		if (!line.startsWith(PROGRESS)) {
			return Double.NaN;
		}
		try {
			return Double.parseDouble(line.substring(PROGRESS.length()).replace("%", "").strip());
		} catch (NumberFormatException e) {
			return Double.NaN;
		}
	}

	/**
	 * A file name for a song title, safe on Windows: no characters it forbids, no {@code %} (yt-dlp would read it as
	 * a template field), no trailing dots or spaces and no reserved device names.
	 */
	static String fileName(String title) {
		String name = title.replaceAll("[\\\\/:*?\"<>|%\\p{Cntrl}]", " ").replaceAll("\\s+", " ").strip();
		name = name.replaceAll("^[. ]+|[. ]+$", "");
		if (name.length() > 80) {
			name = name.substring(0, 80).strip();
		}
		if (name.isEmpty()) {
			return "song";
		}
		return name.matches("(?i)(con|prn|aux|nul|com\\d|lpt\\d)(\\..*)?") ? name + " song" : name;
	}

	private static Path require(String name, List<Path> dirs) throws MissingTools {
		Path file = find(name, dirs);
		if (file == null) {
			throw new MissingTools();
		}
		return file;
	}

	/** The first of {@code dirs} that holds the program, or null. */
	static Path find(String name, List<Path> dirs) {
		String exe = WINDOWS ? name + ".exe" : name;
		for (Path dir : dirs) {
			Path file = dir.resolve(exe);
			if (Files.exists(file)) {
				return file;
			}
		}
		return null;
	}

	private static void run(List<String> command, List<Path> dirs, Consumer<String> onLine) throws IOException, InterruptedException {
		ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
		// The folders find() searched, so yt-dlp finds FFmpeg and Deno even when the game's PATH is stale.
		StringBuilder path = new StringBuilder();
		for (Path dir : dirs) {
			path.append(dir).append(File.pathSeparator);
		}
		builder.environment().put("PATH", path.toString());
		Process process = builder.start();
		Deque<String> last = new ArrayDeque<>();
		try (BufferedReader reader = process.inputReader(StandardCharsets.UTF_8)) {
			for (String line; (line = reader.readLine()) != null; ) {
				onLine.accept(line);
				last.addLast(line);
				if (last.size() > 5) {
					last.removeFirst();
				}
			}
		}
		if (process.waitFor() != 0) {
			throw new IOException("yt-dlp failed: " + String.join(" / ", last));
		}
	}

	/** Self-check: {@code java src/main/java/it/vitoesposito/mineatdisco/YouTube.java}. Parsing and file names only, no network. */
	public static void main(String[] args) {
		Result song = parse("dQw4w9WgXcQ|213.0|Rick Astley - Never Gonna Give You Up (Official Video)");
		check(song != null && song.seconds() == 213 && song.playable(), "plain result: " + song);
		Result pipes = parse("abcdefghijk|95|A | B | C");
		check(pipes != null && pipes.title().equals("A | B | C"), "title with pipes: " + pipes);
		Result stream = parse("abcdefghijk|NA|Lofi radio 24/7");
		check(stream != null && stream.seconds() == -1 && !stream.playable(), "live stream: " + stream);
		check(!parse("abcdefghijk|4000|Two hour mix").playable(), "long mix is playable");
		check(parse("WARNING: something") == null && parse("short|1|x") == null, "noise parsed as a result");
		check(percent("MAD  45.3%") == 45.3 && Double.isNaN(percent("MAD Unknown%")) && Double.isNaN(percent("[download] 5%")), "percent");
		check(fileName("AC/DC: Back In Black?").equals("AC DC Back In Black"), fileName("AC/DC: Back In Black?"));
		check(fileName("100% Pure \"Love\"...").equals("100 Pure Love"), fileName("100% Pure \"Love\"..."));
		check(fileName("con").equals("con song") && fileName("...").equals("song"), "reserved and empty names");
		check(fileName("x".repeat(200)).length() == 80, "long name");
		check(target("daft punk").equals("ytsearch8:daft punk"), "words search");
		check(target(" https://www.youtube.com/watch?v=dQw4w9WgXcQ ").equals("https://www.youtube.com/watch?v=dQw4w9WgXcQ"), "watch link");
		check(target("youtu.be/dQw4w9WgXcQ").equals("https://youtu.be/dQw4w9WgXcQ"), "short link without scheme");
		check(target("https://music.youtube.com/playlist?list=PL123").contains("/playlist"), "playlist link");
		boolean refused = false;
		try {
			target("https://soundcloud.com/someone/song");
		} catch (NotYouTube e) {
			refused = true;
		}
		check(refused, "a link to another site is refused");
		System.out.println("ok: results, progress and file names");
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
