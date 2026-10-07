Minimal stand-ins for the parts of `androidx.test:monitor` and `espresso-idling-resource`
that Robolectric calls. They exist only so that `tools/offline-build/rbuild.py robotest`
can run Robolectric where Google's Maven repository is unreachable. They are never packaged
into the app; the normal Gradle build uses the real artifacts.
