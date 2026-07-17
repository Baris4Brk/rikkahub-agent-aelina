package me.rerere.rikkahub.assistantproxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public final class ProxyManifestContractTest {
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    private static final String VOICE_SERVICE =
            "me.rerere.rikkahub.assistantproxy.ProxyVoiceInteractionService";
    private static final String SESSION_SERVICE =
            "me.rerere.rikkahub.assistantproxy.ProxyVoiceSessionService";
    private static final String RECOGNITION_SERVICE =
            "me.rerere.rikkahub.assistantproxy.ProxyNoOpRecognitionService";

    private File projectDir;
    private Document manifest;

    @Before
    public void loadManifest() throws Exception {
        projectDir = new File(System.getProperty("assistantProxy.projectDir"));
        manifest = parse("src/main/AndroidManifest.xml");
    }

    @Test
    public void whitelistVoiceServiceIsDiscoverableWithoutDataPermissions() throws Exception {
        String buildScript = Files.readString(
                new File(projectDir, "build.gradle.kts").toPath(), StandardCharsets.UTF_8);
        assertTrue(buildScript.contains("applicationId = \"android.voiceinteraction.service\""));

        Element application = first(manifest, "application");
        assertEquals("false", androidAttribute(application, "allowBackup"));
        assertEquals("", androidAttribute(application, "name"));
        assertEquals(0, manifest.getElementsByTagName("uses-permission").getLength());
        assertEquals(0, manifest.getElementsByTagName("uses-permission-sdk-23").getLength());

        Element service = serviceNamed(VOICE_SERVICE);
        assertNotNull(service);
        assertEquals("true", androidAttribute(service, "exported"));
        assertEquals(
                "android.permission.BIND_VOICE_INTERACTION",
                androidAttribute(service, "permission"));
        assertEquals(":voice_interactor", androidAttribute(service, "process"));
        assertTrue(hasAction(service, "android.service.voice.VoiceInteractionService"));
        assertTrue(hasMetadata(service, "android.voice_interaction", "@xml/voice_interaction_service"));
    }

    @Test
    public void sessionIsExplicitAndRecognitionServiceIsNotDiscoverable() throws Exception {
        Element session = serviceNamed(SESSION_SERVICE);
        assertNotNull(session);
        assertEquals("true", androidAttribute(session, "exported"));
        assertEquals(
                "android.permission.BIND_VOICE_INTERACTION",
                androidAttribute(session, "permission"));
        assertEquals("", androidAttribute(session, "process"));

        Element recognition = serviceNamed(RECOGNITION_SERVICE);
        assertNotNull(recognition);
        assertEquals("true", androidAttribute(recognition, "exported"));
        assertEquals(
                "android.permission.BIND_SPEECH_RECOGNITION",
                androidAttribute(recognition, "permission"));
        assertEquals(":voice_interactor", androidAttribute(recognition, "process"));
        assertEquals(0, recognition.getElementsByTagName("intent-filter").getLength());
        assertTrue(hasMetadata(
                recognition,
                "android.speech",
                "@xml/recognition_service"));

        Document voiceConfig = parse("src/main/res/xml/voice_interaction_service.xml");
        Element voiceInteraction = first(voiceConfig, "voice-interaction-service");
        assertEquals(SESSION_SERVICE, androidAttribute(voiceInteraction, "sessionService"));
        assertEquals(RECOGNITION_SERVICE, androidAttribute(voiceInteraction, "recognitionService"));
        assertEquals("true", androidAttribute(voiceInteraction, "supportsAssist"));
        assertEquals(
                "false",
                androidAttribute(voiceInteraction, "supportsLaunchVoiceAssistFromKeyguard"));

        Document recognitionConfig = parse("src/main/res/xml/recognition_service.xml");
        Element recognitionService = first(recognitionConfig, "recognition-service");
        assertEquals("false", androidAttribute(recognitionService, "selectableAsDefault"));
    }

    private Document parse(String relativePath) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new File(projectDir, relativePath));
    }

    private Element serviceNamed(String name) {
        NodeList services = manifest.getElementsByTagName("service");
        for (int index = 0; index < services.getLength(); index++) {
            Element service = (Element) services.item(index);
            if (name.equals(androidAttribute(service, "name"))) {
                return service;
            }
        }
        return null;
    }

    private static Element first(Document document, String tagName) {
        return (Element) document.getElementsByTagName(tagName).item(0);
    }

    private static String androidAttribute(Element element, String localName) {
        return element.getAttributeNS(ANDROID_NS, localName);
    }

    private static boolean hasAction(Element service, String expected) {
        NodeList actions = service.getElementsByTagName("action");
        for (int index = 0; index < actions.getLength(); index++) {
            if (expected.equals(androidAttribute((Element) actions.item(index), "name"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasMetadata(Element service, String name, String resource) {
        NodeList metadata = service.getElementsByTagName("meta-data");
        for (int index = 0; index < metadata.getLength(); index++) {
            Element item = (Element) metadata.item(index);
            if (name.equals(androidAttribute(item, "name"))
                    && resource.equals(androidAttribute(item, "resource"))) {
                return true;
            }
        }
        return false;
    }
}
