import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.Map;

public class SimpleWebServer {
    private static final String CONFIG_FILE = "server.conf";
    private static int PORT = 8080;
    private static String HOST = "0.0.0.0";
    private static String defaultRoot = "public";
    private static Map<String, String> vhosts = new HashMap<>();
    private static boolean sslEnabled = false;
    private static String sslKeystorePath = null;
    private static String sslKeystorePassword = null;

    public static void main(String[] args) throws IOException {
        // Load configuration
        loadConfig();

        // Create public folder if it doesn't exist
        Path publicPath = Paths.get(defaultRoot);
        if (!Files.exists(publicPath)) {
            Files.createDirectory(publicPath);
            System.out.println("Created folder: " + defaultRoot);
        }

        // Create HTTP/HTTPS server
        InetSocketAddress address = HOST.equals("0.0.0.0") 
            ? new InetSocketAddress(PORT) 
            : new InetSocketAddress(HOST, PORT);
        
        HttpServer server;
        if (sslEnabled && sslKeystorePath != null) {
            server = createHttpsServer(address);
            System.out.println("Server started at https://" + HOST + ":" + PORT);
        } else {
            server = HttpServer.create(address, 0);
            System.out.println("Server started at http://" + HOST + ":" + PORT);
        }
        
        server.createContext("/", new FileHandler());
        server.setExecutor(null);
        server.start();
        System.out.println("Serving files from: " + publicPath.toAbsolutePath());
        if (!vhosts.isEmpty()) {
            System.out.println("Virtual hosts configured: " + vhosts.size());
            vhosts.forEach((domain, folder) -> 
                System.out.println("  " + domain + " -> " + folder));
        }
        System.out.println("Type 'stop' to shutdown the server");

        // Command handler thread
        Thread commandThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().equalsIgnoreCase("stop")) {
                        System.out.println("Stopping server...");
                        server.stop(0);
                        System.out.println("Server stopped");
                        System.exit(0);
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
        commandThread.setDaemon(false);
        commandThread.start();
    }

    private static HttpsServer createHttpsServer(InetSocketAddress address) throws IOException {
        try {
            // Check if PEM files exist in ssl/ folder
            File certFile = new File("ssl/cert.pem");
            File keyFile = new File("ssl/key.pem");
            
            File keystoreFile = new File(sslKeystorePath);
            
            if (certFile.exists() && keyFile.exists()) {
                System.out.println("Found PEM certificates in ssl/ folder, converting to keystore...");
                convertPemToKeystore();
            } else if (!keystoreFile.exists()) {
                System.out.println("No certificates found, generating self-signed certificate...");
                generateSelfSignedCertificate();
            }
            
            HttpsServer httpsServer = HttpsServer.create(address, 0);
            
            // Load keystore
            KeyStore keyStore = KeyStore.getInstance("JKS");
            try (FileInputStream fis = new FileInputStream(sslKeystorePath)) {
                keyStore.load(fis, sslKeystorePassword.toCharArray());
            }
            
            // Setup key manager factory
            KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
            kmf.init(keyStore, sslKeystorePassword.toCharArray());
            
            // Setup trust manager factory
            TrustManagerFactory tmf = TrustManagerFactory.getInstance("SunX509");
            tmf.init(keyStore);
            
            // Setup SSL context
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
            
            httpsServer.setHttpsConfigurator(new HttpsConfigurator(sslContext));
            
            System.out.println("SSL enabled with keystore: " + sslKeystorePath);
            return httpsServer;
        } catch (Exception e) {
            System.err.println("Failed to setup SSL: " + e.getMessage());
            System.err.println("Falling back to HTTP");
            throw new IOException("SSL setup failed", e);
        }
    }

    private static void convertPemToKeystore() {
        try {
            System.out.println("Converting PEM certificates to keystore...");
            
            // Step 1: Convert PEM to PKCS12
            String[] pkcs12Command = {
                "openssl", "pkcs12", "-export",
                "-in", "ssl/cert.pem",
                "-inkey", "ssl/key.pem",
                "-out", "ssl/keystore.p12",
                "-name", "server",
                "-password", "pass:" + sslKeystorePassword
            };
            
            ProcessBuilder pb1 = new ProcessBuilder(pkcs12Command);
            pb1.redirectErrorStream(true);
            Process process1 = pb1.start();
            
            BufferedReader reader1 = new BufferedReader(new InputStreamReader(process1.getInputStream()));
            String line;
            while ((line = reader1.readLine()) != null) {
                System.out.println(line);
            }
            
            int exitCode1 = process1.waitFor();
            if (exitCode1 != 0) {
                System.err.println("Failed to convert PEM to PKCS12, exit code: " + exitCode1);
                return;
            }
            
            // Step 2: Convert PKCS12 to JKS
            String[] jksCommand = {
                "keytool",
                "-importkeystore",
                "-srckeystore", "ssl/keystore.p12",
                "-srcstoretype", "PKCS12",
                "-srcstorepass", sslKeystorePassword,
                "-destkeystore", sslKeystorePath,
                "-deststoretype", "JKS",
                "-deststorepass", sslKeystorePassword,
                "-noprompt"
            };
            
            ProcessBuilder pb2 = new ProcessBuilder(jksCommand);
            pb2.redirectErrorStream(true);
            Process process2 = pb2.start();
            
            BufferedReader reader2 = new BufferedReader(new InputStreamReader(process2.getInputStream()));
            while ((line = reader2.readLine()) != null) {
                System.out.println(line);
            }
            
            int exitCode2 = process2.waitFor();
            if (exitCode2 == 0) {
                System.out.println("Successfully converted PEM certificates to keystore!");
                // Clean up temporary PKCS12 file
                new File("ssl/keystore.p12").delete();
            } else {
                System.err.println("Failed to convert PKCS12 to JKS, exit code: " + exitCode2);
            }
        } catch (Exception e) {
            System.err.println("Error converting PEM to keystore: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void generateSelfSignedCertificate() {
        try {
            // Use keytool to generate self-signed certificate
            String[] command = {
                "keytool",
                "-genkeypair",
                "-alias", "server",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "365",
                "-keystore", sslKeystorePath,
                "-storepass", sslKeystorePassword,
                "-keypass", sslKeystorePassword,
                "-dname", "CN=localhost, OU=SimpleWebServer, O=Auto-Generated, L=Unknown, ST=Unknown, C=US"
            };
            
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            // Read output
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
            }
            
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                System.out.println("Self-signed certificate generated successfully: " + sslKeystorePath);
                System.out.println("WARNING: Self-signed certificates are not trusted by browsers!");
            } else {
                System.err.println("Failed to generate certificate, exit code: " + exitCode);
            }
        } catch (Exception e) {
            System.err.println("Error generating self-signed certificate: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void loadConfig() {
        File configFile = new File(CONFIG_FILE);
        
        if (!configFile.exists()) {
            createDefaultConfig();
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(configFile))) {
            String line;
            String currentServerName = null;
            String currentRoot = null;
            boolean inServerBlock = false;
            
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                
                // Skip comments and empty lines
                if (line.isEmpty() || line.startsWith("#")) continue;
                
                // Parse listen directive
                if (line.startsWith("listen ")) {
                    PORT = Integer.parseInt(line.substring(7).replace(";", "").trim());
                }
                
                // Parse host directive
                else if (line.startsWith("host ")) {
                    HOST = line.substring(5).replace(";", "").trim();
                }
                
                // Parse SSL directives
                else if (line.startsWith("ssl ")) {
                    String value = line.substring(4).replace(";", "").trim();
                    sslEnabled = value.equals("on") || value.equals("true");
                }
                else if (line.startsWith("ssl_keystore ")) {
                    sslKeystorePath = line.substring(13).replace(";", "").trim();
                }
                else if (line.startsWith("ssl_keystore_password ")) {
                    sslKeystorePassword = line.substring(22).replace(";", "").trim();
                }
                
                // Parse server block start
                else if (line.equals("server {")) {
                    inServerBlock = true;
                    currentServerName = null;
                    currentRoot = null;
                }
                
                // Parse server block end
                else if (line.equals("}") && inServerBlock) {
                    if (currentServerName != null && currentRoot != null) {
                        // Support multiple server names
                        String[] names = currentServerName.split("\\s+");
                        for (String name : names) {
                            vhosts.put(name.toLowerCase(), currentRoot);
                        }
                        
                        // Create folder if doesn't exist
                        Path vhostPath = Paths.get(currentRoot);
                        if (!Files.exists(vhostPath)) {
                            Files.createDirectories(vhostPath);
                            System.out.println("Created vhost folder: " + currentRoot);
                        }
                    } else if (currentRoot != null && currentServerName == null) {
                        // Default server block
                        defaultRoot = currentRoot;
                    }
                    inServerBlock = false;
                }
                
                // Parse server_name directive
                else if (inServerBlock && line.startsWith("server_name ")) {
                    currentServerName = line.substring(12).replace(";", "").trim();
                }
                
                // Parse root directive
                else if (inServerBlock && line.startsWith("root ")) {
                    currentRoot = line.substring(5).replace(";", "").trim();
                }
            }
            
            System.out.println("Loaded config: host=" + HOST + ", port=" + PORT);
        } catch (Exception e) {
            System.out.println("Error loading config, using defaults: " + e.getMessage());
        }
    }

    private static void createDefaultConfig() {
        try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
            writer.write("# Simple Web Server Configuration\n\n");
            writer.write("listen 8080;\n");
            writer.write("host 0.0.0.0;\n\n");
            writer.write("# SSL Configuration (optional)\n");
            writer.write("# ssl on;\n");
            writer.write("# ssl_keystore /path/to/keystore.jks;\n");
            writer.write("# ssl_keystore_password changeit;\n\n");
            writer.write("# Default server\n");
            writer.write("server {\n");
            writer.write("    root public;\n");
            writer.write("}\n\n");
            writer.write("# Virtual hosts\n");
            writer.write("# server {\n");
            writer.write("#     server_name example.com;\n");
            writer.write("#     root sites/example.com;\n");
            writer.write("# }\n");
            System.out.println("Created default config file: " + CONFIG_FILE);
        } catch (IOException e) {
            System.out.println("Could not create config file: " + e.getMessage());
        }
    }

    static class FileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // Only allow GET and HEAD methods
            String method = exchange.getRequestMethod();
            if (!method.equals("GET") && !method.equals("HEAD")) {
                sendError(exchange, 405, "Method Not Allowed");
                System.out.println("405 METHOD NOT ALLOWED: " + method);
                return;
            }
            
            String path = exchange.getRequestURI().getPath();
            
            // Decode URL to prevent path traversal attacks
            try {
                path = URLDecoder.decode(path, StandardCharsets.UTF_8.name());
            } catch (Exception e) {
                sendError(exchange, 400, "Bad Request");
                return;
            }
            
            // Get Host header to determine which vhost to use
            String hostHeader = exchange.getRequestHeaders().getFirst("Host");
            String domain = hostHeader != null ? hostHeader.split(":")[0].toLowerCase() : "";
            
            // Determine base directory
            String baseDir = defaultRoot;
            if (vhosts.containsKey(domain)) {
                baseDir = vhosts.get(domain);
                System.out.println("Using vhost: " + domain + " -> " + baseDir);
            }
            
            // If request to root, serve index.html
            if (path.equals("/")) {
                path = "/index.html";
            }
            
            // Normalize path and check for path traversal
            try {
                Path basePath = Paths.get(baseDir).toAbsolutePath().normalize();
                Path requestedPath = basePath.resolve(path.substring(1)).normalize();
                
                // Security check: ensure requested path is within base directory
                if (!requestedPath.startsWith(basePath)) {
                    System.out.println("403 FORBIDDEN (path traversal attempt): " + path);
                    sendError(exchange, 403, "Forbidden");
                    return;
                }
                
                File file = requestedPath.toFile();

            // Check if file exists and is not a directory
            if (file.exists() && file.isFile()) {
                // Determine Content-Type
                String contentType = getContentType(file.getName());
                
                byte[] fileContent = Files.readAllBytes(file.toPath());
                
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.sendResponseHeaders(200, fileContent.length);
                
                OutputStream os = exchange.getResponseBody();
                os.write(fileContent);
                os.close();
                
                System.out.println("200 OK: " + path);
            } else {
                // File not found
                sendError(exchange, 404, "File not found");
                System.out.println("404 NOT FOUND: " + path);
            }
            } catch (Exception e) {
                sendError(exchange, 500, "Internal Server Error");
                e.printStackTrace();
            }
        }
        
        private void sendError(HttpExchange exchange, int code, String message) throws IOException {
            exchange.sendResponseHeaders(code, message.length());
            OutputStream os = exchange.getResponseBody();
            os.write(message.getBytes());
            os.close();
        }

        private String getContentType(String fileName) {
            if (fileName.endsWith(".html")) return "text/html; charset=utf-8";
            if (fileName.endsWith(".css")) return "text/css";
            if (fileName.endsWith(".js")) return "application/javascript";
            if (fileName.endsWith(".json")) return "application/json";
            if (fileName.endsWith(".png")) return "image/png";
            if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) return "image/jpeg";
            if (fileName.endsWith(".gif")) return "image/gif";
            if (fileName.endsWith(".svg")) return "image/svg+xml";
            return "text/plain";
        }
    }
}
