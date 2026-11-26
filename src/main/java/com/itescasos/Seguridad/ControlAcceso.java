package com.itescasos.Seguridad;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Matriz de acceso: sujetos (usuarios) × objetos (idObjeto estable del FS).
 * El idObjeto no cambia al renombrar y no se reutiliza (ver InfoArchivo).
 *
 * <p><strong>Autoridad:</strong> esta clase es solo el almacén de la matriz.
 * {@link #conceder(String, int, String)}, {@link #revocar(String, int)} y
 * {@link #hacerAdministrador(String)} NO comprueban si el usuario de la sesión
 * es administrador; esa decisión debe tomarla la fachada {@code ServicioSeguridad}.
 *
 * <p>Formato de persistencia (UTF-8, una directiva por línea):
 * <pre>
 * ADMIN:usuario
 * RUTA:idObjeto:ruta
 * PERM:idObjeto:ruta:usuario:rwx
 * </pre>
 * Las líneas vacías y las que empiezan por {@code #} se ignoran. Un archivo que
 * no existe produce una matriz vacía con {@code admin} y {@code docente} como
 * administradores iniciales.
 *
 * <p>{@link #PERMITIR_TODO} autoriza cualquier operación y no acumula matriz,
 * rutas ni administradores: las operaciones demutación sobre él son inertes,
 * de modo que el formateo del FS no contamina la instancia real.
 */
public class ControlAcceso {

    /** Implementación que autoriza cualquier operación (útil para pruebas unitarias del FS). */
    public static final ControlAcceso PERMITIR_TODO = new ControlAcceso(true);

    private final boolean permitirTodo;
    private String usuarioActual; // null = no autenticado
    // idObjeto -> (usuario -> máscara de bits rwx)
    private final Map<Integer, Map<String, Integer>> matriz = new HashMap<>();
    // objetos conocidos (ruta solo informativa; se reconstruye al montar)
    private final Map<Integer, String> rutas = new HashMap<>();
    // sujetos con autoridad administrativa (la comprueba la fachada)
    private final Set<String> administradores = new HashSet<>();

    /** Matriz vacía con admin y docente como administradores iniciales. */
    public ControlAcceso() {
        this(false);
        administradores.add("admin");
        administradores.add("docente");
    }

    private ControlAcceso(boolean permitirTodo) {
        this.permitirTodo = permitirTodo;
    }

    /** Reinicia matriz y rutas al formatear; conserva los administradores declarados. */
    public void limpiar() {
        if (permitirTodo) return;
        matriz.clear();
        rutas.clear();
    }

    // ------------------------------------------------------------------ sesión

    public void login(String usuario) {
        if (usuario == null || usuario.trim().isEmpty()) {
            throw new IllegalArgumentException("usuario vacío");
        }
        this.usuarioActual = usuario.trim();
    }

    public void logout() {
        this.usuarioActual = null;
    }

    public String usuarioActual() {
        return usuarioActual;
    }

    public boolean haySesion() {
        return usuarioActual != null;
    }

    // ------------------------------------------------------------------ matriz

    /**
     * Concede o actualiza derechos del usuario sobre el objeto.
     * <p><strong>No comprueba autoridad:</strong> la validación de que la sesión
     * pertenece a un administrador es responsabilidad de {@code ServicioSeguridad}.
     *
     * @param rwx cadena tipo "rw", "rwx", "r", etc.
     */
    public void conceder(String usuario, int idObjeto, String rwx) {
        if (permitirTodo || usuario == null || usuario.isEmpty()) return;
        int mask = Derecho.mascaraDesdeCadena(rwx);
        matriz.computeIfAbsent(idObjeto, k -> new HashMap<>()).put(usuario, mask);
    }

    /**
     * Revoca todos los derechos del usuario sobre el objeto.
     * <p><strong>No comprueba autoridad:</strong> ver {@link #conceder}.
     */
    public void revocar(String usuario, int idObjeto) {
        if (permitirTodo) return;
        Map<String, Integer> fila = matriz.get(idObjeto);
        if (fila != null) fila.remove(usuario);
    }

    /** Consulta usada por SistemaArchivos antes de cada operación protegida. */
    public boolean autorizar(int idObjeto, Derecho derecho, String ruta) {
        if (permitirTodo) return true;
        if (usuarioActual == null) return false; // sin sesión → denegado
        Map<String, Integer> fila = matriz.get(idObjeto);
        if (fila == null) return false;
        Integer mask = fila.get(usuarioActual);
        if (mask == null) return false;
        return (mask & derecho.bit()) != 0;
    }

    // ------------------------------------------------------------------ administradores

    public boolean esAdministrador(String usuario) {
        return usuario != null && administradores.contains(usuario.trim());
    }

    /**
     * Añade un administrador. <strong>No comprueba autoridad:</strong> la fachada
     * {@code ServicioSeguridad} debe decidir quién puede elevar privilegios.
     */
    public void hacerAdministrador(String usuario) {
        if (permitirTodo || usuario == null || usuario.trim().isEmpty()) return;
        administradores.add(usuario.trim());
    }

    /** Copia no modificable del conjunto de administradores. */
    public Set<String> administradores() {
        return Collections.unmodifiableSet(new HashSet<>(administradores));
    }

    // ------------------------------------------------------------------ ciclo de vida de objetos

    /** Llamado por el FS cuando se crea un archivo o directorio. */
    public void objetoCreado(int idObjeto, String ruta) {
        if (permitirTodo) return;
        rutas.put(idObjeto, ruta);
        // El usuario actual (si hay sesión) recibe rwx por defecto sobre lo que crea
        if (usuarioActual != null) {
            conceder(usuarioActual, idObjeto, "rwx");
        }
    }

    /** Llamado por el FS cuando se elimina un objeto. */
    public void objetoEliminado(int idObjeto) {
        if (permitirTodo) return;
        matriz.remove(idObjeto);
        rutas.remove(idObjeto);
    }

    /** El idObjeto es estable: al renombrar solo cambia la ruta informativa. */
    public void objetoRenombrado(int idObjeto, String nuevaRuta) {
        if (permitirTodo) return;
        rutas.put(idObjeto, nuevaRuta);
    }

    /** Registra la ruta de un objeto al montar, sin conceder permisos nuevos. */
    public void objetoCargado(int idObjeto, String ruta) {
        if (permitirTodo) return;
        rutas.put(idObjeto, ruta);
    }

    // ------------------------------------------------------------------ persistencia

    /**
     * Carga matriz, rutas y administradores. Si el archivo no existe devuelve una
     * matriz vacía con admin/docente como administradores.
     *
     * @throws IOException si el archivo existe pero contiene directivas inválidas
     */
    public static ControlAcceso cargar(Path archivo) throws IOException {
        if (archivo == null) throw new IllegalArgumentException("archivo nulo");
        ControlAcceso resultado = new ControlAcceso();
        if (!Files.exists(archivo)) return resultado;

        // El archivo existente es la fuente de verdad para los administradores.
        resultado.administradores.clear();
        List<String> lineas = Files.readAllLines(archivo, StandardCharsets.UTF_8);
        for (int i = 0; i < lineas.size(); i++) {
            String linea = lineas.get(i).trim();
            if (linea.isEmpty() || linea.startsWith("#")) continue;
            if (linea.startsWith("ADMIN:")) {
                String usuario = linea.substring("ADMIN:".length()).trim();
                if (usuario.isEmpty()) {
                    throw new IOException("ADMIN sin usuario en línea " + (i + 1));
                }
                resultado.administradores.add(usuario);
            } else if (linea.startsWith("RUTA:")) {
                String[] campos = linea.split(":", 3);
                if (campos.length != 3) {
                    throw new IOException("RUTA inválida en línea " + (i + 1));
                }
                resultado.rutas.put(parsearId(campos[1], i + 1), campos[2].trim());
            } else if (linea.startsWith("PERM:")) {
                // split con límite 5: la ruta puede contener dos puntos.
                String[] campos = linea.split(":", 5);
                if (campos.length != 5 || campos[3].trim().isEmpty()) {
                    throw new IOException("PERM inválido en línea " + (i + 1));
                }
                int id = parsearId(campos[1], i + 1);
                String ruta = campos[2].trim();
                String usuario = campos[3].trim();
                if (!ruta.isEmpty() && !resultado.rutas.containsKey(id)) {
                    resultado.rutas.put(id, ruta);
                }
                resultado.matriz.computeIfAbsent(id, k -> new HashMap<>())
                        .put(usuario, Derecho.mascaraDesdeCadena(campos[4].trim()));
            } else {
                throw new IOException("Directiva desconocida en línea " + (i + 1));
            }
        }
        return resultado;
    }

    /** Escribe ADMIN, RUTA y PERM en orden determinista; nunca guarda la sesión. */
    public void guardar(Path archivo) throws IOException {
        if (archivo == null) throw new IllegalArgumentException("archivo nulo");
        Path padre = archivo.toAbsolutePath().getParent();
        if (padre != null) Files.createDirectories(padre);

        List<String> lineas = new ArrayList<>();
        for (String usuario : new TreeSet<>(administradores)) {
            lineas.add("ADMIN:" + usuario);
        }
        Set<Integer> ids = new TreeSet<>(rutas.keySet());
        ids.addAll(matriz.keySet());
        for (Integer id : ids) {
            String ruta = rutas.get(id);
            if (ruta != null && !ruta.isEmpty()) {
                lineas.add("RUTA:" + id + ":" + ruta);
            }
            Map<String, Integer> fila = matriz.get(id);
            if (fila == null) continue;
            for (String usuario : new TreeSet<>(fila.keySet())) {
                lineas.add("PERM:" + id + ":" + (ruta == null ? "" : ruta)
                        + ":" + usuario + ":" + mascaraACadena(fila.get(usuario)));
            }
        }
        Files.write(archivo, lineas, StandardCharsets.UTF_8);
    }

    private static int parsearId(String texto, int numeroLinea) throws IOException {
        try {
            return Integer.parseInt(texto.trim());
        } catch (NumberFormatException e) {
            throw new IOException("idObjeto inválido en línea " + numeroLinea, e);
        }
    }

    // ------------------------------------------------------------------ presentación

    /** Lista legible de la matriz (para la CLI). */
    public String listarMatriz() {
        if (permitirTodo) return "(modo PERMITIR_TODO — sin restricciones)";
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-8s %-30s %s%n", "ID", "Ruta", "Permisos"));
        sb.append("----------------------------------------------------------\n");
        Set<Integer> ids = new HashSet<>(matriz.keySet());
        ids.addAll(rutas.keySet());
        List<Integer> ordenados = new ArrayList<>(ids);
        Collections.sort(ordenados);
        for (Integer id : ordenados) {
            String ruta = rutas.getOrDefault(id, "?");
            Map<String, Integer> fila = matriz.get(id);
            if (fila == null || fila.isEmpty()) {
                sb.append(String.format("%-8d %-30s (sin permisos)%n", id, ruta));
                continue;
            }
            for (String usuario : new TreeSet<>(fila.keySet())) {
                sb.append(String.format("%-8d %-30s %s:%s%n",
                        id, ruta, usuario, mascaraACadena(fila.get(usuario))));
            }
        }
        sb.append("Administradores: ").append(String.join(", ", new TreeSet<>(administradores))).append('\n');
        return sb.toString();
    }

    private static String mascaraACadena(int m) {
        StringBuilder s = new StringBuilder();
        if ((m & 1) != 0) s.append('r');
        if ((m & 2) != 0) s.append('w');
        if ((m & 4) != 0) s.append('x');
        return s.length() == 0 ? "-" : s.toString();
    }
}
