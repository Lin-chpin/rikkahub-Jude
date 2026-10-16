package android.util;

/** JVM protocol tests run real SSE callbacks; Logcat has no role in their results. */
public final class Log {
    public static boolean isLoggable(String tag, int priority) { return false; }
    public static int println(int priority, String tag, String message) { return 0; }
    public static int i(String tag, String message) { return 0; }
    public static int d(String tag, String message) { return 0; }
    public static int w(String tag, String message) { return 0; }
    public static int w(String tag, String message, Throwable error) { return 0; }
    public static int e(String tag, String message) { return 0; }
    public static int e(String tag, String message, Throwable error) { return 0; }
}
