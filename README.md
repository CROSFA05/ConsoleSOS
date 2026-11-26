# SOS — Simulador Educativo de Sistema Operativo

Integración de los cinco módulos del proyecto (planificación de CPU, memoria, E/S,
sistema de archivos y seguridad) en **una sola CLI y un solo `sos.jar`**. Todas las
métricas se calculan a partir del estado simulado; no hay resultados precalculados.

## Requisitos y compilación

- JDK 17+ (probado con JDK 25). No se necesita Maven: `pom.xml` se conserva solo
  como referencia del proyecto original.
- Desde la raíz del repositorio:

```powershell
.\build.ps1
java -jar sos.jar help
```

`build.ps1` compila `src/main/java` con `javac -encoding UTF-8` y genera `sos.jar`
con `com.itescasos.cli.SosCli` como clase principal. `com.itescasos.App` queda como
alias histórico que delega en la misma CLI.

## Decisiones de diseño

- **`Core.Reloj` es la única fuente de tiempo.** CPU, memoria, E/S, archivos y
  seguridad comparten esa línea de tiempo. Un intervalo de CPU es `[t, t+1)`; el
  cambio de contexto cuesta 0 ticks.
- **CPU:** prioridad numérica menor = mayor prioridad; SJF compara la ráfaga
  actual; RR reencola al final; `quantum=0` es válido salvo en RR.
- **Memoria y E/S se enganchan al mismo motor** mediante
  `PlanificadorCPU.ejecutarConGancho(...)`. Una carga de página o una operación de
  archivo bloquean solo al proceso solicitante; el Gantt nunca cuenta ese tiempo
  como ejecución de CPU.
- **El FS alcanza el disco a través del driver.** Cada lectura/escritura de bloque
  se convierte en una solicitud de `DiscoSimulado`; la CLI y el ejecutor de
  escenarios resumen el número de accesos para no inundar la pantalla.
- **Seguridad:** `ServicioSeguridad` es el único punto de autenticación,
  autorización y cifrado. La sesión **no** se persiste; la matriz y los
  verificadores PBKDF2 **sí**. Ser administrador permite conceder permisos, pero
  no equivale a ignorar la matriz: el creador de un objeto recibe `rwx` sobre él.
- **`usuario=` en un escenario solo elige la identidad a autenticar.** Nunca
  concede autoridad y nunca incluye una clave.
- **`fsck` trabaja siempre sobre una copia temporal.** La imagen original no se
  modifica, ni siquiera con `--fix`.

## CLI

```powershell
java -jar sos.jar sim plan [escenario.txt]   # Gantt y métricas de CPU
java -jar sos.jar sim mem  [escenario.txt]   # fallos/aciertos de memoria
java -jar sos.jar sim io   [escenario.txt]   # FCFS / SCAN / C-SCAN
java -jar sos.jar sim run  <escenario.txt>   # ciclo integrado completo

java -jar sos.jar fs format [imagen.img]
java -jar sos.jar fs mount [imagen.img] [fcfs|scan|cscan]
java -jar sos.jar fs umount
java -jar sos.jar fs mkdir <ruta> | fs create <ruta> [tam]
java -jar sos.jar fs write <ruta> <texto...> | fs append <ruta> <texto...>
java -jar sos.jar fs read <ruta> | fs ls [ruta] | fs stat <ruta> | fs df
java -jar sos.jar fs rename <origen> <destino> | fs rm <ruta>
java -jar sos.jar fs fsck [--fix] [--dano N]
java -jar sos.jar fsck [imagen.img] [--fix] [--dano N]

java -jar sos.jar auth login <usuario> [clave] | auth logout | auth who | auth users
java -jar sos.jar sec permiso <usuario> <id|ruta> <rwx> | sec revocar <usuario> <id|ruta>
java -jar sos.jar sec administrador <usuario> | sec dispositivo <nombre> <usuario> <rwx>
java -jar sos.jar sec matriz | sec usuarios
java -jar sos.jar sec cifrar <ruta> [clave] | sec descifrar <ruta> [clave]
```

Sin argumentos, `java -jar sos.jar` entra en **modo interactivo** y lee comandos
por stdin (`help`, `exit`). Es la forma recomendada de encadenar operaciones de
archivos con una sesión: cada invocación con argumentos es un proceso nuevo.

### Variables de entorno y secretos

| Variable | Uso |
|---|---|
| `SOS_CLAVE` | Clave de sesión cuando no se pasa como argumento. |
| `SOS_CLAVE_<USUARIO>` | Clave específica (p. ej. `SOS_CLAVE_ADMIN`) al cambiar de usuario en modo interactivo. |
| `SOS_CLAVE_ARCHIVO` | Clave de cifrado/descifrado. |
| `SOS_IMAGEN` | Imagen por defecto; se monta automáticamente si existe. |
| `SOS_SEGURIDAD_DIR` | Directorio de `sos-usuarios.txt` y `sos-matriz.txt` (por defecto `.`). |

La clave nunca se escribe en el log ni en un escenario. Si no hay variable de
entorno, se pide por consola sin eco (`Console.readPassword`); al canalizar un
archivo por stdin, **defina las variables antes** para evitar que un comando se
consuma como si fuera la clave.

Usuarios de demostración: `admin/admin123`, `docente/docente`,
`alumno/alumno`, `invitado/invitado`, `ana/ana`, `bruno/bruno`. `admin` y
`docente` son administradores iniciales.

## Formato de escenarios

Un escenario es un `.txt` con secciones `[procesos]`, `[planificacion]`,
`[memoria]`, `[io]`, `[archivos]` y `[seguridad]`. Las líneas que empiezan por
`#` son comentarios y los errores se informan con el número de línea real.

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

Convenciones:

- `cpu=2+3, io=0+2` significa CPU 2, E/S 2, CPU 3. Las ráfagas de `io` se
  alinean con las de `cpu`; si faltan se rellenan con 0.
- `referencias=` pertenece al `pid` indicado en `[memoria]`. Las líneas `R1, ...`
  permiten además elegir `t` y `op=leer|escribir` por referencia.
- `clave_archivo=NOMBRE_VAR` declara **qué variable** consultar; el valor nunca
  se copia al escenario. Si no se declara, se usa `SOS_CLAVE_ARCHIVO`.
- `sim plan|mem|io` ejecuta solo la sección correspondiente; `sim run` ejecuta
  todas las secciones presentes y después el ciclo integrado con un solo reloj.

Los archivos listos para reproducir la demo están en `escenarios/`:
`cpu.txt`, `memoria.txt`, `disco.txt`, `integrado.txt` y
`cli-comandos.txt` (este último es un guion para el modo interactivo, **no** un
escenario del parser).

## Demo reproducible

### 1. Planificación

```powershell
java -jar sos.jar sim plan
java -jar sos.jar sim plan escenarios\cpu.txt
```

Caso de control (cinco procesos, cambio de contexto 0): FCFS espera/retorno
`6,60 / 9,60`; SJF `4,00 / 7,00`; RR con `q=2` `7,60 / 10,60`; prioridades
`6,20 / 9,20`.

### 2. Memoria

```powershell
java -jar sos.jar sim mem
java -jar sos.jar sim mem escenarios\memoria.txt
```

Cadena `0,1,2,0,3,1,4,0` con 3 marcos: FIFO **6** fallos, LRU **7**, Óptimo **5**.

### 3. E/S

```powershell
java -jar sos.jar sim io
java -jar sos.jar sim io escenarios\disco.txt
```

Cabezal en 40, cola `10,70,20,90`: FCFS **210**, SCAN **148**, C-SCAN **178**
cilindros.

### 4. Ciclo integrado (la demostración obligatoria, en este orden)

```powershell
$env:SOS_CLAVE = 'docente'
java -jar sos.jar sim run escenarios\integrado.txt
```

Muestra, con un solo reloj: comparación de planificadores, fallos de memoria,
comparación de políticas de disco y después el ciclo completo — montaje y
formateo a través del driver, creación/escritura/lectura de archivos con control
de acceso, permisos y dispositivos concedidos, referencias de página con carga
bloqueante, solicitudes de disco y terminal, y liberación de recursos al
terminar. El Gantt, los contadores de marcos y el resumen de E/S salen del estado
simulado.

### 5. Archivos, permisos y cifrado

```powershell
java -jar sos.jar fs format cli.img
$env:SOS_IMAGEN = 'cli.img'
$env:SOS_CLAVE_ADMIN = 'admin123'
$env:SOS_CLAVE_ALUMNO = 'alumno'
$env:SOS_CLAVE_INVITADO = 'invitado'
$env:SOS_CLAVE_ARCHIVO = 'clave-de-demo'
Get-Content escenarios\cli-comandos.txt | java -jar sos.jar
```

Evidencia esperada: `admin` crea y escribe `/clase/notas.txt`; el permiso concedido
a `alumno` sigue vigente tras `fs rename` porque el `idObjeto` no cambia;
`invitado` obtiene `PERMISO_DENEGADO`; `sec cifrar` hace que `fs read` muestre solo
el tamaño cifrado; `sec descifrar` con la clave correcta restaura el texto; y el
resumen final cuenta los accesos a bloques por el driver de disco.

Para probar una clave incorrecta, cambie `SOS_CLAVE_ARCHIVO` antes de
`sec descifrar`: el comando responde `Clave incorrecta: el archivo no se modificó`.

### 6. `fsck` sobre una copia (sin tocar la imagen original)

```powershell
$hashAntes = (Get-FileHash cli.img -Algorithm SHA256).Hash
java -jar sos.jar fsck cli.img                 # consistente
java -jar sos.jar fsck cli.img --dano 67       # corrompe SOLO la copia temporal
java -jar sos.jar fsck cli.img --dano 67 --fix # repara SOLO la copia temporal
$hashDespues = (Get-FileHash cli.img -Algorithm SHA256).Hash
$hashAntes -eq $hashDespues                    # True
```

El bloque 67 es el que ocupa `/clase/apuntes.txt` en la secuencia anterior;
`fs stat` lo muestra en `bloques=[67]`. `--dano N` apaga el bit de ese bloque en
el mapa de bits de la copia, sin alterar los bytes del bloque, y `fsck` detecta
`BLOQUE_EN_USO_MARCADO_LIBRE`. El número de bloque depende de la imagen; consulte
siempre `fs stat` antes de elegirlo.

## Pruebas unitarias

Si hay JUnit 4 disponible en `~/.m2`:

```powershell
$cp = "build\classes;$HOME\.m2\repository\junit\junit\4.12\junit-4.12.jar;$HOME\.m2\repository\org\hamcrest\hamcrest-core\1.3\hamcrest-core-1.3.jar"
javac -encoding UTF-8 -cp $cp -d build\test-classes (Get-ChildItem -Recurse -Filter *.java src\test\java | ForEach-Object { $_.FullName })
java -cp "build\test-classes;$cp" org.junit.runner.JUnitCore com.itescasos.AppTest com.itescasos.Memoria.GestorMemoriaTest com.itescasos.Seguridad.SeguridadPersistenciaTest
```

Resultado esperado: `OK (15 tests)`.
