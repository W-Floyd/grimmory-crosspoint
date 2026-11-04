package org.booklore.service.acsm;

import lombok.RequiredArgsConstructor;
import org.booklore.exception.ApiError;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Locale;

/**
 * Turns an uploaded {@code .acsm} fulfillment token into the book it stands for, so the ordinary
 * upload path imports that instead. Runs before the upload's transaction: the external tool can take
 * minutes, and a pooled connection would be held for all of it.
 */
@Service
@RequiredArgsConstructor
public class AcsmUploadConverter {

    private final AcsmHandler acsmHandler;

    /** The book an {@code .acsm} upload produces, or {@code file} itself for anything else. */
    public MultipartFile materializeIfAcsm(MultipartFile file) {
        String name = file.getOriginalFilename() == null ? null
                : StringUtils.getFilename(StringUtils.cleanPath(file.getOriginalFilename()));
        if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".acsm")) {
            return file;
        }
        if (!acsmHandler.isConfigured()) {
            throw ApiError.INVALID_FILE_FORMAT.createException(
                    "ACSM files require a configured external ACSM handler (app.acsm.*).");
        }
        byte[] content;
        try {
            content = acsmHandler.handle(file.getBytes());
        } catch (IOException e) {
            throw ApiError.FILE_READ_ERROR.createException(e.getMessage());
        }
        if (content == null || content.length == 0) {
            throw ApiError.GENERIC_BAD_REQUEST.createException(
                    "The external ACSM handler did not produce a book file from the uploaded ACSM.");
        }
        String base = name.substring(0, name.length() - ".acsm".length());
        return new ConvertedBook(base + (isPdf(content) ? ".pdf" : ".epub"), content);
    }

    private static boolean isPdf(byte[] content) {
        return content.length >= 4 && content[0] == '%' && content[1] == 'P' && content[2] == 'D' && content[3] == 'F';
    }

    /** The produced book, presented as the upload it replaces. */
    private record ConvertedBook(String fileName, byte[] content) implements MultipartFile {

        @Override
        public String getName() {
            return "file";
        }

        @Override
        public String getOriginalFilename() {
            return fileName;
        }

        @Override
        public String getContentType() {
            return null;
        }

        @Override
        public boolean isEmpty() {
            return content.length == 0;
        }

        @Override
        public long getSize() {
            return content.length;
        }

        @Override
        public byte[] getBytes() {
            return content.clone();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(content);
        }

        @Override
        public void transferTo(File dest) throws IOException {
            Files.write(dest.toPath(), content);
        }
    }
}
