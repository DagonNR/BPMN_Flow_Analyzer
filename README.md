# BPMN Flow Analyzer

Proyecto desarrollado para la materia de Verificación y Validación de Software.

## Objetivo

Desarrollar una herramienta en Java que permita analizar modelos BPMN,
identificar caminos estructurales y generar candidatos a casos de prueba,
tomando como referencia el procedimiento propuesto por De Moura (2017).

## Modelos BPMN

Se utilizan dos modelos para el desarrollo y las pruebas:

- **ApplicationProcess.bpmn:** proceso de evaluación de solicitudes de inscripción de proveedores de servicios de salud.
- **HardwareRetailer.bpmn:** proceso utilizado como referencia en el trabajo de De Moura.

Ambos archivos se encuentran en la carpeta `bpmn/`.

## Tecnologías

- Java 17
- Maven
- JUnit
- Apache POI 6.0.0 (`poi-ooxml`, compatible con Java 17)
- Git y GitHub

## Funcionalidades

**Implementadas:**

- Lectura de archivos BPMN 2.0.
- Identificación de tareas, eventos y gateways.
- Extracción de conexiones mediante `sequenceFlow`.
- Construcción de un grafo dirigido.
- Consulta de conexiones entrantes y salientes.
- Selección del archivo BPMN y proceso mediante argumentos.
- Validación de archivos, identificadores y referencias.
- Análisis de caminos con XOR, OR y AND, sincronización y límite de retornos.
- Registro de ramas activadas conjuntamente, sin enumerar sus permutaciones temporales.
- Tablas de tareas en consola y CSV para ambos modelos.
- CSV adicional de flujos por ramas AND, con trazabilidad a los caminos completos.
- Excel nativo con hojas `Caminos` y `Ramas`, generado directamente en Java.
- Clasificación de tareas humanas por tipo BPMN y configuración externa por ID.
- Escenarios Gherkin para ApplicationProcess y Hardware Retailer.

**Pendientes:**

- Generación de código Java inicial para Cucumber.

## Ejecución

Se requiere Java 17 o superior y Maven.

Desde la raíz del proyecto, compilar con:

```bash
mvn package
```

Maven copia las dependencias de ejecución a `target/dependency`. Los siguientes
comandos usan el separador `;` de Windows. En Linux/macOS se debe sustituir por `:`.
No se requiere Microsoft Excel instalado para generar los libros.
Se utiliza el logger sencillo incluido en las dependencias de POI, sin agregar
una implementación de logging adicional.

**Ejecutar ApplicationProcess (predeterminado):**

```bash
java -cp "target/classes;target/dependency/*" com.pia.vvs.Main
```

**Ejecutar un BPMN específico:**

```bash
java -cp "target/classes;target/dependency/*" com.pia.vvs.Main "bpmn/ApplicationProcess.bpmn" "gov.medicaid.process.application"
```

**Ejecutar Hardware Retailer:**

```bash
java -cp "target/classes;target/dependency/*" com.pia.vvs.Main "bpmn/HardwareRetailer.bpmn" "_6-1"
```

El programa recibe cero, dos, tres o cuatro argumentos:

```text
java -cp "target/classes;target/dependency/*" com.pia.vvs.Main [archivo.bpmn procesoId [directorioSalida [maxRetornos]]]
```

Sin argumentos utiliza ApplicationProcess. El directorio predeterminado es
`target/generated-tests` y el límite predeterminado es `2` (admite de 0 a 100).
ApplicationProcess genera `ApplicationProcess.csv`, `ApplicationProcess-ramas.csv`,
`ApplicationProcess.xlsx` y `ApplicationProcess.feature`. Hardware Retailer genera
`HardwareRetailer.csv`, `HardwareRetailer-ramas.csv`, `HardwareRetailer.xlsx` y
`HardwareRetailer.feature`. Para otros modelos se utiliza
el nombre base del archivo de entrada. El CSV sin sufijo conserva los caminos
conjuntos, incluidos los truncados; el sufijo `-ramas` identifica las proyecciones
de caminos completos descritas más adelante.

```bash
java -cp "target/classes;target/dependency/*" com.pia.vvs.Main bpmn/HardwareRetailer.bpmn _6-1 target/hardware 2
```

### Exportación Excel

Cada ejecución genera un `.xlsx` con exactamente dos hojas, a partir de las mismas
filas utilizadas por los CSV:

- **Caminos:** identificador, estado, retornos, evento final, flujo bloqueado y tareas
  por nombre e ID con marcas `X`/`-`. `COMPLETE` es un resultado estructural completo;
  `TRUNCATED` indica que se alcanzó el límite de retornos y no representa un final exitoso.
- **Ramas:** filas `BRANCH_VIEW`, caminos de origen, salidas AND elegidas y tareas
  presentes/ausentes. Son proyecciones de caminos completos, no ejecuciones
  independientes. Excluyen los caminos truncados.

Las hojas tienen encabezados en negritas, filtros, primera fila inmovilizada y anchos
limitados con ajuste de texto. Los retornos son números; los textos del BPMN son
celdas de texto, incluso cuando comienzan con `=`, `+`, `-` o `@`. Por eso el XLSX
conserva el texto original sin el apóstrofo de seguridad que utiliza el CSV.
Los nombres repetidos se distinguen por su ID.

El libro se escribe y cierra en un archivo temporal antes de reemplazar el destino.
Si falla la exportación, se informa el error y se conserva el libro anterior, si
existía. No se sobrescribe el BPMN de entrada. Los formatos se publican por archivo;
no se garantiza una actualización conjunta de todos ellos si falla una escritura posterior.

Se mantienen las limitaciones del analizador. El exportador trabaja en memoria y
está destinado a modelos pequeños. Se aplican los límites de XLSX (1.048.576 filas,
16.384 columnas y 32.767 caracteres por celda); si se superan, la exportación falla
sin recortar datos. Las celdas muy extensas pueden requerir ampliar manualmente la
altura de fila para ver todo el texto.

## Resultados esperados

| Elemento | ApplicationProcess | Hardware Retailer |
|---|---:|---:|
| Tareas | 10 | 8 |
| Gateways | 5 | 6 |
| Eventos iniciales | 1 | 1 |
| Eventos finales | 3 | 1 |
| Sequence Flows | 20 | 18 |
| Total de nodos | 19 | 16 |

El programa muestra los elementos, conexiones, tabla y conteos en consola.

| Modelo | Límite | Completos | Truncados | Filas por ramas | Escenarios Gherkin |
|---|---:|---:|---:|---:|---:|
| ApplicationProcess | 0 | 3 | 1 | 3 | 3 |
| ApplicationProcess | 1 | 5 | 1 | 5 | 5 |
| ApplicationProcess | 2 | 7 | 1 | 7 | 7 |
| Hardware Retailer | 2 | 4 | 0 | 5 | 4 |

### Clasificación de tareas y Gherkin

`userTask` y `manualTask` se seleccionan automáticamente. Las tareas genéricas
`task` requieren clasificación adicional; los tipos automáticos no se convierten
en acciones de usuario. No se deduce el tipo a partir del nombre de la tarea.

Main busca junto al BPMN un archivo con el mismo nombre base y sufijo
`.tasks.properties`, sin cambiar los argumentos existentes. Por ejemplo,
`bpmn/HardwareRetailer.tasks.properties` contiene un `processId` y entradas por ID:

```properties
processId=_6-1
_6-102=human
_6-226=excluded
```

El archivo completo incluido clasifica las ocho tareas. `human` incorpora una tarea
genérica a las acciones funcionales; `excluded` la deja fuera de esas acciones,
sin afirmar que sea automática ni eliminarla del análisis. Se comprueban el proceso,
los IDs, los valores y las claves duplicadas. No se permite excluir una tarea humana
explícita ni convertir una tarea de servicio, script u otro tipo especializado en humana.
Si quedan tareas genéricas sin clasificar, Main informa los IDs y genera las tablas,
pero no crea ni actualiza el `.feature`; un archivo previo sigue siendo un resultado
anterior. Una configuración inválida detiene la ejecución antes de escribir salidas.

ApplicationProcess conserva su plantilla de dominio validada: `_3` es la acción
humana, DMF/OIG son contexto y la reevaluación y los finales son resultados. No exige
clasificar sus nueve tareas genéricas porque esa plantilla ya establece sus papeles.
Con L=2 mantiene siete escenarios: seis con revisión humana, incluidas sus repeticiones,
y uno de rechazo automático inicial. Si una configuración selecciona más tareas humanas,
se utiliza la plantilla genérica para incluirlas, sin ignorar esa selección.

El generador usa exclusivamente caminos `COMPLETE`. Agrupa los que tienen la misma
secuencia de IDs de acciones humanas (con repeticiones), los mismos textos de alternativas
y condiciones, y los mismos IDs de eventos iniciales y finales. Conserva todos los
orígenes F y sus `sequenceFlow` en comentarios. No decide equivalencia lógica entre
expresiones ni elimina variantes con condiciones o resultados diferentes.

La plantilla genérica utiliza eventos iniciales como contexto, tareas seleccionadas
como acciones y eventos finales como resultados por comprobar. Las tablas Gherkin
conservan nombres e IDs. La lista de actividades no impone un orden entre ramas
concurrentes: las futuras definiciones de pasos deben respetar las dependencias del
BPMN. Los comentarios identifican las salidas concurrentes y las tareas no seleccionadas.
Las etiquetas de flujos se conservan como alternativas, no se interpretan como reglas.

Son candidatos funcionales: el modelo no aporta datos de prueba, acciones de interfaz
ni mecanismos para observar los resultados. La sintaxis se valida con el parser oficial
`io.cucumber:gherkin`, usado solo en pruebas. No hay todavía definiciones Java,
ejecución Cucumber ni Selenium; tampoco se garantiza la viabilidad de los candidatos.

## Contrato del análisis de caminos

Un `ProcessPath` es un resultado **estructural**, no una ejecución cuya viabilidad
de negocio se haya demostrado. Conserva los nodos procesados, los `sequenceFlow`
originales (ID, extremos, nombre y condición), los retornos y las ramas concurrentes.

- `COMPLETE`: alcanza un evento final y no quedan tokens pendientes de procesar o sincronizar.
- `TRUNCATED`: se intentó superar el límite de una arista de retorno. Conserva el
  `blockedFlow`, que no se añade a los flujos recorridos. No equivale a un final exitoso.
- XOR divergente: una salida por alternativa. XOR convergente: consume una entrada
  disponible sin esperar las demás.
- OR divergente: enumera los `2^n - 1` subconjuntos no vacíos de sus salidas, sin
  declararlos factibles. OR convergente: requiere una entrada disponible y espera
  mientras algún token activo pueda alcanzar una entrada vacía sin atravesar la propia
  convergencia. Cuando se habilita, consume una unidad de cada entrada marcada.
  No depende de nombres, IDs ni de una pareja de gateways identificada manualmente.
- AND divergente: activa todas las salidas. AND convergente: exige una unidad en cada
  entrada antes de continuar. Una compuerta con varias entradas y salidas sincroniza primero.
- La concurrencia se representa mediante tokens, sin hilos. `getConcurrentBranches()`
  devuelve grupos inmutables de salidas activadas juntas, uno por ocurrencia de una
  bifurcación con varias ramas seleccionadas. Permite reconocer su origen y consultar
  el tipo de gateway en el grafo; no contiene una proyección completa por rama.
- `getNodes()` es una linealización determinista según el orden de nodos del modelo.
  Dos nodos consecutivos pueden pertenecer a ramas diferentes y no tener una arista
  entre sí. **No se inventan conexiones**: las conexiones están en `getFlows()`.
  No se enumeran todos los órdenes temporales de tareas concurrentes.
- La tabla conserva una fila por resultado conjunto y marca presencia de tareas con
  `X`. No expresa orden ni número de visitas; esos datos permanecen en `ProcessPath`.
  Con varios eventos finales visitados, la columna «Evento final» muestra el último
  procesado, no necesariamente el único.

Las condiciones se conservan como texto: no se ejecutan Java, scripts, servicios ni
reglas de datos. Incluso una condición literal `false` puede aparecer en un camino
estructural completo. Tampoco se interpreta un nombre como `Always` como expresión.
Una ejecución válida requeriría comprobar condiciones, datos y semántica adicional.

### Vista de flujos por ramas

`BranchFlowProjector` recibe el grafo y los resultados de `PathAnalyzer`. Para cada
camino completo, localiza bifurcaciones AND y una convergencia AND común: las regiones
de sus salidas deben ser disjuntas, sin entradas externas y sin llegar a un final
antes de la convergencia. Conserva una rama por proyección, junto con el prefijo
común, la convergencia y la continuación. Los nodos y flujos se filtran del camino
original; no se crean conexiones ni se vuelve a ejecutar la sincronización.

Se mantienen las alternativas XOR y las combinaciones OR seleccionadas. Las regiones
AND anidadas se separan solo si permanecen en la proyección; las sucesivas combinan
sus elecciones. ApplicationProcess no tiene una bifurcación AND: sus verificaciones
DMF/OIG nacen de OR y permanecen juntas, aunque luego sincronicen en AND.

Cada `BranchFlow` conserva nodos y flujos con sus repeticiones. Solo se agrupan vistas
con las mismas secuencias de IDs de nodos **y** conexiones, acumulando sus caminos de
origen. No se deduplica por las marcas X: distintas vueltas de un ciclo pueden mostrar
las mismas tareas y aun así representar recorridos diferentes. Los truncados no se
proyectan y continúan disponibles en el CSV conjunto.

El CSV adicional utiliza las mismas columnas de tareas X/- y estos metadatos:

| Columna | Significado |
|---|---|
| Flujo rama | Identificador B1, B2, etc., en orden de primera aparición |
| Representación | Siempre `BRANCH_VIEW`; no es un estado `COMPLETE` |
| Caminos origen | F1;F2;… del CSV conjunto, conservando la numeración que incluye truncados |
| Salidas AND elegidas | IDs de los flujos seleccionados en bifurcaciones AND; vacío si no hay ninguna |

Una fila no afirma que la rama pueda ejecutarse independientemente. El análisis
conjunto sigue siendo responsable de verificar que las ramas se sincronicen.

### ApplicationProcess y ciclos

`ApplicationProcessRules` conserva la excepción de dominio del modelo conocido:
en `_7`, SUCCESS activa conjuntamente DMF (`_34`) y OIG (`_10`); FAILURE activa
solamente el rechazo (`_9`). Se valida la estructura y el texto esperado de las
condiciones antes de aplicar esta regla. `_35` espera ambas verificaciones.
La regla evita combinaciones incompatibles con este modelo sin introducir un
evaluador general ni trasladar IDs específicos al soporte OR de otros procesos.

Los retornos se identifican como aristas hacia un nodo activo durante DFS. Cada
alternativa tiene contadores independientes por arista; `getReturnCount()` devuelve
su suma. En ApplicationProcess la arista es `_32-_33`: L=2 permite dos retornos
adicionales a Manual Review (hasta tres visitas). El intento de un tercer retorno
queda truncado. En un grafo con varios ciclos, L no es un límite global de vueltas;
la clasificación DFS depende del orden del grafo. Una activación concurrente se
comprueba completa antes de modificar el estado si una de sus salidas supera L.
Si varias combinaciones de una misma bifurcación se bloquean antes de activarse con
el mismo prefijo y flujo bloqueado, se devuelve un único truncado para ese prefijo.

### Hardware Retailer y comparación metodológica

Los cuatro resultados conjuntos incluyen `Decide if normal post or special shipment`
(`_6-102`), `Package goods` (`_6-153`) y la tarea final de documentación (`_6-569`).
Se distinguen por estas tareas adicionales:

| Fila | Tareas adicionales |
|---|---|
| F1 | Solicitar cotizaciones (`_6-226`) y asignar transportista (`_6-277`) |
| F2 | Comprobar necesidad de seguro (`_6-379`) y contratar seguro (`_6-452`) |
| F3 | Comprobar necesidad de seguro (`_6-379`) y llenar etiqueta postal (`_6-328`) |
| F4 | Comprobar necesidad de seguro (`_6-379`), contratar seguro (`_6-452`) y llenar etiqueta (`_6-328`) |

El OR `_6-430` produce tres combinaciones estructurales; la ruta XOR de transportista
añade una cuarta. `_6-503` reúne las ramas OR activas y `_6-547` sincroniza envío
con empaquetado. La fila F2 no demuestra una ejecución de negocio válida: omite la
rama cuyo **nombre**, sin `conditionExpression`, es `Always`.

Se revisó directamente el PDF aportado por el usuario: De Moura et al. (2017),
*Test Case Generation from BPMN Models for Automated Testing of Web-Based BPM
Applications*. Referencias por página del PDF de siete páginas: II.A (pp. 1-2),
III.A (p. 3), III.B-C (pp. 3-4), IV y Tabla II (pp. 4-5), Figuras 4-5 (p. 6).
Esta revisión sustituye la comparación provisional realizada sin acceso al artículo.

III.A describe columnas por tarea y filas por flujo, marcadas con X o guion.
III.C explica que las ramas paralelas se representan por separado para facilitar
su visualización y generación de escenarios. La Tabla II contiene estas cinco
filas, identificadas aquí como R1-R5 según su orden de aparición. Los IDs se
corresponden por nombre con nuestro BPMN; el artículo no muestra esos IDs.

| Fila del artículo | Tareas presentes, expresadas con nuestros IDs |
|---|---|
| R1 | `_6-102`, `_6-226`, `_6-277`, `_6-569` (transportista) |
| R2 | `_6-102`, `_6-379`, `_6-452`, `_6-569` (solo seguro) |
| R3 | `_6-102`, `_6-379`, `_6-328`, `_6-569` (solo etiqueta) |
| R4 | `_6-153`, `_6-569` (empaquetado) |
| R5 | `_6-102`, `_6-379`, `_6-452`, `_6-328`, `_6-569` (seguro y etiqueta) |

La correspondencia con nuestro CSV fue comprobada por conjuntos de tareas:
`F1 = R1 ∪ R4`, `F2 = R2 ∪ R4`, `F3 = R3 ∪ R4`, `F4 = R5 ∪ R4`.
Por tanto, cuatro resultados conjuntos y cinco filas separadas son representaciones
distintas de estas ramas. La tarea posterior a la convergencia (`_6-569`) aparece
también en la fila de empaquetado del artículo. Esta igualdad de conjuntos no prueba
equivalencia de ejecución ni permite tratar cada fila separada como una ejecución
completa independiente de la sincronización AND.

El artículo también incluye la combinación «solo seguro» en R2. No se modifica
nuestro tratamiento de `Always`: la coincidencia con esa fila no demuestra viabilidad
de negocio. II.A define OR como una o varias salidas, pero el texto no especifica
un algoritmo formal de sincronización OR ni un evaluador de condiciones que podamos
reproducir o atribuirle.

III.B restringe la generación funcional a tareas realizadas por usuarios. IV explica
que la división inclusiva no distingue escenarios porque las tareas posteriores no
son realizadas por el usuario. La Figura 4 muestra tres escenarios: transportista,
correo postal y empaquetado. En el primero se omite solicitar cotizaciones; en el
segundo se omiten contratar seguro y llenar etiqueta. Al proyectar R2, R3 y R5 sobre
las tareas que aparecen en esa figura, las tres filas resultan iguales. R1 y R4
aportan las otras dos variantes. Esto explica los tres escenarios observados, sin
afirmar que el artículo detalle un algoritmo general de deduplicación.

Nuestro XML declara las ocho tareas como `semantic:task`, sin `userTask` ni
`manualTask`. El perfil externo reproduce la selección de la Figura 4 por correspondencia
entre nombres e IDs del XML: decidir envío (`_6-102`), asignar transportista (`_6-277`),
comprobar necesidad de seguro (`_6-379`), empaquetar (`_6-153`) y añadir documentación
(`_6-569`). Se excluyen de las acciones cotizaciones (`_6-226`), contratar seguro
(`_6-452`) y etiqueta (`_6-328`). Esto documenta el perfil funcional del artículo;
la omisión de cotizaciones no demuestra por sí sola su tipo de ejecución en un BPMS.

**Decisión de esta implementación:** generar desde los cuatro caminos completos,
con ambas ramas AND y su reunión. Se obtienen cuatro candidatos: transportista y
tres alternativas de correo (seguro, etiqueta, ambos). Se conservan los textos
`extra insurance required` y `Always` como alternativas diferentes, sin interpretar
`Always` como una obligación. Por eso las tres variantes postales no se deduplican.
No se agregan reglas de negocio para forzar tres escenarios.

Los tres fragmentos de la Figura 4 separan la rama de empaquetado y omiten la división
OR posterior a las tareas humanas. Una prueba comprueba que proyectar nuestras cinco
filas por ramas sobre las cinco tareas seleccionadas produce esas tres combinaciones.
Esa correspondencia no convierte una `BRANCH_VIEW` en una ejecución independiente;
nuestro `.feature` conserva la sincronización mediante los caminos completos.
El artículo ofrece esqueletos con pasos vacíos (Figuras 4 y 5); aquí se generan pasos
genéricos no vacíos, que aún requieren datos, observaciones y una implementación posterior.

Otras diferencias verificadas: III.C trata los subprocesos como una tarea opaca,
mientras nuestro analizador los rechaza; su delimitador controla repeticiones de un
`sequenceFlow`, pero no fija L=2 ni describe exactamente nuestro límite sobre aristas
de retorno DFS. La Tabla I indica tres gateways para Hardware, frente a seis nodos
gateway en nuestro XML. Contar solo las tres divergencias es una explicación posible,
no una convención de conteo explicitada por el artículo.

La nueva vista obtiene automáticamente cinco filas a partir del grafo y los cuatro
caminos completos. En el orden actual del modelo: B1 corresponde a R1, B2 a R4,
B3 a R2, B4 a R3 y B5 a R5. B2 agrupa el empaquetado de F1-F4; las otras filas
proceden respectivamente de F1, F2, F3 y F4. La comparación comprueba tareas presentes
y ausentes, sin depender del orden de las filas ni codificar esa correspondencia
en producción. Hay pruebas que renombran todos los IDs, igualan los nombres y
reordenan los nodos. Se mantienen la sincronización y el CSV conjunto originales.

Como referencia auxiliar para el comportamiento de activación y reunión OR se consultó
la [documentación de Camunda](https://docs.camunda.org/manual/7.24/reference/bpmn20/gateways/inclusive-gateway/).
El proyecto no reproduce su motor ni sus particularidades de implementación.

### Alcance y limitaciones

Se comprueban ambos modelos incluidos y grafos pequeños con secuencias, XOR, AND,
OR y combinaciones anidadas. El análisis requiere exactamente un inicio. Rechaza
tipos no soportados (subprocesos, eventos intermedios, gateways complejos o basados en
eventos), flujos por defecto y características de bucle señaladas por el parser,
nodos sin salida y ramificaciones fuera de gateways. Las convergencias OR que
pertenecen a un ciclo se rechazan explícitamente para no mezclar iteraciones.

La consulta de alcanzabilidad del OR es estructural y conservadora; no resuelve
viabilidad de datos ni todas las dependencias de grafos no estructurados. Si quedan
tokens sin poder avanzar, se informa un error de sincronización y el análisis falla
sin devolver una lista parcial como si fuera completa. No hay diagnóstico por ruta
de bloqueos ni comprobación completa de soundness BPMN.

Las tareas son nodos opacos y los eventos iniciales/finales se tratan estructuralmente:
no se ejecutan servicios, mensajes, temporizadores, compensaciones o terminaciones
especiales. El parser no conserva toda esa semántica para validarla. La enumeración
OR es exponencial y el límite de retornos no acota el número total de resultados;
el alcance son modelos pequeños, no un motor BPMN general.

La vista por ramas exige regiones AND estructuradas. Rechaza cruces de ramas,
entradas externas, salidas sin convergencia AND común y caminos que visiten una
misma bifurcación AND varias veces. Sí conserva ciclos dentro de una rama o en una
sección secuencial que no repitan la bifurcación. Ante una estructura no soportada,
Main informa el motivo, genera las salidas conjuntas y no genera ni actualiza el CSV
por ramas; si ya existía uno, sigue siendo un resultado anterior. El Excel conserva
`Caminos` y muestra en `Ramas` un aviso `NO DISPONIBLE`, sin inventar proyecciones.
No devuelve una
vista parcial como si cubriera todos los caminos. El número de proyecciones puede
crecer con las combinaciones de regiones paralelas sucesivas.

## Pruebas

Las pruebas unitarias se realizan con JUnit 5 y se encuentran en `src/test/`.

Para ejecutarlas:

```bash
mvn clean test
mvn package
```

Las pruebas cubren parser, grafo, análisis, tablas CSV/Excel, Gherkin y ejecuciones de Main.
La clasificación se prueba por tipo e ID, con perfiles válidos, incompletos y
contradictorios. Los escenarios se analizan formalmente en español y se comprueban
acciones, resultados, condiciones, deduplicación, ciclos, trazabilidad y caracteres especiales.
Los XLSX se reabren con Apache POI para comprobar hojas, dimensiones, tipos de celda,
contenido completo frente al CSV, truncamientos, trazabilidad, texto especial y
errores de escritura. No se ejecutan fórmulas ni se requiere Excel instalado.
Incluyen combinaciones OR de una, varias y todas las ramas, convergencias antes de
las tareas en el orden del XML, OR anidados, condiciones sin evaluar, ciclos,
inmutabilidad y Hardware Retailer con IDs renombrados. La vista por ramas se prueba
con regiones simples, anidadas, sucesivas y mixtas, alternativas, ciclos internos,
trazabilidad y la correspondencia con las cinco filas de la Tabla II.
