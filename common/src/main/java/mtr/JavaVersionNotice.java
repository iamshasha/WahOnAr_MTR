package mtr;

/**
 * Tells anyone still on Java 17 that MTR 4 will need Java 21, and from this build stops them going any further.
 *
 * MTR 4's simulation engine is built for Java 21, and a mod compiled for 17 rejects those class files outright --
 * it is not a degraded experience but a refusal to load. Minecraft 1.19.4 and Fabric both run happily on 21, so
 * the upgrade can be done today, long before anything depends on it. Doing it early also means the migration day
 * itself is only about the world, not about anyone's launcher.
 *
 * The deadline was announced in 3.5.0 and lands in 3.5.1: the screen has no way past it, but the game still
 * starts, the screen still explains itself, and the guide is one click away. Someone who reads it can act on it in
 * the launcher they already have open.
 *
 * A third step was planned, where the mod would refuse to load at all. It is not going to happen, and the code for
 * it has been taken out rather than left behind a flag.
 *
 * The reason is that the premise was wrong. This was written believing MTR 4's simulation engine needed Java 21,
 * so that a client on 17 would meet a mod that could not load whatever anyone did. The engine actually vendored
 * with MTR 4.0.6 is Java 8 bytecode throughout -- upstream caps its own build at 17 -- so nothing in the version
 * this server is heading for requires 21 at all. Refusing to start someone's game is only defensible when the
 * alternative is a game that would not have worked anyway, and that is not the situation.
 *
 * The screen stays. Moving to 21 early is still worth doing and costs nothing: 1.19.4 and Fabric both run on it,
 * and it is one less thing to vary on migration day. But it is worth a screen, not a crash.
 */
public interface JavaVersionNotice {

	/** What MTR 4 will be built for. Java 21 is a long-term release, so 21 through 25 are all fine. */
	int REQUIRED_FOR_MTR_4 = 21;

	/**
	 * Whether the screen can be got past.
	 *
	 * False through 3.5.0, when this was a notice. True from 3.5.1, when it became the deadline.
	 */
	boolean BLOCKS_LAUNCH = true;

	/** Says how to change it, for anyone who has never had to. Printed as well as linked, so it can be read anywhere. */
	String GUIDE_URL = "https://srn.netartisan.site/mtr-manual/java-update";

	static boolean upgradeNeeded() {
		return Runtime.version().feature() < REQUIRED_FOR_MTR_4;
	}

	/** The major version alone, so "17" rather than "17.0.8+7". */
	static String currentVersion() {
		return String.valueOf(Runtime.version().feature());
	}

	/** What the log says once, per session, when the screen goes up. */
	static String noticeMessage() {
		return "Minecraft Transit Railway asks for Java " + REQUIRED_FOR_MTR_4 + " or newer. This game is running Java "
				+ currentVersion() + ". Change your Java version in your launcher -- see " + GUIDE_URL;
	}
}
