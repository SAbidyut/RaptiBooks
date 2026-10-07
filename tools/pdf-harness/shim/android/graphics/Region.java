package android.graphics;

/** Desktop stand-in for android.graphics.Region (bounds only). */
public class Region {
    private final Path path = new Path();
    public Region() {}
    public Region(int left, int top, int right, int bottom) { path.moveTo(left, top); path.lineTo(right, bottom); }
    public boolean setPath(Path p, Region clip) { path.set(p); return !p.isEmpty(); }
    public Path getBoundaryPath() { return new Path(path); }
    public boolean isEmpty() { return path.isEmpty(); }
}
