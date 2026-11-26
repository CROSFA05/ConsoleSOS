package com.itescasos.Seguridad;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Registro de usuarios. Nunca guarda contraseñas en claro: almacena únicamente un
 * verificador derivado con PBKDF2WithHmacSHA256, sal aleatoria de 16 bytes por
 * usuario y 100000 iteraciones. La comparación se hace en tiempo constante.
 *
 * <p>Usuarios de prueba (credenciales de demostración, no secretos reales):
 * <ul>
 *   <li>{@code admin / admin123}</li>
 *   <li>{@code docente / docente}</li>
 *   <li>{@code alumno / alumno}</li>
 *   <li>{@code invitado / invitado}</li>
 *   <li>{@code ana / ana}</li>
 *   <li>{@code bruno / bruno}</li>
 * </ul>
 *
 * <p>Formato de persistencia (texto UTF-8, una línea por usuario, sin claves):
 * <pre>
 * usuario: Base64(sal): Base64(verificador): iteraciones
 * </pre>
 * Los espacios después de los dos puntos son opcionales al leer. Las líneas
 * vacías y las que empiezan por {@code #} se ignoran. Si el archivo no existe,
 * {@link #cargar(Path)} devuelve los usuarios de prueba con sales nuevas, que se
 * materializan al llamar a {@link #guardar(Path)}.
 */
public class Autenticacion {

    private static final int SAL_BYTES = 16;
    private static final int ITERACIONES = 100000;
    private static final int BITS_VERIFICADOR = 256;
    private static final String ALGORITMO = "PBKDF2WithHmacSHA256";
    private static final SecureRandom ALEATORIO = new SecureRandom();

    private final Map<String, Registro> usuarios = new TreeMap<>();

    /** Crea la tabla con los usuarios de demostración documentados en el README. */
    public Autenticacion() {
        this(true);
    }

    private Autenticacion(boolean conUsuariosDemo) {
        if (conUsuariosDemo) {
            registrar("admin", "admin123");
            registrar("docente", "docente");
            registrar("alumno", "alumno");
            registrar("invitado", "invitado");
            registrar("ana", "ana");
            registrar("bruno", "bruno");
        }
    }

    /**
     * Registra (o actualiza) un usuario generando una sal nueva. La clave se
     * convierte en verificador derivado y no se conserva en memoria.
     */
    public void registrar(String usuario, String clave) {
        if (usuario == null || usuario.trim().isEmpty()) {
            throw new IllegalArgumentException("usuario vacío");
        }
        byte[] sal = new byte[SAL_BYTES];
        ALEATORIO.nextBytes(sal);
        usuarios.put(usuario.trim(), new Registro(sal, derivar(clave, sal, ITERACIONES), ITERACIONES));
    }

    /** Verifica la clave usando el verificador derivado del usuario. */
    public boolean verificar(String usuario, String clave) {
        if (usuario == null) return false;
        Registro registro = usuarios.get(usuario.trim());
        if (registro == null) return false;
        byte[] calculado = derivar(clave, registro.sal, registro.iteraciones);
        return MessageDigest.isEqual(calculado, registro.verificador);
    }

    public boolean existe(String usuario) {
        return usuario != null && usuarios.containsKey(usuario.trim());
    }

    public Iterable<String> listarUsuarios() {
        return usuarios.keySet();
    }

    /**
     * Carga usuarios desde un archivo de texto. Si no existe, devuelve la tabla
     * de demostración (con sales nuevas) sin crear el archivo.
     *
     * @throws IOException si el archivo existe pero alguna línea no es válida
     */
    public static Autenticacion cargar(Path archivo) throws IOException {
        if (archivo == null) throw new IllegalArgumentException("archivo nulo");
        if (!Files.exists(archivo)) return new Autenticacion();

        Autenticacion resultado = new Autenticacion(false);
        List<String> lineas = Files.readAllLines(archivo, StandardCharsets.UTF_8);
        for (int i = 0; i < lineas.size(); i++) {
            String linea = lineas.get(i).trim();
            if (linea.isEmpty() || linea.startsWith("#")) continue;
            String[] campos = linea.split(":", -1);
            if (campos.length != 4 || campos[0].trim().isEmpty()) {
                throw new IOException("Formato de autenticación inválido en línea " + (i + 1));
            }
            try {
                byte[] sal = Base64.getDecoder().decode(campos[1].trim());
                byte[] verificador = Base64.getDecoder().decode(campos[2].trim());
                int iteraciones = Integer.parseInt(campos[3].trim());
                if (sal.length == 0 || verificador.length == 0 || iteraciones < 1 || iteraciones > 10000000) {
                    throw new IllegalArgumentException("valores fuera de rango");
                }
                resultado.usuarios.put(campos[0].trim(), new Registro(sal, verificador, iteraciones));
            } catch (IllegalArgumentException e) {
                throw new IOException("Formato de autenticación inválido en línea " + (i + 1), e);
            }
        }
        return resultado;
    }

    /** Escribe los verificadores (nunca las claves) en texto UTF-8. */
    public void guardar(Path archivo) throws IOException {
        if (archivo == null) throw new IllegalArgumentException("archivo nulo");
        Path padre = archivo.toAbsolutePath().getParent();
        if (padre != null) Files.createDirectories(padre);

        List<String> lineas = new ArrayList<>();
        for (Map.Entry<String, Registro> entrada : usuarios.entrySet()) {
            Registro r = entrada.getValue();
            lineas.add(entrada.getKey() + ": "
                    + Base64.getEncoder().encodeToString(r.sal) + ": "
                    + Base64.getEncoder().encodeToString(r.verificador) + ": "
                    + r.iteraciones);
        }
        Files.write(archivo, lineas, StandardCharsets.UTF_8);
    }

    private static byte[] derivar(String clave, byte[] sal, int iteraciones) {
        char[] caracteres = (clave == null ? "" : clave).toCharArray();
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITMO);
            PBEKeySpec especificacion = new PBEKeySpec(caracteres, sal, iteraciones, BITS_VERIFICADOR);
            try {
                return factory.generateSecret(especificacion).getEncoded();
            } finally {
                especificacion.clearPassword();
            }
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("No se puede derivar la clave: " + ALGORITMO, e);
        } finally {
            Arrays.fill(caracteres, '\0');
        }
    }

    private static final class Registro {
        private final byte[] sal;
        private final byte[] verificador;
        private final int iteraciones;

        private Registro(byte[] sal, byte[] verificador, int iteraciones) {
            this.sal = sal;
            this.verificador = verificador;
            this.iteraciones = iteraciones;
        }
    }
}
