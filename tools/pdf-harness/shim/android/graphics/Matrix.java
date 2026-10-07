package android.graphics;

/** Desktop stand-in for android.graphics.Matrix (values only). */
public class Matrix {
    private final float[] v = {1, 0, 0, 0, 1, 0, 0, 0, 1};
    public Matrix() {}
    public Matrix(Matrix src) { if (src != null) System.arraycopy(src.v, 0, v, 0, 9); }
    public void setValues(float[] values) { System.arraycopy(values, 0, v, 0, 9); }
    public void getValues(float[] values) { System.arraycopy(v, 0, values, 0, 9); }
    public void reset() { setValues(new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1}); }
    public void setScale(float sx, float sy) { reset(); v[0] = sx; v[4] = sy; }
    public boolean postTranslate(float dx, float dy) { v[2] += dx; v[5] += dy; return true; }
}
