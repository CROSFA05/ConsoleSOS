package com.itescasos.Seguridad;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.function.Consumer;

import com.itescasos.Archivos.InfoArchivo;
import com.itescasos.Archivos.SistemaArchivos;

/**
 * Fachada única de autenticación, autorización y cifrado. Tanto la CLI como el
 * ejecutor de escenarios pasan por aquí: ningún comando puede fijar directamente
 * la identidad de la sesión ni alterar la matriz sin autoridad administrativa.
 *
 * <p>La sesión NO se persiste: cada proceso Java debe autenticarse de nuevo.
 * Lo que persiste es el verificador de la clave (con sal) y la matriz de acceso.
 */
public class ServicioSeguridad {

    /** Objetos synthetic de la matriz para los dispositivos simulados. */
    public static final int ID_DISCO = 9001;
    public static final int ID_TERMINAL = 9002;

    public static class SeguridadException extends RuntimeException {
        public SeguridadException(String mensaje) { super(mensaje); }
    }

    private final Autenticacion autenticacion;
    private final ControlAcceso control;
    private final Path archivoUsuarios;
    private final Path archivoMatriz;
    private final Consumer<String> registro;

    public ServicioSeguridad(Path archivoUsuarios, Path archivoMatriz, Consumer<String> registro)
            throws IOException {
        this.archivoUsuarios = archivoUsuarios;
        this.archivoMatriz = archivoMatriz;
        this.autenticacion = Autenticacion.cargar(archivoUsuarios);
        this.control = ControlAcceso.cargar(archivoMatriz);
        this.registro = registro;
        guardar(); // materializa verificadores con sal y la política inicial
    }

    public ControlAcceso control() { return control; }

    public String usuario() { return control.usuarioActual(); }

    public boolean haySesion() { return control.haySesion(); }

    public Iterable<String> usuarios() { return autenticacion.listarUsuarios(); }

    public void exigirSesion() {
        if (!control.haySesion()) {
            throw new SeguridadException("se requiere autenticación (auth login)");
        }
    }

    /**
     * Autentica y establece la identidad de la sesión. Un fracaso invalida
     * cualquier identidad anterior. La clave nunca se registra ni se guarda.
     */
    public boolean login(String usuario, String clave) {
        control.logout();
        boolean ok = autenticacion.verificar(usuario, clave);
        if (ok) {
            control.login(usuario.trim());
        }
        auditar("AUTH", usuario, "LOGIN", ok ? "OK" : "DENEGADO");
        guardarSilencioso();
        return ok;
    }

    public void loginYaAutenticado(String usuario) {
        if (!autenticacion.existe(usuario)) {
            throw new SeguridadException("usuario desconocido: " + usuario);
        }
        control.logout();
        control.login(usuario.trim());
        auditar("AUTH", usuario, "LOGIN", "OK");
    }

    public void logout() {
        String u = control.usuarioActual();
        control.logout();
        auditar("AUTH", u, "LOGOUT", "OK");
    }

    /** Política inicial tras formatear: el administrador recibe rwx sobre la raíz. */
    public void bootstrap() {
        control.limpiar();
        control.conceder("admin", 1, "rwx");
        control.conceder("docente", 1, "rwx");
        control.objetoCargado(1, "/");
        // El formateo crea siempre /lost+found con idObjeto 2.
        control.conceder("admin", 2, "rwx");
        control.conceder("docente", 2, "rwx");
        control.objetoCargado(2, "/lost+found");
        auditar("SEC", "sistema", "BOOTSTRAP", "administradores con rwx en /");
        guardar();
    }

    /** Concede derechos. Requiere sesión de administrador; nunca deduce autoridad de rw. */
    public void conceder(String usuario, int idObjeto, String rwx) {
        exigirAdministrador();
        control.conceder(usuario, idObjeto, rwx);
        auditar("SEC", usuario, "CONCEDER", "id=" + idObjeto + " [" + rwx + "]");
        guardar();
    }

    /** Revoca todos los derechos. Requiere sesión de administrador. */
    public void revocar(String usuario, int idObjeto) {
        exigirAdministrador();
        control.revocar(usuario, idObjeto);
        auditar("SEC", usuario, "REVOCAR", "id=" + idObjeto);
        guardar();
    }

    public void hacerAdministrador(String usuario) {
        exigirAdministrador();
        control.hacerAdministrador(usuario);
        auditar("SEC", usuario, "ADMIN", "OK");
        guardar();
    }

    public void concederDispositivo(String dispositivo, String usuario, String rwx) {
        exigirAdministrador();
        int id = idDispositivo(dispositivo);
        control.objetoCargado(id, dispositivo);
        control.conceder(usuario, id, rwx);
        auditar("SEC", usuario, "CONCEDER_DISPOSITIVO", dispositivo + " [" + rwx + "]");
        guardar();
    }

    /** Comprobación usada antes de enviar solicitudes a un driver. */
    public boolean autorizarDispositivo(String dispositivo, Derecho derecho, String pid) {
        int id = idDispositivo(dispositivo);
        boolean ok = control.autorizar(id, derecho, dispositivo);
        auditar("SEC", pid == null ? "-" : pid, "ACCESO_DISPOSITIVO",
                dispositivo + " " + derecho + "=" + (ok ? "OK" : "DENEGADO"));
        return ok;
    }

    public boolean esAdministrador(String usuario) { return control.esAdministrador(usuario); }

    public void exigirAdministrador() {
        exigirSesion();
        if (!control.esAdministrador(usuario())) {
            auditar("SEC", usuario(), "AUTORIDAD", "DENEGADO");
            throw new SeguridadException("el usuario " + usuario() + " no puede administrar permisos");
        }
    }

    /**
     * Cifra un archivo conservando el estado anterior si falla cualquier paso.
     * La clave es material de demostración; nunca se escribe en el log.
     */
    public void cifrar(SistemaArchivos fs, String ruta, String clave) {
        exigirSesion();
        InfoArchivo info = fs.info(ruta); // comprueba LEER antes de tocar el contenido
        if (info.cifrado()) {
            auditar("SEC", usuario(), "CIFRAR", "sin cambios (ya cifrado)");
            return;
        }
        byte[] transformado = Cifrado.aplicar(fs.leerTodo(ruta), clave);
        fs.reemplazarContenidoAtomico(ruta, transformado,
                SistemaArchivos.FLAG_CIFRADO, Cifrado.atributosParaClave(clave));
        auditar("SEC", usuario(), "CIFRAR", ruta + " estado=cifrado algoritmo=XOR");
    }

    /** Descifra solo con la clave correcta; no entrega texto claro por pantalla. */
    public boolean descifrar(SistemaArchivos fs, String ruta, String clave) {
        exigirSesion();
        InfoArchivo info = fs.info(ruta);
        if (!info.cifrado()) {
            auditar("SEC", usuario(), "DESCIFRAR", "sin cambios (no cifrado)");
            return false;
        }
        if (!Cifrado.claveCoincide(info.atributos(), clave)) {
            auditar("SEC", usuario(), "DESCIFRAR", "CLAVE_INCORRECTA sin cambios");
            return false;
        }
        byte[] plano = Cifrado.aplicar(fs.leerTodo(ruta), clave);
        fs.reemplazarContenidoAtomico(ruta, plano, 0, new byte[48]);
        auditar("SEC", usuario(), "DESCIFRAR", ruta + " estado=claro bytes=" + plano.length);
        return true;
    }

    public void registrarLectura(String ruta, boolean cifrado) {
        auditar("SEC", usuario() == null ? "-" : usuario(), "LEER",
                ruta + (cifrado ? " cifrado=si (sin texto claro)" : " cifrado=no"));
    }

    public void guardar() {
        try {
            autenticacion.guardar(archivoUsuarios);
            control.guardar(archivoMatriz);
        } catch (IOException e) {
            throw new SeguridadException("no se pudo persistir la seguridad: " + e.getMessage());
        }
    }

    private void guardarSilencioso() {
        try {
            autenticacion.guardar(archivoUsuarios);
            control.guardar(archivoMatriz);
        } catch (IOException ignored) {
            // La operación en memoria sigue siendo válida; se reintentará en el próximo cambio.
        }
    }

    private void auditar(String modulo, String sujeto, String evento, String resultado) {
        if (registro == null) return;
        registro.accept("t=- modulo=" + modulo + " sujeto=" + sujeto
                + " evento=" + evento + " resultado=" + resultado);
    }

    private static int idDispositivo(String nombre) {
        String n = nombre.trim().toLowerCase();
        if (n.equals("disco")) return ID_DISCO;
        if (n.equals("terminal")) return ID_TERMINAL;
        throw new SeguridadException("dispositivo desconocido: " + nombre);
    }

    /** Texto de apoyo para la CLI; NUNCA se usa como clave de cifrado ni de login. */
    public String huellaClaveDemo(String clave) {
        byte[] s = clave.getBytes(StandardCharsets.UTF_8);
        int h = 0;
        for (byte b : s) h = 31 * h + b;
        return String.format("%08x", h);
    }
}
