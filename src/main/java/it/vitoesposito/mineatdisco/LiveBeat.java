package it.vitoesposito.mineatdisco;

import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Follows the beat of audio heard live, such as the system audio from {@link LoopbackCapture}. Every update runs
 * {@link BeatTracker} on the last seconds heard, keeps the beats older than {@link #FREEZE} as they are so what was
 * already shown never jumps, takes the newer ones from the fresh analysis and predicts {@link #AHEAD} more from the
 * current tempo, so visuals can land on a beat that hasn't been heard yet.
 *
 * <p>Beat {@code i} of the timeline sits at position {@code i % 8} of its phrase, like the beats of a local song. The
 * tracker picks each window's phrase start from the bass, so when several updates in a row put it elsewhere the
 * oldest beats are dropped until the indices line up again. Old beats are invisible, so that costs nothing.
 */
final class LiveBeat {
	/** Seconds of audio each update looks at. */
	static final double WINDOW = 12;
	/** Beats older than this many seconds stay as they are. */
	static final double FREEZE = 1;
	/** Beats predicted past the newest one heard. */
	static final int AHEAD = 8;
	/** Updates in a row that must agree before the phrase start moves. */
	private static final int PHASE_VOTES = 3;
	/** Beats of loudness history the energy is measured against. */
	private static final int HISTORY = 300;

	/** The timeline at one moment: beat times in seconds, how loud each beat is from 0 to 1, and the drops. */
	record Snapshot(double[] beats, float[] energy, boolean[] drops) {
		static final Snapshot EMPTY = new Snapshot(new double[0], new float[0], new boolean[0]);
	}

	private final List<Double> beats = new ArrayList<>();
	private final List<Float> loudness = new ArrayList<>();
	private int proposedShift;
	private int votes;

	/**
	 * Analyses {@code window}, mono 16-bit PCM whose first sample plays at {@code windowStart} seconds, at time
	 * {@code now} on the same clock, and returns the updated timeline.
	 */
	Snapshot update(short[] window, int rate, double windowStart, double now) {
		while (!beats.isEmpty() && beats.getLast() > now - FREEZE) {
			beats.removeLast();
			loudness.removeLast();
		}
		double[] found = BeatTracker.track(ShortBuffer.wrap(window), 1, rate);
		if (found.length < 2 * BeatTracker.PHRASE) {
			// No steady beat in the window, such as silence or speech: stop predicting.
			return snapshot();
		}
		double period = (found[found.length - 1] - found[found.length - 1 - BeatTracker.PHRASE]) / BeatTracker.PHRASE;
		double last = beats.isEmpty() ? Double.NEGATIVE_INFINITY : beats.getLast();
		boolean first = true;
		for (int k = 0; k < found.length; k++) {
			double time = found[k] + windowStart;
			if (time <= last + period / 2 || time > now) {
				continue;
			}
			if (first) {
				first = false;
				// Window beat k opens position k % 8 of its phrase; it is about to get index beats.size().
				int shift = Math.floorMod(beats.size() - k, BeatTracker.PHRASE);
				if (beats.isEmpty() && shift != 0) {
					// Starting out: begin at the next phrase start instead of shifting.
					k += Math.floorMod(-k, BeatTracker.PHRASE) - 1;
					first = true;
					continue;
				}
				vote(shift);
			}
			beats.add(time);
			loudness.add(rms(window, rate, (time - windowStart), Math.min(time + period, now) - windowStart));
		}
		double newest = beats.isEmpty() ? now : beats.getLast();
		for (int i = 1; i <= AHEAD && !beats.isEmpty(); i++) {
			beats.add(newest + i * period);
			loudness.add(loudness.getLast());
		}
		return snapshot();
	}

	/** Counts a vote for moving the phrase start, and moves it once enough updates in a row agree. */
	private void vote(int shift) {
		if (shift == 0) {
			votes = 0;
			return;
		}
		votes = shift == proposedShift ? votes + 1 : 1;
		proposedShift = shift;
		if (votes >= PHASE_VOTES) {
			for (int i = 0; i < shift && !beats.isEmpty(); i++) {
				beats.removeFirst();
				loudness.removeFirst();
			}
			votes = 0;
		}
	}

	private Snapshot snapshot() {
		int n = beats.size();
		double[] times = new double[n];
		float[] energy = new float[n];
		for (int i = 0; i < n; i++) {
			times[i] = beats.get(i);
		}
		// Like BeatTracker.energy, against the quietest tenth and the loudest twentieth, but of recent history only.
		float[] recent = new float[Math.min(n, HISTORY)];
		for (int i = 0; i < recent.length; i++) {
			recent[i] = loudness.get(n - recent.length + i);
		}
		Arrays.sort(recent);
		float quiet = recent.length == 0 ? 0 : recent[recent.length / 10];
		float loud = recent.length == 0 ? 0 : recent[recent.length * 19 / 20];
		for (int i = 0; i < n; i++) {
			energy[i] = loud > quiet ? Math.clamp((loudness.get(i) - quiet) / (loud - quiet), 0, 1) : 1;
		}
		return new Snapshot(times, energy, BeatTracker.drops(energy));
	}

	private static float rms(short[] window, int rate, double from, double to) {
		int start = Math.max(0, (int) (from * rate));
		int end = Math.min(window.length, (int) (to * rate));
		double sum = 0;
		for (int i = start; i < end; i++) {
			sum += window[i] * (double) window[i];
		}
		return end > start ? (float) Math.sqrt(sum / (end - start)) : 0;
	}

	/**
	 * Self-check, from {@code src/main/java}: {@code java it/vitoesposito/mineatdisco/LiveBeat.java}. A 128 BPM click track with a kick on
	 * every phrase start is heard window by window, half a second apart, as the game does. The timeline must keep one
	 * beat per click, with no duplicates or gaps where updates meet, predict past now, and keep phrase starts on the kicks.
	 */
	public static void main(String[] args) {
		int rate = 22050;
		double interval = 60.0 / 128;
		double firstKick = 0.3 + 3 * interval;
		short[] song = new short[rate * 60];
		for (int click = 0; 0.3 + click * interval < 59; click++) {
			int start = (int) ((0.3 + click * interval) * rate);
			for (int i = 0; i < 300; i++) {
				song[start + i] = (short) (15000 * Math.sin(i * 0.3));
			}
			for (int i = 0; click % BeatTracker.PHRASE == 3 && i < 4000; i++) {
				song[start + i] += (short) (15000 * Math.sin(i * 2 * Math.PI * 60 / rate));
			}
		}
		LiveBeat live = new LiveBeat();
		Snapshot snapshot = Snapshot.EMPTY;
		double now;
		for (now = WINDOW; now < 58; now += 0.5) {
			int end = (int) (now * rate);
			short[] window = Arrays.copyOfRange(song, end - (int) (WINDOW * rate), end);
			snapshot = live.update(window, rate, now - WINDOW, now);
		}
		double[] beats = snapshot.beats();
		check(beats.length > 50, "only " + beats.length + " beats");
		check(beats[beats.length - 1] > now, "no beat predicted past now");
		for (int i = 1; i < beats.length; i++) {
			double gap = beats[i] - beats[i - 1];
			check(Math.abs(gap - interval) < 0.02, "gap of " + gap + " s before beat " + i);
		}
		for (int i = 0; i < beats.length; i++) {
			double fromKick = Math.floorMod(Math.round((beats[i] - firstKick) / interval), BeatTracker.PHRASE);
			check(fromKick == i % BeatTracker.PHRASE, "beat " + i + " is " + fromKick + " beats past a kick");
		}
		System.out.println("ok: " + beats.length + " beats, phrase starts on the kicks");
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
