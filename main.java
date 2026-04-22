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
            doc.append("\n");
        }
    }

    static final class DefaultStyledDocumentWithTokens extends javax.swing.text.DefaultStyledDocument {
        private final javax.swing.text.SimpleAttributeSet base = new javax.swing.text.SimpleAttributeSet();
        private final Map<String, javax.swing.text.SimpleAttributeSet> styles = new HashMap<>();

        DefaultStyledDocumentWithTokens() {
            javax.swing.text.StyleConstants.setFontFamily(base, Font.MONOSPACED);
            javax.swing.text.StyleConstants.setFontSize(base, 13);
            javax.swing.text.StyleConstants.setForeground(base, Theme.FG0);
            styles.put("dim", style(Theme.FG1, false));
            styles.put("bold", style(Theme.FG0, true));
            styles.put("cyan", style(Theme.ACC2, false));
            styles.put("y", style(Theme.WARN, false));
            styles.put("r", style(Theme.BAD, false));
            styles.put("g", style(Theme.GOOD, false));
            styles.put("o", style(Theme.ACC, false));
        }

        private javax.swing.text.SimpleAttributeSet style(Color c, boolean bold) {
            javax.swing.text.SimpleAttributeSet s = new javax.swing.text.SimpleAttributeSet();
            javax.swing.text.StyleConstants.setFontFamily(s, Font.MONOSPACED);
            javax.swing.text.StyleConstants.setFontSize(s, 13);
            javax.swing.text.StyleConstants.setForeground(s, c);
            javax.swing.text.StyleConstants.setBold(s, bold);
            return s;
        }

        void clearAll() {
            try {
                remove(0, getLength());
            } catch (BadLocationException ignored) {
            }
        }

        void append(String raw) {
            if (raw == null) raw = "";
            int i = 0;
            String current = null;
            StringBuilder buf = new StringBuilder();
            while (i < raw.length()) {
                int open = raw.indexOf("\u241B[", i);
                if (open < 0) {
                    buf.append(raw.substring(i));
                    break;
                }
                buf.append(raw, i, open);
                int close = raw.indexOf("]", open);
                if (close < 0) {
                    buf.append(raw.substring(open));
                    break;
                }
                flush(buf, current);
                String tag = raw.substring(open + 3, close);
                i = close + 1;
                if (tag.startsWith("/")) current = null;
                else current = tag;
            }
            flush(buf, current);
        }

        private void flush(StringBuilder buf, String styleKey) {
            if (buf.length() == 0) return;
            javax.swing.text.SimpleAttributeSet s = styleKey == null ? base : styles.getOrDefault(styleKey, base);
            try {
                insertString(getLength(), buf.toString(), s);
            } catch (BadLocationException ignored) {
            }
            buf.setLength(0);
        }
    }

    // Minimal scrollbar UI
    static final class MinimalScrollUI extends javax.swing.plaf.basic.BasicScrollBarUI {
        @Override protected void configureScrollBarColors() {
            this.thumbColor = Theme.BG2;
            this.trackColor = Theme.BG0;
        }
        @Override protected JButton createDecreaseButton(int orientation) { return zero(); }
        @Override protected JButton createIncreaseButton(int orientation) { return zero(); }
        private JButton zero() {
            JButton b = new JButton();
            b.setPreferredSize(new Dimension(0, 0));
            b.setMinimumSize(new Dimension(0, 0));
            b.setMaximumSize(new Dimension(0, 0));
            return b;
        }
    }

    // Top bar + status
    static final class TopBar extends JPanel {
        TopBar(AppState state, TerminalPanel terminal, CommandRouter router, MarketEngine market, StateStore store, RpcClient rpc) {
            super(new BorderLayout());
            setBackground(Theme.BG0);
            setBorder(new EmptyBorder(0, 0, 8, 0));

            JLabel left = new JLabel(" WALLSTDEGENS / AI FINANCE TERMINAL ");
            left.setForeground(Theme.ACC);
            left.setFont(Theme.MONO_14);

            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            right.setBackground(Theme.BG0);

            JButton help = miniBtn("HELP");
            help.addActionListener(e -> router.handle("help"));

            JButton pages = miniBtn("PAGES");
            pages.addActionListener(e -> router.handle("pages"));

            JButton watch = miniBtn("WATCH");
            watch.addActionListener(e -> router.handle("watch"));

            JButton rpcBtn = miniBtn("RPC");
            rpcBtn.addActionListener(e -> router.handle("rpc"));

            JButton export = miniBtn("EXPORT");
            export.addActionListener(e -> router.handle("export log"));

            JButton panic = miniBtn("PANIC");
            panic.setForeground(Theme.BAD);
            panic.addActionListener(e -> {
                market.panic();
                terminal.notifyWarn("panic toggled: vol spike + micro-liq mode");
            });

            right.add(help);
            right.add(pages);
            right.add(watch);
            right.add(rpcBtn);
            right.add(export);
            right.add(panic);

            add(left, BorderLayout.WEST);
            add(right, BorderLayout.EAST);
        }

        private JButton miniBtn(String text) {
            JButton b = new JButton(text);
            b.setFont(Theme.MONO_12);
            b.setFocusPainted(false);
            b.setBackground(Theme.BG1);
            b.setForeground(Theme.FG0);
            b.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Theme.BG2),
                    new EmptyBorder(6, 10, 6, 10)
            ));
            b.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { b.setBackground(Theme.BG2); }
                @Override public void mouseExited(MouseEvent e) { b.setBackground(Theme.BG1); }
            });
            return b;
        }
    }

    static final class StatusBar extends JPanel {
        private final JLabel left = new JLabel();
        private final JLabel right = new JLabel();
        private final AppState state;
        private final MarketEngine market;
        private final RpcClient rpc;
        private volatile MarketQuote last;
        private long start = System.currentTimeMillis();

        StatusBar(AppState state, MarketEngine market, RpcClient rpc) {
            super(new BorderLayout());
            this.state = state;
            this.market = market;
            this.rpc = rpc;
            setBackground(Theme.BG0);
            setBorder(new EmptyBorder(8, 0, 0, 0));
            left.setFont(Theme.MONO_12);
            left.setForeground(Theme.FG1);
            right.setFont(Theme.MONO_12);
            right.setForeground(Theme.FG1);
            add(left, BorderLayout.WEST);
            add(right, BorderLayout.EAST);
            tick();
        }

        void onQuote(MarketQuote q) { last = q; }

        void tick() {
            long up = System.currentTimeMillis() - start;
            String uptime = Format.dur(up);
            String mode = market.modeLabel();
            String heap = Format.mem();
            String rpcEp = rpc.endpoint;
            left.setText("  " + "uptime " + uptime + "   " + "mode " + mode + "   " + "heap " + heap);
            MarketQuote q = last;
            if (q == null) {
                right.setText("rpc " + rpcEp + "   seed " + Long.toHexString(state.seed) + "  ");
            } else {
                String px = Format.fmtPx(q.mid, 4);
                String ch = Format.fmtSigned(q.changeBps / 100.0, 2) + "bp";
                right.setText(q.symbol + " " + px + "   " + ch + "   rpc " + rpcEp + "  ");
            }
        }
    }

    // Commands & router
    static final class CommandRouter {
        private final TerminalPanel terminal;
        private final MarketEngine market;
        private final StateStore store;
        private final RpcClient rpc;
        private final ExecutorService ioPool;

        private final Watchlist watch = new Watchlist();
        private String page = "HOME";
        private boolean showTape = true;
        private boolean showSignals = true;

        CommandRouter(TerminalPanel terminal, MarketEngine market, StateStore store, RpcClient rpc, ExecutorService ioPool) {
            this.terminal = terminal;
            this.market = market;
            this.store = store;
            this.rpc = rpc;
            this.ioPool = ioPool;
        }

        void bootstrap() {
            AppState st = store.loadOrDefault();
            watch.setAll(st.watchlist);
            if (st.page != null && !st.page.isBlank()) page = st.page;
            showTape = st.showTape;
            showSignals = st.showSignals;
            terminal.println(Ansi.dim("restored: ") + "page=" + page + " watch=" + watch.size() + " rpc=" + st.rpcEndpoint);
            handle("pages");
            handle("watch");
        }

        AppState captureState() {
            AppState st = store.loadOrDefault();
            st.watchlist = watch.asList();
            st.page = page;
            st.showTape = showTape;
            st.showSignals = showSignals;
            st.rpcEndpoint = rpc.endpoint;
            return st;
        }

        void handle(String line) {
            String[] parts = Split.smart(line);
            if (parts.length == 0) return;
            String cmd = parts[0].toLowerCase(Locale.ROOT);

            switch (cmd) {
                case "help": cmdHelp(); break;
                case "pages": cmdPages(); break;
                case "about": cmdAbout(); break;
                case "seed": cmdSeed(); break;
                case "theme": cmdTheme(parts); break;
                case "quote": cmdQuote(parts); break;
                case "tape": cmdTape(parts); break;
                case "signals": cmdSignals(parts); break;
                case "watch": cmdWatch(parts); break;
                case "export": cmdExport(parts); break;
                case "copy": cmdCopy(parts); break;
                case "rpc": cmdRpc(parts); break;
                case "snapshot": cmdSnapshot(parts); break;
                default:
                    terminal.notifyWarn("unknown cmd: " + cmd + " (try 'help')");
            }
        }

        private void cmdHelp() {
            terminal.println("");
            terminal.println(Ansi.bold("COMMAND MAP"));
            terminal.println(Ansi.dim("help") + "                show this map");
            terminal.println(Ansi.dim("pages") + "               show page shortcuts");
            terminal.println(Ansi.dim("quote [SYM]") + "          last quote + derived");
            terminal.println(Ansi.dim("tape [SYM] [N]") + "       show N tape prints (default 16)");
            terminal.println(Ansi.dim("signals [SYM] [N]") + "    show N signals (default 12)");
            terminal.println(Ansi.dim("watch") + "               show watchlist");
            terminal.println(Ansi.dim("watch add SYM") + "       add to watchlist");
            terminal.println(Ansi.dim("watch rm SYM") + "        remove from watchlist");
            terminal.println(Ansi.dim("watch clear") + "         clear watchlist");
            terminal.println(Ansi.dim("export log|watch") + "    export data to files");
            terminal.println(Ansi.dim("copy addr|rpc") + "       copy sample addresses/rpc endpoint");
            terminal.println(Ansi.dim("rpc") + "                 show rpc config");
            terminal.println(Ansi.dim("rpc set URL") + "         set rpc endpoint");
            terminal.println(Ansi.dim("rpc ping") + "            health call to rpc");
            terminal.println(Ansi.dim("snapshot SYM") + "        eth_call placeholder for onchain snapshot");
            terminal.println(Ansi.dim("about") + "               build + environment");
            terminal.println("");
        }

        private void cmdPages() {
            terminal.println("");
            terminal.println(Ansi.bold("PAGES"));
            terminal.println(Ansi.dim("HOME") + "   overview grid / watch / tape / signals");
            terminal.println(Ansi.dim("RISK") + "   vol, spread, funding, OI drift");
            terminal.println(Ansi.dim("FLOW") + "   prints + imbalance, micro-lq modes");
            terminal.println(Ansi.dim("ALPHA") + "  signals + tag heat");
            terminal.println(Ansi.dim("OPS") + "    export + rpc + state paths");
            terminal.println(Ansi.dim("set page <NAME>") + " via command: " + Ansi.cyan("pages set HOME|RISK|FLOW|ALPHA|OPS"));
            terminal.println("");
        }

        private void cmdAbout() {
            terminal.println("");
            terminal.println(Ansi.bold("ABOUT"));
            terminal.println("build " + store.loadOrDefault().version + "  java " + System.getProperty("java.version"));
            terminal.println("os " + System.getProperty("os.name") + " " + System.getProperty("os.version") + "  arch " + System.getProperty("os.arch"));
            terminal.println("vm " + System.getProperty("java.vm.name"));
            terminal.println("pid " + ProcessHandle.current().pid());
            terminal.println("home " + Paths.appHome());
            terminal.println("state " + store.path);
            terminal.println("");
        }

        private void cmdSeed() {
            AppState st = store.loadOrDefault();
            terminal.println("");
            terminal.println(Ansi.bold("SEED"));
            terminal.println("seed 0x" + Long.toHexString(st.seed));
            terminal.println("regen: edit state file (or delete it) and restart.");
            terminal.println("");
        }

        private void cmdTheme(String[] parts) {
            terminal.println(Ansi.dim("theme is fixed: ")) ;
            terminal.println("bg " + Theme.BG0 + "  fg " + Theme.FG0 + "  acc " + Theme.ACC);
        }

        private void cmdQuote(String[] parts) {
            if (parts.length < 2) { terminal.notifyWarn("usage: quote SYM"); return; }
            String sym = parts[1].toUpperCase(Locale.ROOT);
            MarketQuote q = market.quote(sym);
            if (q == null) { terminal.notifyWarn("unknown sym: " + sym); return; }
            terminal.println("");
            terminal.println(Ansi.bold("QUOTE ") + sym);
            terminal.println("mid " + Format.fmtPx(q.mid, 6) + "   spr " + Format.fmtSigned(q.spreadBps / 100.0, 2) + "bp   fnd " + Format.fmtSigned(q.fundingBps / 100.0, 2) + "bp");
            terminal.println("oi  " + Format.compact(q.openInterest) + "   vol1m " + Format.fmt(q.vol1m, 4) + "   drift " + Format.fmtSigned(q.changeBps / 100.0, 2) + "bp");
            terminal.println("time " + Format.ts(q.ts));
            terminal.println("");
        }

        private void cmdTape(String[] parts) {
            String sym = parts.length >= 2 ? parts[1].toUpperCase(Locale.ROOT) : null;
            int n = parts.length >= 3 ? Parse.i(parts[2], 16) : 16;
            n = Math.max(1, Math.min(80, n));
            List<MarketPrint> prints = sym == null ? market.tapeLatest(n) : market.tapeLatest(sym, n);
            terminal.println("");
            terminal.println(Ansi.bold("TAPE ") + (sym == null ? "(all)" : sym));
            for (MarketPrint p : prints) {
                terminal.println(formatPrint(p));
            }
            terminal.println("");
        }

        private void cmdSignals(String[] parts) {
            String sym = parts.length >= 2 ? parts[1].toUpperCase(Locale.ROOT) : null;
            int n = parts.length >= 3 ? Parse.i(parts[2], 12) : 12;
            n = Math.max(1, Math.min(80, n));
            List<MarketSignal> sigs = sym == null ? market.signalsLatest(n) : market.signalsLatest(sym, n);
            terminal.println("");
            terminal.println(Ansi.bold("SIGNALS ") + (sym == null ? "(all)" : sym));
            for (MarketSignal s : sigs) {
                terminal.println(formatSignal(s));
            }
            terminal.println("");
        }

        private void cmdWatch(String[] parts) {
            if (parts.length == 1) {
                terminal.println("");
                terminal.println(Ansi.bold("WATCHLIST"));
                if (watch.size() == 0) terminal.println(Ansi.dim("(empty)"));
                for (String sym : watch.asList()) {
                    MarketQuote q = market.quote(sym);
                    if (q == null) continue;
                    String px = Format.fmtPx(q.mid, 6);
                    String ch = Format.fmtSigned(q.changeBps / 100.0, 2) + "bp";
                    String tone = q.changeBps >= 0 ? Ansi.green(ch) : Ansi.red(ch);
                    terminal.println(String.format(Locale.ROOT, "%-10s  %14s  %12s  spr %7s  oi %10s",
                            sym, px, tone, Format.fmtSigned(q.spreadBps / 100.0, 2) + "bp", Format.compact(q.openInterest)));
                }
                terminal.println("");
                return;
            }
            String sub = parts[1].toLowerCase(Locale.ROOT);
            if (sub.equals("add") && parts.length >= 3) {
                String sym = parts[2].toUpperCase(Locale.ROOT);
                if (!market.known(sym)) { terminal.notifyWarn("unknown sym: " + sym); return; }
                watch.add(sym);
                terminal.notifyInfo("watch + " + sym);
            } else if ((sub.equals("rm") || sub.equals("remove")) && parts.length >= 3) {
                String sym = parts[2].toUpperCase(Locale.ROOT);
                watch.remove(sym);
                terminal.notifyInfo("watch - " + sym);
            } else if (sub.equals("clear")) {
                watch.clear();
                terminal.notifyWarn("watch cleared");
            } else if (sub.equals("set") && parts.length >= 2) {
                terminal.notifyWarn("usage: watch add|rm|clear");
            } else {
                terminal.notifyWarn("usage: watch | watch add SYM | watch rm SYM | watch clear");
            }
        }

        private void cmdExport(String[] parts) {
            if (parts.length < 2) { terminal.notifyWarn("usage: export log|watch"); return; }
            String what = parts[1].toLowerCase(Locale.ROOT);
            if (what.equals("watch")) {
                Path out = Paths.appHome().resolve("export-watch-" + System.currentTimeMillis() + ".txt");
                try {
                    Files.writeString(out, String.join("\n", watch.asList()) + "\n", StandardCharsets.UTF_8);
                    terminal.notifyInfo("exported watch -> " + out);
                } catch (IOException e) {
                    terminal.notifyError("export failed: " + e.getMessage());
                }
            } else if (what.equals("log")) {
                Path out = Paths.appHome().resolve("export-log-" + System.currentTimeMillis() + ".txt");
                try {
                    Files.writeString(out, "(log export is best-effort; copy/paste from UI if needed)\n", StandardCharsets.UTF_8);
                    terminal.notifyInfo("exported log stub -> " + out);
                } catch (IOException e) {
                    terminal.notifyError("export failed: " + e.getMessage());
                }
            } else {
                terminal.notifyWarn("usage: export log|watch");
            }
        }

        private void cmdCopy(String[] parts) {
            if (parts.length < 2) { terminal.notifyWarn("usage: copy addr|rpc"); return; }
            String what = parts[1].toLowerCase(Locale.ROOT);
            if (what.equals("rpc")) {
                clipboard(rpc.endpoint);
                terminal.notifyInfo("copied rpc endpoint");
            } else if (what.equals("addr")) {
                // Fresh sample addresses for UI testing (not used on-chain by this app).
                String sample =
                        "0x059b4C8d1537c8896e00Bf14a50a2802A6Aff6Ca\n" +
                        "0x0D86F7073d27afd28f8b548aF0D37CaBb8CD1504\n" +
                        "0x3457080a92F621A10B04176d4565a75aAab8d837\n" +
                        "0x481725EAB4713BE5913D22731A87a3b852b75530\n" +
                        "0x4A07b28c80325265257d2c49C46BAB12f7B14fC2\n";
                clipboard(sample);
                terminal.notifyInfo("copied sample addresses");
            } else {
                terminal.notifyWarn("usage: copy addr|rpc");
            }
        }

        private void clipboard(String s) {
            Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
            cb.setContents(new StringSelection(s), null);
        }

        private void cmdRpc(String[] parts) {
            if (parts.length == 1) {
                terminal.println("");
                terminal.println(Ansi.bold("RPC"));
                terminal.println("endpoint " + rpc.endpoint);
                terminal.println(Ansi.dim("commands: ") + "rpc set <url> | rpc ping | rpc call <method> <params-json>");
                terminal.println("");
                return;
            }
            String sub = parts[1].toLowerCase(Locale.ROOT);
            if (sub.equals("set") && parts.length >= 3) {
                String url = parts[2];
                rpc.endpoint = url;
                terminal.notifyInfo("rpc -> " + url);
            } else if (sub.equals("ping")) {
                terminal.notifyInfo("rpc ping...");
                ioPool.submit(() -> {
                    try {
                        long ms = rpc.ping();
                        SwingUtilities.invokeLater(() -> terminal.notifyInfo("rpc ok " + ms + "ms"));
                    } catch (Exception e) {
                        SwingUtilities.invokeLater(() -> terminal.notifyError("rpc ping failed: " + e.getMessage()));
                    }
                });
            } else if (sub.equals("call") && parts.length >= 3) {
                String method = parts[2];
                String params = parts.length >= 4 ? parts[3] : "[]";
                terminal.notifyInfo("rpc call " + method + " ...");
                ioPool.submit(() -> {
                    try {
                        String res = rpc.call(method, params);
                        SwingUtilities.invokeLater(() -> {
                            terminal.println("");
                            terminal.println(Ansi.bold("RPC RESULT"));
                            terminal.println(res);
                            terminal.println("");
                        });
                    } catch (Exception e) {
                        SwingUtilities.invokeLater(() -> terminal.notifyError("rpc call failed: " + e.getMessage()));
                    }
                });
            } else {
                terminal.notifyWarn("usage: rpc | rpc set URL | rpc ping | rpc call METHOD PARAMS_JSON");
            }
        }

        private void cmdSnapshot(String[] parts) {
            if (parts.length < 2) { terminal.notifyWarn("usage: snapshot SYM"); return; }
            String sym = parts[1].toUpperCase(Locale.ROOT);
            MarketQuote q = market.quote(sym);
            if (q == null) { terminal.notifyWarn("unknown sym: " + sym); return; }

            terminal.println("");
            terminal.println(Ansi.bold("SNAPSHOT ") + sym);
            terminal.println(Ansi.dim("note: ") + "this is a placeholder eth_call builder; wire it to your contract ABI if desired.");

            String fakeTo = "0x6ba2FaC13d1f942411d2C59B025B9bE43A74d726";
            String data = "0x" + Hex.repeat("ab", 4) + Hex.padLeft(Hex.ofAscii(sym), 64);

            terminal.println("to   " + fakeTo);
            terminal.println("data " + data);
            terminal.println("rpc  " + rpc.endpoint);

            terminal.println(Ansi.dim("try: ") + "rpc call eth_call " + Json.arr(Json.obj("to", fakeTo, "data", data), "latest"));
            terminal.println("");
        }

        private String formatPrint(MarketPrint p) {
            String side = p.side > 0 ? (p.side == 1 ? Ansi.green("BUY ") : Ansi.red("SELL")) : Ansi.dim("----");
            String px = Format.fmtPx(p.px, 6);
            String qty = Format.compact(Math.abs(p.qty));
            String t = Format.ts(p.ts);
            return String.format(Locale.ROOT, "%s  %-10s  %14s  %10s  %s", t, p.symbol, px, qty, side);
        }

        private String formatSignal(MarketSignal s) {
            String t = Format.ts(s.ts);
            String score = Format.fmtSigned(s.score / 100.0, 2);
            String tone = s.score >= 0 ? Ansi.green(score) : Ansi.red(score);
            return String.format(Locale.ROOT, "%s  %-10s  %10s  tag %-10s  %s", t, s.symbol, tone, s.tag, Ansi.dim(s.note));
        }
    }

    // Watchlist
    static final class Watchlist {
        private final LinkedHashSet<String> set = new LinkedHashSet<>();
        void add(String sym) { set.add(sym); }
        void remove(String sym) { set.remove(sym); }
        void clear() { set.clear(); }
        int size() { return set.size(); }
        List<String> asList() { return new ArrayList<>(set); }
        void setAll(List<String> s) { set.clear(); if (s != null) for (String x : s) if (x != null && !x.isBlank()) set.add(x.trim().toUpperCase(Locale.ROOT)); }
        boolean has(String sym) { return set.contains(sym); }
    }

    // Right dock panels
    static final class RightDock extends JPanel {
        private final QuoteTableModel quoteModel;
        private final TapeTableModel tapeModel;
        private final SignalTableModel sigModel;

        RightDock(MarketEngine market, TerminalPanel terminal, CommandRouter router, AppState state) {
            super(new BorderLayout());
            setBackground(Theme.BG0);
            setBorder(BorderFactory.createLineBorder(Theme.BG2));

            JTabbedPane tabs = new JTabbedPane();
            tabs.setFont(Theme.MONO_12);
            tabs.setBackground(Theme.BG0);
            tabs.setForeground(Theme.FG0);
            tabs.setBorder(null);

            quoteModel = new QuoteTableModel(market);
            tapeModel = new TapeTableModel(market);
            sigModel = new SignalTableModel(market);

            tabs.addTab("GRID", wrapTable(new JTable(quoteModel), 5));
            tabs.addTab("TAPE", wrapTable(new JTable(tapeModel), 4));
            tabs.addTab("ALPHA", wrapTable(new JTable(sigModel), 4));

            tabs.addChangeListener(new ChangeListener() {
                @Override public void stateChanged(ChangeEvent e) {
                    terminal.focusInput();
                }
            });

            add(tabs, BorderLayout.CENTER);
        }

        void onQuote(MarketQuote q) { quoteModel.onQuote(q); }
        void onPrint(MarketPrint p) { tapeModel.onPrint(p); }
        void onSignal(MarketSignal s) { sigModel.onSignal(s); }

        private JScrollPane wrapTable(JTable t, int rowsHint) {
            t.setFont(Theme.MONO_12);
            t.setBackground(Theme.BG0);
            t.setForeground(Theme.FG0);
            t.setGridColor(Theme.BG2);
            t.setRowHeight(22);
            t.setFillsViewportHeight(true);
            t.getTableHeader().setFont(Theme.MONO_12);
            t.getTableHeader().setBackground(Theme.BG1);
            t.getTableHeader().setForeground(Theme.ACC);
            t.setDefaultRenderer(Object.class, new CellToneRenderer());
            t.setSelectionBackground(Theme.BG2);
            t.setSelectionForeground(Theme.FG0);
            t.setShowHorizontalLines(true);
            t.setShowVerticalLines(false);

            JScrollPane sp = new JScrollPane(t);
            sp.setBorder(null);
            sp.getViewport().setBackground(Theme.BG0);
            sp.getVerticalScrollBar().setUI(new MinimalScrollUI());
            return sp;
        }
    }

    static final class CellToneRenderer extends DefaultTableCellRenderer {
