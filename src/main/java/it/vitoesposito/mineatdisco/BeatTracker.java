package it.vitoesposito.mineatdisco;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Finds the beats of a song: onset envelope, tempo by autocorrelation, then dynamic programming (Ellis, 2007). */
public final class BeatTracker {
	private static final int FPS = 200;
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
		double peak = 0;
		for (int f = 0; f < frames; f++) {
			long sum = 0;
			for (int i = f * hop, end = i + hop; i < end; i++) {
				sum += Math.abs(pcm.get(pcm.position() + i));
			}
			loudness[f] = Math.log1p((double) sum / hop);
			peak = Math.max(peak, loudness[f]);
		}

		// An onset is a rise in loudness. Scale to unit variance so TIGHTNESS means the same for every song.
		double[] onset = new double[frames];
		double mean = 0;
		for (int f = 2; f < frames; f++) {
			onset[f] = Math.max(0, loudness[f] - loudness[f - 2]);
			mean += onset[f] / frames;
		}
		double variance = 0;
		for (double o : onset) {
			variance += (o - mean) * (o - mean) / frames;
		}
		if (variance == 0) {
			return new double[0];
		}
		for (int f = 0; f < frames; f++) {
			onset[f] = (onset[f] - mean) / Math.sqrt(variance);
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
		double[] beats = new double[count];
		for (int t = last; t >= 0; t = back[t]) {
			beats[--count] = t / fps;
		}
		return beats;
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
		int i = Math.min((int) beat, beats.length - 2);
		return beats[i] + (beat - i) * (beats[i + 1] - beats[i]);
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

		// A 128 BPM click track with two seconds of silence at the end.
		int rate = 44100;
		double interval = 60.0 / 128;
		double firstClick = 0.2;
		short[] pcm = new short[rate * 30];
		for (double t = firstClick; t < 28; t += interval) {
			for (int i = 0; i < 400; i++) {
				pcm[(int) (t * rate) + i] = (short) (20000 * Math.sin(i * 0.3));
			}
		}
		double[] beats = track(ShortBuffer.wrap(pcm), 1, rate);
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
		System.out.println("ok: " + beats.length + " beats at " + bpm(beats) + " BPM");
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
