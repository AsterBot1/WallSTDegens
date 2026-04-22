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
            root.setBackground(Theme.BG0);
            root.setBorder(new EmptyBorder(10, 10, 10, 10));

            TopBar top = new TopBar(state, terminal, router, market, store, rpc);
            root.add(top, BorderLayout.NORTH);

            JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
            split.setResizeWeight(0.72);
            split.setBorder(null);
            split.setDividerSize(8);
            split.setBackground(Theme.BG0);

            JPanel left = new JPanel(new BorderLayout());
            left.setBackground(Theme.BG0);
            left.setBorder(BorderFactory.createLineBorder(Theme.BG2));
            left.add(terminal, BorderLayout.CENTER);

            RightDock dock = new RightDock(market, terminal, router, state);
            split.setLeftComponent(left);
            split.setRightComponent(dock);

            root.add(split, BorderLayout.CENTER);

            StatusBar statusBar = new StatusBar(state, market, rpc);
            root.add(statusBar, BorderLayout.SOUTH);

            frame.setContentPane(root);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);

            terminal.printBanner(Banner.compose(state));
            router.bootstrap();

            market.setListener(new MarketListener() {
                @Override public void onQuote(MarketQuote q) {
                    dock.onQuote(q);
                    statusBar.onQuote(q);
                }
                @Override public void onPrint(MarketPrint p) {
                    dock.onPrint(p);
                    terminal.onPrint(p);
                }
                @Override public void onSignal(MarketSignal s) {
                    dock.onSignal(s);
                    terminal.onSignal(s);
                }
            });
            market.start();

            terminal.setOnCommand(line -> router.handle(line));

            terminal.installKeymap(frame.getRootPane());

            scheduler.scheduleAtFixedRate(() -> SwingUtilities.invokeLater(statusBar::tick), 250, 250, TimeUnit.MILLISECONDS);
        }

        private void shutdown() {
            if (!running.compareAndSet(true, false)) return;
            terminal.println("");
            terminal.println(Ansi.dim("closing... writing state, draining streams"));

            try {
                store.save(router.captureState());
            } catch (Exception e) {
                terminal.println(Ansi.red("state save failed: ") + e.getMessage());
            }

            try { market.stop(); } catch (Exception ignored) {}
            try { scheduler.shutdownNow(); } catch (Exception ignored) {}
            try { ioPool.shutdownNow(); } catch (Exception ignored) {}
            try { frame.dispose(); } catch (Exception ignored) {}
        }
    }

    // Banner
    static final class Banner {
        static String compose(AppState state) {
            StringBuilder sb = new StringBuilder();
            sb.append(Ansi.bold("WALLSTDEGENS")).append("  ")
              .append(Ansi.dim("v")).append(Ansi.dim(state.version)).append("  ")
              .append(Ansi.dim("pid:")).append(Ansi.dim(String.valueOf(ProcessHandle.current().pid()))).append("  ")
              .append(Ansi.dim("jvm:")).append(Ansi.dim(System.getProperty("java.version"))).append("\n");

            sb.append(Ansi.dim("hotkeys: "))
              .append(Ansi.cyan("Ctrl+K")).append(Ansi.dim(" command  "))
              .append(Ansi.cyan("Ctrl+L")).append(Ansi.dim(" clear  "))
              .append(Ansi.cyan("Ctrl+W")).append(Ansi.dim(" watch  "))
              .append(Ansi.cyan("Ctrl+E")).append(Ansi.dim(" export  "))
              .append(Ansi.cyan("Ctrl+R")).append(Ansi.dim(" rpc  "))
              .append(Ansi.cyan("Ctrl+Q")).append(Ansi.dim(" quit"))
              .append("\n");

            sb.append(Ansi.dim("seed: ")).append(Ansi.yellow(Long.toHexString(state.seed))).append("   ")
              .append(Ansi.dim("rpc: ")).append(Ansi.yellow(state.rpcEndpoint)).append("\n");

            sb.append(Ansi.dim("type ")).append(Ansi.cyan("help")).append(Ansi.dim(" to see command map. "))
              .append(Ansi.dim("style: 'AI finance terminal like bloomberg for degens'")).append("\n");
            return sb.toString();
        }
    }

    // Theme
    static final class Theme {
        static final Color BG0 = new Color(10, 12, 14);
        static final Color BG1 = new Color(16, 18, 22);
        static final Color BG2 = new Color(28, 33, 40);
        static final Color FG0 = new Color(218, 224, 230);
        static final Color FG1 = new Color(167, 175, 183);
        static final Color ACC = new Color(255, 170, 0);
        static final Color ACC2 = new Color(0, 202, 255);
        static final Color GOOD = new Color(68, 230, 140);
        static final Color BAD = new Color(255, 88, 88);
        static final Color WARN = new Color(255, 200, 84);
        static final Font MONO_12 = new Font(Font.MONOSPACED, Font.PLAIN, 12);
        static final Font MONO_13 = new Font(Font.MONOSPACED, Font.PLAIN, 13);
        static final Font MONO_14 = new Font(Font.MONOSPACED, Font.PLAIN, 14);
        static final Font MONO_16 = new Font(Font.MONOSPACED, Font.PLAIN, 16);
    }

    // Terminal + log model
    static final class TerminalPanel extends JPanel {
        private final JTextPane text = new JTextPane();
        private final JScrollPane scroll;
        private final JTextField input = new JTextField();
        private Consumer<String> onCommand = s -> {};
        private final TerminalDoc doc = new TerminalDoc();

        private final Deque<HistoryEntry> history = new ArrayDeque<>();
        private int historyCursor = 0;

        private volatile MarketPrint lastPrint;
        private volatile MarketSignal lastSignal;

        TerminalPanel() {
            super(new BorderLayout());
            setBackground(Theme.BG0);
            setBorder(new EmptyBorder(10, 10, 10, 10));

            text.setEditable(false);
            text.setBackground(Theme.BG0);
            text.setForeground(Theme.FG0);
            text.setFont(Theme.MONO_13);
            text.setCaretColor(Theme.ACC2);
            text.setDocument(doc.swingDocument());
            text.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);

            scroll = new JScrollPane(text);
            scroll.setBorder(null);
            scroll.getViewport().setBackground(Theme.BG0);
            scroll.getVerticalScrollBar().setUI(new MinimalScrollUI());

            JPanel inputRow = new JPanel(new BorderLayout());
            inputRow.setBackground(Theme.BG0);
            inputRow.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BG2));

            JLabel prompt = new JLabel("  > ");
            prompt.setForeground(Theme.ACC);
            prompt.setBackground(Theme.BG0);
            prompt.setFont(Theme.MONO_14);
            prompt.setOpaque(true);

            input.setBackground(Theme.BG0);
            input.setForeground(Theme.FG0);
            input.setCaretColor(Theme.ACC2);
            input.setBorder(new EmptyBorder(8, 6, 8, 6));
            input.setFont(Theme.MONO_14);

            input.addActionListener(e -> {
                String line = input.getText();
                if (line == null) line = "";
                line = line.trim();
                if (line.isEmpty()) return;
                remember(line);
                input.setText("");
                println(Ansi.dim("> ") + line);
                onCommand.accept(line);
            });

            input.addKeyListener(new KeyAdapter() {
                @Override public void keyPressed(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_UP) {
                        e.consume();
                        String s = historyUp();
                        if (s != null) input.setText(s);
                    } else if (e.getKeyCode() == KeyEvent.VK_DOWN) {
                        e.consume();
                        String s = historyDown();
                        input.setText(s == null ? "" : s);
                    } else if (e.getKeyCode() == KeyEvent.VK_TAB) {
                        e.consume();
                        autocomplete();
                    }
                }
            });

            inputRow.add(prompt, BorderLayout.WEST);
            inputRow.add(input, BorderLayout.CENTER);

            add(scroll, BorderLayout.CENTER);
            add(inputRow, BorderLayout.SOUTH);
        }

        void setOnCommand(Consumer<String> handler) { this.onCommand = handler == null ? s -> {} : handler; }

        void focusInput() { input.requestFocusInWindow(); }

        void clear() { doc.clear(); }

        void println(String s) {
            doc.appendLine(s);
            SwingUtilities.invokeLater(() -> {
                JScrollBar v = scroll.getVerticalScrollBar();
                v.setValue(v.getMaximum());
            });
        }

        void printBanner(String s) {
            for (String line : s.split("\n", -1)) {
                doc.appendLine(line);
            }
        }

        void onPrint(MarketPrint p) { lastPrint = p; }
        void onSignal(MarketSignal s) { lastSignal = s; }

        void notifyInfo(String msg) { println(Ansi.cyan("[i] ") + msg); }
        void notifyWarn(String msg) { println(Ansi.yellow("[!] ") + msg); }
        void notifyError(String msg) { println(Ansi.red("[x] ") + msg); }

        void installKeymap(JRootPane root) {
            InputMap im = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
            ActionMap am = root.getActionMap();

            im.put(KeyStroke.getKeyStroke(KeyEvent.VK_K, InputEvent.CTRL_DOWN_MASK), "cmd");
            am.put("cmd", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { focusInput(); input.setText(""); } });

            im.put(KeyStroke.getKeyStroke(KeyEvent.VK_L, InputEvent.CTRL_DOWN_MASK), "clear");
            am.put("clear", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { clear(); } });

            im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Q, InputEvent.CTRL_DOWN_MASK), "quit");
            am.put("quit", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { Window w = SwingUtilities.getWindowAncestor(TerminalPanel.this); if (w != null) w.dispatchEvent(new WindowEvent(w, WindowEvent.WINDOW_CLOSING)); } });

            im.put(KeyStroke.getKeyStroke(KeyEvent.VK_E, InputEvent.CTRL_DOWN_MASK), "export");
            am.put("export", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { println(Ansi.dim("hint: ") + "export log"); focusInput(); input.setText("export log"); } });

            im.put(KeyStroke.getKeyStroke(KeyEvent.VK_W, InputEvent.CTRL_DOWN_MASK), "watch");
            am.put("watch", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { println(Ansi.dim("hint: ") + "watch"); focusInput(); input.setText("watch"); } });

            im.put(KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK), "rpc");
            am.put("rpc", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { println(Ansi.dim("hint: ") + "rpc set https://..."); focusInput(); input.setText("rpc "); } });
        }

        private void remember(String line) {
            history.addFirst(new HistoryEntry(System.currentTimeMillis(), line));
            while (history.size() > 250) history.removeLast();
            historyCursor = 0;
        }

        private String historyUp() {
            if (history.isEmpty()) return null;
            historyCursor = Math.min(historyCursor + 1, history.size());
            int idx = historyCursor - 1;
            int i = 0;
            for (HistoryEntry he : history) {
                if (i == idx) return he.line;
                i++;
            }
            return null;
        }

        private String historyDown() {
            if (history.isEmpty()) return null;
            historyCursor = Math.max(historyCursor - 1, 0);
            if (historyCursor == 0) return "";
            int idx = historyCursor - 1;
            int i = 0;
            for (HistoryEntry he : history) {
                if (i == idx) return he.line;
                i++;
            }
            return "";
        }

        private void autocomplete() {
            String t = input.getText();
            if (t == null) t = "";
            String s = t.trim();
            if (s.isEmpty()) return;
            List<String> options = Arrays.asList(
                    "help", "pages", "watch", "watch add ", "watch rm ", "watch clear",
                    "tape", "signals", "quote", "snapshot", "export log", "export watch",
                    "rpc", "rpc set ", "rpc ping", "rpc call ", "about", "theme", "seed", "copy "
            );
            for (String opt : options) {
                if (opt.startsWith(s)) {
                    input.setText(opt);
                    input.setCaretPosition(opt.length());
                    return;
                }
            }
        }
    }

    static final class HistoryEntry {
        final long ts;
        final String line;
        HistoryEntry(long ts, String line) { this.ts = ts; this.line = line; }
    }

    // Minimal "ANSI" coloration using pseudo tokens
    static final class Ansi {
        static String dim(String s) { return "\u241B[dim]" + s + "\u241B[/]"; }
        static String bold(String s) { return "\u241B[bold]" + s + "\u241B[/]"; }
        static String cyan(String s) { return "\u241B[cyan]" + s + "\u241B[/]"; }
        static String yellow(String s) { return "\u241B[y]" + s + "\u241B[/]"; }
        static String red(String s) { return "\u241B[r]" + s + "\u241B[/]"; }
        static String green(String s) { return "\u241B[g]" + s + "\u241B[/]"; }
        static String orange(String s) { return "\u241B[o]" + s + "\u241B[/]"; }
    }

    static final class TerminalDoc {
        private final DefaultStyledDocumentWithTokens doc = new DefaultStyledDocumentWithTokens();

        Document swingDocument() { return doc; }
        void clear() { doc.clearAll(); }

        void appendLine(String raw) {
            doc.append(raw);
