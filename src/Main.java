import client.GameClient;
import lookup.LookupServer;
import server.core.GameServer;

public class Main {
    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("Usage: java Main [server|client|lookup] [host] [port]");
            return;
        }

        switch (args[0].toLowerCase()) {
            case "server":
                new GameServer().startServer();
                break;
            case "client":
                String host = args.length > 1 ? args[1] : "localhost";
                int port = args.length > 2 ? Integer.parseInt(args[2]) : 5000;
                new GameClient(host, port).start();
                break;
            case "lookup":
                int lookupPort = args.length > 1 ? Integer.parseInt(args[1]) : 6000;
                new LookupServer(lookupPort).start();
                break;
            default:
                System.out.println("Unknown argument: " + args[0]);
                System.out.println("Usage: java Main [server|client|lookup]");
        }
    }
}
