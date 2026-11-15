package it.vitoesposito.mineatdisco;

/**
 * {@link BeatTracker#punch} for audio heard live: the same 30 ms bass climb, 20 ms rise and visualiser fall, worked out
 * on each new stretch of captured audio once per frame, so the HUD bounces on a kick as soon as it is heard. A song
 * known in advance is normalized by its loudest kicks; here the loudest recent kick sets the scale and fades with a
 * 7 s half-life, so a break after a loud chorus stays soft.
 */
final class LivePunch {
	/** Readings kept, four seconds. */
	private final float[] values = new float[BeatTracker.PUNCH_RATE * 4];
	/** Bass level of the last four readings, for the 30 ms climb. */
	private final float[] levels = new float[4];
	private long readings;
	private long nextSample = -1;
	private int hop;
	private double low1;
	private double low2;
	private float loud;
	private float value;

	/** Works through the audio captured since the last call. */
	void advance(LoopbackCapture capture) {
		int rate = capture.rate();
		if (rate == 0) {
			return;
		}
		hop = rate / BeatTracker.PUNCH_RATE;
		long written = capture.written();
		if (nextSample < capture.oldest()) {
			// First call, or the frame stalled for longer than the capture keeps: start from now.
			nextSample = written - written % hop;
			readings = nextSample / hop;
		}
		double smoothing = 1 - Math.exp(-2 * Math.PI * BeatTracker.BASS_HZ / rate);
		float dt = 1f / BeatTracker.PUNCH_RATE;
		float rise = 1 - (float) Math.exp(-dt / 0.02);
		float[] samples = new float[hop];
		while (nextSample + hop <= written) {
			capture.copyFrom(nextSample, samples);
			double sum = 0;
			for (float sample : samples) {
				low1 += smoothing * (sample - low1);
				low2 += smoothing * (low1 - low2);
				sum += Math.abs(low2);
			}
			float level = (float) (sum / hop);
			int slot = (int) (readings % levels.length);
			float attack = Math.max(0, level - levels[slot]);
			levels[slot] = level;
			loud = Math.max(attack, loud * 0.999f);
			float target = loud > 0 ? Math.min(1, attack / loud) : 0;
			value = target > value ? value + (target - value) * rise : Math.max(target, value - dt * BeatTracker.PUNCH_DECAY * (value + 0.03f));
			values[(int) (readings % values.length)] = value;
			readings++;
			nextSample += hop;
		}
	}

	/** Punch at {@code time} on the nanoTime timeline: the newest reading for later times, 0 for times no longer kept. */
	float at(LoopbackCapture capture, double time) {
		if (readings == 0 || hop == 0) {
			return 0;
		}
		double at = capture.indexAt(time) / hop;
		if (at >= readings - 1) {
			return values[(int) ((readings - 1) % values.length)];
		}
		long i = (long) Math.floor(at);
		if (i < Math.max(0, readings - values.length)) {
			return 0;
		}
		float a = values[(int) (i % values.length)];
		float b = values[(int) ((i + 1) % values.length)];
		return (float) (a + (at - i) * (b - a));
	}
}
