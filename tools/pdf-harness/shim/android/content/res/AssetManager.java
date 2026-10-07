package android.content.res;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Desktop stand-in that serves assets from a directory (an exploded AAR's assets/). */
public final class AssetManager {
    private final File root;
    public AssetManager(File root) { this.root = root; }
    public InputStream open(String path) throws IOException { return new FileInputStream(new File(root, path)); }
    public String[] list(String path) { String[] l = new File(root, path).list(); return l == null ? new String[0] : l; }
}
