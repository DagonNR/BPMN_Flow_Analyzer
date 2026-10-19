# BPMN Flow Analyzer

Proyecto desarrollado para la materia de Verificación y Validación de Software.

## Objetivo

Desarrollar una herramienta en Java que permita analizar modelos BPMN,
identificar sus posibles flujos de ejecución y generar casos de prueba,
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

**Pendientes:**

- Identificación de caminos de ejecución.
- Manejo de decisiones y ciclos.
- Generación de tablas de flujos en Excel.
- Generación de escenarios Gherkin.
- Generación de código Java inicial para Cucumber.

## Ejecución

Se requiere Java 17 o superior y Maven.

Desde la raíz del proyecto, compilar con:

```bash
mvn package
```

**Ejecutar ApplicationProcess (predeterminado):**

```bash
java -cp target/classes com.pia.vvs.Main
```

**Ejecutar un BPMN específico:**

```bash
java -cp target/classes com.pia.vvs.Main "bpmn/ApplicationProcess.bpmn" "gov.medicaid.process.application"
```

**Ejecutar Hardware Retailer:**

```bash
java -cp target/classes com.pia.vvs.Main "bpmn/HardwareRetailer.bpmn" "_6-1"
```

El programa recibe dos argumentos: la ruta del archivo BPMN y el ID del proceso.
Si no se proporcionan argumentos, utiliza ApplicationProcess.

## Resultados esperados

| Elemento | ApplicationProcess | Hardware Retailer |
|---|---:|---:|
| Tareas | 10 | 8 |
| Gateways | 5 | 6 |
| Eventos iniciales | 1 | 1 |
| Eventos finales | 3 | 1 |
| Sequence Flows | 20 | 18 |
| Total de nodos | 19 | 16 |

El programa muestra los elementos identificados y sus conexiones en consola.

## Pruebas

Las pruebas unitarias se realizan con JUnit 5 y se encuentran en `src/test/`.

Para ejecutarlas:

```bash
mvn test
```

Actualmente se cuenta con 47 pruebas unitarias.

## Estado del proyecto

- **Etapa 1:** lectura e identificación de elementos BPMN. Completada.
- **Etapa 2:** construcción del grafo de conexiones. Completada.
- **Etapa 3:** análisis de caminos y generación de tablas. Pendiente.
- **Etapa 4:** generación de escenarios Gherkin y código Cucumber. Pendiente.
