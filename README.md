# BPMN Flow Analyzer

Proyecto desarrollado para la materia de **Verificación y Validación de Software**.

## Objetivo

Analizar modelos BPMN, identificar sus caminos y generar archivos que sirvan como base para casos de prueba, tomando como referencia el procedimiento de **De Moura et al. (2017)**.

## Requisitos

- Java 17 o superior.
- Maven.

No es necesario tener Microsoft Excel instalado.

## Modelos incluidos

- **ApplicationProcess.bpmn:** proceso de evaluación de solicitudes de inscripción de proveedores de servicios de salud.
- **HardwareRetailer.bpmn:** proceso utilizado como referencia en el artículo de De Moura.

Ambos se encuentran en la carpeta `bpmn/`.

## Funcionalidades

- Lectura de modelos BPMN y construcción de su grafo.
- Análisis de caminos con compuertas XOR, OR y AND, concurrencia y límite de retornos.
- Generación de tablas de caminos y ramas en CSV y Excel.
- Generación de escenarios Gherkin estructurales y fragmentos de tareas humanas por rama.
- Generación de clases Java iniciales para Cucumber.
- Soporte de subprocesos ordinarios como una sola actividad.

## Instalación y ejecución

Desde la carpeta principal del proyecto, compilar con:

```bash
mvn package
```

**Ejecutar ApplicationProcess (predeterminado):**

```powershell
java -cp "target/classes;target/dependency/*" com.pia.vvs.Main
```

**Ejecutar Hardware Retailer:**

```powershell
java -cp "target/classes;target/dependency/*" com.pia.vvs.Main "bpmn/HardwareRetailer.bpmn" "_6-1"
```

**Ejecutar otro modelo con opciones:**

```powershell
java -cp "target/classes;target/dependency/*" com.pia.vvs.Main "archivo.bpmn" "idProceso" "carpetaSalida" 2
```

Los dos primeros argumentos indican el archivo y el ID del proceso. La carpeta de salida y el límite de retornos son opcionales. El límite predeterminado es **2** (rango permitido: 0 a 100).

La generación de De Moura se realiza automáticamente. El argumento anterior `--demoura` sigue siendo compatible, pero ya no es necesario.

En Linux y macOS, sustituir `;` por `:` en el classpath.

## Archivos generados

Los resultados se guardan por defecto en `target/generated-tests/`.

| Archivo | Contenido |
|---|---|
| `<modelo>.csv` | Caminos completos y truncados. |
| `<modelo>-ramas.csv` | Ramas de los caminos completos. |
| `<modelo>.xlsx` | Excel con hojas **Caminos** y **Ramas**. |
| `<modelo>.feature` | Escenarios estructurales Gherkin. |
| `steps/` | Definiciones Java originales de Cucumber. |
| `demoura/<modelo>-demoura.feature` | Fragmentos de tareas humanas por rama. |
| `demoura/steps/` | Definiciones Java adicionales para Cucumber. |

En Hardware Retailer se generan **4 escenarios estructurales y 3 fragmentos humanos**, correspondientes a las representaciones utilizadas en el artículo.

Las ramas no representan necesariamente ejecuciones independientes.

## Clasificación de tareas

Las tareas `userTask` y `manualTask` se reconocen automáticamente. Las tareas genéricas pueden clasificarse mediante un archivo `<modelo>.tasks.properties` junto al BPMN.

Ejemplo:

```properties
processId=_6-1
_6-102=human
_6-226=excluded
```

Hardware Retailer incluye su archivo de clasificación. Si faltan clasificaciones necesarias, el programa conserva las salidas válidas e informa qué archivos Gherkin o Java no pudo generar.

## Limitaciones

- El análisis es estructural: no ejecuta condiciones de negocio ni pruebas web.
- Los subprocesos ordinarios se representan como una actividad, sin analizar su interior. Se rechazan variantes especiales y estructuras no soportadas, incluidas ciertas declaraciones `xsi:type`.
- Las clases Java generadas contienen pasos pendientes de implementación.

**Importante:** si se modifican manualmente las clases Java generadas, deben copiarse fuera de `target/`, porque `mvn clean` elimina esa carpeta.