package org.involens_be.service;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Verifies real preview rendering with mocked storage and locally generated signed URLs. */
@Slf4j
class DocumentThumbnailServiceTest {
    private MinioClient storage;
    private DocumentThumbnailService service;

    /** Creates isolated storage and a real signer that requires no network requests. */
    @BeforeEach
    void setUp() {
        log.info("Preparing thumbnail rendering tests");
        storage = mock(MinioClient.class);
        var signer = new org.involens_be.config.AppConfig().publicMinioClient(
                "http://localhost:9000", "test-access", "test-secret", "us-east-1");
        service = new DocumentThumbnailService(storage, signer);
        ReflectionTestUtils.setField(service, "bucket", "test-documents");
    }

    /** Preserves portrait and landscape proportions and uses a white background for transparent PNG files. */
    @Test
    void scalesImagesWithoutCropping() throws Exception {
        log.info("Testing proportional image thumbnail rendering");
        for (int[] size : new int[][]{{512, 768}, {768, 384}}) {
            byte[] jpeg = service.createJpeg(png(size[0], size[1]), "image/png");
            assertEquals(0xff, jpeg[0] & 0xff);
            assertEquals(0xd8, jpeg[1] & 0xff);
            var image = ImageIO.read(new ByteArrayInputStream(jpeg));
            assertEquals(256, image.getWidth());
            assertEquals(size[1] * 256 / size[0], image.getHeight());
            assertEquals(Color.WHITE.getRGB(), image.getRGB(0, 0));
        }
    }

    /** Leaves small uploaded images at their original dimensions. */
    @Test
    void doesNotUpscaleSmallImage() throws Exception {
        log.info("Testing small image thumbnail dimensions");
        var image = ImageIO.read(new ByteArrayInputStream(service.createJpeg(png(32, 48), "image/png")));
        assertEquals(32, image.getWidth());
        assertEquals(48, image.getHeight());
    }

    /** Uses only the first PDF page and respects its display rotation. */
    @Test
    void rendersFirstPdfPageIncludingRotation() throws Exception {
        log.info("Testing first-page PDF thumbnail rendering");
        for (int rotation : new int[]{0, 90}) {
            try (var pdf = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
                var page = new PDPage(new PDRectangle(512, 768));
                page.setRotation(rotation);
                pdf.addPage(page);
                try (var content = new PDPageContentStream(pdf, page)) {
                    content.setNonStrokingColor(Color.RED);
                    content.addRect(0, 0, 512, 768);
                    content.fill();
                }
                pdf.addPage(new PDPage(new PDRectangle(100, 100)));
                pdf.save(bytes);
                var file = new MockMultipartFile("files", "invoice.pdf", "application/pdf", bytes.toByteArray());
                var image = ImageIO.read(new ByteArrayInputStream(service.createJpeg(file, "application/pdf")));
                assertEquals(256, image.getWidth());
                assertEquals(rotation == 0 ? 384 : 170, image.getHeight());
                var center = new Color(image.getRGB(image.getWidth() / 2, image.getHeight() / 2));
                assertTrue(center.getRed() > 240 && center.getGreen() < 15 && center.getBlue() < 15);
            }
        }
    }

    /** Stores previews separately with the JPEG content type and returns the persisted object key. */
    @Test
    void storesJpegThumbnail() throws Exception {
        log.info("Testing thumbnail object storage metadata");
        assertEquals("documents/original.png.thumbnail.jpg",
                service.createAndStore(png(512, 768), "image/png", "documents/original.png"));
        var args = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(storage).putObject(args.capture());
        assertEquals("test-documents", args.getValue().bucket());
        assertEquals("documents/original.png.thumbnail.jpg", args.getValue().object());
        assertEquals("image/jpeg", args.getValue().contentType());
    }

    /** Treats corrupt images and PDFs as missing optional previews without writing objects. */
    @Test
    void corruptInputsReturnNull() {
        log.info("Testing corrupt preview input fallback");
        for (String mime : new String[]{"image/jpeg", "application/pdf"}) {
            var file = new MockMultipartFile("files", "broken", mime, new byte[]{1, 2, 3});
            assertNull(service.createAndStore(file, mime, "documents/broken"));
        }
        verifyNoInteractions(storage);
    }

    /** Preserves optional preview semantics when MinIO cannot store the generated thumbnail. */
    @Test
    void storageFailureReturnsNull() throws Exception {
        log.info("Testing thumbnail storage failure fallback");
        when(storage.putObject(any())).thenThrow(new java.io.IOException("storage unavailable"));
        assertNull(service.createAndStore(png(32, 48), "image/png", "documents/original.png"));
    }

    /** Produces hour-long public URLs and returns null for old documents or signing errors. */
    @Test
    void signsUrlsAndPreservesMissingPreviews() {
        log.info("Testing thumbnail URL expiry and graceful fallback");
        String url = service.createUrl("documents/original.png.thumbnail.jpg");
        assertTrue(url.startsWith("http://localhost:9000/test-documents/documents/original.png.thumbnail.jpg?"));
        assertTrue(url.contains("X-Amz-Expires=3600"));
        assertTrue(url.contains("X-Amz-Signature="));
        assertNull(service.createUrl(null));
        assertNull(service.createUrl(" "));
        ReflectionTestUtils.setField(service, "bucket", "invalid bucket");
        assertNull(service.createUrl("documents/preview.jpg"));
    }

    /** Creates an in-memory transparent PNG upload for rendering tests. */
    private MockMultipartFile png(int width, int height) throws Exception {
        log.debug("Preparing test image. width={}, height={}", width, height);
        try (var bytes = new ByteArrayOutputStream()) {
            ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", bytes);
            return new MockMultipartFile("files", "image.png", "image/png", bytes.toByteArray());
        }
    }
}
