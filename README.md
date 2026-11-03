# BPMN Flow Analyzer

Proyecto desarrollado para la materia de **Verificación y Validación de Software**.

## Objetivo

Analizar modelos BPMN para identificar caminos y generar archivos que sirvan como base para casos de prueba, tomando como referencia el procedimiento de **De Moura et al. (2017)**.

## Requisitos

- Java 17 o superior.
- Maven.

No es necesario tener Microsoft Excel instalado.

## Modelos incluidos

En la carpeta `bpmn/` se incluyen dos modelos:

- **ApplicationProcess.bpmn:** proceso de evaluación de solicitudes de inscripción de proveedores de servicios de salud.
- **HardwareRetailer.bpmn:** proceso utilizado como referencia en el artículo de De Moura.

## Funcionalidades

- Lectura de modelos BPMN y construcción de su grafo de tareas y conexiones.
- Análisis de caminos con compuertas XOR, OR y AND, concurrencia y límite de retornos.
- Generación de tablas de caminos y ramas en CSV y Excel.
- Identificación de tareas humanas y generación de escenarios Gherkin (`.feature`).
- Generación de clases Java iniciales con definiciones de pasos para Cucumber.

## Instalación y ejecución

Desde la carpeta principal del proyecto, compilar con:

```bash
mvn package
```

**Ejecutar el modelo predeterminado (ApplicationProcess):**

```bash
java -cp "target/classes;target/dependency/*" com.pia.vvs.Main
```

**Ejecutar Hardware Retailer:**

```bash
java -cp "target/classes;target/dependency/*" com.pia.vvs.Main "bpmn/HardwareRetailer.bpmn" "_6-1"
```

**Ejecutar otro modelo e indicar opciones:**

```bash
java -cp "target/classes;target/dependency/*" com.pia.vvs.Main "archivo.bpmn" "idProceso" "carpetaSalida" 2
```

Los dos primeros argumentos indican el archivo BPMN y el ID del proceso. La carpeta de salida y el número máximo de retornos son opcionales. El límite predeterminado es **2** (se permiten valores de 0 a 100).

En Windows se usa `;` para separar las rutas del classpath; en Linux y macOS debe cambiarse por `:`.

## Archivos generados

Por defecto, los resultados se guardan en `target/generated-tests/`.

| Archivo | Contenido |
|---|---|
| `<modelo>.csv` | Caminos identificados, incluidos los truncados. |
| `<modelo>-ramas.csv` | Vista de las ramas de los caminos completos. |
| `<modelo>.xlsx` | Excel con las hojas **Caminos** y **Ramas**. |
| `<modelo>.feature` | Escenarios generados en Gherkin. |
| `<Modelo>Steps.java` | Definiciones iniciales de pasos para Cucumber, dentro de `steps/`. |

Los caminos completos y las vistas por ramas son representaciones diferentes: una rama no debe interpretarse como una ejecución independiente del proceso.

**Importante:** si se desea completar manualmente una clase Java generada, debe copiarse fuera de `target/`, ya que `mvn clean` elimina esa carpeta.

## Clasificación de tareas

Las tareas BPMN de tipo `userTask` y `manualTask` se reconocen automáticamente. Para las tareas genéricas `task`, se puede colocar un archivo `<modelo>.tasks.properties` junto al BPMN, indicando sus IDs:

```properties
processId=_6-1
_6-102=human
_6-226=excluded
```

`human` selecciona una tarea como acción humana; `excluded` la deja fuera de esa selección. El modelo Hardware Retailer incluye su archivo de configuración completo.

Si faltan clasificaciones necesarias, el programa puede generar las tablas, pero no generará nuevos archivos Gherkin ni Java para ese modelo.
