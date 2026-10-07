package androidx.test.espresso;
import java.util.ArrayList;
import java.util.Collection;
public final class IdlingRegistry {
    private static final IdlingRegistry INSTANCE = new IdlingRegistry();
    public static IdlingRegistry getInstance() { return INSTANCE; }
    public Collection<IdlingResource> getResources() { return new ArrayList<>(); }
    public Collection<android.os.Looper> getLoopers() { return new ArrayList<>(); }
}
