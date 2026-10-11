import ru.example.childwatch.designsystem.SnakeEngine;
import java.io.BufferedReader;
import java.io.InputStreamReader;

/** Test-only bridge. Every input command emits one complete production-engine snapshot. */
public final class SnakeGoldenRunner {
    private static void emit(SnakeEngine game) {
        long[] data = game.snapshot();
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < data.length; i++) {
            if (i > 0) out.append(',');
            out.append('"').append(data[i]).append('"');
        }
        System.out.println(out.append(']'));
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("seed difficulty");
        SnakeEngine game = new SnakeEngine(SnakeEngine.Mode.COOP,
                SnakeEngine.Difficulty.valueOf(args[1]), Long.parseLong(args[0]));
        emit(game);
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = input.readLine()) != null) {
            String[] command = line.split(" ");
            switch (command[0]) {
                case "launch": game.launchOrResume(); break;
                case "pause": game.pause(); break;
                case "tick": game.tick(); break;
                case "turn": game.turn(Integer.parseInt(command[1]),
                        SnakeEngine.Direction.valueOf(command[2])); break;
                case "restore":
                    String[] parts = command[1].split(",");
                    long[] saved = new long[parts.length];
                    for (int i = 0; i < parts.length; i++) saved[i] = Long.parseLong(parts[i]);
                    if (!game.restore(saved)) throw new IllegalStateException("invalid fixture snapshot");
                    break;
                default: throw new IllegalArgumentException("unknown command: " + line);
            }
            emit(game);
        }
    }
}
