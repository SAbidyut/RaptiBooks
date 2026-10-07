package android.graphics;

/** Desktop stand-in for android.graphics.Path that only tracks bounds (enough for parsing). */
public class Path {
    public enum FillType { WINDING, EVEN_ODD, INVERSE_WINDING, INVERSE_EVEN_ODD }
    public enum Op { DIFFERENCE, INTERSECT, UNION, XOR, REVERSE_DIFFERENCE }
    public enum Direction { CW, CCW }

    float l, t, r, b;
    boolean empty = true;
    private FillType fill = FillType.WINDING;

    public Path() {}
    public Path(Path src) { set(src); }

    public void set(Path src) { l = src.l; t = src.t; r = src.r; b = src.b; empty = src.empty; fill = src.fill; }
    public void reset() { empty = true; }
    public void rewind() { empty = true; }
    private void add(float x, float y) {
        if (empty) { l = r = x; t = b = y; empty = false; return; }
        l = Math.min(l, x); r = Math.max(r, x); t = Math.min(t, y); b = Math.max(b, y);
    }
    public void moveTo(float x, float y) { add(x, y); }
    public void lineTo(float x, float y) { add(x, y); }
    public void quadTo(float x1, float y1, float x2, float y2) { add(x1, y1); add(x2, y2); }
    public void cubicTo(float x1, float y1, float x2, float y2, float x3, float y3) { add(x1, y1); add(x2, y2); add(x3, y3); }
    public void close() {}
    public void addRect(float left, float top, float right, float bottom, Direction dir) { add(left, top); add(right, bottom); }
    public void addRect(RectF rect, Direction dir) { addRect(rect.left, rect.top, rect.right, rect.bottom, dir); }
    public void computeBounds(RectF bounds, boolean exact) { if (empty) bounds.set(0, 0, 0, 0); else bounds.set(l, t, r, b); }
    public boolean isEmpty() { return empty; }
    public void setFillType(FillType ft) { fill = ft; }
    public FillType getFillType() { return fill; }
    public void offset(float dx, float dy) { l += dx; r += dx; t += dy; b += dy; }
    public void transform(Matrix m) { transform(m, this); }
    public void transform(Matrix m, Path dst) {
        if (empty) { dst.empty = true; return; }
        float[] v = new float[9]; m.getValues(v);
        float[][] pts = {{l, t}, {r, t}, {l, b}, {r, b}};
        Path out = new Path();
        for (float[] p : pts) out.add(v[0] * p[0] + v[1] * p[1] + v[2], v[3] * p[0] + v[4] * p[1] + v[5]);
        dst.set(out);
    }
    public boolean op(Path path, Op op) { return op(this, path, op); }
    public boolean op(Path a, Path b2, Op op) {
        Path res = new Path();
        if (op == Op.INTERSECT) {
            if (!a.empty && !b2.empty) {
                float nl = Math.max(a.l, b2.l), nt = Math.max(a.t, b2.t), nr = Math.min(a.r, b2.r), nb = Math.min(a.b, b2.b);
                if (nr >= nl && nb >= nt) { res.add(nl, nt); res.add(nr, nb); }
            }
        } else if (op == Op.UNION || op == Op.XOR) {
            if (!a.empty) { res.add(a.l, a.t); res.add(a.r, a.b); }
            if (!b2.empty) { res.add(b2.l, b2.t); res.add(b2.r, b2.b); }
        } else {
            res.set(op == Op.REVERSE_DIFFERENCE ? b2 : a);
        }
        set(res);
        return true;
    }
}
