package org.fresnel.backend.measurement;

import org.fresnel.measurement.BosTarget;
import org.fresnel.measurement.BosTargetGenerator;
import org.fresnel.measurement.BosTargetParameters;
import org.junit.jupiter.api.Test;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Iterator;

import static org.assertj.core.api.Assertions.assertThat;

class BosTargetPngEncoderTest {

    @Test
    void encodingIsLosslessDeterministicAndCarriesPhysicalDpi() throws Exception {
        BosTargetParameters parameters = new BosTargetParameters(
                640,
                480,
                100.0,
                123456789L,
                5,
                0.10,
                2,
                32,
                true,
                false);
        BosTarget target = BosTargetGenerator.generate(parameters);

        byte[] first = BosTargetPngEncoder.encode(target);
        byte[] second = BosTargetPngEncoder.encode(target);

        assertThat(first).isEqualTo(second);
        assertThat(first).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(first));
        assertThat(decoded).isNotNull();
        assertThat(decoded.getWidth()).isEqualTo(parameters.widthPx());
        assertThat(decoded.getHeight()).isEqualTo(parameters.heightPx());
        assertThat(decoded.getRaster().getSample(0, 0, 0)).isEqualTo(target.pixelUnsigned(0, 0));
        assertThat(decoded.getRaster().getSample(
                target.activeRegion().x(), target.activeRegion().y(), 0))
                .isEqualTo(target.pixelUnsigned(
                        target.activeRegion().x(), target.activeRegion().y()));

        try (ImageInputStream input = ImageIO.createImageInputStream(
                new ByteArrayInputStream(first))) {
            assertThat(input).isNotNull();
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            assertThat(readers.hasNext()).isTrue();
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, false);
                IIOMetadata metadata = reader.getImageMetadata(0);
                Node physical = find(metadata.getAsTree("javax_imageio_png_1.0"), "pHYs");
                assertThat(physical).isNotNull();
                NamedNodeMap attributes = physical.getAttributes();
                assertThat(attributes.getNamedItem("pixelsPerUnitXAxis").getNodeValue())
                        .isEqualTo("3937");
                assertThat(attributes.getNamedItem("pixelsPerUnitYAxis").getNodeValue())
                        .isEqualTo("3937");
                assertThat(attributes.getNamedItem("unitSpecifier").getNodeValue())
                        .isEqualTo("meter");
            } finally {
                reader.dispose();
            }
        }
    }

    @Test
    void nullTargetIsRejectedBeforeImageAllocation() {
        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> BosTargetPngEncoder.encode(null))
                .withMessageContaining("target");
    }

    private static Node find(Node node, String name) {
        if (name.equals(node.getNodeName())) return node;
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            Node match = find(child, name);
            if (match != null) return match;
        }
        return null;
    }
}
