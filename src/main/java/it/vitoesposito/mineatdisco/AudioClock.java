package it.vitoesposito.mineatdisco;

import java.util.Random;

/**
 * Smooths the song position like osu-framework's InterpolatingFramedClock. OpenAL moves the playback offset only once
 * per mixer period, 5 to 20 ms, so between readings this clock runs on real time. Each fresh reading pulls it toward
 * the truth with an 80 ms half-life, the value lazer's FramedBeatmapClock uses, which also cancels drift between the
 * sound card and the system clock. An error past two 60 fps frames is a hitch, so the clock jumps to the reading
 * instead. It never runs backwards: after a stall it waits for the audio to catch up.
 */
final class AudioClock {
	static final double HALF_LIFE = 0.08;
	static final double ALLOWED_ERROR = 2.0 / 60;
	/** No fresh reading for this long means the audio stalled, so trust the reading as it is. */
	private static final double STALE = 0.1;

	private double time = Double.NaN;
	private double lastReading = Double.NaN;
	private double sinceReading;

	/** Moves on by {@code elapsed} seconds of real time, takes the latest OpenAL reading and returns the smoothed time. */
	double update(double reading, double elapsed) {
		if (Double.isNaN(time)) {
			time = reading;
			lastReading = reading;
			return time;
		}
		double last = time;
		time += elapsed;
		sinceReading += elapsed;
		if (reading != lastReading) {
			// The reading changed at some point during the last frame, on average halfway through it.
			double error = reading + elapsed / 2 - time;
			time = Math.abs(error) > ALLOWED_ERROR ? reading : time + error * (1 - Math.pow(0.5, sinceReading / HALF_LIFE));
			lastReading = reading;
			sinceReading = 0;
		} else if (sinceReading > STALE) {
			time = reading;
		}
		time = Math.max(last, time);
		return time;
	}

	/** Forgets the past, for a new song or after a pause. */
	void reset() {
		time = Double.NaN;
		lastReading = Double.NaN;
		sinceReading = 0;
	}

	/**
	 * Self-check: {@code java AudioClock.java}. A sound card that reports every 10 ms and runs 0.5% slow, read at a
	 * jittery 60 to 144 fps, must be followed within 5 ms, never backwards, and through a 200 ms stall.
	 */
	public static void main(String[] args) {
		AudioClock clock = new AudioClock();
		Random random = new Random(1);
		double real = 0;
		double last = -1;
		double worst = 0;
		boolean stalled = false;
		while (real < 60) {
			double frame = 1 / (60 + 84 * random.nextDouble());
			real += frame;
			stalled = real > 30 && real < 30.2;
			double truth = real * 0.995;
			double reading = stalled ? 30 * 0.995 : Math.floor(truth / 0.01) * 0.01;
			double time = clock.update(reading, frame);
			check(time >= last, "went backwards at " + real);
			last = time;
			if (real > 1 && !stalled && real > 30.5 || real > 1 && real < 30) {
				worst = Math.max(worst, Math.abs(time - truth));
			}
		}
		check(worst < 0.005, "worst error " + worst * 1000 + " ms");
		System.out.printf("ok: worst error %.2f ms%n", worst * 1000);
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
