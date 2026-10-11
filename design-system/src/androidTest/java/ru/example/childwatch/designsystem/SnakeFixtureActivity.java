package ru.example.childwatch.designsystem;

/** Enlarged type in the isolated test application; working family data remains untouched. */
public class SnakeFixtureActivity extends SnakeActivity {
    @Override protected void attachBaseContext(android.content.Context context) {
        android.content.res.Configuration config = new android.content.res.Configuration(context.getResources().getConfiguration());
        config.fontScale = 1.5f;
        super.attachBaseContext(context.createConfigurationContext(config));
    }
}
