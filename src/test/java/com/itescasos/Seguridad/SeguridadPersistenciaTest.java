package com.itescasos.Seguridad;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Pruebas de persistencia y autoridad de la capa de seguridad. */
public class SeguridadPersistenciaTest {

    @Rule
    public TemporaryFolder temporal = new TemporaryFolder();

    @Test
    public void credencialesDemoVerificadasSinClaro() throws Exception {
        Autenticacion auth = new Autenticacion();
        assertTrue(auth.verificar("admin", "admin123"));
        assertTrue(auth.verificar("docente", "docente"));
        assertTrue(auth.verificar("alumno", "alumno"));
        assertTrue(auth.verificar("invitado", "invitado"));
        assertTrue(auth.verificar("ana", "ana"));
        assertTrue(auth.verificar("bruno", "bruno"));
        assertFalse(auth.verificar("admin", "docente"));
        assertFalse(auth.verificar("desconocido", "admin123"));

        Path archivo = temporal.getRoot().toPath().resolve("auth.txt");
        auth.guardar(archivo);
        String contenido = new String(Files.readAllBytes(archivo), StandardCharsets.UTF_8);
        assertFalse(contenido.contains("admin123"));
        List<String> lineas = Files.readAllLines(archivo, StandardCharsets.UTF_8);
        assertEquals(6, lineas.size());
        assertEquals(4, lineas.get(0).split(":", -1).length);
    }

    @Test
    public void autenticacionRoundTrip() throws Exception {
        Path archivo = temporal.getRoot().toPath().resolve("sub/auth.txt");
        Autenticacion cargada = Autenticacion.cargar(archivo); // no existe aún
        assertTrue(cargada.verificar("admin", "admin123"));
        cargada.registrar("admin", "nuevaClave");
        cargada.guardar(archivo);

        Autenticacion recargada = Autenticacion.cargar(archivo);
        assertFalse(recargada.verificar("admin", "admin123"));
        assertTrue(recargada.verificar("admin", "nuevaClave"));
        assertTrue(recargada.existe("alumno"));
    }

    @Test
    public void controlAccesoRoundTripYAutoridad() throws Exception {
        Path archivo = temporal.getRoot().toPath().resolve("control.txt");
        ControlAcceso control = ControlAcceso.cargar(archivo);
        assertTrue(control.esAdministrador("admin"));
        assertTrue(control.esAdministrador("docente"));
        assertFalse(control.esAdministrador("alumno"));
        assertFalse(control.autorizar(1, Derecho.LEER, "/"));

        control.login("alumno");
        control.objetoCreado(7, "/tareas");
        assertTrue(control.autorizar(7, Derecho.ESCRIBIR, "/tareas"));
        control.objetoRenombrado(7, "/tareas-v2");
        // concesión directa: la fachada es quien comprueba autoridad
        control.conceder("docente", 7, "r");
        control.hacerAdministrador("alumno");
        control.guardar(archivo);

        ControlAcceso recargado = ControlAcceso.cargar(archivo);
        assertTrue(recargado.esAdministrador("alumno"));
        assertFalse(recargado.haySesion());
        assertFalse(recargado.autorizar(7, Derecho.LEER, "/tareas-v2")); // no hay sesión al cargar
        recargado.login("alumno");
        assertTrue(recargado.autorizar(7, Derecho.ESCRIBIR, "/tareas-v2"));
        recargado.login("docente");
        assertTrue(recargado.autorizar(7, Derecho.LEER, "/tareas-v2"));
        assertFalse(recargado.autorizar(7, Derecho.ESCRIBIR, "/tareas-v2"));

        recargado.revocar("alumno", 7);
        recargado.login("alumno");
        assertFalse(recargado.autorizar(7, Derecho.LEER, "/tareas-v2"));
    }

    @Test
    public void permitirTodoNoSeContamina() throws Exception {
        ControlAcceso real = new ControlAcceso();
        real.login("admin");
        real.objetoCreado(1, "/");
        real.hacerAdministrador("invitado");

        ControlAcceso permisivo = ControlAcceso.PERMITIR_TODO;
        assertTrue(permisivo.autorizar(999, Derecho.EJECUTAR, "/no/existe"));
        // Mutaciones sobre el singleton (p. ej. al formatear) no dejan rastro.
        permisivo.objetoCreado(999, "/no/existe");
        permisivo.conceder("alumno", 999, "rwx");
        permisivo.hacerAdministrador("alumno");
        assertTrue(permisivo.listarMatriz().contains("PERMITIR_TODO"));
        assertFalse(permisivo.administradores().contains("alumno"));
        assertFalse(permisivo.esAdministrador("invitado"));
        assertEquals("(modo PERMITIR_TODO — sin restricciones)", permisivo.listarMatriz());

        Path archivo = temporal.getRoot().toPath().resolve("permitir-todo.txt");
        permisivo.guardar(archivo);
        assertEquals(0, Files.readAllLines(archivo, StandardCharsets.UTF_8).size());
        assertFalse(ControlAcceso.cargar(archivo).autorizar(1, Derecho.LEER, "/"));
        assertTrue(real.autorizar(1, Derecho.LEER, "/"));
    }
}
