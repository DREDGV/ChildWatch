package ru.example.childwatch.designsystem;

/** Isolated copy of the production screen. No working ChildWatch APK is replaced. */
public class ArcanoidFixtureActivity extends ArcanoidActivity {
    @Override protected void attachBaseContext(android.content.Context context) {
        android.content.res.Configuration config = new android.content.res.Configuration(context.getResources().getConfiguration());
        config.fontScale = 1.5f;
        super.attachBaseContext(context.createConfigurationContext(config));
    }
}
