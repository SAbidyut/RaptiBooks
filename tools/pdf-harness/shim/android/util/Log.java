package android.util;

/** Desktop stand-in for android.util.Log (the real one is native). */
public final class Log {
    public static final int VERBOSE = 2, DEBUG = 3, INFO = 4, WARN = 5, ERROR = 6, ASSERT = 7;
    private static final boolean VERBOSE_OUT = System.getenv("HARNESS_LOG") != null;
    private Log() {}
    private static int p(String level, String tag, String msg, Throwable t) {
        if (VERBOSE_OUT || level.equals("E")) System.err.println(level + "/" + tag + ": " + msg + (t != null ? " " + t : ""));
        return 0;
    }
    public static int v(String tag, String msg) { return p("V", tag, msg, null); }
    public static int v(String tag, String msg, Throwable t) { return p("V", tag, msg, t); }
    public static int d(String tag, String msg) { return p("D", tag, msg, null); }
    public static int d(String tag, String msg, Throwable t) { return p("D", tag, msg, t); }
    public static int i(String tag, String msg) { return p("I", tag, msg, null); }
    public static int i(String tag, String msg, Throwable t) { return p("I", tag, msg, t); }
    public static int w(String tag, String msg) { return p("W", tag, msg, null); }
    public static int w(String tag, String msg, Throwable t) { return p("W", tag, msg, t); }
    public static int w(String tag, Throwable t) { return p("W", tag, "", t); }
    public static int e(String tag, String msg) { return p("E", tag, msg, null); }
    public static int e(String tag, String msg, Throwable t) { return p("E", tag, msg, t); }
    public static boolean isLoggable(String tag, int level) { return VERBOSE_OUT; }
    public static String getStackTraceString(Throwable t) { return String.valueOf(t); }
    public static int println(int priority, String tag, String msg) { return p("P", tag, msg, null); }
}
