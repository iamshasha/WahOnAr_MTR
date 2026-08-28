package mtr;

/**
 * Tells anyone still on Java 17 that MTR 4 will need Java 21, while there is still time to act on it.
 *
 * MTR 4's simulation engine is built for Java 21, and a mod compiled for 17 rejects those class files outright --
 * it is not a degraded experience but a refusal to load. Minecraft 1.19.4 and Fabric both run happily on 21, so
 * the upgrade can be done today, long before anything depends on it. Doing it early also means the migration day
 * itself is only about the world, not about anyone's launcher.
 *
 * Through 3.5.0 this is a notice and nothing more: nothing requires Java 21 and ignoring it changes nothing. From
 * 3.5.1 the build requires it, so that when MTR 4 becomes real the fleet is already there and migration day is
 * about the world rather than about everyone's launcher.
 */
public interface JavaVersionNotice {

	/** What MTR 4 will be built for. Java 21 is a long-term release, so 21 through 25 are all fine. */
	int REQUIRED_FOR_MTR_4 = 21;

	/** Says how to change it, for anyone who has never had to. Printed as well as linked, so it can be read anywhere. */
	String GUIDE_URL = "https://srn.netartisan.site/mtr-manual/java-update";

	static boolean upgradeNeeded() {
		return Runtime.version().feature() < REQUIRED_FOR_MTR_4;
	}

	/** The major version alone, so "17" rather than "17.0.8+7". */
	static String currentVersion() {
		return String.valueOf(Runtime.version().feature());
	}
}
