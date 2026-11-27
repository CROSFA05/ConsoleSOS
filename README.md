# SOS — Simulador Educativo de Sistema Operativo

Integración completa de los cinco módulos del proyecto — **planificación de CPU**, **administración de memoria**, **entrada/salida (E/S)**, **sistema de archivos** y **seguridad** — dentro de **una sola CLI y un solo JAR ejecutable**, respaldado por el lanzador `sos.ps1`. Todas las métricas, tablas, contadores y resúmenes se obtienen a partir del estado simulado en ejecución; no existen resultados precalculados ni simulaciones desconectadas del reloj.

---

## Requisitos y compilación

- **Java 8 (JDK 1.8)** configurado como versión objetivo en `pom.xml` (`1.8`).
- **Apache Maven** (`mvn`) para compilar el código fuente y empaquetar el JAR ejecutable mediante `maven-jar-plugin` con clase principal `com.itescasos.App`.
- **Lanzador de PowerShell (`sos.ps1`)** ubicado en la raíz del repositorio para simplificar la compilación, el diagnóstico del entorno y la ejecución tanto en modo interactivo como por subcomandos.

Desde la raíz del repositorio (`consolesos`):

```powershell
# Compilar y empaquetar el JAR en target\consolesos-1.0-SNAPSHOT.jar
mvn clean package

# Abrir el simulador sin argumentos (modo interactivo / consola)
.\sos.ps1

# Compilar con Maven y ejecutar en un solo paso
.\sos.ps1 -Build

# Verificar disponibilidad de Java y Maven en el sistema
.\sos.ps1 -Doctor
```

> **Nota:** Si ejecutas `java -jar` directamente sin pasar por `sos.ps1`, el archivo generado por Maven se encuentra en `.\target\consolesos-1.0-SNAPSHOT.jar`.

---

## Decisiones de diseño

- **`Core.Reloj` como única fuente de tiempo:** Todos los subsistemas (CPU, memoria, E/S, sistema de archivos y seguridad) comparten la misma línea de tiempo. Cada intervalo de CPU corresponde a `[t, t+1)` y el cambio de contexto tiene un costo de `0` ticks.
- **Planificación de CPU:** Prioridad numérica menor representa mayor prioridad (`0` es más prioritario que `1`). En SJF se evalúa la ráfaga actual del proceso; en RR los procesos que agotan su quantum se reencolan al final de la cola de listos; `quantum=0` es válido en algoritmos sin quantum fijo, pero no aplica en RR.
- **Integración de memoria y E/S:** Memoria y E/S se conectan al motor de ejecución mediante `PlanificadorCPU.ejecutarConGancho(...)`. Cuando ocurre un fallo de página con carga bloqueante o una operación de E/S, solo se bloquea el proceso solicitante y el diagrama de Gantt nunca contabiliza ese tiempo de espera como ejecución efectiva de CPU.
- **Acceso a disco mediante driver:** El sistema de archivos no escribe ni lee directamente el medio ignorando el driver: cada operación de bloque genera una solicitud hacia `DiscoSimulado`. Tanto la CLI como el ejecutor de escenarios presentan resúmenes de accesos para mantener legible la salida.
- **Seguridad centralizada:** `ServicioSeguridad` concentra la autenticación, la autorización y el cifrado de archivos. La sesión activa **no** se persiste entre procesos independientes, mientras que la matriz de permisos y los verificadores PBKDF2 **sí** se persisten. Contar con privilegios de administrador habilita la gestión de permisos y usuarios, pero no omite la evaluación de la matriz; el creador de un objeto recibe permisos `rwx` sobre él.
- **Identidad en escenarios:** La directiva `usuario=` dentro de un archivo de escenario únicamente selecciona la identidad con la que se autentica el flujo; no otorga privilegios por sí sola ni almacena contraseñas.
- **Verificación segura con `fsck`:** El diagnóstico y reparación de consistencia operan siempre sobre una copia temporal de la imagen, de modo que el archivo `.img` original nunca se altera, aun cuando se utilice `--fix`.

---

## Referencia de la CLI

### Subcomandos de simulación (`sim`)

```powershell
.\sos.ps1 sim plan [escenario.txt]   # Diagrama de Gantt y métricas de CPU
.\sos.ps1 sim mem  [escenario.txt]   # Fallos y aciertos de paginación
.\sos.ps1 sim io   [escenario.txt]   # Comparación de políticas FCFS / SCAN / C-SCAN
.\sos.ps1 sim run  <escenario.txt>   # Ejecución del ciclo integrado completo
```

### Subcomandos de sistema de archivos (`fs` y `fsck`)

```powershell
.\sos.ps1 fs format [imagen.img]
.\sos.ps1 fs mount [imagen.img] [fcfs|scan|cscan]
.\sos.ps1 fs umount
.\sos.ps1 fs mkdir <ruta> | fs create <ruta> [tam]
.\sos.ps1 fs write <ruta> <texto...> | fs append <ruta> <texto...>
.\sos.ps1 fs read <ruta> | fs ls [ruta] | fs stat <ruta> | fs df
.\sos.ps1 fs rename <origen> <destino> | fs rm <ruta>
.\sos.ps1 fs fsck [--fix] [--dano N]
.\sos.ps1 fsck [imagen.img] [--fix] [--dano N]
```

### Subcomandos de autenticación y seguridad (`auth` y `sec`)

```powershell
.\sos.ps1 auth login <usuario> [clave] | auth logout | auth who | auth users
.\sos.ps1 sec permiso <usuario> <id|ruta> <rwx> | sec revocar <usuario> <id|ruta>
.\sos.ps1 sec administrador <usuario> | sec dispositivo <nombre> <usuario> <rwx>
.\sos.ps1 sec matriz | sec usuarios
.\sos.ps1 sec cifrar <ruta> [clave] | sec descifrar <ruta> [clave]
```

### Modo interactivo

Al invocar `.\sos.ps1` (o `java -jar .\target\consolesos-1.0-SNAPSHOT.jar`) sin argumentos, el programa entra en **modo interactivo** y lee comandos desde la entrada estándar (`help`, `exit`). Este modo es el recomendado cuando necesitas encadenar varias operaciones de sesión, autenticación y sistema de archivos dentro de un mismo estado vivo, ya que cada invocación externa con argumentos arranca un proceso nuevo.

### Variables de entorno y manejo de credenciales

| Variable | Propósito |
|---|---|
| `SOS_CLAVE` | Contraseña de sesión cuando no se proporciona explícitamente en el comando. |
| `SOS_CLAVE_<USUARIO>` | Contraseña específica por usuario (por ejemplo `SOS_CLAVE_ADMIN`) al cambiar de sesión en modo interactivo. |
| `SOS_CLAVE_ARCHIVO` | Clave utilizada para cifrar y descifrar archivos en el sistema de archivos. |
| `SOS_IMAGEN` | Ruta de imagen de disco por defecto; se monta de forma automática si existe. |
| `SOS_SEGURIDAD_DIR` | Directorio donde se almacenan `sos-usuarios.txt` y `sos-matriz.txt` (por defecto el directorio actual `.`). |

Las contraseñas nunca se escriben en archivos de registro ni dentro de archivos de escenario. Si no existe una variable de entorno definida, el sistema solicita la clave sin eco en consola mediante `Console.readPassword`. Cuando canalices un archivo de comandos por `stdin`, define primero las variables de entorno correspondientes para que ninguna línea del guion sea leída como contraseña.

**Usuarios de demostración disponibles:** `admin/admin123`, `docente/docente`, `alumno/alumno`, `invitado/invitado`, `ana/ana` y `bruno/bruno`. Los usuarios `admin` y `docente` cuentan con rol de administrador desde el inicio.

---

## Formato de escenarios

Los archivos de escenario tienen extensión `.txt` y pueden organizar su configuración en las secciones `[procesos]`, `[planificacion]`, `[memoria]`, `[io]`, `[archivos]` y `[seguridad]`. Las líneas que inician con `#` se tratan como comentarios, y los errores de sintaxis o validación reportan el número de línea real del archivo.

```text
[procesos]
P1, llegada=0, cpu=2+2, io=0+0, prioridad=2, memoria=8192, paginas=3, leer=/docs/notas.txt

[planificacion]
algoritmo = rr
quantum = 2
comparar = fcfs, sjf, rr

[memoria]
marcos_totales = 3
tam_pagina = 4096
reemplazo = fifo
pid = P1
R1, pid=P1, pagina=0, t=0, op=leer
R2, pid=P1, pagina=1, t=1, op=escribir
# también vale: referencias = 0,1,2,0,3,1,4,0

[io]
politica = scan            # alias: algoritmo = scan
cilindros = 100
bloquesPorCilindro = 1
cabezal = 40
direccion = asc
cilindrosPorTick = 10
terminal.entrada = hola
D1, t=0, pid=P1, dispositivo=disco, op=leer, destino=10

[archivos]
imagen = demo.img
formatear = si
mkdir /docs
crear /docs/notas.txt 64
escribir /docs/notas.txt "contenido de prueba"

[seguridad]
usuario = docente
clave_archivo = SOS_CLAVE_ARCHIVO
permiso = ana:/docs/notas.txt:rw
dispositivo = disco:bruno:rwx
```

### Reglas y convenciones de escenarios

- **Ráfagas alternadas:** La notación `cpu=2+3, io=0+2` representa una secuencia de CPU `2`, E/S `2` y CPU `3`. Las ráfagas de E/S se alinean con las de CPU y, si se omiten o faltan valores, se completan con `0`.
- **Referencias de memoria:** La lista `referencias=` se asocia al `pid` declarado en `[memoria]`. Las líneas tipo `R1, ...` permiten especificar adicionalmente el instante `t` y el tipo de acceso `op=leer|escribir`.
- **Referencia a clave de archivo:** `clave_archivo=NOMBRE_VAR` indica el nombre de la variable de entorno a consultar; el secreto en sí nunca se escribe dentro del escenario. Si se omite, se toma `SOS_CLAVE_ARCHIVO`.
- **Alcance de ejecución:** `sim plan`, `sim mem` y `sim io` procesan la sección correspondiente, mientras que `sim run` ejecuta las secciones presentes y luego corre el ciclo integrado bajo un único reloj.

En la carpeta `escenarios/` se incluyen los archivos de prueba listos para la demostración: `cpu.txt`, `memoria.txt`, `disco.txt`, `integrado.txt` y `cli-comandos.txt` (este último es un guion de comandos para entrada interactiva, no un escenario para el parser de simulación).

---

## Demo reproducible

### 1. Planificación de CPU

```powershell
.\sos.ps1 sim plan
.\sos.ps1 sim plan escenarios\cpu.txt
```

Caso de control (cinco procesos, costo de cambio de contexto `0`): tiempos promedio de espera / retorno esperados de `6,60 / 9,60` en FCFS, `4,00 / 7,00` en SJF, `7,60 / 10,60` en RR (`q=2`) y `6,20 / 9,20` por prioridades.

### 2. Administración de memoria

```powershell
.\sos.ps1 sim mem
.\sos.ps1 sim mem escenarios\memoria.txt
```

Para la cadena de referencias `0,1,2,0,3,1,4,0` con `3` marcos: FIFO produce **6** fallos, LRU produce **7** fallos y Óptimo produce **5** fallos.

### 3. Entrada/Salida y planificación de disco

```powershell
.\sos.ps1 sim io
.\sos.ps1 sim io escenarios\disco.txt
```

Con cabezal inicial en `40` y cola `10,70,20,90`: FCFS recorre **210** cilindros, SCAN recorre **148** cilindros y C-SCAN recorre **178** cilindros.

### 4. Ciclo integrado (demostración principal)

```powershell
$env:SOS_CLAVE = 'docente'
.\sos.ps1 sim run escenarios\integrado.txt
```

Ejecuta bajo un solo reloj la comparación de planificadores, el análisis de fallos de memoria, la comparación de políticas de disco y el ciclo integrado completo: formateo y montaje a través del driver, creación/escritura/lectura de archivos con validación de permisos y dispositivos, referencias de página con carga bloqueante, atención de solicitudes de disco y terminal, y liberación ordenada de recursos al finalizar.

### 5. Sistema de archivos, permisos y cifrado

```powershell
.\sos.ps1 fs format cli.img
$env:SOS_IMAGEN = 'cli.img'
$env:SOS_CLAVE_ADMIN = 'admin123'$env:SOS_CLAVE_ALUMNO = 'alumno'
$env:SOS_CLAVE_INVITADO = 'invitado'$env:SOS_CLAVE_ARCHIVO = 'clave-de-demo'
Get-Content escenarios\cli-comandos.txt | .\sos.ps1
```

Comportamiento esperado: `admin` crea y escribe `/clase/notas.txt`; el permiso otorgado a `alumno` permanece tras `fs rename` al conservarse el `idObjeto`; `invitado` recibe `PERMISO_DENEGADO` ante accesos no autorizados; `sec cifrar` hace que `fs read` reporte únicamente el tamaño cifrado; `sec descifrar` con la clave correcta recupera el contenido original; y el cierre resume los accesos a bloques realizados mediante el driver de disco.

Para comprobar el rechazo por clave errónea, modifica el valor de `$env:SOS_CLAVE_ARCHIVO` antes de ejecutar `sec descifrar` y verifica la respuesta `Clave incorrecta: el archivo no se modificó`.

### 6. Verificación de integridad con `fsck` sobre copia temporal

```powershell
$hashAntes = (Get-FileHash cli.img -Algorithm SHA256).Hash
.\sos.ps1 fsck cli.img                 # Verifica consistencia inicial
.\sos.ps1 fsck cli.img --dano 67       # Simula daño solo en copia temporal
.\sos.ps1 fsck cli.img --dano 67 --fix # Repara solo en copia temporal
$hashDespues = (Get-FileHash cli.img -Algorithm SHA256).Hash
$hashAntes -eq$hashDespues                    # Debe resultar True
```

El bloque `67` corresponde a `/clase/apuntes.txt` en la secuencia de ejemplo (`fs stat` lo muestra en `bloques=[67]`). El parámetro `--dano N` limpia el bit del bloque `N` en el mapa de bits de la copia temporal sin alterar los datos del bloque, provocando que `fsck` reporte `BLOQUE_EN_USO_MARCADO_LIBRE`. Revisa siempre `fs stat` en tu imagen antes de elegir el número de bloque.

---

## Información del equipo

- **Integrantes:** `Oscar Cantú`, `Jesós Páez`, `Samuel Escalante`, `Luis Figueroa`, `Tadeo Peña`
- **Materia:** Sistemas Operativos `WSIO01`
- **Fecha de Entrega:** 27/11/2026

## Repositorio y GitHub

- **Repositorio de GitHub:** `https://github.com/CROSFA05/ConsoleSOS.git`
- **Archivos incluidos en el control de versiones:** código fuente Java en `src/`, configuración de compilación `pom.xml`, lanzador `sos.ps1`, archivos de prueba en `escenarios/` y este `README.md`.
- **Archivos excluidos por `.gitignore`:** `target/`, `build/`, `*.img`, `.vscode/`, `sos-matriz.txt` y `sos-usuarios.txt`, evitando subir binarios compilados, imágenes de disco o archivos de estado locales generados durante las pruebas.
- **Comandos para revisar y publicar en GitHub:**

```powershell
git status
git add .
git commit -m "Finalizar simulador SOS con JAR Java 8, sos.ps1 y README"
git push -u origin main
```