package com.itescasos.cli;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;

import com.itescasos.Archivos.ErrorFS;
import com.itescasos.Archivos.Fsck;
import com.itescasos.Archivos.InfoArchivo;
import com.itescasos.Archivos.InfoEspacio;
import com.itescasos.Archivos.ReporteFsck;
import com.itescasos.Archivos.SistemaArchivos;
import com.itescasos.Archivos.TipoObjeto;
import com.itescasos.Core.PCB;
import com.itescasos.Core.PlanificadorCPU;
import com.itescasos.IO.ImagenDisco;
import com.itescasos.Memoria.GestorMemoria;
import com.itescasos.Seguridad.Autenticacion;
import com.itescasos.Seguridad.Cifrado;
import com.itescasos.Seguridad.ControlAcceso;

/**
 * CLI unificado del simulador SOS (requisito del Módulo 5).
 *
 *   java -cp ... com.itescasos.cli.SosCli           → interactivo
 *   java -cp ... com.itescasos.cli.SosCli <cmd> ... → un comando
 */
public class SosCli {

    private final Autenticacion auth = new Autenticacion();
    private final ControlAcceso control = new ControlAcceso();
    private SistemaArchivos fs;
    private ImagenDisco dispositivo;
    private Path imagenPath = Paths.get("disk.img");
    private boolean corriendo = true;
    private static final int TAM_BLOQUE = 128;
    private static final int TOTAL_BLOQUES = 256;
    private static final int TOTAL_INODOS = 64;

    public static void main(String[] args) {
        SosCli cli = new SosCli();
        if (args.length == 0) {
            cli.interactivo();
        } else {
            try {
                cli.ejecutar(args);
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                System.exit(1);
            } finally {
                cli.cerrarTodo();
            }
        }
    }

    private void interactivo() {
        System.out.println("SOS — Simulador Educativo de Sistema Operativo");
        System.out.println("Escribe 'help' para ver comandos. 'exit' para salir.\n");
        Scanner sc = new Scanner(System.in, "UTF-8");
        while (corriendo) {
            String prompt = control.haySesion()
                    ? control.usuarioActual() + "@sos> "
                    : "sos> ";
            System.out.print(prompt);
            if (!sc.hasNextLine()) break;
            String linea = sc.nextLine().trim();
            if (linea.isEmpty()) continue;
            String[] partes = linea.split("\\s+");
            try {
                ejecutar(partes);
            } catch (ErrorFS e) {
                System.out.println("FS: " + e.getMessage());
            } catch (Exception e) {
                System.out.println("Error: " + e.getMessage());
            }
        }
        sc.close();
        cerrarTodo();
    }

    public void ejecutar(String[] args) throws Exception {
        if (args.length == 0) return;
        String cmd = args[0].toLowerCase();

        switch (cmd) {
            case "help": case "?": ayuda(); break;
            case "exit": case "quit":
                corriendo = false;
                System.out.println("Adiós.");
                break;
            case "sim":  sim(args); break;
            case "fs":   fsCmd(args); break;
            case "auth": authCmd(args); break;
            case "sec":  secCmd(args); break;
            default:
                System.out.println("Comando desconocido: " + cmd + " (escribe help)");
        }
    }

    private void ayuda() {
        System.out.println(
            "\n=== SOS CLI ===\n" +
            "  sim plan                 Caso de control planificador (FCFS/SJF/RR/Prioridades)\n" +
            "  sim mem                  Caso de control memoria (FIFO/LRU/Óptimo)\n" +
            "  sim run <escenario.txt>  (integración — en desarrollo)\n\n" +
            "  fs format [disk.img]     Crea y formatea imagen\n" +
            "  fs mount  [disk.img]     Monta imagen existente\n" +
            "  fs umount                Desmonta\n" +
            "  fs mkdir <ruta>\n" +
            "  fs create <ruta>\n" +
            "  fs write <ruta> <texto...>\n" +
            "  fs read  <ruta>\n" +
            "  fs ls    [ruta]\n" +
            "  fs rm    <ruta>\n" +
            "  fs df\n" +
            "  fs stat  <ruta>\n" +
            "  fs fsck  [--fix]\n\n" +
            "  auth login <usuario> [clave]\n" +
            "  auth logout | who | users\n\n" +
            "  sec permiso <usuario> <idObjeto> <rwx>\n" +
            "  sec matriz\n" +
            "  sec cifrar    <ruta> <clave>\n" +
            "  sec descifrar <ruta> <clave>\n\n" +
            "  help | exit\n"
        );
    }

    // ===================== SIM =====================

    private void sim(String[] args) {
        if (args.length < 2) {
            System.out.println("Uso: sim plan|mem|run");
            return;
        }
        switch (args[1].toLowerCase()) {
            case "plan": simPlan(); break;
            case "mem":  simMem();  break;
            case "run":
                System.out.println("sim run: use los módulos por separado por ahora (plan/mem).");
                if (args.length >= 3) System.out.println("Escenario: " + args[2]);
                break;
            default:
                System.out.println("Subcomando sim desconocido: " + args[1]);
        }
    }

    private void simPlan() {
        System.out.println("=== MÓDULO 1: PLANIFICADOR DE CPU (caso de control) ===");
        List<PCB> procesos = Arrays.asList(
                new PCB("P1", 0, 5, 0, 3, 8192),
                new PCB("P2", 0, 3, 0, 1, 8192),
                new PCB("P3", 0, 1, 0, 4, 8192),
                new PCB("P4", 0, 2, 0, 2, 8192),
                new PCB("P5", 0, 4, 0, 2, 8192));
        PlanificadorCPU plan = new PlanificadorCPU();
        plan.simular(procesos, "FCFS", 0);
        plan.simular(procesos, "SJF", 0);
        plan.simular(procesos, "RR", 2);
        plan.simular(procesos, "PRIORIDADES", 0);
    }

    private void simMem() {
        System.out.println("=== MÓDULO 2: ADMINISTRADOR DE MEMORIA (caso de control) ===");
        List<Integer> cadena = Arrays.asList(0, 1, 2, 0, 3, 1, 4, 0);
        GestorMemoria mem = new GestorMemoria(3, 4096);
        mem.simularPaginacion(cadena, "FIFO");
        mem.simularPaginacion(cadena, "LRU");
        mem.simularPaginacion(cadena, "OPTIMO");
    }

    // ===================== FS =====================

    private void fsCmd(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Uso: fs <subcommand> ...");
            return;
        }
        String sub = args[1].toLowerCase();
        switch (sub) {
            case "format": {
                Path p = args.length >= 3 ? Paths.get(args[2]) : imagenPath;
                formatear(p);
                break;
            }
            case "mount": {
                Path p = args.length >= 3 ? Paths.get(args[2]) : imagenPath;
                montar(p);
                break;
            }
            case "umount":
                cerrarTodo();
                System.out.println("Desmontado.");
                break;
            case "mkdir":
                exigirFs();
                exigir(args, 3, "fs mkdir <ruta>");
                fs.mkdir(args[2]);
                System.out.println("Directorio creado: " + args[2]);
                break;
            case "create":
                exigirFs();
                exigir(args, 3, "fs create <ruta>");
                fs.crearArchivo(args[2], 0);
                System.out.println("Archivo creado: " + args[2]);
                break;
            case "write":
                exigirFs();
                exigir(args, 4, "fs write <ruta> <texto...>");
                String texto = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
                fs.reemplazarContenido(args[2], texto.getBytes(StandardCharsets.UTF_8));
                System.out.println("Escrito en " + args[2]);
                break;
            case "read":
                exigirFs();
                exigir(args, 3, "fs read <ruta>");
                InfoArchivo inf = fs.info(args[2]);
                byte[] datos = fs.leerTodo(args[2]);
                if (inf.cifrado()) {
                    System.out.println("[cifrado — muestra bytes en bruto; use sec descifrar]");
                }
                System.out.println(new String(datos, StandardCharsets.UTF_8));
                break;
            case "ls": {
                exigirFs();
                String ruta = args.length >= 3 ? args[2] : "/";
                for (InfoArchivo e : fs.listar(ruta)) {
                    String tipo = e.tipo == TipoObjeto.DIRECTORIO ? "d" : "-";
                    String cif = e.cifrado() ? "C" : " ";
                    System.out.printf("%s%s  id=%-4d  %6d  %s%n",
                            tipo, cif, e.idObjeto, e.tamano, e.nombre);
                }
                break;
            }
            case "rm":
                exigirFs();
                exigir(args, 3, "fs rm <ruta>");
                fs.eliminar(args[2]);
                System.out.println("Eliminado: " + args[2]);
                break;
            case "df":
                exigirFs();
                InfoEspacio esp = fs.espacio();
                System.out.print(esp.toString());
                break;
            case "stat":
                exigirFs();
                exigir(args, 3, "fs stat <ruta>");
                InfoArchivo st = fs.info(args[2]);
                System.out.printf("ruta=%s  tipo=%s  inodo=%d  idObjeto=%d  tam=%d  cifrado=%s%n",
                        st.ruta, st.tipo, st.inodo, st.idObjeto, st.tamano, st.cifrado());
                break;
            case "fsck": {
                boolean reparar = args.length >= 3 && "--fix".equals(args[2]);
                // fsck trabaja sobre el dispositivo desmontado
                if (fs != null) {
                    System.out.println("Desmontando para fsck...");
                    cerrarTodo();
                }
                if (!java.nio.file.Files.exists(imagenPath)) {
                    throw new IllegalStateException("No existe " + imagenPath);
                }
                ImagenDisco dev = ImagenDisco.abrir(imagenPath, TAM_BLOQUE);
                try {
                    ReporteFsck rep = Fsck.ejecutar(dev, reparar);
                    System.out.println(rep.texto());
                } finally {
                    dev.cerrar();
                }
                break;
            }
            default:
                System.out.println("Subcomando fs desconocido: " + sub);
        }
    }

    private void formatear(Path p) throws Exception {
        cerrarTodo();
        ImagenDisco dev = ImagenDisco.crear(p, TAM_BLOQUE, TOTAL_BLOQUES, true);
        SistemaArchivos.formatear(dev, TOTAL_INODOS);
        dev.cerrar();
        imagenPath = p;
        System.out.println("Imagen formateada: " + p.toAbsolutePath()
                + " (" + TAM_BLOQUE + " B/bloque, " + TOTAL_BLOQUES + " bloques, "
                + TOTAL_INODOS + " inodos)");
    }

    private void montar(Path p) throws Exception {
        cerrarTodo();
        dispositivo = ImagenDisco.abrir(p, TAM_BLOQUE);
        fs = SistemaArchivos.montar(dispositivo, control);
        imagenPath = p;
        System.out.println("Montado: " + p.toAbsolutePath()
                + (fs.fueDesmontadoLimpio() ? " (limpio)" : " (no limpio — considere fsck)"));
    }

    private void exigirFs() {
        if (fs == null) {
            throw new IllegalStateException("No hay volumen montado. Use: fs format | fs mount");
        }
    }

    private void cerrarTodo() {
        if (fs != null) {
            try { fs.desmontar(); } catch (Exception ignored) { }
            fs = null;
        }
        if (dispositivo != null) {
            try { dispositivo.cerrar(); } catch (Exception ignored) { }
            dispositivo = null;
        }
    }

    // ===================== AUTH =====================

    private void authCmd(String[] args) {
        if (args.length < 2) {
            System.out.println("Uso: auth login|logout|who|users");
            return;
        }
        switch (args[1].toLowerCase()) {
            case "login":
                exigir(args, 3, "auth login <usuario> [clave]");
                String u = args[2];
                String c = args.length >= 4 ? args[3] : "";
                if (!auth.verificar(u, c)) {
                    System.out.println("Credenciales incorrectas.");
                    return;
                }
                control.login(u);
                // Bootstrap: el admin recibe control total sobre la raíz (id=1) y lost+found (id=2)
                // porque el formateo se hizo con PERMITIR_TODO y no registró permisos.
                if ("admin".equals(u) || "docente".equals(u)) {
                    control.conceder(u, 1, "rwx");
                    control.conceder(u, 2, "rwx");
                }
                System.out.println("Sesión iniciada: " + u);
                break;
            case "logout":
                control.logout();
                System.out.println("Sesión cerrada.");
                break;
            case "who":
                System.out.println(control.haySesion()
                        ? "Usuario: " + control.usuarioActual()
                        : "No hay sesión activa.");
                break;
            case "users":
                System.out.println("Usuarios de prueba: " + auth.listarUsuarios());
                break;
            default:
                System.out.println("Subcomando auth desconocido: " + args[1]);
        }
    }

    // ===================== SEC =====================

    private void secCmd(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Uso: sec permiso|matriz|cifrar|descifrar");
            return;
        }
        switch (args[1].toLowerCase()) {
            case "permiso":
                exigir(args, 5, "sec permiso <usuario> <idObjeto> <rwx>");
                int id = Integer.parseInt(args[3]);
                control.conceder(args[2], id, args[4]);
                System.out.println("Permiso: " + args[2] + " → id=" + id + " [" + args[4] + "]");
                break;
            case "matriz":
                System.out.println(control.listarMatriz());
                break;
            case "cifrar":
                exigirFs();
                exigir(args, 4, "sec cifrar <ruta> <clave>");
                cifrar(args[2], args[3]);
                break;
            case "descifrar":
                exigirFs();
                exigir(args, 4, "sec descifrar <ruta> <clave>");
                descifrar(args[2], args[3]);
                break;
            default:
                System.out.println("Subcomando sec desconocido: " + args[1]);
        }
    }

    private void cifrar(String ruta, String clave) {
        InfoArchivo info = fs.info(ruta);
        if (info.cifrado()) {
            System.out.println("Ya está cifrado.");
            return;
        }
        byte[] plano = fs.leerTodo(ruta);
        byte[] cifrado = Cifrado.aplicar(plano, clave);
        byte[] attrs = Cifrado.atributosParaClave(clave);
        fs.reemplazarContenidoAtomico(ruta, cifrado, SistemaArchivos.FLAG_CIFRADO, attrs);
        System.out.println("Cifrado: " + ruta);
    }

    private void descifrar(String ruta, String clave) {
        InfoArchivo info = fs.info(ruta);
        if (!info.cifrado()) {
            System.out.println("No está cifrado.");
            return;
        }
        if (!Cifrado.claveCoincide(info.atributos(), clave)) {
            System.out.println("Clave incorrecta.");
            return;
        }
        byte[] cifrado = fs.leerTodo(ruta);
        byte[] plano = Cifrado.aplicar(cifrado, clave);
        fs.reemplazarContenidoAtomico(ruta, plano, 0, new byte[48]);
        System.out.println("Descifrado: " + ruta);
        System.out.println(new String(plano, StandardCharsets.UTF_8));
    }

    private static void exigir(String[] args, int min, String uso) {
        if (args.length < min) throw new IllegalArgumentException("Uso: " + uso);
    }
}
