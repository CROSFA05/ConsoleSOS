package com.itescasos.Seguridad;

import java.util.HashMap;
import java.util.Map;

/**
 * Tabla simple de usuarios y contraseñas en memoria (educativa).
 * Las contraseñas no se persisten en texto claro en el README ni en logs.
 */
public class Autenticacion {

    private final Map<String, String> usuarios = new HashMap<>();

    public Autenticacion() {
        // Usuarios de prueba documentados en el README (no son secretos reales)
        usuarios.put("admin", "admin123");
        usuarios.put("docente", "docente");
        usuarios.put("alumno", "alumno");
        usuarios.put("invitado", "invitado");
    }

    public void registrar(String usuario, String clave) {
        if (usuario == null || usuario.trim().isEmpty()) {
            throw new IllegalArgumentException("usuario vacío");
        }
        usuarios.put(usuario.trim(), clave == null ? "" : clave);
    }

    public boolean verificar(String usuario, String clave) {
        if (usuario == null) return false;
        String esperada = usuarios.get(usuario.trim());
        if (esperada == null) return false;
        return esperada.equals(clave == null ? "" : clave);
    }

    public boolean existe(String usuario) {
        return usuario != null && usuarios.containsKey(usuario.trim());
    }

    public Iterable<String> listarUsuarios() {
        return usuarios.keySet();
    }
}
