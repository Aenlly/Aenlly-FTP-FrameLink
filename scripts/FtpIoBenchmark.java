/* Local FTP receive benchmark. This measures the file I/O path, not camera Wi-Fi. */
import app.framelink.camera.FtpTransferIo;
import app.framelink.camera.FtpTransferReceipt;
import app.framelink.camera.FtpConnection;
import app.framelink.camera.FtpProfile;
import java.io.*;
import java.net.ServerSocket;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.ftpserver.*;
import org.apache.ftpserver.command.CommandFactoryFactory;
import org.apache.ftpserver.ftplet.DefaultFtpReply;
import org.apache.ftpserver.impl.*;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.usermanager.*;
import org.apache.ftpserver.usermanager.impl.*;

/** Compares Apache's 8KiB receive buffer with FrameLink's 256KiB buffer; both hash after ACK */
public class FtpIoBenchmark {
    private record Received(long bytes, String sha256, int writes) {}
    private record Sample(String mode, double ackMs, double readyMs, int writes) {}

    public static void main(String[] args) throws Exception {
        int sizeMiB = args.length > 0 ? Integer.parseInt(args[0]) : 32;
        int rounds = args.length > 1 ? Integer.parseInt(args[1]) : 5;
        if (sizeMiB < 1 || sizeMiB > 128 || rounds < 3 || rounds > 20) {
            throw new IllegalArgumentException("sizeMiB=1..128, rounds=3..20");
        }
        byte[] bytes = new byte[sizeMiB * 1024 * 1024 + 73];
        new Random(20261027).nextBytes(bytes);
        String expectedHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        Path root = Files.createTempDirectory("framelink-ftp-io-");
        AtomicReference<CompletableFuture<Received>> pending = new AtomicReference<>();
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        FtpServer server = null;
        List<Sample> samples = new ArrayList<>();
        try {
            server = server(root, port, pending);
            server.start();
            FTPClient client = new FTPClient();
            try {
                client.setConnectTimeout(10000);
                client.setDefaultTimeout(20000);
                client.setDataTimeout(java.time.Duration.ofSeconds(20));
                client.setBufferSize(65536);
                client.connect("127.0.0.1", port);
                if (!client.login("benchmark", "local-benchmark-password")) throw new IOException("Login failed");
                client.enterLocalPassiveMode();
                if (!client.setFileType(FTP.BINARY_FILE_TYPE)) throw new IOException("TYPE I failed");
                for (int round = -2; round < rounds; round++) {
                    List<String> modes = round % 2 == 0 ? List.of("baseline", "optimized") : List.of("optimized", "baseline");
                    for (String mode : modes) {
                        CompletableFuture<Received> future = new CompletableFuture<>();
                        pending.set(future);
                        long start = System.nanoTime();
                        if (!client.storeFile(mode + ".bin", new ByteArrayInputStream(bytes))) {
                            throw new IOException(client.getReplyString());
                        }
                        long ack = System.nanoTime();
                        Received received = future.get(30, TimeUnit.SECONDS);
                        long ready = System.nanoTime();
                        if (received.bytes != bytes.length || !received.sha256.equals(expectedHash)) {
                            throw new AssertionError("Original bytes or SHA-256 differ");
                        }
                        if (round >= 0) samples.add(new Sample(mode, (ack - start) / 1e6, (ready - start) / 1e6, received.writes));
                    }
                }
                client.logout();
            } finally {
                if (client.isConnected()) client.disconnect();
            }
            FtpProfile profile = new FtpProfile("127.0.0.1", port, "benchmark", "local-benchmark-password", false, "UTF-8", "/");
            try (FtpConnection connection = new FtpConnection(profile)) {
                connection.connect();
                ByteArrayOutputStream downloaded = new ByteArrayOutputStream();
                FtpTransferReceipt receipt = connection.download("/baseline.bin", downloaded, done -> kotlin.Unit.INSTANCE);
                if (receipt.getBytes() != bytes.length || !receipt.getSha256().equals(expectedHash) || !Arrays.equals(bytes, downloaded.toByteArray())) {
                    throw new AssertionError("Production FTP download differs");
                }
                CompletableFuture<Received> uploaded = new CompletableFuture<>();
                pending.set(uploaded);
                connection.upload("/uploaded.bin", UUID.randomUUID().toString(), new ByteArrayInputStream(bytes), done -> kotlin.Unit.INSTANCE);
                if (!uploaded.get(30, TimeUnit.SECONDS).sha256.equals(expectedHash) || !Arrays.equals(bytes, Files.readAllBytes(root.resolve("uploaded.bin")))) {
                    throw new AssertionError("Production FTP upload differs");
                }
                boolean refused = false;
                try {
                    connection.upload("/uploaded.bin", UUID.randomUUID().toString(), new ByteArrayInputStream(new byte[] {1}), done -> kotlin.Unit.INSTANCE);
                } catch (IllegalStateException expected) { refused = true; }
                if (!refused || !Arrays.equals(bytes, Files.readAllBytes(root.resolve("uploaded.bin")))) {
                    throw new AssertionError("Existing original was overwritten");
                }
            }
            System.out.println("Production FtpConnection download/upload/SHA-256/no-overwrite checks passed");
            System.out.printf(Locale.ROOT, "Loopback FTP, bytes=%d, measured rounds=%d per path, SHA-256 verified for every file%n", bytes.length, rounds);
            for (Sample sample : samples) {
                System.out.printf(Locale.ROOT, "%s,ack_ms=%.3f,ready_ms=%.3f,disk_writes=%d%n", sample.mode, sample.ackMs, sample.readyMs, sample.writes);
            }
            for (String mode : List.of("baseline", "optimized")) {
                double[] ack = samples.stream().filter(s -> s.mode.equals(mode)).mapToDouble(Sample::ackMs).sorted().toArray();
                double[] ready = samples.stream().filter(s -> s.mode.equals(mode)).mapToDouble(Sample::readyMs).sorted().toArray();
                double medianAck = median(ack);
                double medianReady = median(ready);
                System.out.printf(Locale.ROOT, "%s median: ack_ms=%.3f ready_ms=%.3f ready_MiB_s=%.2f%n", mode, medianAck, medianReady, bytes.length / 1048576.0 / (medianReady / 1000));
            }
            System.out.println("Scope: Apache FTP data transfer + file fsync + local SHA-256. Excludes Room, MediaStore, preview generation, Android and camera radio.");
        } finally {
            if (server != null) server.stop();
            try (var files = Files.walk(root)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }

    private static FtpServer server(Path root, int port, AtomicReference<CompletableFuture<Received>> pending) throws Exception {
        FtpServerFactory factory = new FtpServerFactory();
        ListenerFactory listener = new ListenerFactory();
        listener.setPort(port);
        listener.setServerAddress("127.0.0.1");
        factory.addListener("default", listener.createListener());
        PropertiesUserManagerFactory users = new PropertiesUserManagerFactory();
        Files.createFile(root.resolve("users.properties"));
        users.setFile(root.resolve("users.properties").toFile());
        users.setPasswordEncryptor(new Md5PasswordEncryptor());
        var manager = users.createUserManager();
        BaseUser user = new BaseUser();
        user.setName("benchmark");
        user.setPassword("local-benchmark-password");
        user.setHomeDirectory(root.toString());
        user.setAuthorities(List.of(new WritePermission()));
        manager.save(user);
        factory.setUserManager(manager);
        CommandFactoryFactory commands = new CommandFactoryFactory();
        commands.getCommandMap().put("STOR", (session, context, request) -> {
            CompletableFuture<Received> future = pending.get();
            boolean optimized = !request.getArgument().equals("baseline.bin");
            try {
                String name = request.getArgument();
                while (name.startsWith("/")) name = name.substring(1);
                Path file = root.resolve(name).normalize();
                if (!file.startsWith(root)) throw new IOException("Path outside benchmark directory");
                session.write(new DefaultFtpReply(150, "Opening binary data connection")).awaitUninterruptibly(10000);
                var connection = session.getDataConnection().openConnection();
                Received received;
                try (FileOutputStream output = new FileOutputStream(file.toFile())) {
                    CountingOutput counter = new CountingOutput(output);
                    if (optimized) {
                        long count = FtpTransferIo.INSTANCE.receive(counter, stream -> {
                            try { return connection.transferFromClient(session.getFtpletSession(), stream); }
                            catch (IOException error) { throw new UncheckedIOException(error); }
                        });
                        output.getFD().sync();
                        received = new Received(count, "", counter.writes);
                    } else {
                        long count = connection.transferFromClient(session.getFtpletSession(), counter);
                        output.getFD().sync();
                        received = new Received(count, "", counter.writes);
                    }
                }
                session.write(new DefaultFtpReply(226, "Original saved"));
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream input = Files.newInputStream(file)) {
                    byte[] buffer = new byte[65536];
                    int count;
                    while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
                }
                received = new Received(received.bytes, HexFormat.of().formatHex(digest.digest()), received.writes);
                future.complete(received);
            } catch (Exception error) {
                future.completeExceptionally(error);
                session.write(new DefaultFtpReply(451, "Benchmark failed"));
            } finally {
                session.resetState();
                session.getDataConnection().closeDataConnection();
            }
        });
        factory.setCommandFactory(commands.createCommandFactory());
        return factory.createServer();
    }

    private static double median(double[] sorted) {
        int middle = sorted.length / 2;
        return sorted.length % 2 == 0 ? (sorted[middle - 1] + sorted[middle]) / 2 : sorted[middle];
    }

    private static class CountingOutput extends OutputStream {
        private final OutputStream output;
        int writes;
        CountingOutput(OutputStream output) { this.output = output; }
        @Override public void write(int value) throws IOException { writes++; output.write(value); }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            writes++;
            output.write(bytes, offset, length);
        }
        @Override public void flush() throws IOException { output.flush(); }
    }
}
