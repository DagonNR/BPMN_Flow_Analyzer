package com.pia.vvs.parser;

import com.pia.vvs.model.BpmnNode;
import com.pia.vvs.model.BpmnProcess;
import com.pia.vvs.model.NodeType;
import com.pia.vvs.model.SequenceFlow;
import com.pia.vvs.analysis.PathAnalyzer;
import com.pia.vvs.graph.ProcessGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class BpmnParserTest {
    private static final String PROCESS_ID = "gov.medicaid.process.application";
    private static final Path APPLICATION = Path.of("bpmn", "ApplicationProcess.bpmn");
    private static final String NAMESPACE = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    private final BpmnParser parser = new BpmnParser();

    @TempDir
    Path tempDirectory;

    @Test
    void readsExpectedApplicationCounts() throws IOException {
        BpmnProcess process = parser.parse(APPLICATION, PROCESS_ID);

        assertEquals(PROCESS_ID, process.getId());
        assertEquals("CMS Application Process", process.getName());
        assertEquals(19, process.getNodes().size());
        assertEquals(9, count(process, NodeType.TASK));
        assertEquals(1, count(process, NodeType.USER_TASK));
        assertEquals(10, process.getNodes().values().stream()
                .filter(node -> node.getType().isTask()).count());
        assertEquals(3, count(process, NodeType.EXCLUSIVE_GATEWAY));
        assertEquals(1, count(process, NodeType.PARALLEL_GATEWAY));
        assertEquals(1, count(process, NodeType.INCLUSIVE_GATEWAY));
        assertEquals(5, process.getNodes().values().stream()
                .filter(node -> node.getType().isGateway()).count());
        assertEquals(1, count(process, NodeType.START_EVENT));
        assertEquals(3, count(process, NodeType.END_EVENT));
        assertEquals(20, process.getSequenceFlows().size());
    }

    @Test
    void readsExpectedHardwareRetailerCounts() throws IOException {
        BpmnProcess process = parser.parse(Path.of("bpmn", "HardwareRetailer.bpmn"), "_6-1");

        assertEquals("_6-1", process.getId());
        assertEquals("", process.getName());
        assertEquals(16, process.getNodes().size());
        assertEquals(8, count(process, NodeType.TASK));
        assertEquals(8, process.getNodes().values().stream()
                .filter(node -> node.getType().isTask()).count());
        assertEquals(2, count(process, NodeType.EXCLUSIVE_GATEWAY));
        assertEquals(2, count(process, NodeType.PARALLEL_GATEWAY));
        assertEquals(2, count(process, NodeType.INCLUSIVE_GATEWAY));
        assertEquals(6, process.getNodes().values().stream()
                .filter(node -> node.getType().isGateway()).count());
        assertEquals(1, count(process, NodeType.START_EVENT));
        assertEquals(1, count(process, NodeType.END_EVENT));
        assertEquals(18, process.getSequenceFlows().size());
    }

    @Test
    void readsHardwareRetailerNamesAndFlowsWithoutIncludingAnnotations() throws IOException {
        BpmnProcess process = parser.parse(Path.of("bpmn", "HardwareRetailer.bpmn"), "_6-1");
        SequenceFlow flow = findFlow(process, "_6-639");

        assertEquals("_6-204", flow.getSourceRef());
        assertEquals("_6-226", flow.getTargetRef());
        assertEquals("Special Carrier", flow.getName());
        assertEquals("", flow.getCondition());
        assertEquals("Assign a carrier & prepare paperwork", process.getNodes().get("_6-277").getName());
        assertFalse(process.getNodes().containsKey("_6-670"));
        assertFalse(process.getSequenceFlows().stream().anyMatch(item -> item.getId().equals("_6-673")));
    }

    @Test
    void readsKnownConnectionAndKeepsNodesWithTheSameName() throws IOException {
        BpmnProcess process = parser.parse(APPLICATION, PROCESS_ID);
        SequenceFlow flow = findFlow(process, "_5-_6");

        assertEquals("_5", flow.getSourceRef());
        assertEquals("_6", flow.getTargetRef());
        assertEquals("PreProcess", process.getNodes().get("_5").getName());
        assertEquals("Validate", process.getNodes().get("_6").getName());
        assertEquals("Reject Application", process.getNodes().get("_9").getName());
        assertEquals("Reject Application", process.getNodes().get("_18").getName());
        assertEquals("", flow.getName());
        assertEquals("", flow.getCondition());
    }

    @Test
    void preservesJavaConditionAsText() throws IOException {
        SequenceFlow flow = findFlow(parser.parse(APPLICATION, PROCESS_ID), "_7-_34");

        assertEquals("success", flow.getName());
        assertEquals("return \"SUCCESS\".equals(model.getProcessResults()"
                + ".getValidationResult().getStatus().getStatusCode());", flow.getCondition());
    }

    @ParameterizedTest
    @ValueSource(strings = {"default", "loop", "multiInstance"})
    void preservesStructuralReadingButRejectsGenerationWithUnsupportedBehavior(String behavior) throws IOException {
        String xml = Files.readString(APPLICATION);
        if (behavior.equals("default")) {
            xml = xml.replace("id=\"_7\" name=\"Reject Or Verify\"", "id=\"_7\" default=\"_7-_34\" name=\"Reject Or Verify\"");
        } else {
            String loop = behavior.equals("loop")
                    ? "<standardLoopCharacteristics testBefore='true'><loopCondition xsi:type='tFormalExpression'>false</loopCondition></standardLoopCharacteristics>"
                    : "<multiInstanceLoopCharacteristics isSequential='true'/>";
            xml = xml.replace("<userTask id=\"_3\" name=\"Manual Review\" >",
                    "<userTask id=\"_3\" name=\"Manual Review\" >" + loop);
        }

        BpmnProcess process = parser.parse(writeXml(xml), PROCESS_ID);

        assertEquals(19, process.getNodes().size());
        assertEquals(20, process.getSequenceFlows().size());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new PathAnalyzer().analyze(new ProcessGraph(process), 2));
        assertTrue(error.getMessage().contains("no soportad"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "semantic:"})
    void readsDefaultAndPrefixedNamespacesAndIgnoresAssociations(String prefix) throws IOException {
        String xml = """
                <p:definitions xmlns:p="%s" xmlns="%s" xmlns:semantic="%s" xmlns:ext="urn:extension">
                  <p:process id="test">
                    <p:extensionElements><p:task id="ignored"/></p:extensionElements>
                    <ext:task id="foreign"/>
                    <ext:sequenceFlow id="foreignFlow" sourceRef="missing" targetRef="missing"/>
                    <@sequenceFlow id="flow" sourceRef="a" targetRef="b" name="seguir">
                      <ext:conditionExpression>ignorar</ext:conditionExpression>
                      <@conditionExpression><![CDATA[x < 2 && y > 0]]></@conditionExpression>
                    </@sequenceFlow>
                    <@task id="a">
                      <p:dataInputAssociation><p:sourceRef>data</p:sourceRef></p:dataInputAssociation>
                    </@task>
                    <@userTask id="b"/>
                  </p:process>
                </p:definitions>
                """.formatted(NAMESPACE, NAMESPACE, NAMESPACE).replace("@", prefix);

        BpmnProcess process = parser.parse(writeXml(xml), "test");

        assertEquals(2, process.getNodes().size());
        assertEquals(1, process.getSequenceFlows().size());
        assertEquals(NodeType.USER_TASK, process.getNodes().get("b").getType());
        assertEquals("", process.getName());
        assertEquals("", process.getNodes().get("a").getName());
        assertEquals("seguir", findFlow(process, "flow").getName());
        assertEquals("x < 2 && y > 0", findFlow(process, "flow").getCondition());
    }

    @Test
    void readsOnlySelectedProcessAndKeepsSubprocessAsOneNode() throws IOException {
        Path file = writeXml("""
                <definitions xmlns="%s">
                  <process id="other"><task id="a"/><task id="otherTask"/></process>
                  <process id="test">
                    <serviceTask id="a"/>
                    <subProcess id="sub"><startEvent id="innerStart"/></subProcess>
                    <intermediateCatchEvent id="wait"/>
                    <eventBasedGateway id="choice"/>
                    <sequenceFlow id="flow" sourceRef="a" targetRef="sub"/>
                  </process>
                </definitions>
                """.formatted(NAMESPACE));

        BpmnProcess process = parser.parse(file, "test");

        assertEquals(4, process.getNodes().size());
        assertEquals(NodeType.SERVICE_TASK, process.getNodes().get("a").getType());
        assertEquals(NodeType.SUB_PROCESS, process.getNodes().get("sub").getType());
        assertEquals(NodeType.INTERMEDIATE_CATCH_EVENT, process.getNodes().get("wait").getType());
        assertEquals(NodeType.EVENT_BASED_GATEWAY, process.getNodes().get("choice").getType());
        assertFalse(process.getNodes().containsKey("innerStart"));
        assertFalse(process.getNodes().containsKey("otherTask"));
    }

    @ParameterizedTest
    @MethodSource("invalidElements")
    void rejectsInvalidIdsAndConnections(String elements, String expectedMessage) throws IOException {
        Path file = writeProcess(elements);

        IOException error = assertThrows(IOException.class, () -> parser.parse(file, "test"));

        assertTrue(error.getMessage().contains(expectedMessage), error.getMessage());
    }

    static Stream<Arguments> invalidElements() {
        return Stream.of(
                Arguments.of("<task id='a'/><userTask id='a'/>", "duplicado"),
                Arguments.of("<task id='test'/>", "duplicado"),
                Arguments.of("<task/>", "'id'"),
                Arguments.of("<task id=' '/>", "'id'"),
                Arguments.of("<task id='a'/><sequenceFlow id='a' sourceRef='a' targetRef='a'/>", "duplicado"),
                Arguments.of("<task id='a'/><sequenceFlow id='f' sourceRef='a' targetRef='a'/>"
                        + "<sequenceFlow id='f' sourceRef='a' targetRef='a'/>", "duplicado"),
                Arguments.of("<task id='a'/><sequenceFlow id='f' targetRef='a'/>", "'sourceRef'"),
                Arguments.of("<task id='a'/><sequenceFlow id='f' sourceRef='a'/>", "'targetRef'"),
                Arguments.of("<task id='a'/><sequenceFlow id='f' sourceRef='missing' targetRef='a'/>", "sourceRef='missing'"),
                Arguments.of("<task id='a'/><sequenceFlow id='f' sourceRef='a' targetRef='missing'/>", "targetRef='missing'")
        );
    }

    @Test
    void rejectsMissingFileAndDirectory() {
        IOException missing = assertThrows(IOException.class,
                () -> parser.parse(tempDirectory.resolve("missing.bpmn"), "test"));
        assertTrue(missing.getMessage().contains("no existe"));
        assertThrows(IOException.class, () -> parser.parse(tempDirectory, "test"));
    }

    @Test
    void rejectsMissingProcess() throws IOException {
        Path file = writeProcess("<task id='a'/>");

        IOException error = assertThrows(IOException.class, () -> parser.parse(file, "missing"));

        assertTrue(error.getMessage().contains("No se encontró el proceso con ID: missing"));
    }

    @Test
    void rejectsDuplicateProcessId() throws IOException {
        Path file = writeXml("<definitions xmlns='" + NAMESPACE
                + "'><process id='test'/><process id='test'/></definitions>");

        IOException error = assertThrows(IOException.class, () -> parser.parse(file, "test"));

        assertTrue(error.getMessage().contains("ID de proceso duplicado"));
    }

    @Test
    void rejectsMalformedXml() throws IOException {
        Path file = writeXml("<definitions><process></definitions>");

        IOException error = assertThrows(IOException.class, () -> parser.parse(file, "test"));

        assertTrue(error.getMessage().contains("XML BPMN inválido"));
        assertNotNull(error.getCause());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "urn:not-bpmn"})
    void rejectsMissingOrIncorrectNamespace(String namespace) throws IOException {
        Path file = writeXml("<definitions xmlns='" + namespace + "'><process id='test'/></definitions>");

        IOException error = assertThrows(IOException.class, () -> parser.parse(file, "test"));

        assertTrue(error.getMessage().contains("namespace de BPMN 2.0"));
    }

    @Test
    void rejectsDoctypeWithExternalEntity() throws IOException {
        Path secret = tempDirectory.resolve("secret.txt");
        Files.writeString(secret, "contenido que no debe leerse");
        Path file = writeXml("""
                <!DOCTYPE definitions [<!ENTITY external SYSTEM "%s">]>
                <definitions xmlns="%s"><process id="test">
                  <task id="a"/><sequenceFlow id="f" sourceRef="a" targetRef="a">
                    <conditionExpression>&external;</conditionExpression>
                  </sequenceFlow>
                </process></definitions>
                """.formatted(secret.toUri(), NAMESPACE));

        IOException error = assertThrows(IOException.class, () -> parser.parse(file, "test"));

        assertTrue(error.getMessage().contains("DOCTYPE"));
    }

    private Path writeProcess(String elements) throws IOException {
        return writeXml("<definitions xmlns='" + NAMESPACE + "'><process id='test'>"
                + elements + "</process></definitions>");
    }

    private Path writeXml(String xml) throws IOException {
        return Files.writeString(tempDirectory.resolve("test.bpmn"), xml);
    }

    private long count(BpmnProcess process, NodeType type) {
        return process.getNodes().values().stream().map(BpmnNode::getType)
                .filter(nodeType -> nodeType == type).count();
    }

    private SequenceFlow findFlow(BpmnProcess process, String id) {
        return process.getSequenceFlows().stream().filter(flow -> flow.getId().equals(id))
                .findFirst().orElseThrow();
    }
}
