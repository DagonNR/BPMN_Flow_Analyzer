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

En desarrollo.