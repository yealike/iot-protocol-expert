package top.yealike.ipe.simulator.multidevice;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Local-only MQTT 3.1.1 subset for the simulator. Not a production broker. */
final class LocalMqttBroker implements AutoCloseable {
    private final ServerSocket server;
    private final ThreadPoolExecutor workers =
            new ThreadPoolExecutor(2, 16, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), r -> {
                Thread t = new Thread(r, "sim-mqtt-client");
                t.setDaemon(true);
                return t;
            });
    private final Set<Client> clients = ConcurrentHashMap.newKeySet();
    private final Thread acceptor;

    LocalMqttBroker(int port) throws IOException {
        server = new ServerSocket();
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
        acceptor = new Thread(this::accept, "sim-mqtt-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    int port() {
        return server.getLocalPort();
    }

    private void accept() {
        while (!server.isClosed()) {
            Socket socket = null;
            try {
                socket = server.accept();
                Client client = new Client(socket);
                clients.add(client);
                try {
                    workers.execute(client);
                } catch (RuntimeException ex) {
                    clients.remove(client);
                    socket.close();
                }
            } catch (IOException e) {
                if (!server.isClosed() && socket != null)
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                    }
            }
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
        for (Client client : clients) client.socket.close();
        workers.shutdownNow();
    }

    private static boolean permitted(String topic) {
        return topic.matches("ipe/simulation/SIM-(LOCK|GATE|CAM)-001/properties");
    }

    private final class Client implements Runnable {
        final Socket socket;
        final Set<String> subscriptions = ConcurrentHashMap.newKeySet();
        DataInputStream in;
        DataOutputStream out;
        boolean connected;

        Client(Socket socket) {
            this.socket = socket;
        }

        public void run() {
            try (socket) {
                socket.setSoTimeout(120000);
                in = new DataInputStream(socket.getInputStream());
                out = new DataOutputStream(socket.getOutputStream());
                while (!socket.isClosed()) {
                    int header = in.readUnsignedByte(), length = remaining(in);
                    if (length > 65536) throw new IOException("packet too large");
                    byte[] body = in.readNBytes(length);
                    if (body.length != length) throw new EOFException();
                    int kind = header >> 4;
                    if (!connected && kind != 1) throw new IOException("connect required");
                    switch (kind) {
                        case 1 -> connect(header, body);
                        case 3 -> publish(header, body);
                        case 8 -> subscribe(header, body);
                        case 12 -> write(new byte[] {(byte) 0xd0, 0});
                        case 14 -> {
                            return;
                        }
                        default -> throw new IOException("unsupported packet");
                    }
                }
            } catch (IOException ignored) {
            } finally {
                clients.remove(this);
            }
        }

        private void connect(int header, byte[] body) throws IOException {
            if (connected
                    || header != 0x10
                    || body.length < 12
                    || body[0] != 0
                    || body[1] != 4
                    || body[2] != 'M'
                    || body[3] != 'Q'
                    || body[4] != 'T'
                    || body[5] != 'T'
                    || body[6] != 4
                    || (body[7] & 2) == 0
                    || (body[7] & 4) != 0) throw new IOException("unsupported connect");
            connected = true;
            write(new byte[] {0x20, 2, 0, 0});
        }

        private void subscribe(int header, byte[] body) throws IOException {
            if (header != 0x82 || body.length < 5) throw new IOException("invalid subscribe");
            int pos = 2;
            java.io.ByteArrayOutputStream response = new java.io.ByteArrayOutputStream();
            response.write(body[0]);
            response.write(body[1]);
            while (pos < body.length) {
                if (pos + 2 > body.length) throw new IOException("bad topic");
                int size = ((body[pos] & 255) << 8) | (body[pos + 1] & 255);
                pos += 2;
                if (size == 0 || pos + size >= body.length) throw new IOException("bad topic");
                String topic = new String(body, pos, size, StandardCharsets.UTF_8);
                pos += size;
                int qos = body[pos++] & 255;
                if (permitted(topic) && qos <= 1) {
                    subscriptions.add(topic);
                    response.write(0);
                } else response.write(0x80);
            }
            send(0x90, response.toByteArray());
        }

        private void publish(int header, byte[] body) throws IOException {
            int qos = (header >> 1) & 3;
            if ((header & 1) != 0 || qos > 1 || body.length < 2) throw new IOException("unsupported publish");
            int size = ((body[0] & 255) << 8) | (body[1] & 255), pos = 2;
            if (size == 0 || pos + size > body.length) throw new IOException("bad topic");
            String topic = new String(body, pos, size, StandardCharsets.UTF_8);
            pos += size;
            int packetId = 0;
            if (qos == 1) {
                if (pos + 2 > body.length) throw new IOException("missing packet id");
                packetId = ((body[pos] & 255) << 8) | (body[pos + 1] & 255);
                pos += 2;
                if (packetId == 0) throw new IOException("invalid packet id");
            }
            if (!permitted(topic)) throw new IOException("topic not allowed");
            byte[] topicBytes = topic.getBytes(StandardCharsets.UTF_8);
            byte[] payload = Arrays.copyOfRange(body, pos, body.length);
            java.io.ByteArrayOutputStream data = new java.io.ByteArrayOutputStream();
            data.write(topicBytes.length >> 8);
            data.write(topicBytes.length);
            data.write(topicBytes);
            data.write(payload);
            for (Client client : clients)
                if (client.subscriptions.contains(topic))
                    try {
                        client.send(0x30, data.toByteArray());
                    } catch (IOException e) {
                        try {
                            client.socket.close();
                        } catch (IOException ignored) {
                        }
                    }
            if (qos == 1) write(new byte[] {0x40, 2, (byte) (packetId >> 8), (byte) packetId});
        }

        synchronized void write(byte[] bytes) throws IOException {
            out.write(bytes);
            out.flush();
        }

        synchronized void send(int header, byte[] body) throws IOException {
            out.writeByte(header);
            int n = body.length;
            do {
                int digit = n % 128;
                n /= 128;
                if (n > 0) digit |= 128;
                out.writeByte(digit);
            } while (n > 0);
            out.write(body);
            out.flush();
        }
    }

    private static int remaining(DataInputStream in) throws IOException {
        int value = 0, multiplier = 1, digit, iterations = 0;
        do {
            if (++iterations > 4) throw new IOException("invalid remaining length");
            digit = in.readUnsignedByte();
            value += (digit & 127) * multiplier;
            multiplier *= 128;
        } while ((digit & 128) != 0);
        return value;
    }
}
