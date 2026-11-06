package org.involens_be.service;

import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** Generates optional document previews and signs their browser-accessible storage URLs. */
@Service
@Slf4j
public class DocumentThumbnailService {
    private static final int MAX_WIDTH = 256;
    private static final float JPEG_QUALITY = 0.8f;
    private final MinioClient minioClient;
    private final MinioClient publicMinioClient;

    @Value("${app.minio.bucket}")
    private String bucket;

    /** Connects preview storage and public URL signing to the existing MinIO clients. */
    public DocumentThumbnailService(MinioClient minioClient,
            @Qualifier("publicMinioClient") MinioClient publicMinioClient) {
        log.debug("Initializing document thumbnail storage and URL signing");
        this.minioClient = minioClient;
        this.publicMinioClient = publicMinioClient;
    }

    /** Stores a JPEG preview beside the original; returns null on failure without failing the original upload. */
    public String createAndStore(MultipartFile file, String mimeType, String originalObjectKey) {
        log.info("Creating document thumbnail. objectKey={}", originalObjectKey);
        try {
            byte[] thumbnail = createJpeg(file, mimeType);
            String thumbnailKey = originalObjectKey + ".thumbnail.jpg";
            try (var input = new ByteArrayInputStream(thumbnail)) {
                minioClient.putObject(PutObjectArgs.builder().bucket(bucket).object(thumbnailKey)
                        .stream(input, thumbnail.length, -1).contentType("image/jpeg").build());
            }
            log.info("Document thumbnail stored. objectKey={}", originalObjectKey);
            return thumbnailKey;
        } catch (Exception exception) {
            log.warn("Document thumbnail unavailable; preserving original upload. objectKey={}",
                    originalObjectKey, exception);
            return null;
        }
    }

    /** Converts an image or the first PDF page into a proportional, white-background JPEG up to 256 pixels wide. */
    byte[] createJpeg(MultipartFile file, String mimeType) throws IOException {
        log.debug("Rendering document preview. mimeType={}", mimeType);
        BufferedImage source;
        if ("application/pdf".equals(mimeType)) {
            try (var pdf = Loader.loadPDF(file.getBytes())) {
                if (pdf.getNumberOfPages() == 0) {
                    throw new IOException("PDF contains no preview page");
                }
                var page = pdf.getPage(0);
                var box = page.getCropBox();
                float width = page.getRotation() % 180 == 0 ? box.getWidth() : box.getHeight();
                var renderer = new PDFRenderer(pdf);
                renderer.setSubsamplingAllowed(true);
                source = renderer.renderImage(0, Math.min(1f, MAX_WIDTH / width), ImageType.RGB);
            }
        } else {
            try (var input = file.getInputStream()) {
                source = ImageIO.read(input);
            }
            if (source == null) {
                throw new IOException("Image cannot be decoded for preview");
            }
        }
        try {
            return encodeJpeg(source);
        } finally {
            source.flush();
        }
    }

    /** Resizes without cropping or upscaling and encodes a JPEG at the approved 80 percent quality. */
    private byte[] encodeJpeg(BufferedImage source) throws IOException {
        log.debug("Encoding document thumbnail. width={}, height={}", source.getWidth(), source.getHeight());
        int width = Math.min(MAX_WIDTH, source.getWidth());
        int height = Math.max(1, (int) Math.round((double) source.getHeight() * width / source.getWidth()));
        var preview = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = preview.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (var bytes = new ByteArrayOutputStream(); var output = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(output);
            var parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(preview, null, null), parameters);
            output.flush();
            log.debug("Document thumbnail encoded. width={}, height={}", width, height);
            return bytes.toByteArray();
        } finally {
            writer.dispose();
            preview.flush();
        }
    }

    /** Signs an hour-long preview URL, preserving missing thumbnails and signing failures as null. */
    public String createUrl(String objectKey) {
        log.debug("Preparing document thumbnail URL. available={}", objectKey != null);
        if (objectKey == null || objectKey.isBlank()) {
            return null;
        }
        try {
            String url = publicMinioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET).bucket(bucket).object(objectKey).expiry(1, TimeUnit.HOURS).build());
            log.debug("Document thumbnail URL created");
            return url;
        } catch (Exception exception) {
            log.warn("Cannot sign document thumbnail URL; returning no preview", exception);
            return null;
        }
    }
}
