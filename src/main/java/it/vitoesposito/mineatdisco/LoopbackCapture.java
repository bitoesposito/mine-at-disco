package it.vitoesposito.mineatdisco;

import com.sun.jna.Function;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.COM.Unknown;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.WTypes;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;

/**
 * Captures what Windows is playing on the default output device, whatever plays it (Spotify, a browser, any player),
 * through WASAPI loopback. It runs on its own thread and keeps the last {@link #SECONDS} seconds as mono samples, each
 * placed on the {@link System#nanoTime} timeline by the timestamps WASAPI gives every packet.
 *
 * <p>The COM calls go through JNA, which Minecraft already ships. Each method is reached by its slot in the
 * interface's vtable, in the order the Windows SDK headers declare them.
 */
public final class LoopbackCapture implements AutoCloseable {
	public static final int SECONDS = 16;

	private static final Guid.CLSID MM_DEVICE_ENUMERATOR = new Guid.CLSID("BCDE0395-E52F-467C-8E3D-C4579291692E");
	private static final Guid.IID IMM_DEVICE_ENUMERATOR = new Guid.IID("A95664D2-9614-4F35-A746-DE8DB63617E6");
	private static final Guid.IID IAUDIO_CLIENT = new Guid.IID("1CB9AD4C-DBFA-4C32-B178-C2F568A703B2");
	private static final Guid.IID IAUDIO_CAPTURE_CLIENT = new Guid.IID("C8ADBD64-E71E-48A0-A4DE-185C395CD317");
	private static final int E_RENDER = 0;
	private static final int E_CONSOLE = 0;
	private static final int AUDCLNT_SHAREMODE_SHARED = 0;
	private static final int AUDCLNT_STREAMFLAGS_LOOPBACK = 0x00020000;
	private static final int AUDCLNT_BUFFERFLAGS_SILENT = 0x2;
	private static final short WAVE_FORMAT_PCM = 1;
	private static final short WAVE_FORMAT_IEEE_FLOAT = 3;
	private static final short WAVE_FORMAT_EXTENSIBLE = (short) 0xFFFE;
	/** A gap in the timestamps longer than this is silence WASAPI didn't send, so the buffer fills it with zeros. */
	private static final double GAP = 0.05;

	private final float[] ring;
	private final Thread thread;
	private volatile boolean running = true;
	private volatile String failure;
	private int rate;
	/** Samples written since the start. The sample at index i played at {@link #timeOf}(i). */
	private long written;
	/** Seconds on the nanoTime timeline minus sample index / rate, smoothed over packets. */
	private double timeOffset = Double.NaN;

	private LoopbackCapture() {
		ring = new float[SECONDS * 192_000];
		thread = new Thread(this::run, "Mine at Disco loopback");
		thread.setDaemon(true);
	}

	/** Starts capturing on a new thread. Check {@link #failure()} for an error that stopped it. */
	public static LoopbackCapture start() {
		LoopbackCapture capture = new LoopbackCapture();
		capture.thread.start();
		return capture;
	}

	/** Null while capturing works, otherwise what went wrong. */
	public String failure() {
		return failure;
	}

	/** Samples per second, 0 until the device answered. */
	public synchronized int rate() {
		return rate;
	}

	/** Seconds on the nanoTime timeline when the sample at {@code index} played, NaN before the first packet. */
	public synchronized double timeOf(long index) {
		return timeOffset + (double) index / rate;
	}

	/** Index one past the newest sample. */
	public synchronized long written() {
		return written;
	}

	/** Sample index that played at {@code time} seconds on the nanoTime timeline, inverse of {@link #timeOf}. */
	public synchronized double indexAt(double time) {
		return (time - timeOffset) * rate;
	}

	/** Index of the oldest sample still kept. */
	public synchronized long oldest() {
		return Math.max(0, written - (long) Math.min(ring.length, rate * SECONDS));
	}

	/** Copies {@code out.length} samples starting at {@code from}, which must lie between oldest() and written(). */
	public synchronized void copyFrom(long from, float[] out) {
		int capacity = Math.min(ring.length, rate * SECONDS);
		for (int i = 0; i < out.length; i++) {
			out[i] = ring[(int) ((from + i) % capacity)];
		}
	}

	/**
	 * Copies the newest {@code count} samples, oldest first, into {@code out} as 16-bit PCM and returns the index of
	 * the first one. Missing history reads as silence.
	 */
	public synchronized long copyLatest(short[] out, int count) {
		long first = written - count;
		int capacity = Math.min(ring.length, rate * SECONDS);
		for (int i = 0; i < count; i++) {
			long index = first + i;
			float sample = index < 0 || index < written - capacity ? 0 : ring[(int) (index % capacity)];
			out[i] = (short) Math.round(Math.max(-1, Math.min(1, sample)) * 32767);
		}
		return first;
	}

	@Override
	public void close() {
		running = false;
		try {
			thread.join(1000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private void run() {
		Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_MULTITHREADED);
		Unknown enumerator = null;
		Unknown device = null;
		Unknown client = null;
		Unknown capture = null;
		try {
			PointerByReference out = new PointerByReference();
			check(Ole32.INSTANCE.CoCreateInstance(MM_DEVICE_ENUMERATOR, null, WTypes.CLSCTX_ALL, IMM_DEVICE_ENUMERATOR, out).intValue(),
					"create device enumerator");
			enumerator = new Unknown(out.getValue());
			// IMMDeviceEnumerator::GetDefaultAudioEndpoint
			check(call(enumerator, 4, E_RENDER, E_CONSOLE, out), "find the default output device");
			device = new Unknown(out.getValue());
			// IMMDevice::Activate
			check(call(device, 3, IAUDIO_CLIENT, WTypes.CLSCTX_ALL, null, out), "open the audio client");
			client = new Unknown(out.getValue());
			// IAudioClient::GetMixFormat
			check(call(client, 8, out), "read the mix format");
			Pointer format = out.getValue();
			short tag = format.getShort(0);
			int channels = format.getShort(2);
			int sampleRate = format.getInt(4);
			int blockAlign = format.getShort(12);
			int bits = format.getShort(14);
			boolean isFloat = tag == WAVE_FORMAT_IEEE_FLOAT
					|| tag == WAVE_FORMAT_EXTENSIBLE && format.getShort(24) == WAVE_FORMAT_IEEE_FLOAT;
			if (!isFloat && !(tag == WAVE_FORMAT_PCM || tag == WAVE_FORMAT_EXTENSIBLE) || !isFloat && bits != 16) {
				throw new IllegalStateException("unsupported mix format tag " + tag + " with " + bits + " bits");
			}
			// IAudioClient::Initialize, shared loopback with a one second buffer (in 100 ns units)
			check(call(client, 3, AUDCLNT_SHAREMODE_SHARED, AUDCLNT_STREAMFLAGS_LOOPBACK, 10_000_000L, 0L, format, null),
					"start loopback");
			Ole32.INSTANCE.CoTaskMemFree(format);
			// IAudioClient::GetService
			check(call(client, 14, IAUDIO_CAPTURE_CLIENT, out), "open the capture client");
			capture = new Unknown(out.getValue());
			synchronized (this) {
				rate = sampleRate;
			}
			// IAudioClient::Start
			check(call(client, 10), "start the stream");

			IntByReference packet = new IntByReference();
			PointerByReference data = new PointerByReference();
			IntByReference frames = new IntByReference();
			IntByReference flags = new IntByReference();
			LongByReference devicePosition = new LongByReference();
			LongByReference qpcPosition = new LongByReference();
			while (running) {
				Thread.sleep(5);
				// IAudioCaptureClient::GetNextPacketSize, GetBuffer, ReleaseBuffer
				while (call(capture, 5, packet) == 0 && packet.getValue() > 0) {
					check(call(capture, 3, data, frames, flags, devicePosition, qpcPosition), "read a packet");
					int count = frames.getValue();
					boolean silent = (flags.getValue() & AUDCLNT_BUFFERFLAGS_SILENT) != 0;
					store(data.getValue(), count, channels, blockAlign, isFloat, silent, qpcPosition.getValue() * 1e-7);
					check(call(capture, 4, count), "release a packet");
				}
			}
			// IAudioClient::Stop
			call(client, 11);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (RuntimeException | Error e) {
			failure = e.getMessage() == null ? e.toString() : e.getMessage();
		} finally {
			for (Unknown object : new Unknown[] {capture, client, device, enumerator}) {
				if (object != null) {
					object.Release();
				}
			}
			Ole32.INSTANCE.CoUninitialize();
		}
	}

	/** Adds one packet as mono samples, filling any gap before it with silence and keeping the timeline in step. */
	private synchronized void store(Pointer data, int count, int channels, int blockAlign, boolean isFloat, boolean silent, double time) {
		int capacity = Math.min(ring.length, rate * SECONDS);
		double expected = timeOffset + (double) written / rate;
		if (Double.isNaN(timeOffset)) {
			timeOffset = time;
		} else if (time - expected > GAP) {
			// WASAPI sends nothing while the device is silent: pad with zeros up to this packet.
			long missing = Math.min((long) ((time - expected) * rate), capacity);
			for (long i = 0; i < missing; i++) {
				ring[(int) (written++ % capacity)] = 0;
			}
		}
		// The sound card and the system clock drift apart slowly, so follow the packet times a little at a time.
		double measured = time - (double) written / rate;
		timeOffset += Math.abs(measured - timeOffset) > GAP ? measured - timeOffset : (measured - timeOffset) * 0.01;
		for (int frame = 0; frame < count; frame++) {
			float sum = 0;
			for (int c = 0; !silent && c < channels; c++) {
				long at = (long) frame * blockAlign + (long) c * (blockAlign / channels);
				sum += isFloat ? data.getFloat(at) : data.getShort(at) / 32768f;
			}
			ring[(int) (written++ % capacity)] = sum / channels;
		}
	}

	/** Calls the COM method in vtable {@code slot} of {@code object} and returns its HRESULT. */
	private static int call(Unknown object, int slot, Object... args) {
		Object[] withThis = new Object[args.length + 1];
		withThis[0] = object.getPointer();
		for (int i = 0; i < args.length; i++) {
			withThis[i + 1] = args[i] instanceof Guid.GUID guid ? new Guid.GUID.ByReference(guid) : args[i];
		}
		Pointer vtable = object.getPointer().getPointer(0);
		return Function.getFunction(vtable.getPointer((long) slot * Native.POINTER_SIZE)).invokeInt(withThis);
	}

	private static void check(int hresult, String what) {
		if (hresult < 0) {
			throw new IllegalStateException(String.format("could not %s (0x%08X)", what, hresult));
		}
	}

	/**
	 * Manual check on Windows: run with JNA on the classpath while something plays. It prints the loudness and how far
	 * behind real time the newest sample is.
	 */
	public static void main(String[] args) throws Exception {
		try (LoopbackCapture capture = start()) {
			for (int i = 0; i < 20; i++) {
				Thread.sleep(200);
				if (capture.failure() != null) {
					throw new IllegalStateException(capture.failure());
				}
				int rate = capture.rate();
				if (rate == 0) {
					continue;
				}
				short[] last = new short[rate / 5];
				long first = capture.copyLatest(last, last.length);
				double sum = 0;
				for (short s : last) {
					sum += s * (double) s;
				}
				double behind = System.nanoTime() / 1e9 - capture.timeOf(first + last.length);
				System.out.printf("rate %d  rms %6.0f  newest sample %.1f ms behind%n", rate, Math.sqrt(sum / last.length), behind * 1000);
			}
		}
	}
}
