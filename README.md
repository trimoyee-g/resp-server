# RespServer

A minimal Redis-compatible server written in Java, built from scratch using the RESP (REdis Serialization Protocol). Listens on port `6379` and handles multiple clients concurrently via non-blocking NIO.

## Supported commands

| Command | Syntax | Description |
|---------|--------|-------------|
| `PING` | `PING [message]` | Returns PONG or echoes the message |
| `SET` | `SET key value` | Stores a string value |
| `GET` | `GET key` | Retrieves a value, returns nil if missing |
| `DEL` | `DEL key` | Deletes a key, returns 1 if deleted |
| `EXISTS` | `EXISTS key` | Returns 1 if key exists, 0 otherwise |
| `KEYS` | `KEYS *` | Returns all keys in the store |

## Run locally

**Requires Java 21+**

```bash
javac RespServer.java
java RespServer
```

Then connect with any Redis client:

```bash
redis-cli -p 6379 ping
redis-cli -p 6379 set foo bar
redis-cli -p 6379 get foo
```

## Run with Docker

```bash
docker build -t resp-server .
docker run -p 6379:6379 resp-server
```

## Architecture

- **Non-blocking NIO** — single-threaded `Selector` loop handles all clients without spawning threads
- **Per-client buffers** — each connection gets its own `StringBuilder` so partial TCP reads are accumulated correctly before parsing
- **RESP parser** — parses the binary-safe RESP array format (`*<n>\r\n$<len>\r\n<data>\r\n...`)
- **In-memory store** — plain `HashMap<String, String>`; data is lost on restart

## Limitations

This is a learning project, not a production Redis replacement. Missing: TTL/expiry, persistence, pub/sub, transactions, all non-string data types (lists, sets, hashes, sorted sets), and most Redis commands.
