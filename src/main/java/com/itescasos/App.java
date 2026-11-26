package com.itescasos;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

import com.itescasos.CLI.SOS_CLI;

/**
 * Consola oscura tipo cmd en una sola superficie (Java 8).
 * Escribe directamente en la linea actual, soporta historial con flechas
 * arriba/abajo y cierra con 'exit' o con la cruz de la ventana.
 */
public class App {

    private static final Color FONDO_CONSOLA = new Color(16, 18, 22);
    private static final Color TEXTO_CONSOLA = new Color(229, 231, 235);
    private static final Color TEXTO_ERROR = new Color(248, 113, 113);
    private static final Color CURSOR_CONSOLA = new Color(110, 231, 183);
    private static final Color BORDE_CONSOLA = new Color(55, 65, 81);
    private static final Font FUENTE_CONSOLA = new Font("Consolas", Font.PLAIN, 19);
    private static final String SANGRIA_RESPUESTA = "    ";
    private static final Pattern PATRON_ERROR = Pattern.compile("\\berror\\b", Pattern.CASE_INSENSITIVE);

    private static final List<String> historialComandos = new ArrayList<String>();
    private static int inicioEntrada = 0;
    private static int indiceHistorial = 0;
    private static boolean bloqueRespuestaAbierto = false;
    private static boolean respuestaConSangria = false;

    public static void main(String[] args) {
        if (args != null && args.length > 0) {
            SOS_CLI.main(args);
            return;
        }

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                iniciarConsola();
            }
        });
    }

    private static void iniciarConsola() {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");

        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }

        final JFrame frame = new JFrame("SOS — Simulador Educativo de Sistema Operativo");
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setMinimumSize(new Dimension(940, 580));
        frame.setPreferredSize(new Dimension(1180, 740));

        final JTextPane consola = new JTextPane() {
            @Override
            protected void paintComponent(Graphics g) {
                if (g instanceof Graphics2D) {
                    Graphics2D g2 = (Graphics2D) g;
                    g2.setRenderingHint(
                            RenderingHints.KEY_TEXT_ANTIALIASING,
                            RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g2.setRenderingHint(
                            RenderingHints.KEY_FRACTIONALMETRICS,
                            RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
                    g2.setRenderingHint(
                            RenderingHints.KEY_RENDERING,
                            RenderingHints.VALUE_RENDER_QUALITY);
                }
                super.paintComponent(g);
            }
        };
        consola.setEditable(true);
        consola.setBackground(FONDO_CONSOLA);
        consola.setForeground(TEXTO_CONSOLA);
        consola.setCaretColor(CURSOR_CONSOLA);
        consola.setSelectedTextColor(FONDO_CONSOLA);
        consola.setSelectionColor(CURSOR_CONSOLA);
        consola.setFont(FUENTE_CONSOLA);
        consola.setMargin(new Insets(14, 16, 14, 16));

        MutableAttributeSet inputAttrs = consola.getInputAttributes();
        StyleConstants.setFontFamily(inputAttrs, FUENTE_CONSOLA.getFamily());
        StyleConstants.setFontSize(inputAttrs, FUENTE_CONSOLA.getSize());
        StyleConstants.setForeground(inputAttrs, TEXTO_CONSOLA);

        JScrollPane scrollPane = new JScrollPane(consola);
        scrollPane.setBorder(BorderFactory.createLineBorder(BORDE_CONSOLA, 1));
        scrollPane.getViewport().setBackground(FONDO_CONSOLA);

        JPanel raiz = new JPanel(new BorderLayout());
        raiz.setBackground(FONDO_CONSOLA);
        raiz.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        raiz.add(scrollPane, BorderLayout.CENTER);

        frame.setContentPane(raiz);
        frame.pack();
        frame.setLocationRelativeTo(null);

        final PipedOutputStream salidaHaciaCli = new PipedOutputStream();
        final PipedInputStream entradaDeCli;
        try {
            entradaDeCli = new PipedInputStream(salidaHaciaCli);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo inicializar el canal de entrada", e);
        }

        final PrintStream streamConsola = new PrintStream(new OutputStream() {
            private final ByteArrayOutputStream bufferParcial = new ByteArrayOutputStream();

            @Override
            public void write(int b) {
                synchronized (bufferParcial) {
                    bufferParcial.write(b);
                    if (b == '\n') {
                        flushBuffer();
                    }
                }
            }

            @Override
            public void write(byte[] b, int off, int len) {
                if (len <= 0) {
                    return;
                }
                synchronized (bufferParcial) {
                    bufferParcial.write(b, off, len);
                    if (tieneSecuenciaUtf8Completa(bufferParcial.toByteArray())) {
                        flushBuffer();
                    }
                }
            }

            @Override
            public void flush() {
                synchronized (bufferParcial) {
                    if (tieneSecuenciaUtf8Completa(bufferParcial.toByteArray())) {
                        flushBuffer();
                    }
                }
            }

            private void flushBuffer() {
                if (bufferParcial.size() == 0) {
                    return;
                }
                try {
                    final String chunk = bufferParcial.toString("UTF-8");
                    bufferParcial.reset();
                    SwingUtilities.invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            procesarSalidaEnEdt(consola, chunk);
                        }
                    });
                } catch (UnsupportedEncodingException ignored) {
                }
            }
        }, true);

        System.setOut(streamConsola);
        System.setErr(streamConsola);
        System.setIn(entradaDeCli);

        final Runnable cerrarTodo = new Runnable() {
            @Override
            public void run() {
                try {
                    salidaHaciaCli.write(("exit" + System.lineSeparator()).getBytes("UTF-8"));
                    salidaHaciaCli.flush();
                } catch (IOException ignored) {
                }
                try {
                    salidaHaciaCli.close();
                } catch (IOException ignored) {
                }
                try {
                    entradaDeCli.close();
                } catch (IOException ignored) {
                }
                frame.dispose();
                System.exit(0);
            }
        };

        consola.addKeyListener(new KeyAdapter() {
            @Override
            public void keyTyped(KeyEvent e) {
                if (e.isControlDown() || e.isMetaDown() || e.isAltDown()) {
                    return;
                }
                Document doc = consola.getDocument();
                int len = doc.getLength();
                if (consola.getCaretPosition() < inicioEntrada) {
                    consola.setCaretPosition(len);
                }
                MutableAttributeSet attrs = consola.getInputAttributes();
                StyleConstants.setFontFamily(attrs, FUENTE_CONSOLA.getFamily());
                StyleConstants.setFontSize(attrs, FUENTE_CONSOLA.getSize());
                StyleConstants.setForeground(attrs, TEXTO_CONSOLA);
            }

            @Override
            public void keyPressed(KeyEvent e) {
                int caret = consola.getCaretPosition();
                int codigo = e.getKeyCode();

                if (codigo == KeyEvent.VK_UP) {
                    e.consume();
                    if (!historialComandos.isEmpty() && indiceHistorial > 0) {
                        indiceHistorial--;
                        ponerTextoEntrada(consola, historialComandos.get(indiceHistorial));
                    }
                    return;
                }

                if (codigo == KeyEvent.VK_DOWN) {
                    e.consume();
                    if (!historialComandos.isEmpty() && indiceHistorial < historialComandos.size()) {
                        indiceHistorial++;
                        if (indiceHistorial == historialComandos.size()) {
                            ponerTextoEntrada(consola, "");
                        } else {
                            ponerTextoEntrada(consola, historialComandos.get(indiceHistorial));
                        }
                    }
                    return;
                }

                if (codigo == KeyEvent.VK_BACK_SPACE || codigo == KeyEvent.VK_LEFT) {
                    if (caret <= inicioEntrada) {
                        e.consume();
                        consola.setCaretPosition(consola.getDocument().getLength());
                    }
                    return;
                }

                if (codigo == KeyEvent.VK_HOME) {
                    e.consume();
                    consola.setCaretPosition(inicioEntrada);
                    return;
                }

                if (codigo == KeyEvent.VK_ENTER) {
                    e.consume();
                    Document doc = consola.getDocument();
                    int fin = doc.getLength();
                    String comando = "";
                    if (fin >= inicioEntrada) {
                        try {
                            comando = doc.getText(inicioEntrada, fin - inicioEntrada);
                        } catch (BadLocationException ignored) {
                        }
                    }

                    String limpio = comando.trim();
                    if (!limpio.isEmpty()) {
                        if (historialComandos.isEmpty()
                                || !historialComandos.get(historialComandos.size() - 1).equals(limpio)) {
                            historialComandos.add(limpio);
                        }
                    }
                    indiceHistorial = historialComandos.size();

                    cerrarBloqueRespuestaEnEdt(consola);
                    insertarTextoEnEdt(consola, "\n", false);
                    inicioEntrada = consola.getDocument().getLength();
                    respuestaConSangria = !limpio.isEmpty();

                    try {
                        salidaHaciaCli.write((comando + System.lineSeparator()).getBytes("UTF-8"));
                        salidaHaciaCli.flush();
                    } catch (IOException ioEx) {
                        insertarTextoEnEdt(
                                consola,
                                "Error enviando comando: " + ioEx.getMessage() + "\n",
                                true);
                        inicioEntrada = consola.getDocument().getLength();
                    }
                    return;
                }

                if (caret < inicioEntrada && !e.isControlDown() && !e.isMetaDown()) {
                    consola.setCaretPosition(consola.getDocument().getLength());
                }
            }
        });

        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                new Thread(cerrarTodo, "cerrar-consola-sos").start();
            }
        });

        frame.setVisible(true);
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                consola.requestFocusInWindow();
                consola.setCaretPosition(consola.getDocument().getLength());
            }
        });

        Thread hiloCli = new Thread(new Runnable() {
            @Override
            public void run() {
                SOS_CLI.main(new String[0]);
                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        consola.setEditable(false);
                        frame.dispose();
                        System.exit(0);
                    }
                });
            }
        }, "sos-cli-hilo");
        hiloCli.setDaemon(true);
        hiloCli.start();
    }

    private static boolean tieneSecuenciaUtf8Completa(byte[] datos) {
        if (datos == null || datos.length == 0) {
            return false;
        }
        int i = datos.length - 1;
        int continuaciones = 0;
        while (i >= 0 && (datos[i] & 0xC0) == 0x80) {
            continuaciones++;
            i--;
        }
        if (i < 0) {
            return false;
        }
        int lider = datos[i] & 0xFF;
        int esperados;
        if ((lider & 0x80) == 0) {
            esperados = 0;
        } else if ((lider & 0xE0) == 0xC0) {
            esperados = 1;
        } else if ((lider & 0xF0) == 0xE0) {
            esperados = 2;
        } else if ((lider & 0xF8) == 0xF0) {
            esperados = 3;
        } else {
            return true;
        }
        return continuaciones >= esperados;
    }

    private static void procesarSalidaEnEdt(final JTextPane consola, String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        String normalizada = chunk.replace("\r\n", "\n").replace("\r", "\n");
        String trimmed = normalizada.trim();

        boolean esPrompt = !trimmed.isEmpty() && !normalizada.endsWith("\n") && trimmed.endsWith(">");
        if (esPrompt) {
            cerrarBloqueRespuestaEnEdt(consola);
            insertarTextoEnEdt(consola, normalizada, false);
            inicioEntrada = consola.getDocument().getLength();
            return;
        }

        if (!respuestaConSangria) {
            insertarTextoEnEdt(consola, normalizada, true);
            inicioEntrada = consola.getDocument().getLength();
            return;
        }

        StringBuilder sb = new StringBuilder();
        if (!bloqueRespuestaAbierto) {
            sb.append("\n");
            bloqueRespuestaAbierto = true;
        }
        sb.append(aplicarSangriaConAjuste(consola, normalizada));
        insertarTextoEnEdt(consola, sb.toString(), true);
        inicioEntrada = consola.getDocument().getLength();
    }

    private static String aplicarSangriaConAjuste(final JTextPane consola, String normalizada) {
        StringBuilder out = new StringBuilder();
        int anchoUtilPx = consola.getWidth() - 40;
        int anchoSangriaPx = consola.getFontMetrics(FUENTE_CONSOLA).stringWidth(SANGRIA_RESPUESTA);
        int anchoCharPx = Math.max(1, consola.getFontMetrics(FUENTE_CONSOLA).charWidth('m'));
        int maxCols = Math.max(24, (anchoUtilPx - anchoSangriaPx) / anchoCharPx);

        String[] lineas = normalizada.split("\n", -1);
        for (int i = 0; i < lineas.length; i++) {
            boolean esUltimoVacio = (i == lineas.length - 1) && lineas[i].isEmpty();
            if (esUltimoVacio) {
                continue;
            }
            String linea = lineas[i];
            if (linea.isEmpty()) {
                out.append(SANGRIA_RESPUESTA).append("\n");
                continue;
            }

            int pos = 0;
            while (pos < linea.length()) {
                int fin = Math.min(linea.length(), pos + maxCols);
                if (fin < linea.length()) {
                    int ultimoEspacio = linea.lastIndexOf(' ', fin);
                    if (ultimoEspacio > pos + 8) {
                        fin = ultimoEspacio;
                    }
                }
                String segmento = linea.substring(pos, fin);
                if (fin < linea.length() && linea.charAt(fin) == ' ') {
                    fin++;
                }
                out.append(SANGRIA_RESPUESTA).append(segmento).append("\n");
                pos = fin;
            }
        }
        return out.toString();
    }

    private static void cerrarBloqueRespuestaEnEdt(final JTextPane consola) {
        if (bloqueRespuestaAbierto) {
            bloqueRespuestaAbierto = false;
            respuestaConSangria = false;
            insertarTextoEnEdt(consola, "\n", false);
        }
    }

    private static void ponerTextoEntrada(final JTextPane consola, final String texto) {
        Document doc = consola.getDocument();
        int len = doc.getLength();
        if (len < inicioEntrada) {
            inicioEntrada = len;
        }
        String valor = (texto == null) ? "" : texto;
        try {
            if (len > inicioEntrada) {
                doc.remove(inicioEntrada, len - inicioEntrada);
            }
            if (!valor.isEmpty()) {
                doc.insertString(inicioEntrada, valor, crearAtributos(TEXTO_CONSOLA));
            }
            consola.setCaretPosition(doc.getLength());
        } catch (BadLocationException ignored) {
        }
    }

    private static SimpleAttributeSet crearAtributos(Color color) {
        SimpleAttributeSet attrs = new SimpleAttributeSet();
        StyleConstants.setFontFamily(attrs, FUENTE_CONSOLA.getFamily());
        StyleConstants.setFontSize(attrs, FUENTE_CONSOLA.getSize());
        StyleConstants.setForeground(attrs, color);
        return attrs;
    }

    private static void insertarTextoEnEdt(final JTextPane consola, final String texto, final boolean resaltarError) {
        if (texto == null || texto.isEmpty()) {
            return;
        }
        StyledDocument doc = consola.getStyledDocument();
        SimpleAttributeSet normal = crearAtributos(TEXTO_CONSOLA);
        SimpleAttributeSet rojo = crearAtributos(TEXTO_ERROR);

        try {
            if (!resaltarError) {
                doc.insertString(doc.getLength(), texto, normal);
            } else {
                Matcher matcher = PATRON_ERROR.matcher(texto);
                int ultimoIndice = 0;
                while (matcher.find()) {
                    if (matcher.start() > ultimoIndice) {
                        doc.insertString(
                                doc.getLength(),
                                texto.substring(ultimoIndice, matcher.start()),
                                normal);
                    }
                    doc.insertString(doc.getLength(), matcher.group(), rojo);
                    ultimoIndice = matcher.end();
                }
                if (ultimoIndice < texto.length()) {
                    doc.insertString(doc.getLength(), texto.substring(ultimoIndice), normal);
                }
            }
        } catch (BadLocationException ignored) {
        }

        int len = doc.getLength();
        inicioEntrada = len;
        consola.setCaretPosition(len);
    }
}   