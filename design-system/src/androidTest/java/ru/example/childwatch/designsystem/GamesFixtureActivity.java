package ru.example.childwatch.designsystem;

public class GamesFixtureActivity extends GamesActivity {
    @Override protected void attachBaseContext(android.content.Context context) {
        android.content.res.Configuration config = new android.content.res.Configuration(context.getResources().getConfiguration());
        config.fontScale = 1.5f;
        super.attachBaseContext(context.createConfigurationContext(config));
    }
}
