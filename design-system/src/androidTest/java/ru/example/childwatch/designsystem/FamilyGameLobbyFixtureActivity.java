package ru.example.childwatch.designsystem;
public final class FamilyGameLobbyFixtureActivity extends FamilyGameLobbyActivity {
    @Override protected void attachBaseContext(android.content.Context context){
        android.content.res.Configuration config=new android.content.res.Configuration(context.getResources().getConfiguration());config.fontScale=2f;
        super.attachBaseContext(context.createConfigurationContext(config));
    }
}
