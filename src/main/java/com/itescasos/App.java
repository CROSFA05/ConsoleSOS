package com.itescasos;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;

import com.itescasos.CLI.SOS_CLI;

/**
 * Consola oscura tipo cmd en una sola superficie (Java 8).
 * Escribe directamente en la línea actual, soporta historial con flechas arriba/abajo
 * y cierra con 'exit' o con la cruz de la ventana.
 */
public class App {

    private static final Color FONDO_CONSOLA = new Color(16, 18, 22);
    private static final Color TEXTO_CONSOLA = new Color(229, 231, 235);
    private static final Color CURSOR_CONSOLA = new Color(110, 231, 183);
    private static final Color BORDE_CONSOLA = new Color(55, 65, 81);
    private static final Font FUENTE_CONSOLA = new Font("Monospaced", Font.PLAIN, 16);

    private static final List<String> historialComandos = new ArrayList<String>();
    private static volatile int inicioEntrada = 0;
    private static int indiceHistorial = 0;

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
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }

        final JFrame frame = new JFrame("SOS — Simulador Educativo de Sistema Operativo");
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setMinimumSize(new Dimension(820, 520));
        frame.setPreferredSize(new Dimension(1020, 660));

        final JTextArea consola = new JTextArea();
        consola.setEditable(true);
        consola.setLineWrap(true);
        consola.setWrapStyleWord(true);
        consola.setBackground(FONDO_CONSOLA);
        consola.setForeground(TEXTO_CONSOLA);
        consola.setCaretColor(CURSOR_CONSOLA);
        consola.setSelectedTextColor(FONDO_CONSOLA);
        consola.setSelectionColor(CURSOR_CONSOLA);
        consola.setFont(FUENTE_CONSOLA);
        consola.setMargin(new Insets(12, 14, 12, 14));

        JScrollPane scrollPane = new JScrollPane(consola);
        scrollPane.setBorder(BorderFactory.createLineBorder(BORDE_CONSOLA, 1));
        scrollPane.getViewport().setBackground(FONDO_CONSOLA);

        JPanel raiz = new JPanel(new BorderLayout());
        raiz.setBackground(FONDO_CONSOLA);
        raiz.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
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
            @Override
            public void write(int b) {
                escribirSalida(consola, new String(new byte[] { (byte) b }, StandardCharsets.UTF_8));
            }

            @Override
            public void write(byte[] b, int off, int len) {
                if (len <= 0) {
                    return;
                }
                escribirSalida(consola, new String(b, off, len, StandardCharsets.UTF_8));
            }
        }, true);

        System.setOut(streamConsola);
        System.setErr(streamConsola);
        System.setIn(entradaDeCli);

        final Runnable cerrarTodo = new Runnable() {
            @Override
            public void run() {
                try {
                    salidaHaciaCli.write(("exit" + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
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

                    escribirSalida(consola, System.lineSeparator());
                    inicioEntrada = consola.getDocument().getLength();

                    try {
                        salidaHaciaCli.write((comando + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
                        salidaHaciaCli.flush();
                    } catch (IOException ioEx) {
                        escribirSalida(consola, "Error enviando comando: " + ioEx.getMessage()
                                + System.lineSeparator());
                    }
                    return;
                }

                if (caret < inicioEntrada && !e.isActionKey() && !e.isControlDown() && !e.isMetaDown()) {
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

    private static void ponerTextoEntrada(final JTextArea consola, final String texto) {
        Runnable tarea = new Runnable() {
            @Override
            public void run() {
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
                        doc.insertString(inicioEntrada, valor, null);
                    }
                    consola.setCaretPosition(doc.getLength());
                } catch (BadLocationException ignored) {
                }
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            tarea.run();
        } else {
            SwingUtilities.invokeLater(tarea);
        }
    }

    private static void escribirSalida(final JTextArea consola, final String texto) {
        if (texto == null || texto.isEmpty()) {
            return;
        }
        Runnable tarea = new Runnable() {
            @Override
            public void run() {
                consola.append(texto);
                int len = consola.getDocument().getLength();
                inicioEntrada = len;
                consola.setCaretPosition(len);
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            tarea.run();
        } else {
            SwingUtilities.invokeLater(tarea);
        }
    }
}