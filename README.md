# BPMN Flow Analyzer

Proyecto desarrollado para la materia de Verificación y Validación de Software.

## Objetivo

Desarrollar una herramienta en Java capaz de analizar un archivo BPMN y obtener
los posibles flujos de ejecución del proceso, tomando como referencia el
procedimiento propuesto por De Moura.

## BPMN seleccionado

**Provider Screening / Application Process**

El proceso representa la evaluación de solicitudes de inscripción de proveedores
de servicios de salud, incluyendo validaciones automáticas, revisión manual y
decisiones para aceptar, rechazar o volver a evaluar una solicitud.

Archivo utilizado:

`ApplicationProcess.bpmn`

## Funcionalidades esperadas

- Lectura de archivos BPMN.
- Identificación de tareas, eventos y gateways.
- Identificación de `sequenceFlow`.
- Lectura de `sourceRef` y `targetRef`.
- Reconstrucción de conexiones entre nodos.
- Identificación de posibles flujos de ejecución.
- Manejo de ciclos.
- Generación de resultados del análisis.

## Tecnologías

- Java 17
- Maven
- IntelliJ IDEA
- Git
- GitHub

## Estado

Etapa 1 implementada: modelos de datos y lectura XML de BPMN 2.0.
Etapa 2 implementada: representación del proceso como grafo dirigido.

## Etapa 1

El programa lee `bpmn/ApplicationProcess.bpmn` y selecciona el proceso
`gov.medicaid.process.application`. Identifica nodos y conexiones, conserva
nombres y condiciones como texto y valida IDs duplicados y referencias de flujos.
Reconoce el namespace BPMN tanto por defecto como con prefijos XML.
No ejecuta condiciones ni scripts y bloquea DTD y entidades XML externas.

Clases en `src/main/java/com/pia/vvs`:

- `model/NodeType`: diferencia tipos de tareas, eventos, gateways y actividades básicas.
- `model/BpmnNode`: almacena ID, nombre y tipo; puede ampliarse con conexiones en una etapa posterior.
- `model/SequenceFlow`: almacena ID, `sourceRef`, `targetRef`, nombre y condición.
- `model/BpmnProcess`: conserva los nodos en un `Map` por ID y los flujos en una `List`,
  con colecciones de solo lectura y en el orden del archivo.
- `parser/BpmnParser`: lee el XML con DOM y devuelve un `BpmnProcess`.
- `Main`: muestra el resumen, el listado de nodos y las conexiones en consola.

Los nombres y condiciones ausentes se representan con una cadena vacía.
Se leen los hijos directos del proceso seleccionado. Los subprocesos se reconocen
como nodos, sin recorrer sus elementos internos. Se ignoran extensiones,
asociaciones de datos y elementos de diagramación. Si un flujo apunta a un nodo
inexistente o de un tipo no soportado, se reporta un error.
Esta etapa no realiza validación completa contra el esquema XSD de BPMN.
El análisis de caminos, las tablas, Gherkin, Cucumber y Selenium quedan para etapas posteriores.

### Ejecución y pruebas

Requisitos: JDK 17 o superior y Maven. Desde la raíz del repositorio:

```shell
mvn test
mvn package
java -cp target/classes com.pia.vvs.Main
```

En IntelliJ IDEA, importar el `pom.xml`, recargar Maven y ejecutar `Main`,
usando la raíz del repositorio como directorio de trabajo. Las pruebas JUnit 5
están en `src/test/java/com/pia/vvs/parser/BpmnParserTest.java` y también pueden
ejecutarse desde el IDE. La primera ejecución requiere descargar las dependencias de Maven.

La salida esperada incluye el proceso `CMS Application Process`, 10 tareas
(9 `task` y 1 `userTask`), 5 gateways (3 exclusivos, 1 paralelo y 1 inclusivo),
1 evento inicial, 3 finales y 20 `sequenceFlow`. Después muestra cada nodo con su
ID, nombre y tipo, y cada conexión con su `sourceRef` y `targetRef`, incluidos
nombre y condición cuando existen. Por ejemplo, `_5-_6` conecta `_5` (`PreProcess`)
con `_6` (`Validate`).

Las pruebas verifican esos resultados y cubren namespaces, condiciones,
selección de proceso, IDs duplicados, referencias incorrectas, XML inválido,
archivos inexistentes y rechazo de entidades externas.

## Etapa 2: grafo BPMN

`graph/ProcessGraph` recibe el `BpmnProcess` generado por `BpmnParser` mediante
`new ProcessGraph(process)`. Organiza conexiones entrantes y salientes por ID de
nodo en dos mapas, recorriendo los `SequenceFlow` una sola vez. Conserva los
objetos completos, incluidas sus condiciones, y el orden original de los flujos.
Los nodos sin conexiones tienen listas vacías.

Métodos públicos:

- `getProcess()`: devuelve el proceso original; sus colecciones son de solo lectura.
- `getNode(String nodeId)`: obtiene un nodo por ID.
- `getOutgoingFlows(String nodeId)` y `getIncomingFlows(String nodeId)`: consultan
  las salidas y entradas del nodo como listas de solo lectura.
- `getSourceNode(SequenceFlow flow)` y `getTargetNode(SequenceFlow flow)`: resuelven
  los nodos indicados por `sourceRef` y `targetRef` dentro del proceso.
- `getStartEvents()` y `getEndEvents()`: devuelven listas de solo lectura de los
  eventos iniciales y finales, identificados por su tipo BPMN.

El constructor rechaza procesos nulos, referencias a nodos inexistentes e IDs de
flujos duplicados. Los argumentos proceso o flujo nulos producen
`NullPointerException` con un mensaje; las referencias e IDs de nodo inválidos
(también nulos o vacíos) y los flujos duplicados producen `IllegalArgumentException`.
El grafo no modifica el proceso recibido y admite procesos vacíos y nodos aislados.

Los comandos de ejecución anteriores siguen vigentes. `Main` añade un resumen
`PROCESS GRAPH` con 19 nodos, 20 conexiones, 1 evento inicial y 3 finales, y muestra
entradas y salidas de `_1`, `_5`, `_7`, `_35` y `_33`.
`mvn test` ejecuta las pruebas de ambas etapas; los casos nuevos están en
`src/test/java/com/pia/vvs/graph/ProcessGraphTest.java`.

Queda pendiente implementar `PathAnalyzer` para interpretar las conexiones y
recorrer caminos, incluyendo decisiones en gateways y control de ciclos.
Esta etapa solo representa la estructura: no evalúa condiciones ni genera tablas,
archivos Excel, Gherkin, Cucumber o Selenium.
