package ru.example.childwatch.designsystem;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Applications provide authenticated transport; UI never handles credentials. */
public final class GameOnlineBridge {
    public interface Host {
        String scopeKey();
        JSONObject request(String method, String path, JSONObject body) throws Exception;
        default SnakeRealtimeBridge.Credentials snakeCredentials() throws Exception {
            throw new Failure(501, "SNAKE_UNSUPPORTED");
        }
    }
    public static volatile Host host;
    public static class Failure extends Exception {
        public final int status;
        public Failure(int status, String message) { super(message); this.status = status; }
    }
    public interface Result { void done(JSONObject value, Exception error); }
    public static final class Client {
        private final Host captured = host;
        private final String scope = captured == null ? "" : captured.scopeKey();
        private final SharedPreferences prefs;
        private final ExecutorService worker = Executors.newSingleThreadExecutor();
        private final Handler main = new Handler(Looper.getMainLooper());
        private volatile boolean closed;
        private boolean busy;
        public Client(Context context) {
            prefs = context.getSharedPreferences("family-games-outbox", Context.MODE_PRIVATE);
        }
        private String key() { return "pending:" + scope; }
        public boolean available() { return captured != null && !scope.isEmpty() && scope.equals(captured.scopeKey()); }
        public boolean hasPending() { return available() && prefs.contains(key()); }
        public void read(String path, Result result) { request("GET", path, null, false, result); }
        public void write(String path, JSONObject body, Result result) {
            if (hasPending()) { result.done(null, new Failure(409,"Сначала повторите неподтверждённое действие")); return; }
            request("POST", path, body, true, result);
        }
        public void retry(Result result) {
            try {
                JSONObject saved = new JSONObject(prefs.getString(key(), ""));
                request("POST", saved.getString("path"), saved.getJSONObject("body"), true, result);
            } catch (Exception error) { result.done(null, error); }
        }
        private void request(String method, String path, JSONObject body, boolean durable, Result result) {
            if (closed || busy) { result.done(null,new Failure(409,"Дождитесь ответа")); return; }
            if (!available()) { result.done(null,new Failure(403,"Откройте игры с главного экрана своего профиля")); return; }
            final JSONObject frozen;
            try {
                frozen = body == null ? null : new JSONObject(body.toString());
                if (durable && !prefs.edit().putString(key(),new JSONObject().put("path",path).put("body",frozen).toString()).commit())
                    throw new IllegalStateException("Не удалось сохранить действие");
            } catch (Exception error) { result.done(null,error); return; }
            busy = true;
            worker.execute(() -> {
                JSONObject value = null; Exception error = null;
                try {
                    if (!available()) throw new Failure(403,"Семейный профиль изменился");
                    value = captured.request(method,path,frozen);
                    if (!available()) throw new Failure(403,"Семейный профиль изменился");
                    if (durable) prefs.edit().remove(key()).commit();
                } catch (Exception failure) {
                    error = failure;
                    if (durable && failure instanceof Failure && ((Failure)failure).status >= 400 && ((Failure)failure).status < 500
                        && ((Failure)failure).status != 408 && ((Failure)failure).status != 429)
                        prefs.edit().remove(key()).commit();
                }
                JSONObject response = value; Exception problem = error;
                main.post(() -> { busy=false; if (!closed) result.done(response,problem); });
            });
        }
        public void close() { closed=true; worker.shutdown(); }
    }
    public static String explain(Exception error) {
        if (error instanceof Failure) {
            int code=((Failure)error).status;
            if(code==404||code==501) return "Совместная игра ещё недоступна на сервере. Одиночная работает без сети.";
            if(code==401||code==403) return "Доступ к семейной игре изменился. Вернитесь на главный экран.";
            if(code==409) return "Партия изменилась. Обновите поле перед следующим ходом.";
            if(code==429) return "Слишком много запросов. Повторите чуть позже.";
        }
        return "Нет подтверждения сервера. Проверьте связь; сохранённое действие можно повторить отдельной кнопкой.";
    }
}
