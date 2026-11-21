package ai.aeiou.edgelab.alarm;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.PrintStream;

/** Shows one line to Thure: a Windows tray pop-up, or the console when there is no tray. */
public interface Notifier extends AutoCloseable {

    void show(String line);

    @Override
    default void close() {}

    /**
     * Tray pop-up when the desktop supports it. Without a tray it does nothing: the alarm
     * prints every alert line to the console anyway.
     */
    static Notifier best(PrintStream console) {
        Notifier text = line -> { };
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
            console.println("(no tray pop-ups on this machine; alerts show in this console only)");
            return text;
        }
        try {
            TrayIcon icon = new TrayIcon(dot(), "edgelab change alarm");
            icon.setImageAutoSize(true);
            SystemTray.getSystemTray().add(icon);
            return new Notifier() {
                @Override
                public void show(String line) {
                    icon.displayMessage("edgelab alarm", line, TrayIcon.MessageType.WARNING);
                }

                @Override
                public void close() {
                    SystemTray.getSystemTray().remove(icon);
                }
            };
        } catch (Exception | LinkageError e) {
            console.println("(no tray pop-ups: " + e.getMessage() + "; using the console)");
            return text;
        }
    }

    private static BufferedImage dot() {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0xE0, 0x5A, 0x1B));
        g.fillOval(1, 1, 14, 14);
        g.dispose();
        return img;
    }
}
