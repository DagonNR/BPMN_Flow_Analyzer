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

### Vista opcional de tareas humanas por rama

Para generar también los fragmentos humanos de la Figura 4 de De Moura,
añade `--demoura` antes de los argumentos habituales. Desde la raíz, en Windows:

```powershell
java -cp "target/classes;target/dependency/*" com.pia.vvs.Main --demoura bpmn/HardwareRetailer.bpmn _6-1
```

Los resultados adicionales se escriben en `target/generated-tests/demoura/` (o en
`demoura/` dentro de la salida elegida): `HardwareRetailer-demoura.feature` y
`steps/com/pia/vvs/generated/hardwareretailer_demourasteps/HardwareRetailer_demouraSteps.java`.
Usa ese paquete como glue de Cucumber para este archivo. La clase contiene pasos
pendientes y se protege frente a cambios manuales igual que la clase original.

Cada escenario conserva las tareas humanas en orden, incluidas sus repeticiones.
Las variantes de una misma selección AND se agrupan cuando coinciden los IDs humanos,
los finales del camino de origen, los flujos con condición explícita y las alternativas
etiquetadas fuera de las bifurcaciones OR. Las etiquetas de estas bifurcaciones por
sí solas no prueban un resultado distinto.
Los comentarios identifican cada fila B, sus caminos F y sus flujos, etiquetas y condiciones.
Los escenarios describen el modelo. Las actividades comunes posteriores a una convergencia
siguen dependiendo de la sincronización de las ramas.

En Hardware Retailer resultan tres fragmentos (transportista, correo, empaquetado)
además de los cuatro escenarios conjuntos. En ApplicationProcess, `--demoura` con
L=2 conserva siete resultados, incluido el rechazo inicial sin tareas humanas. Una
proyección vacía de acciones se identifica expresamente y conserva sus eventos finales.
Cuando falta la clasificación necesaria o la proyección AND no está soportada, la consola
indica que los archivos de esta modalidad no se actualizaron.

**Importante:** si se desea completar manualmente una clase Java generada, debe copiarse fuera de `target/`, ya que `mvn clean` elimina esa carpeta.

## Subprocesos ordinarios

Un `subProcess` ordinario con una entrada y una salida exteriores se representa
como una sola actividad, suponiendo que termina normalmente. Su columna lleva el
nombre y el ID exteriores. Las tareas internas quedan fuera del grafo y de los pasos humanos.
Se rechazan eventos de borde, activación por eventos, transacciones, subprocesos ad hoc,
bucles declarados, multiinstancia, compensación y eventos internos con definición especial.
También se excluyen llamadas internas a procesos externos y tipos de nodo redefinidos con `xsi:type`.
El subproceso exterior debe consumir y producir un solo token.

## Clasificación de tareas

Las tareas BPMN de tipo `userTask` y `manualTask` se reconocen automáticamente. Para las tareas genéricas `task`, se puede colocar un archivo `<modelo>.tasks.properties` junto al BPMN, indicando sus IDs:

```properties
processId=_6-1
_6-102=human
_6-226=excluded
```

`human` selecciona una tarea como acción humana; `excluded` la deja fuera de esa selección. El modelo Hardware Retailer incluye su archivo de configuración completo.

Si faltan clasificaciones necesarias, el programa puede generar las tablas, pero no generará nuevos archivos Gherkin ni Java para ese modelo.
