module.exports = {
  apps: [
    {
      name: "childwatch",
      script: "./index.js",
      cwd: __dirname,
      autorestart: true,
      watch: false,
      time: true,
      env: {
        NODE_ENV: "production",
        PORT: "3000",
        CW_DB_PATH: "./childwatch.db",
        CW_AUTH_SESSION_PATH: "./data/auth-sessions.json",
        CW_REQUIRE_WS_AUTH: "0",
        CW_VERBOSE_WS_LOGS: "0",
        CW_VERBOSE_AUDIO_LOGS: "0",
      },
      env_debug: {
        NODE_ENV: "development",
        PORT: "3000",
        CW_DB_PATH: "./childwatch.db",
        CW_AUTH_SESSION_PATH: "./data/auth-sessions.json",
        CW_REQUIRE_WS_AUTH: "0",
        CW_VERBOSE_WS_LOGS: "1",
        CW_VERBOSE_AUDIO_LOGS: "1",
      },
    },
  ],
};
