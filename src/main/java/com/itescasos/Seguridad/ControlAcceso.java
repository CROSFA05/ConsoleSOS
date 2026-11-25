package com.itescasos.Seguridad;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Matriz de acceso: sujetos (usuarios) × objetos (idObjeto estable del FS).
 * El idObjeto no cambia al renombrar y no se reutiliza (ver InfoArchivo).
 *
 * PERMITIR_TODO se usa en montajes de prueba o cuando aún no hay sesión.
 */
public class ControlAcceso {

    /** Implementación que autoriza cualquier operación (útil para pruebas unitarias del FS). */
    public static final ControlAcceso PERMITIR_TODO = new ControlAcceso(true);

    private final boolean permitirTodo;
    private String usuarioActual; // null = no autenticado
    // idObjeto -> (usuario -> máscara de bits rwx)
    private final Map<Integer, Map<String, Integer>> matriz = new HashMap<>();
    // objetos conocidos (ruta solo informativa)
    private final Map<Integer, String> rutas = new HashMap<>();

    public ControlAcceso() {
        this(false);
    }

    private ControlAcceso(boolean permitirTodo) {
        this.permitirTodo = permitirTodo;
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
     * @param rwx cadena tipo "rw", "rwx", "r", etc.
     */
    public void conceder(String usuario, int idObjeto, String rwx) {
        if (usuario == null || usuario.isEmpty()) return;
        int mask = Derecho.mascaraDesdeCadena(rwx);
        matriz.computeIfAbsent(idObjeto, k -> new HashMap<>()).put(usuario, mask);
    }

    public void revocar(String usuario, int idObjeto) {
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

    // ------------------------------------------------------------------ ciclo de vida de objetos

    /** Llamado por el FS cuando se crea un archivo o directorio. */
    public void objetoCreado(int idObjeto, String ruta) {
        rutas.put(idObjeto, ruta);
        // El usuario actual (si hay sesión) recibe rwx por defecto sobre lo que crea
        if (usuarioActual != null) {
            conceder(usuarioActual, idObjeto, "rwx");
        }
    }

    /** Llamado por el FS cuando se elimina un objeto. */
    public void objetoEliminado(int idObjeto) {
        matriz.remove(idObjeto);
        rutas.remove(idObjeto);
    }

    /** Lista legible de la matriz (para la CLI). */
    public String listarMatriz() {
        if (permitirTodo) return "(modo PERMITIR_TODO — sin restricciones)";
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-8s %-30s %s%n", "ID", "Ruta", "Permisos"));
        sb.append("----------------------------------------------------------\n");
        Set<Integer> ids = new HashSet<>(matriz.keySet());
        ids.addAll(rutas.keySet());
        for (Integer id : ids) {
            String ruta = rutas.getOrDefault(id, "?");
            Map<String, Integer> fila = matriz.get(id);
            if (fila == null || fila.isEmpty()) {
                sb.append(String.format("%-8d %-30s (sin permisos)%n", id, ruta));
                continue;
            }
            for (Map.Entry<String, Integer> e : fila.entrySet()) {
                sb.append(String.format("%-8d %-30s %s:%s%n",
                        id, ruta, e.getKey(), mascaraACadena(e.getValue())));
            }
        }
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
