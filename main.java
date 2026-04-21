import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Single-file Bloomberg-ish degen terminal (Swing + JSON-RPC). */
public class WallSTDegens {

    // Entry point
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
            }
            new App().start();
        });
    }

    // App shell
    static final class App {
        private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "wsd-sched-" + UUID.randomUUID());
            t.setDaemon(true);
            return t;
        });
        private final ExecutorService ioPool = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "wsd-io-" + UUID.randomUUID());
            t.setDaemon(true);
            return t;
        });

        private JFrame frame;
        private TerminalPanel terminal;
        private MarketEngine market;
        private StateStore store;
        private CommandRouter router;
        private RpcClient rpc;
        private final AtomicBoolean running = new AtomicBoolean(false);

        void start() {
            if (!running.compareAndSet(false, true)) return;

            store = new StateStore(Paths.appHome().resolve("wallstdegens.state.json"));
            AppState state = store.loadOrDefault();

            rpc = new RpcClient(state.rpcEndpoint);
            market = new MarketEngine(scheduler, state.seed);
            terminal = new TerminalPanel();
            router = new CommandRouter(terminal, market, store, rpc, ioPool);

            frame = new JFrame("WallSTDegens — Terminal");
            frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            frame.addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent e) {
                    shutdown();
                }
            });
            frame.setLayout(new BorderLayout());
            frame.setMinimumSize(new Dimension(1040, 720));
            frame.getContentPane().setBackground(Theme.BG0);

            JPanel root = new JPanel(new BorderLayout());
