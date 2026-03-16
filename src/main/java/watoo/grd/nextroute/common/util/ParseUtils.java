package watoo.grd.nextroute.common.util;

public final class ParseUtils {

	private ParseUtils() {}

	public static Integer parseInteger(String value) {
		if (value == null || value.isBlank()) return null;
		try { return Integer.parseInt(value.trim()); }
		catch (NumberFormatException e) { return null; }
	}

	public static Double parseDouble(String value) {
		if (value == null || value.isBlank()) return null;
		try { return Double.parseDouble(value.trim()); }
		catch (NumberFormatException e) { return null; }
	}
}
