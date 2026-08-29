package mtr.data;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Where a held train says so, which is deliberately not the server's own log.
 *
 * These lines are the only way to tell a train that will not move from a depot that will not dispatch, so they
 * have to be written. They just cannot be written to the console. {@code System.out} is synchronised and goes
 * through the server's log appender on the thread that called it, so on a railway with a hundred held trains it
 * became the most expensive thing the server did — 101ms a tick, 2 TPS — and the report that existed to explain a
 * stuck railway was what stopped it.
 *
 * Here instead: its own file, opened once, appended to in batches by a thread of its own. The server thread only
 * ever adds a string to a list. Being off the console also means the rate can be generous, and a generous rate is
 * what makes these lines worth reading — throttling them to one a minute per train is what hid, for a long time,
 * that trains were waiting on each other in a ring.
 *
 * The file is {@code logs/mtr-holds.log}, truncated on each start, so what is in it belongs to the run being
 * looked at.
 */
public final class HoldLog {

	private static final Path FILE = Paths.get("logs", "mtr-holds.log");
	/** Long enough that the writer is not woken constantly, short enough that a live tail is still live. */
	private static final long FLUSH_INTERVAL_MILLIS = 1000;
	/**
	 * A ceiling on what is held in memory if writing falls behind or fails outright.
	 *
	 * Dropping lines is a worse diagnostic than keeping them, and a better outcome than an unbounded list on a
	 * server that is already struggling, which is the only situation in which this fills up.
	 */
	private static final int MAX_PENDING = 20000;

	private static final List<String> pending = new ArrayList<>();
	private static boolean started;
	private static long dropped;

	private HoldLog() {
	}

	/**
	 * Takes a line to be written, from the server thread.
	 *
	 * Does no I/O and takes no lock anybody else holds for long: the whole point is that a train reporting a hold
	 * costs almost nothing, so that the reports can be frequent enough to be useful.
	 */
	public static void write(String line) {
		synchronized (pending) {
			if (!started) {
				start();
			}
			if (pending.size() >= MAX_PENDING) {
				dropped++;
				return;
			}
			pending.add(line);
		}
	}

	private static void start() {
		started = true;
		try {
			Files.createDirectories(FILE.getParent());
			Files.write(FILE, new byte[0]);
		} catch (IOException e) {
			// Nothing here is worth interfering with the server for; the reports are simply lost
			System.out.println("MTR: could not open " + FILE + ", hold reports will not be written: " + e.getMessage());
			return;
		}

		final Thread writer = new Thread(HoldLog::drainForever, "MTR hold log");
		writer.setDaemon(true);
		// Below the server thread: this must never be the reason a tick is late
		writer.setPriority(Thread.MIN_PRIORITY);
		writer.start();
	}

	private static void drainForever() {
		while (true) {
			try {
				Thread.sleep(FLUSH_INTERVAL_MILLIS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
			drain();
		}
	}

	private static void drain() {
		final List<String> batch;
		final long lost;
		synchronized (pending) {
			if (pending.isEmpty() && dropped == 0) {
				return;
			}
			batch = new ArrayList<>(pending);
			pending.clear();
			lost = dropped;
			dropped = 0;
		}

		if (lost > 0) {
			batch.add("[" + lost + " reports dropped: they were arriving faster than they could be written]");
		}

		final StringBuilder text = new StringBuilder();
		for (final String line : batch) {
			text.append(line).append(System.lineSeparator());
		}

		try {
			Files.write(FILE, text.toString().getBytes(StandardCharsets.UTF_8),
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			// Losing a batch is not worth a line on the console every second while whatever is wrong stays wrong
		}
	}
}
