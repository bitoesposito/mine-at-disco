package it.vitoesposito.mineatdisco;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Finds the beats of a song: onset envelope, tempo by autocorrelation, then dynamic programming (Ellis, 2007).
 * The first beat returned opens a phrase, so beat i sits at position i % 4 of its bar and i % PHRASE of its phrase.
 */
public final class BeatTracker {
	public static final int PHRASE = 8;
	/** Readings per second in a punch curve, see punch. */
	public static final int PUNCH_RATE = 100;
	/** How fast a punch falls back, per second. lazer's logo visualiser uses 2.4; bounces want to settle within a beat. */
	static final float PUNCH_DECAY = 6;
	private static final int FPS = 200;
	static final double BASS_HZ = 150;
	/** How strongly the tracker holds the tempo against a louder onset nearby. */
	private static final double TIGHTNESS = 400;

	private BeatTracker() {
	}

	/** Returns the beat times in seconds for interleaved 16-bit PCM. */
	public static double[] track(ShortBuffer pcm, int channels, int sampleRate) {
		int hop = sampleRate / FPS * channels;
		// The hop is rounded to whole samples, so the real frame rate is not exactly FPS.
		double fps = (double) sampleRate * channels / hop;
		int frames = pcm.remaining() / hop;
		if (frames < FPS) {
			return new double[0];
		}

		double[] loudness = new double[frames];
		double[] bass = new double[frames];
		double peak = 0;
		// Two one-pole low-pass filters in a row keep the kick and the bass line.
		double smoothing = 1 - Math.exp(-2 * Math.PI * BASS_HZ / sampleRate);
		double low1 = 0;
		double low2 = 0;
		for (int f = 0; f < frames; f++) {
			long sum = 0;
			for (int i = f * hop, end = i + hop; i < end; i += channels) {
				int sample = 0;
				for (int c = 0; c < channels; c++) {
					sample += pcm.get(pcm.position() + i + c);
				}
				sum += Math.abs(sample);
				low1 += smoothing * (sample - low1);
				low2 += smoothing * (low1 - low2);
				bass[f] += Math.abs(low2);
			}
			loudness[f] = Math.log1p((double) sum / hop);
			peak = Math.max(peak, loudness[f]);
		}

		// An onset is a rise in overall loudness plus a rise in the bass, which is where the kick lands. Each part is
		// scaled to unit variance, so neither drowns the other and TIGHTNESS means the same for every song.
		double[] full = new double[frames];
		double[] kick = new double[frames];
		for (int f = 2; f < frames; f++) {
			full[f] = Math.max(0, loudness[f] - loudness[f - 2]);
			kick[f] = Math.max(0, bass[f] - bass[f - 2]);
		}
		if (!standardize(full) || !standardize(kick)) {
			return new double[0];
		}
		double[] onset = new double[frames];
		for (int f = 0; f < frames; f++) {
			onset[f] = full[f] + kick[f];
		}

		// ponytail: tempo is folded into 90-180 BPM, so a slow song reads as double time. Add a x2 and /2 control if that bothers.
		double period = 0;
		double best = Double.NEGATIVE_INFINITY;
		for (double bpm = 90; bpm < 180; bpm += 0.5) {
			double lag = 60.0 * fps / bpm;
			double score = correlation(onset, lag) + correlation(onset, lag * 2) + correlation(onset, lag * 4);
			if (score > best) {
				best = score;
				period = lag;
			}
		}

		// score[t] is the best total for a beat sequence ending at frame t, back[t] the beat before it.
		int nearest = (int) (period / 2);
		int farthest = (int) (period * 2);
		double[] penalty = new double[farthest + 1];
		for (int gap = nearest; gap <= farthest; gap++) {
			double stretch = Math.log(gap / period);
			penalty[gap] = -TIGHTNESS * stretch * stretch;
		}
		double[] score = new double[frames];
		int[] back = new int[frames];
		for (int t = 0; t < frames; t++) {
			double bestPrevious = 0;
			back[t] = -1;
			for (int gap = nearest; gap <= farthest && gap <= t; gap++) {
				double candidate = score[t - gap] + penalty[gap];
				if (candidate > bestPrevious) {
					bestPrevious = candidate;
					back[t] = t - gap;
				}
			}
			score[t] = onset[t] + bestPrevious;
		}

		// Start from the last audible stretch, so trailing silence adds no beats.
		int end = frames - 1;
		while (end > 0 && loudness[end] < peak / 2) {
			end--;
		}
		int last = end;
		for (int t = Math.max(0, end - (int) period); t <= end; t++) {
			if (score[t] > score[last]) {
				last = t;
			}
		}
		int count = 0;
		for (int t = last; t >= 0; t = back[t]) {
			count++;
		}
		int[] beatFrames = new int[count];
		for (int t = last; t >= 0; t = back[t]) {
			beatFrames[--count] = t;
		}

		// A phrase opens on its heaviest beat: bands land their accents and re-entries there.
		// ponytail: assumes 4/4 and phrases that never shift. Track the phase per section if a song breaks that.
		double[] weight = new double[PHRASE];
		for (int i = 0; i < beatFrames.length; i++) {
			for (int f = beatFrames[i], stop = Math.min(frames, f + (int) (period / 2)); f < stop; f++) {
				weight[i % PHRASE] += bass[f];
			}
		}
		int first = 0;
		for (int k = 1; k < PHRASE; k++) {
			if (weight[k] > weight[first]) {
				first = k;
			}
		}
		if (beatFrames.length < 2 * PHRASE) {
			first = 0;
		}
		double[] beats = new double[beatFrames.length - first];
		for (int i = 0; i < beats.length; i++) {
			beats[i] = beatFrames[i + first] / fps;
		}
		return beats;
	}

	/** Returns how loud each beat is next to the rest of the song: 0 up to the quietest tenth, 1 from the loudest twentieth. */
	public static float[] energy(ShortBuffer pcm, int channels, int sampleRate, double[] beats) {
		float[] energy = new float[beats.length];
		if (beats.length < 2) {
			Arrays.fill(energy, 1);
			return energy;
		}
		for (int i = 0; i < beats.length - 1; i++) {
			int from = (int) (beats[i] * sampleRate) * channels;
			int to = Math.min(pcm.remaining(), (int) (beats[i + 1] * sampleRate) * channels);
			long sum = 0;
			for (int s = from; s < to; s++) {
				sum += Math.abs(pcm.get(pcm.position() + s));
			}
			energy[i] = (float) sum / Math.max(1, to - from);
		}
		energy[beats.length - 1] = energy[beats.length - 2];
		float[] sorted = energy.clone();
		Arrays.sort(sorted);
		float quiet = sorted[sorted.length / 10];
		float loud = sorted[sorted.length * 19 / 20];
		for (int i = 0; i < energy.length; i++) {
			energy[i] = loud > quiet ? Math.clamp((energy[i] - quiet) / (loud - quiet), 0, 1) : 1;
		}
		return energy;
	}

	/**
	 * How hard the low end hits, {@link #PUNCH_RATE} times a second, from 0 to 1, with a 20 ms rise and the fall of
	 * lazer's logo visualiser (v -= dt * decay * (v + 0.03)). It measures how fast the bass level climbs over 30 ms,
	 * not the level itself: a sustained bass line, which in merengue sits on the off-beats, barely registers, while a
	 * kick does. Linear levels keep a quiet break quiet. The whole song is known in advance, so the HUD can read the
	 * curve at exactly the moment the player hears, with no analysis delay.
	 */
	public static float[] punch(ShortBuffer pcm, int channels, int sampleRate) {
		int hop = sampleRate / PUNCH_RATE;
		int frames = pcm.remaining() / channels / hop;
		float[] level = new float[frames];
		double smoothing = 1 - Math.exp(-2 * Math.PI * BASS_HZ / sampleRate);
		double low1 = 0;
		double low2 = 0;
		for (int f = 0; f < frames; f++) {
			double sum = 0;
			for (int i = f * hop * channels, end = i + hop * channels; i < end; i += channels) {
				int sample = 0;
				for (int c = 0; c < channels; c++) {
					sample += pcm.get(pcm.position() + i + c);
				}
				low1 += smoothing * (sample - low1);
				low2 += smoothing * (low1 - low2);
				sum += Math.abs(low2);
			}
			level[f] = (float) (sum / hop);
		}
		float[] attack = new float[frames];
		for (int f = 3; f < frames; f++) {
			attack[f] = Math.max(0, level[f] - level[f - 3]);
		}
		float[] sorted = attack.clone();
		Arrays.sort(sorted);
		float loud = frames == 0 ? 0 : sorted[frames * 49 / 50];
		float[] punch = new float[frames];
		float value = 0;
		float dt = 1f / PUNCH_RATE;
		float rise = 1 - (float) Math.exp(-dt / 0.02);
		for (int f = 0; f < frames; f++) {
			float target = loud > 0 ? Math.min(1, attack[f] / loud) : 0;
			value = target > value ? value + (target - value) * rise : Math.max(target, value - dt * PUNCH_DECAY * (value + 0.03f));
			punch[f] = value;
		}
		return punch;
	}

	/** Reads a punch curve at {@code seconds}, between two readings by straight line, 0 outside it. */
	public static float punchAt(float[] punch, double seconds) {
		double at = seconds * PUNCH_RATE;
		int i = (int) Math.floor(at);
		if (i < 0 || i + 1 >= punch.length) {
			return 0;
		}
		return (float) (punch[i] + (at - i) * (punch[i + 1] - punch[i]));
	}

	/**
	 * Marks the bars where the band comes back after a quiet stretch: the first beat of a bar whose next four beats are
	 * loud after eight quiet ones. Two drops sit at least four bars apart.
	 */
	public static boolean[] drops(float[] energy) {
		boolean[] drops = new boolean[energy.length];
		int last = -16;
		for (int i = 8; i + 4 <= energy.length; i += 4) {
			float before = 0;
			float after = 0;
			for (int k = 0; k < 8; k++) {
				before += energy[i - 8 + k] / 8;
			}
			for (int k = 0; k < 4; k++) {
				after += energy[i + k] / 4;
			}
			if (before <= 0.4f && after >= 0.55f && i - last >= 16) {
				drops[i] = true;
				last = i;
			}
		}
		return drops;
	}

	private static boolean standardize(double[] values) {
		double mean = 0;
		for (double v : values) {
			mean += v / values.length;
		}
		double variance = 0;
		for (double v : values) {
			variance += (v - mean) * (v - mean) / values.length;
		}
		if (variance == 0) {
			return false;
		}
		for (int i = 0; i < values.length; i++) {
			values[i] = (values[i] - mean) / Math.sqrt(variance);
		}
		return true;
	}

	/** Returns the beat index plus the phase within that beat, or -1 outside the tracked beats. */
	public static double beatAt(double[] beats, double seconds) {
		int i = Arrays.binarySearch(beats, seconds);
		if (i < 0) {
			i = -i - 2;
		}
		if (i < 0 || i >= beats.length - 1) {
			return -1;
		}
		return i + (seconds - beats[i]) / (beats[i + 1] - beats[i]);
	}

	/** Inverse of beatAt, for a beat position inside the tracked beats. */
	public static double timeAt(double[] beats, double beat) {
		if (beats.length < 2) {
			return Double.NaN;
		}
		int i = Math.clamp((int) beat, 0, beats.length - 2);
		return beats[i] + (beat - i) * (beats[i + 1] - beats[i]);
	}

	/**
	 * Picks the beat an action that takes {@code duration} beats should land on, close to {@code ideal}. Repeated
	 * actions drift toward strong beats: one that takes about a bar lands on beat 1, one that takes about two beats on
	 * beats 1 and 3. Each landing moves at most 30% of the duration, so a chain settles onto the strong beat over a
	 * few repeats. It leans early and takes a later beat only within a quarter beat of {@code ideal}, so repeated
	 * actions never run much slower than vanilla. The caller keeps it from landing before what the server accepts.
	 */
	public static double landing(double ideal, double duration) {
		int grid = duration >= 3.2 ? 4 : duration >= 1.6 ? 2 : 1;
		double reach = Math.max(0.75, duration * 0.3);
		double best = Math.round(ideal);
		double bestScore = Double.MAX_VALUE;
		for (double beat = Math.ceil(ideal - reach); beat <= ideal + reach; beat++) {
			double fromStrong = Math.abs(beat - grid * Math.round(beat / grid));
			double score = fromStrong * 100 + Math.abs(beat - ideal) + (beat > ideal ? 0.5 : 0);
			if (score < bestScore) {
				bestScore = score;
				best = beat;
			}
		}
		return best;
	}

	public static double bpm(double[] beats) {
		return beats.length < 2 ? 0 : 60 * (beats.length - 1) / (beats[beats.length - 1] - beats[0]);
	}

	private static double correlation(double[] onset, double lag) {
		int whole = (int) lag;
		double fraction = lag - whole;
		double sum = 0;
		int n = onset.length - whole - 1;
		for (int i = 0; i < n; i++) {
			sum += onset[i] * ((1 - fraction) * onset[i + whole] + fraction * onset[i + whole + 1]);
		}
		return sum / n;
	}

	/**
	 * Self-check: {@code java BeatTracker.java}.
	 * To inspect a real song, pass mono 16-bit little-endian PCM and its sample rate:
	 * {@code ffmpeg -i song.ogg -ac 1 -f s16le song.raw && java BeatTracker.java song.raw 44100}.
	 */
	public static void main(String[] args) throws Exception {
		if (args.length == 2) {
			ShortBuffer pcm = ByteBuffer.wrap(Files.readAllBytes(Path.of(args[0]))).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
			double[] beats = track(pcm, 1, Integer.parseInt(args[1]));
			System.out.printf("%d beats, %.2f BPM, first at %.3f s, last at %.3f s%n",
					beats.length, bpm(beats), beats[0], beats[beats.length - 1]);
			return;
		}

		// A 128 BPM click track with two seconds of silence at the end. A kick opens each phrase, from the fourth click.
		int rate = 44100;
		double interval = 60.0 / 128;
		double firstClick = 0.2;
		double firstKick = firstClick + 3 * interval;
		short[] pcm = new short[rate * 30];
		for (int click = 0; firstClick + click * interval < 28; click++) {
			int start = (int) ((firstClick + click * interval) * rate);
			for (int i = 0; i < 400; i++) {
				pcm[start + i] = (short) (15000 * Math.sin(i * 0.3));
			}
			for (int i = 0; click % PHRASE == 3 && i < 8000; i++) {
				pcm[start + i] += (short) (15000 * Math.sin(i * 2 * Math.PI * 60 / rate));
			}
		}
		double[] beats = track(ShortBuffer.wrap(pcm), 1, rate);
		double sinceKick = (beats[0] - firstKick) % (PHRASE * interval);
		check(Math.abs(sinceKick) < 0.015, "first beat at " + beats[0] + " does not open a phrase");
		check(Math.abs(bpm(beats) - 128) < 0.5, "tempo was " + bpm(beats));
		check(beats[beats.length - 1] < 28, "beats ran into the silence");
		for (double beat : beats) {
			double sinceClick = (beat - firstClick) % interval;
			check(Math.min(sinceClick, interval - sinceClick) < 0.015, "beat at " + beat + " is off the click");
		}
		check(beatAt(beats, beats[0] - 1) == -1, "before the first beat");
		check(Math.abs(beatAt(beats, (beats[3] + beats[4]) / 2) - 3.5) < 1e-9, "halfway through beat 3");
		check(Math.abs(timeAt(beats, 3.5) - (beats[3] + beats[4]) / 2) < 1e-9, "time of beat 3.5");
		check(timeAt(beats, beats.length - 1) == beats[beats.length - 1], "time of the last beat");

		// Quiet for ten seconds, then loud, with a beat every half second.
		short[] wave = new short[rate * 20];
		for (int i = 0; i < wave.length; i++) {
			wave[i] = (short) ((i < wave.length / 2 ? 3000 : 20000) * Math.sin(i * 0.05));
		}
		double[] grid = new double[40];
		for (int i = 0; i < grid.length; i++) {
			grid[i] = i * 0.5;
		}
		float[] loudness = energy(ShortBuffer.wrap(wave), 1, rate, grid);
		check(loudness[2] < 0.01 && loudness[19] < 0.01, "quiet half should be near 0");
		check(loudness[20] > 0.99 && loudness[39] > 0.99, "loud half should be near 1");

		// A 60 Hz kick of 50 ms every half second: the punch peaks on each kick and falls before the next.
		short[] kicks = new short[rate * 4];
		for (int i = 0; i < kicks.length; i++) {
			double t = (double) i / rate;
			kicks[i] = (short) (t % 0.5 < 0.05 ? 20000 * Math.sin(2 * Math.PI * 60 * t) : 0);
		}
		float[] punch = punch(ShortBuffer.wrap(kicks), 1, rate);
		float peak = 0;
		for (double t = 2; t < 2.1; t += 0.005) {
			peak = Math.max(peak, punchAt(punch, t));
		}
		check(peak > 0.6, "punch peak on a kick was " + peak);
		check(punchAt(punch, 2.4) < 0.2, "punch before the next kick was " + punchAt(punch, 2.4));

		// Loud, a break, the band back at beat 40, a short dip and back again at 52, too soon for a second drop.
		float[] song = new float[64];
		for (int i = 0; i < song.length; i++) {
			song[i] = i < 24 || i >= 40 && i < 48 || i >= 52 ? 0.9f : 0.1f;
		}
		boolean[] drops = drops(song);
		for (int i = 0; i < song.length; i++) {
			check(drops[i] == (i == 40), "drop at beat " + i + " should be " + (i == 40));
		}

		check(landing(5.6, 1) == 5 && landing(5.8, 1) == 6, "a short action waits at most a quarter beat");
		check(landing(1.57, 1.57) == 1, "diamond pickaxe on stone keeps one block per beat");
		check(landing(14.6, 1.6) == 14, "a two-beat action takes beat 1 or 3");
		check(landing(6, 4) == 5 && landing(9, 4) == 8, "a bar-long action steps toward beat 1, earlier on a tie");
		for (int start = 0; start < 4; start++) {
			double last = start;
			for (int repeat = 0; repeat < 2; repeat++) {
				last = landing(last + 4, 4);
			}
			check(last % 4 == 0, "a chain of bar-long actions from beat " + start + " ends on " + last);
		}
		System.out.println("ok: " + beats.length + " beats at " + bpm(beats) + " BPM");
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
