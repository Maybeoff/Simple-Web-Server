public class Launcher {
    public static void main(String[] args) {
        try {
            // Просто запускаем SimpleWebServer напрямую
            // JVM уже запущена с параметрами, мы ничего не можем изменить
            SimpleWebServer.main(args);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}
