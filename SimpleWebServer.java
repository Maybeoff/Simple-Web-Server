import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

public class SimpleWebServer {
    private static final String CONFIG_FILE = "server.conf";
    private static int PORT = 8080;
    private static String HOST = "0.0.0.0";
    private static String defaultRoot = "public";
    private static Map<String, String> vhosts = new HashMap<>();

    public static void main(String[] args) throws IOException {
        // Load configuration
        loadConfig();

        // Create public folder if it doesn't exist
        Path publicPath = Paths.get(defaultRoot);
        if (!Files.exists(publicPath)) {
            Files.createDirectory(publicPath);
            System.out.println("Created folder: " + defaultRoot);
        }

        // Create HTTP server
        InetSocketAddress address = HOST.equals("0.0.0.0") 
            ? new InetSocketAddress(PORT) 
            : new InetSocketAddress(HOST, PORT);
        HttpServer server = HttpServer.create(address, 0);
        server.createContext("/", new FileHandler());
        server.setExecutor(null);
        server.start();

        System.out.println("Server started at http://" + HOST + ":" + PORT);
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
            String path = exchange.getRequestURI().getPath();
            
            // Get Host header to determine which vhost to use
            String hostHeader = exchange.getRequestHeaders().getFirst("Host");
            String domain = hostHeader != null ? hostHeader.split(":")[0].toLowerCase() : "";
            
            // Determine base directory
            String baseDir = defaultRoot;
            if (vhosts.containsKey(domain)) {
                baseDir = vhosts.get(domain);
                System.out.println("Using vhost: " + domain + " -> " + baseDir);
            }
            
            // Если запрос к корню, отдаем index.html
            if (path.equals("/")) {
                path = "/index.html";
            }

            File file = new File(baseDir + path);

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
                String response = "404 - File not found";
                exchange.sendResponseHeaders(404, response.length());
                
                OutputStream os = exchange.getResponseBody();
                os.write(response.getBytes());
                os.close();
                
                System.out.println("404 NOT FOUND: " + path);
            }
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
