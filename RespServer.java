import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;

public class RespServer {

    private static final Map<String, String> store = new HashMap<>();

    // Per-client accumulation buffer — THIS is the key addition
    private static final Map<SocketChannel, StringBuilder> clientBuffers = new HashMap<>();

    public static void main(String[] args) throws IOException {
        ServerSocketChannel serverChannel = ServerSocketChannel.open();
        serverChannel.bind(new InetSocketAddress(6379));
        serverChannel.configureBlocking(false);

        Selector selector = Selector.open();
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        System.out.println("RESP server on port 6379");

        ByteBuffer readBuf = ByteBuffer.allocate(4096);

        while (true) {
            selector.select();
            Iterator<SelectionKey> keys = selector.selectedKeys().iterator();

            while (keys.hasNext()) {
                SelectionKey key = keys.next();
                keys.remove();

                if (key.isAcceptable()) {
                    SocketChannel client = serverChannel.accept();
                    client.configureBlocking(false);
                    client.register(selector, SelectionKey.OP_READ);
                    clientBuffers.put(client, new StringBuilder()); // init buffer for this client
                    System.out.println("Connected: " + client.getRemoteAddress());

                } else if (key.isReadable()) {
                    SocketChannel client = (SocketChannel) key.channel();
                    readBuf.clear();

                    int n = client.read(readBuf);
                    if (n == -1) {
                        clientBuffers.remove(client);
                        key.cancel();
                        client.close();
                        continue;
                    }

                    // Append newly read bytes into this client's personal buffer
                    readBuf.flip();
                    String chunk = new String(readBuf.array(), 0, readBuf.limit());
                    clientBuffers.get(client).append(chunk);

                    // Try to parse and execute as many complete commands as possible
                    // (one read might have delivered 2 commands worth of data)
                    StringBuilder buf = clientBuffers.get(client);
                    while (true) {
                        ParseResult result = tryParseResp(buf.toString());
                        if (result == null) break; // incomplete, wait for more bytes

                        String response = execute(result.args);
                        client.write(ByteBuffer.wrap(response.getBytes()));

                        // Consume only the bytes we parsed, leave the rest
                        buf.delete(0, result.bytesConsumed);
                    }
                }
            }
        }
    }

    // --- RESP Parser ---

    static class ParseResult {
        List<String> args;
        int bytesConsumed;
        ParseResult(List<String> args, int bytesConsumed) {
            this.args = args;
            this.bytesConsumed = bytesConsumed;
        }
    }

    static ParseResult tryParseResp(String data) {
        // RESP command must start with '*' (array)
        if (!data.startsWith("*")) return null;

        String[] lines = data.split("\r\n", -1);
        // -1 keeps trailing empty strings so we can detect incomplete data

        int idx = 0;

        // Parse: *<count>
        if (idx >= lines.length) return null;
        int argCount;
        try {
            argCount = Integer.parseInt(lines[idx++].substring(1));
        } catch (NumberFormatException e) {
            return null;
        }

        List<String> args = new ArrayList<>();

        for (int i = 0; i < argCount; i++) {
            // Parse: $<length>
            if (idx >= lines.length) return null;
            String lenLine = lines[idx++];
            if (!lenLine.startsWith("$")) return null;

            int strLen;
            try {
                strLen = Integer.parseInt(lenLine.substring(1));
            } catch (NumberFormatException e) {
                return null;
            }

            // Parse: <value>
            if (idx >= lines.length) return null;
            String value = lines[idx++];

            // Validate length matches what was declared
            if (value.length() != strLen) return null; // incomplete bulk string

            args.add(value);
        }

        // Calculate how many bytes we consumed
        // Each line we parsed contributes (line.length + 2) for the \r\n
        int bytesConsumed = 0;
        for (int i = 0; i < idx; i++) {
            bytesConsumed += lines[i].length() + 2; // +2 for \r\n
        }

        return new ParseResult(args, bytesConsumed);
    }

    // --- Command executor ---

    static String execute(List<String> args) {
        if (args.isEmpty()) return respError("empty command");

        return switch (args.get(0).toUpperCase()) {
            case "SET" -> {
                if (args.size() < 3) yield respError("syntax: SET key value");
                store.put(args.get(1), args.get(2));
                yield respSimple("OK");
            }
            case "GET" -> {
                if (args.size() < 2) yield respError("syntax: GET key");
                String val = store.get(args.get(1));
                yield val != null ? respBulk(val) : respNull();
            }
            case "DEL" -> {
                if (args.size() < 2) yield respError("syntax: DEL key");
                boolean removed = store.remove(args.get(1)) != null;
                yield respInt(removed ? 1 : 0);
            }
            case "EXISTS" -> {
                if (args.size() < 2) yield respError("syntax: EXISTS key");
                yield respInt(store.containsKey(args.get(1)) ? 1 : 0);
            }
            case "KEYS" -> {
                List<String> keys = new ArrayList<>(store.keySet());
                yield respArray(keys);
            }
            case "PING" -> args.size() > 1 ? respBulk(args.get(1)) : respSimple("PONG");
            default -> respError("unknown command: " + args.get(0));
        };
    }

    // --- RESP response builders ---

    static String respSimple(String msg)   { return "+" + msg + "\r\n"; }
    static String respError(String msg)    { return "-ERR " + msg + "\r\n"; }
    static String respInt(int n)           { return ":" + n + "\r\n"; }
    static String respNull()               { return "$-1\r\n"; }
    static String respBulk(String s)       { return "$" + s.length() + "\r\n" + s + "\r\n"; }
    static String respArray(List<String> items) {
        StringBuilder sb = new StringBuilder("*").append(items.size()).append("\r\n");
        for (String item : items) sb.append(respBulk(item));
        return sb.toString();
    }
}