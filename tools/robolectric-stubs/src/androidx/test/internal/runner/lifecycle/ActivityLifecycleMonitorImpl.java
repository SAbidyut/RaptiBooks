package androidx.test.internal.runner.lifecycle;
import android.app.Activity;
import androidx.test.runner.lifecycle.ActivityLifecycleCallback;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitor;
import androidx.test.runner.lifecycle.Stage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
public final class ActivityLifecycleMonitorImpl implements ActivityLifecycleMonitor {
    private final List<ActivityLifecycleCallback> callbacks = new ArrayList<>();
    private final Map<Activity, Stage> stages = new WeakHashMap<>();
    public ActivityLifecycleMonitorImpl() {}
    public ActivityLifecycleMonitorImpl(boolean declawThreadCheck) {}
    @Override public synchronized void addLifecycleCallback(ActivityLifecycleCallback c) { callbacks.add(c); }
    @Override public synchronized void removeLifecycleCallback(ActivityLifecycleCallback c) { callbacks.remove(c); }
    @Override public synchronized Stage getLifecycleStageOf(Activity a) { Stage s = stages.get(a); if (s == null) throw new IllegalArgumentException("Unknown activity"); return s; }
    @Override public synchronized Collection<Activity> getActivitiesInStage(Stage stage) {
        List<Activity> out = new ArrayList<>();
        for (Map.Entry<Activity, Stage> e : stages.entrySet()) if (e.getValue() == stage) out.add(e.getKey());
        return out;
    }
    public void signalLifecycleChange(Stage stage, Activity activity) {
        List<ActivityLifecycleCallback> copy;
        synchronized (this) { stages.put(activity, stage); copy = new ArrayList<>(callbacks); }
        for (ActivityLifecycleCallback c : copy) c.onActivityLifecycleChanged(activity, stage);
    }
}
