package com.pia.vvs.parser;

import com.pia.vvs.model.BpmnNode;
import com.pia.vvs.model.BpmnProcess;
import com.pia.vvs.model.NodeType;
import com.pia.vvs.model.SequenceFlow;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class BpmnParser {
    private static final String BPMN_NAMESPACE = "http://www.omg.org/spec/BPMN/20100524/MODEL";

    public BpmnProcess parse(Path file, String processId) throws IOException {
        Objects.requireNonNull(file, "La ruta del archivo es obligatoria.");
        if (processId == null || processId.isBlank()) {
            throw new IllegalArgumentException("El ID del proceso es obligatorio.");
        }
        if (!Files.isRegularFile(file)) {
            throw new IOException("El archivo BPMN no existe o no es un archivo regular: " + file);
        }

        Document document = readDocument(file);
        Element process = findProcess(document, processId);
        List<BpmnNode> nodes = new ArrayList<>();
        List<SequenceFlow> flows = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        ids.add(processId);

        // Solo hijos directos: las extensiones y los subprocesos tienen su propio ámbito.
        for (Node child = process.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (!(child instanceof Element element) || !isBpmnElement(element)) {
                continue;
            }
            NodeType type = NodeType.fromXmlName(element.getLocalName());
            if (type == null && !"sequenceFlow".equals(element.getLocalName())) {
                continue;
            }
            String id = requiredAttribute(element, "id");
            if (!ids.add(id)) {
                throw new IOException("ID BPMN duplicado en el proceso: " + id);
            }
            if (type != null) {
                nodes.add(new BpmnNode(id, element.getAttribute("name"), type));
            } else {
                flows.add(new SequenceFlow(id, requiredAttribute(element, "sourceRef"),
                        requiredAttribute(element, "targetRef"), element.getAttribute("name"),
                        readCondition(element)));
            }
        }

        BpmnProcess result = new BpmnProcess(processId, process.getAttribute("name"), nodes, flows);
        for (SequenceFlow flow : flows) {
            validateReference(result, flow, flow.getSourceRef(), "sourceRef");
            validateReference(result, flow, flow.getTargetRef(), "targetRef");
        }
        return result;
    }

    private Document readDocument(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler() {
                @Override
                public void fatalError(SAXParseException exception) throws SAXException {
                    throw exception;
                }
            });
            return builder.parse(input);
        } catch (SAXException exception) {
            throw new IOException("XML BPMN inválido o contenido XML no permitido en "
                    + file + ": " + exception.getMessage(), exception);
        } catch (ParserConfigurationException exception) {
            throw new IllegalStateException("No se pudo configurar el lector XML seguro.", exception);
        }
    }

    private Element findProcess(Document document, String processId) throws IOException {
        Element root = document.getDocumentElement();
        if (!isBpmnElement(root) || !"definitions".equals(root.getLocalName())) {
            throw new IOException("Se esperaba definitions con el namespace de BPMN 2.0.");
        }
        Element found = null;
        for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && isBpmnElement(element)
                    && "process".equals(element.getLocalName())
                    && processId.equals(element.getAttribute("id"))) {
                if (found != null) {
                    throw new IOException("ID de proceso duplicado: " + processId);
                }
                found = element;
            }
        }
        if (found == null) {
            throw new IOException("No se encontró el proceso con ID: " + processId);
        }
        return found;
    }

    private boolean isBpmnElement(Element element) {
        return BPMN_NAMESPACE.equals(element.getNamespaceURI());
    }

    private String requiredAttribute(Element element, String attribute) throws IOException {
        String value = element.getAttribute(attribute);
        if (value.isBlank()) {
            throw new IOException("Falta el atributo obligatorio '" + attribute + "' en "
                    + element.getLocalName() + " (id=" + element.getAttribute("id") + ").");
        }
        return value;
    }

    private String readCondition(Element flow) {
        for (Node child = flow.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && isBpmnElement(element)
                    && "conditionExpression".equals(element.getLocalName())) {
                return element.getTextContent();
            }
        }
        return "";
    }

    private void validateReference(BpmnProcess process, SequenceFlow flow,
                                   String nodeId, String attribute) throws IOException {
        if (!process.getNodes().containsKey(nodeId)) {
            throw new IOException("El flujo '" + flow.getId() + "' tiene " + attribute
                    + "='" + nodeId + "', que apunta a un nodo inexistente o no soportado.");
        }
    }
}
