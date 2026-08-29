package mtr;

/**
 * Tells anyone still on Java 17 that MTR 4 will need Java 21, and from this build stops them going any further.
 *
 * MTR 4's simulation engine is built for Java 21, and a mod compiled for 17 rejects those class files outright --
 * it is not a degraded experience but a refusal to load. Minecraft 1.19.4 and Fabric both run happily on 21, so
 * the upgrade can be done today, long before anything depends on it. Doing it early also means the migration day
 * itself is only about the world, not about anyone's launcher.
 *
 * The deadline was announced in 3.5.0 and lands here. Three steps, deliberately:
 *
 * <ul>
 * <li>3.5.0 said it and let the game start. A notice with nothing behind it, so it could be ignored.</li>
 * <li>3.5.1 says it and stops. The screen has no way past it, which is the whole point -- but the game still
 * starts, the screen still explains itself, and the guide is one click away. Someone who reads it can act on it
 * in the launcher they already have open.</li>
 * <li>3.5.2 refuses to load, with a crash report and no screen. By then the notice has been shown for three
 * releases and there is nothing left to explain.</li>
 * </ul>
 *
 * Stepping it that way means nobody meets the crash without having first met the screen.
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

	/**
	 * Whether the mod refuses to load at all rather than showing anything.
	 *
	 * Set from 3.5.2. Kept as a flag beside {@link #BLOCKS_LAUNCH} so the two states are one line apart and the
	 * order they arrive in is readable from here, rather than being spread across a version comparison.
	 */
	boolean REFUSES_TO_LOAD = false;

	/** Says how to change it, for anyone who has never had to. Printed as well as linked, so it can be read anywhere. */
	String GUIDE_URL = "https://srn.netartisan.site/mtr-manual/java-update";

	static boolean upgradeNeeded() {
		return Runtime.version().feature() < REQUIRED_FOR_MTR_4;
	}

	/** The major version alone, so "17" rather than "17.0.8+7". */
	static String currentVersion() {
		return String.valueOf(Runtime.version().feature());
	}

	/**
	 * What a crash report says when the mod will not load, which is the only place it can be said.
	 *
	 * There is no screen at that point and no chat, so this line has to carry the version, the requirement and the
	 * guide on its own.
	 */
	static String refusalMessage() {
		return "Minecraft Transit Railway requires Java " + REQUIRED_FOR_MTR_4 + " or newer. This game is running Java "
				+ currentVersion() + ". Change your Java version in your launcher -- see " + GUIDE_URL;
	}
}
